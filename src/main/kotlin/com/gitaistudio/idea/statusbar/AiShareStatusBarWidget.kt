package com.gitaistudio.idea.statusbar

import com.gitaistudio.idea.cli.GitAiCli
import com.gitaistudio.idea.editor.BlameAttributionSupport
import com.gitaistudio.idea.editor.GitAiActionSupport
import com.gitaistudio.idea.service.GitAiSettings
import com.gitaistudio.idea.service.RepoService
import com.gitaistudio.idea.toolwindow.WebUiPanel
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.util.Consumer
import java.awt.event.MouseEvent
import java.net.URLEncoder

/**
 * 状态栏常驻显示「当前编辑文件的 AI 占比」(如 `AI 34%`),切换编辑器即刷新。
 * 这是 IDE 独有、桌面版做不到的能力——把归因变成边写边看的环境信息。
 * 点击 → 打开 Git AI Studio 工具窗口,跳到该文件的逐行归因(无文件时跳 Dashboard)。
 */
class AiShareStatusBarWidget(private val project: Project) :
    StatusBarWidget, StatusBarWidget.TextPresentation {

    private var statusBar: StatusBar? = null
    @Volatile private var text: String = DEFAULT_TEXT
    @Volatile private var tooltip: String = "Git AI · AI authorship of the current file"
    @Volatile private var currentRel: String? = null

    override fun ID(): String = WIDGET_ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        val conn = project.messageBus.connect(this)
        conn.subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) = refresh(event.newFile)
            },
        )
        refresh(FileEditorManager.getInstance(project).selectedFiles.firstOrNull())
    }

    override fun dispose() {}

    // ── TextPresentation ──
    override fun getText(): String = text
    override fun getAlignment(): Float = 0.5f
    override fun getTooltipText(): String = tooltip
    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer {
        val rel = currentRel
        val hash = if (rel != null) "#/stats/HEAD?file=" + URLEncoder.encode(rel, "UTF-8") else "#/dashboard"
        WebUiPanel.openWebviewAt(project, hash)
    }

    private fun refresh(file: VirtualFile?) {
        if (file == null || file.isDirectory) {
            currentRel = null
            update(DEFAULT_TEXT, "Git AI · open a file to see its AI share")
            return
        }
        val totalLines = FileDocumentManager.getInstance().getDocument(file)?.lineCount ?: 0
        ApplicationManager.getApplication().executeOnPooledThread {
            val repo = RepoService.getInstance(project).currentRepoDir()
            val rel = repo?.let { GitAiActionSupport.relativePath(it, file) }
            currentRel = rel
            if (repo == null || rel == null) {
                update(DEFAULT_TEXT, "Git AI · file not in a git-ai repository")
                return@executeOnPooledThread
            }
            val cli = runCatching { GitAiCli.resolve(repo, GitAiSettings.getInstance().gitAiPath) }.getOrNull()
            if (cli == null) {
                update(DEFAULT_TEXT, "Git AI · git-ai not found on PATH")
                return@executeOnPooledThread
            }
            val r = cli.blameJson(rel, emptyList())
            if (!r.ok) {
                update(DEFAULT_TEXT, "Git AI · attribution unavailable")
                return@executeOnPooledThread
            }
            val share = BlameAttributionSupport.fileShare(r.stdout, totalLines)
            val ai = share.ai
            val total = share.total
            if (total == 0) {
                update(DEFAULT_TEXT, "Git AI · no attributed lines in ${file.name}")
            } else {
                val pct = share.pct
                update("AI $pct%", "Git AI · ${file.name}: AI $ai · You ${total - ai} (of $total attributed lines) — click to open")
            }
        }
    }

    private fun update(newText: String, newTooltip: String) {
        text = newText
        tooltip = newTooltip
        ApplicationManager.getApplication().invokeLater { statusBar?.updateWidget(WIDGET_ID) }
    }

    companion object {
        const val WIDGET_ID = "GitAiStudio.AiShare"
        private const val DEFAULT_TEXT = "Git AI"
    }
}
