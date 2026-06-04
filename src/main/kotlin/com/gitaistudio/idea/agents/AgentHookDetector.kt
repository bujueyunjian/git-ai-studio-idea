package com.gitaistudio.idea.agents

import com.gitaistudio.idea.bridge.JsonUtil
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.io.File

/**
 * 逐 AI agent 的 hook 配置探测,对齐桌面版 `src-tauri/src/agents/`(git-ai 上游是唯一权威)。
 *
 * 规则:
 * - **detected** = 配置文件存在。
 * - **configured**(命令型 agent)= 配置里某条命令串严格匹配「git-ai 可执行 + `checkpoint <agent>`」:
 *   拒 shell 短路(`;` / `&&` / `||`)与注释(`#`),首 token 必须以 git-ai / git-ai.exe 结尾。
 *   统一从配置文件抽取所有引号字符串字面量(JSON 双引号 / TOML 单引号通吃),逐个套该规则。
 * - **configured**(TS 插件型 OpenCode / Pi)= 文件含 `GIT_AI_BIN` 常量且已非 `__GIT_AI_BINARY_PATH__` 占位符。
 *
 * 与桌面版差异:桌面解析 JSON/TOML 结构定位 hooks 段,这里用「引号字面量 + 严格命令判定」的等价启发式,
 * 对路径含 shell 污染的伪配置同样拒绝;report.sections 的细粒度清单解析未移植(Diagnostic 仍展示 git-ai debug 原文)。
 */
object AgentHookDetector {

    private data class Spec(
        val kind: String,
        val cli: String?,
        val relPaths: List<String>,
        val tsPlugin: Boolean,
    )

    private val SPECS = listOf(
        Spec("Claude", "claude", listOf(".claude/settings.json"), false),
        Spec("Cursor", "cursor", listOf(".cursor/hooks.json"), false),
        Spec("Codex", "codex", listOf(".codex/config.toml", ".codex/hooks.json"), false),
        Spec("OpenCode", null, listOf(".config/opencode/plugins/git-ai.ts"), true),
        Spec("Gemini", "gemini", listOf(".gemini/settings.json"), false),
        Spec("Pi", null, listOf(".pi/agent/extensions/git-ai.ts"), true),
    )

    fun detectAll(): JsonArray = JsonArray().apply { SPECS.forEach { add(detect(it)) } }

    /** Claude hook 模式:~/.claude/settings.json 含 git-ai checkpoint claude → "official",否则 "none"。 */
    fun claudeHookMode(): String {
        val f = File(System.getProperty("user.home").orEmpty(), ".claude/settings.json")
        if (!f.isFile) return "none"
        val text = runCatching { f.readText() }.getOrNull() ?: return "none"
        return if (extractQuotedLiterals(text).any { isGitAiHook(it, "claude") }) "official" else "none"
    }

    private fun detect(spec: Spec): JsonObject {
        val home = System.getProperty("user.home").orEmpty()
        val existing = spec.relPaths.map { File(home, it) }.firstOrNull { it.isFile }
        if (existing == null) {
            val shown = File(home, spec.relPaths.first()).path
            return status(
                spec.kind, detected = false, configured = false, configPath = shown,
                hookType = null, excerpt = null,
                issues = listOf("未检测到 $shown(${spec.kind} 未配置)"),
            )
        }
        val text = runCatching { existing.readText() }.getOrElse {
            return status(
                spec.kind, detected = true, configured = false, configPath = existing.path,
                hookType = null, excerpt = null, issues = listOf("配置文件读取失败: ${it.message}"),
            )
        }
        return if (spec.tsPlugin) probeTsPlugin(spec.kind, existing.path, text)
        else probeMarker(spec.kind, spec.cli!!, existing.path, text)
    }

    private fun probeMarker(kind: String, cli: String, path: String, text: String): JsonObject {
        val match = extractQuotedLiterals(text).firstOrNull { isGitAiHook(it, cli) }
        val configured = match != null
        return status(
            kind, detected = true, configured = configured, configPath = path,
            hookType = if (configured) "command" else null,
            excerpt = match,
            issues = if (configured) emptyList() else listOf("未找到 'git-ai checkpoint $cli' 配置"),
        )
    }

    private fun probeTsPlugin(kind: String, path: String, text: String): JsonObject {
        val hasConst = text.contains("GIT_AI_BIN")
        val stillPlaceholder = text.contains("__GIT_AI_BINARY_PATH__")
        val configured = hasConst && !stillPlaceholder
        val issues = buildList {
            if (!hasConst) add("插件文件缺少 GIT_AI_BIN 常量,可能不是 git-ai 安装版本")
            if (stillPlaceholder) add("GIT_AI_BIN 仍是占位符,git-ai install-hooks 未替换真实路径")
        }
        val excerpt = text.lineSequence().firstOrNull { it.contains("GIT_AI_BIN") && it.contains('=') }?.trim()
        return status(
            kind, detected = true, configured = configured, configPath = path,
            hookType = if (configured) "command" else null, excerpt = excerpt, issues = issues,
        )
    }

    /** 命令串是否真的执行 git-ai checkpoint <cli>。对齐桌面版 claude.rs::is_git_ai_*_hook 的严格判定。 */
    private fun isGitAiHook(s: String, cli: String): Boolean {
        val t = s.trim()
        if (t.contains(';') || t.contains("&&") || t.contains("||") || t.contains('#')) return false
        if (!t.contains("checkpoint $cli")) return false
        val first = t.split(Regex("\\s+")).firstOrNull()?.lowercase() ?: return false
        return first.endsWith("git-ai") || first.endsWith("git-ai.exe")
    }

    private val DOUBLE_QUOTED = Regex("\"((?:\\\\.|[^\"\\\\])*)\"")
    private val SINGLE_QUOTED = Regex("'([^']*)'")

    /** 抽取文件里的引号字符串字面量(JSON 双引号含转义 / TOML 单引号字面量),双引号做基本反转义。 */
    private fun extractQuotedLiterals(text: String): List<String> {
        val out = ArrayList<String>()
        DOUBLE_QUOTED.findAll(text).forEach { out.add(unescapeJson(it.groupValues[1])) }
        SINGLE_QUOTED.findAll(text).forEach { out.add(it.groupValues[1]) }
        return out
    }

    private fun unescapeJson(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    '\\' -> sb.append('\\')
                    '"' -> sb.append('"')
                    '/' -> sb.append('/')
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    else -> { sb.append('\\'); sb.append(s[i + 1]) }
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private fun status(
        kind: String, detected: Boolean, configured: Boolean, configPath: String?,
        hookType: String?, excerpt: String?, issues: List<String>,
    ): JsonObject = JsonUtil.obj(
        "agent" to kind,
        "detected" to detected,
        "configured" to configured,
        "config_path" to (configPath ?: ""),
        "hook_type" to (hookType?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE),
        "raw_excerpt" to (excerpt?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE),
        "issues" to JsonArray().apply { issues.forEach { add(it) } },
    )
}
