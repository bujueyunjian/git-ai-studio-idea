package com.gitaistudio.idea.annotate

import com.gitaistudio.idea.cli.GitAiCli
import com.gitaistudio.idea.editor.AttributionColors
import com.gitaistudio.idea.editor.BlameAttributionSupport
import com.gitaistudio.idea.editor.GitAiActionSupport
import com.gitaistudio.idea.service.GitAiSettings
import com.gitaistudio.idea.service.RepoService
import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.vcs.annotate.AnnotationGutterColumnProvider
import com.intellij.openapi.vcs.annotate.FileAnnotation
import com.intellij.openapi.vcs.annotate.LineAnnotationAspect

/**
 * 给 IntelliJ 原生 Annotate(blame)gutter 加一列「AI」:逐行标出 AI / 人工。
 * 与自研 [com.gitaistudio.idea.editor.AnnotateAttributionAction] 并存(后者作回退,等原生版验证达标再决定是否下线)。
 *
 * createColumn 在 annotate 的后台任务里调用,故在此一次性跑 git-ai blame 建好「行→AI」map;
 * aspect 的 getValue/getColor 只读 map(同步安全),规避 EDT 阻塞与异步 repaint。
 */
class AiAnnotationColumnProvider : AnnotationGutterColumnProvider {

    override fun createColumn(annotation: FileAnnotation): LineAnnotationAspect? {
        val project = annotation.project ?: return null
        val vf = annotation.file ?: return null
        val repo = RepoService.getInstance(project).currentRepoDir() ?: return null
        val rel = GitAiActionSupport.relativePath(repo, vf) ?: return null
        val cli = runCatching { GitAiCli.resolve(repo, GitAiSettings.getInstance().gitAiPath) }.getOrNull() ?: return null
        val r = cli.blameJson(rel, emptyList())
        if (!r.ok) return null
        val aiByLine = BlameAttributionSupport.parseAiLineAgents(r.stdout)
        if (aiByLine.isEmpty()) return null // 无 AI 行就不加列,避免一整列空白
        return AiAspect(aiByLine)
    }

    /** aiByLine 用 1-based 行号;LineAnnotationAspect 的 line 是 0-based,故查 line+1。 */
    private class AiAspect(private val aiByLine: Map<Int, String?>) : LineAnnotationAspect {
        // 列内放短模型名(gpt-5.5 / claude-sonnet-4-5),完整 tool::model 在 tooltip——与看板口径一致且不撑宽 gutter。
        override fun getValue(line: Int): String =
            if (aiByLine.containsKey(line + 1)) BlameAttributionSupport.shortModelName(aiByLine[line + 1]) else ""

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
