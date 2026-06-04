package com.gitaistudio.idea.toolwindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class GitAiToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val cf = ContentFactory.getInstance()

        // Tab 1:总览(复用的 webview 仪表盘)
        val web = WebUiPanel(project)
        Disposer.register(toolWindow.disposable, web)
        toolWindow.contentManager.addContent(cf.createContent(web, "总览", false))

        // Tab 2:按文件(原生 JBTable,当前提交每文件 AI 占比,IDE 风格)
        val byFile = com.gitaistudio.idea.filestable.AiByFilePanel(project)
        toolWindow.contentManager.addContent(cf.createContent(byFile, "按文件", false))
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}
