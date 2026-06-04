package com.gitaistudio.idea.annotate

import com.gitaistudio.idea.cli.GitAiCli
import com.gitaistudio.idea.editor.AttributionColors
import com.gitaistudio.idea.editor.GitAiActionSupport
import com.gitaistudio.idea.service.GitAiSettings
import com.gitaistudio.idea.service.RepoService
import com.google.gson.JsonParser
import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.vcs.annotate.AnnotationGutterColumnProvider
import com.intellij.openapi.vcs.annotate.FileAnnotation
import com.intellij.openapi.vcs.annotate.LineAnnotationAspect

/**
 * 给 IntelliJ 原生 Annotate(blame)gutter 加一列「AI」:逐行标出 AI / 人工。
 * 与自研 [com.gitaistudio.idea.editor.AnnotateAttributionAction] 并存(后者作回退,等原生版验证达标再决定是否下线)。
 *
 * createColumn 在 annotate 的后台任务里调用,故在此一次性跑 git-ai blame-analysis 建好「行→AI」map;
 * aspect 的 getValue/getColor 只读 map(同步安全),规避 EDT 阻塞与异步 repaint。
 */
class AiAnnotationColumnProvider : AnnotationGutterColumnProvider {

    override fun createColumn(annotation: FileAnnotation): LineAnnotationAspect? {
        val project = annotation.project ?: return null
        val vf = annotation.file ?: return null
        val repo = RepoService.getInstance(project).currentRepoDir() ?: return null
        val rel = GitAiActionSupport.relativePath(repo, vf) ?: return null
        val cli = runCatching { GitAiCli.resolve(repo, GitAiSettings.getInstance().gitAiPath) }.getOrNull() ?: return null
        val r = cli.blameAnalysis(rel, emptyList(), "HEAD")
        if (!r.ok) return null
        val aiByLine = parseAiLines(r.stdout)
        if (aiByLine.isEmpty()) return null // 无 AI 行就不加列,避免一整列空白
        return AiAspect(aiByLine)
    }

    /** line_authors 里 value 是 prompt_records 的 key 即 AI 行;返回 1-based 行号 → agent_id。 */
    private fun parseAiLines(stdout: String): Map<Int, String?> {
        val root = runCatching { JsonParser.parseString(stdout.trim().ifBlank { "{}" }).asJsonObject }.getOrNull()
            ?: return emptyMap()
        val lineAuthors = root.getAsJsonObject("line_authors") ?: return emptyMap()
        val promptRecords = root.getAsJsonObject("prompt_records")
        val out = HashMap<Int, String?>()
        for ((k, v) in lineAuthors.entrySet()) {
            val line = k.toIntOrNull() ?: continue
            val author = v.asString
            if (promptRecords?.has(author) == true) {
                out[line] = promptRecords.getAsJsonObject(author)?.get("agent_id")
                    ?.takeIf { !it.isJsonNull }?.asString
            }
        }
        return out
    }

    /** aiByLine 用 1-based 行号;LineAnnotationAspect 的 line 是 0-based,故查 line+1。 */
    private class AiAspect(private val aiByLine: Map<Int, String?>) : LineAnnotationAspect {
        override fun getValue(line: Int): String = if (aiByLine.containsKey(line + 1)) "AI" else ""

        override fun getTooltipText(line: Int): String? {
            if (!aiByLine.containsKey(line + 1)) return null
            val agent = aiByLine[line + 1]?.takeIf { it.isNotBlank() } ?: "AI"
            return "AI-authored — agent: $agent"
        }

        override fun getColor(line: Int): ColorKey? =
            if (aiByLine.containsKey(line + 1)) AttributionColors.AI else null

        override fun getId(): String = "GitAiStudio.AiAnnotate"
        override fun isShowByDefault(): Boolean = true
        override fun getDisplayName(): String = "AI"
    }
}
