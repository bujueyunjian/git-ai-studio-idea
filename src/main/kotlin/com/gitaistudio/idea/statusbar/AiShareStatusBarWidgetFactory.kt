package com.gitaistudio.idea.statusbar

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory

class AiShareStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = AiShareStatusBarWidget.WIDGET_ID
    override fun getDisplayName(): String = "Git AI: AI Share"
    override fun isAvailable(project: Project): Boolean = true
    override fun createWidget(project: Project): StatusBarWidget = AiShareStatusBarWidget(project)
    override fun disposeWidget(widget: StatusBarWidget) = Disposer.dispose(widget)
    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}
