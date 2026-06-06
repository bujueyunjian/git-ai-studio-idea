package com.gitaistudio.idea.editor

import com.gitaistudio.idea.cli.GitAiCli
import com.gitaistudio.idea.service.GitAiSettings
import com.gitaistudio.idea.service.RepoService
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor

/**
 * 编辑器右键 →「Git AI: 本文件 AI 占比」:**就地**在编辑器弹气泡显示本文件归因,不切换到工具窗口/看板。
 *
 * - 已提交(HEAD):git-ai blame --json 求本文件 AI 行 / 总行 / AI%。
 * - 未提交(工作树):git-ai status 的整库 AI / 人工 / 未知 行(若有改动才显示)。
 * 后台算,EDT 不阻塞;算完在光标处弹气泡(就地、克制)。
 */
class FileAiSummaryAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val vf = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible =
            e.getData(CommonDataKeys.EDITOR) != null && vf != null && !vf.isDirectory
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val vfile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val repo = RepoService.getInstance(project).currentRepoDir()
            ?: return showBalloon(editor, "本文件不在 git-ai 跟踪的仓库内。", MessageType.WARNING)
        val rel = GitAiActionSupport.relativePath(repo, vfile)
            ?: return showBalloon(editor, "本文件不在当前仓库内。", MessageType.WARNING)
        val totalLines = editor.document.lineCount

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Computing file AI share", true) {
            private var html: String? = null
            private var type = MessageType.INFO

            override fun run(indicator: ProgressIndicator) {
                val cli = runCatching { GitAiCli.resolve(repo, GitAiSettings.getInstance().gitAiPath) }.getOrNull()
                if (cli == null) {
                    html = "git-ai 未找到(请确认已安装且在 PATH)。"
                    type = MessageType.WARNING
                    return
                }
                // 已提交:本文件 blame
                val committed = cli.blameJson(rel, emptyList()).let { r ->
                    if (r.ok) fileShare(r.stdout, totalLines) else null
                }
                // 未提交:整库工作树 status
                val working = cli.status().let { r -> if (r.ok) workingStats(r.stdout) else null }

                html = summaryHtml(vfile.name, committed, working)
            }

            override fun onSuccess() {
                showBalloon(editor, html ?: "无数据", type)
            }
        })
    }

    private fun fileShare(stdout: String, totalLines: Int): BlameAttributionSupport.Share =
        BlameAttributionSupport.fileShare(stdout, totalLines)

    /**
     * 气泡 HTML:标题 + 每个区块「彩色堆积进度条 + 图例」。
     * 颜色复用 [AttributionColors] 的锁死语义(紫=AI、蓝=人工),未知用灰;
     * Swing HTML 不支持 CSS 圆角/渐变,进度条用定宽 bgcolor 表格单元实现。
     */
    private fun summaryHtml(
        fileName: String,
        committed: BlameAttributionSupport.Share?,
        working: Working?,
    ): String {
        val ai = "#" + ColorUtil.toHex(AttributionColors.AI.defaultColor ?: JBColor.GRAY)
        val you = "#" + ColorUtil.toHex(AttributionColors.YOU.defaultColor ?: JBColor.GRAY)
        val gray = "#" + ColorUtil.toHex(JBColor.GRAY)
        return buildString {
            append("<div><b>").append(StringUtil.escapeXmlEntities(fileName)).append(" · AI 占比</b><br/><br/>")
            if (committed != null && committed.total > 0) {
                val rest = (committed.total - committed.ai).toLong()
                append("已提交(HEAD)&nbsp;&nbsp;<b>AI ").append(committed.pct).append("%</b><br/>")
                append(barHtml(listOf(committed.ai.toLong() to ai, rest to gray)))
                append(legendHtml(listOf(Triple(ai, "AI", committed.ai.toLong()), Triple(gray, "其他", rest))))
                append(" 行")
            } else {
                append("已提交(HEAD):本文件暂无 AI 归因")
            }
            if (working != null && (working.ai + working.human + working.unknown) > 0) {
                append("<br/><br/>未提交(工作树·整库)<br/>")
                append(barHtml(listOf(working.ai to ai, working.human to you, working.unknown to gray)))
                append(legendHtml(listOf(Triple(ai, "AI", working.ai), Triple(you, "人工", working.human), Triple(gray, "未知", working.unknown))))
                append(" 行")
            }
            append("</div>")
        }
    }

    /** 单行堆积条:非零段按比例分宽(最窄 4px 保证可见),余数补给末段。 */
    private fun barHtml(segments: List<Pair<Long, String>>, totalWidth: Int = 240): String {
        val total = segments.sumOf { it.first }
        if (total <= 0) return ""
        val nonZero = segments.filter { it.first > 0 }
        val sb = StringBuilder("<table cellspacing='0' cellpadding='0' border='0'><tr>")
        var used = 0
        nonZero.forEachIndexed { i, (value, color) ->
            val w = if (i == nonZero.lastIndex) (totalWidth - used).coerceAtLeast(4)
            else ((value * totalWidth) / total).toInt().coerceAtLeast(4)
            used += w
            sb.append("<td bgcolor='").append(color).append("' width='").append(w)
                .append("'><font size='-3'>&nbsp;</font></td>")
        }
        sb.append("</tr></table>")
        return sb.toString()
    }

    private fun legendHtml(items: List<Triple<String, String, Long>>): String =
        items.filter { it.third > 0 }
            .joinToString("&nbsp;&nbsp;") { (color, label, value) -> "<font color='$color'>●</font> $label $value" }

    private data class Working(val ai: Long, val human: Long, val unknown: Long)

    private fun workingStats(stdout: String): Working {
        val stats = parseObj(stdout).getAsJsonObject("stats") ?: JsonObject()
        fun n(k: String) = stats.get(k)?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L
        return Working(n("ai_additions"), n("human_additions"), n("unknown_additions"))
    }

    private fun parseObj(s: String): JsonObject =
        runCatching { JsonParser.parseString(s.trim().ifBlank { "{}" }).asJsonObject }.getOrDefault(JsonObject())

    private fun showBalloon(editor: Editor, html: String, type: MessageType) {
        val point = JBPopupFactory.getInstance().guessBestPopupLocation(editor)
        JBPopupFactory.getInstance()
            .createHtmlTextBalloonBuilder(html, type, null)
            .setHideOnClickOutside(true)
            // 「不容易看到」反馈:8s 不够读完两组数据,延长;点击外部仍即时关闭。
            .setFadeoutTime(15_000)
            .createBalloon()
            .show(point, Balloon.Position.above)
    }
}
