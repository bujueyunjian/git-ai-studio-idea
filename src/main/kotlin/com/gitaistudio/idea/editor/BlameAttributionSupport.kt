package com.gitaistudio.idea.editor

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** git-ai blame --json 输出解析。官方 schema:顶层 `lines` 只包含 AI 行,`prompts` 存 prompt 元数据。 */
object BlameAttributionSupport {

    data class Share(val ai: Int, val total: Int, val pct: Int)

    fun parseLineAttributions(stdout: String, totalLines: Int): Map<Int, LineAttribution> {
        val out = HashMap<Int, LineAttribution>()
        for (line0 in 0 until totalLines.coerceAtLeast(0)) {
            out[line0] = LineAttribution(isAi = false, agent = null, promptId = null)
        }
        parseAiLines(stdout).forEach { (line0, attribution) ->
            if (line0 in 0 until totalLines) out[line0] = attribution
        }
        return out
    }

    fun parseAiLineAgents(stdout: String): Map<Int, String?> =
        parseAiLines(stdout)
            .filterValues { it.isAi }
            .mapKeys { (line0, _) -> line0 + 1 }
            .mapValues { (_, value) -> value.agent }

    fun fileShare(stdout: String, totalLines: Int): Share {
        val total = totalLines.coerceAtLeast(0)
        val ai = parseAiLines(stdout).keys.count { it in 0 until total }
        val pct = if (total > 0) (ai * 100 + total / 2) / total else 0
        return Share(ai, total, pct)
    }

    private fun parseAiLines(stdout: String): Map<Int, LineAttribution> {
        val root = parseObject(stdout)
        val lines = root.obj("lines") ?: return emptyMap()
        val prompts = root.obj("prompts") ?: JsonObject()
        val out = HashMap<Int, LineAttribution>()
        lines.entrySet().forEach { (key, promptIdElement) ->
            val promptId = promptIdElement.takeIf { it.isJsonPrimitive }?.asString ?: return@forEach
            val agent = promptModel(prompts, promptId)
            expandLineKey(key)?.forEach { line1 ->
                out[line1 - 1] = LineAttribution(isAi = true, agent = agent, promptId = promptId)
            }
        }
        return out
    }

    private fun promptModel(prompts: JsonObject, promptId: String): String? =
        prompts.obj(promptId)
            ?.obj("agent_id")
            ?.get("model")
            ?.takeIf { it.isJsonPrimitive }
            ?.asString

    private fun expandLineKey(key: String): IntRange? {
        val parts = key.split("-", limit = 2)
        val start = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val end = parts.getOrNull(1)?.toIntOrNull() ?: start
        if (start < 1 || end < start) return null
        return start..end
    }

    private fun parseObject(stdout: String): JsonObject =
        runCatching { JsonParser.parseString(stdout.trim().ifBlank { "{}" }).asJsonObject }
            .getOrDefault(JsonObject())

    private fun JsonObject.obj(key: String): JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject
}
