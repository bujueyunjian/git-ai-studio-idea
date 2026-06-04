package com.gitaistudio.idea.editor

import com.gitaistudio.idea.GitAiBundle
import com.gitaistudio.idea.service.RepoService
import com.gitaistudio.idea.toolwindow.WebUiPanel
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import java.net.URLEncoder

/**
 * 编辑器 / 项目视图右键:在 Git AI Studio 工具窗口里打开本文件的归因下钻(Stats 页的逐行 blame 视图)。
 * 只要 VIRTUAL_FILE(不要求 EDITOR),故在项目视图里也可用;目录除外。
 */
class ViewFileAttributionAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val vf = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = vf != null && !vf.isDirectory
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val vfile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val repo = RepoService.getInstance(project).currentRepoDir()
            ?: return GitAiActionSupport.warn(project, GitAiBundle.message("action.annotate.notRepo"))
        val rel = GitAiActionSupport.relativePath(repo, vfile)
            ?: return GitAiActionSupport.warn(project, GitAiBundle.message("action.annotate.notRepo"))
        val encoded = URLEncoder.encode(rel, "UTF-8")
        WebUiPanel.openWebviewAt(project, "#/stats/HEAD?file=$encoded")
    }
}
