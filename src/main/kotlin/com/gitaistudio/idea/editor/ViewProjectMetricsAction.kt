package com.gitaistudio.idea.editor

import com.gitaistudio.idea.toolwindow.WebUiPanel
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/** 编辑器 / 项目视图右键:在 Git AI Studio 工具窗口里打开当前项目的 Dashboard(归因总览)。 */
class ViewProjectMetricsAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let { WebUiPanel.openWebviewAt(it, "#/dashboard") }
    }
}
