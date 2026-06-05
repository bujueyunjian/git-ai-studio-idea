package com.gitaistudio.idea.editor

import com.gitaistudio.idea.GitAiBundle
import com.gitaistudio.idea.cli.GitAiCli
import com.gitaistudio.idea.cli.GitAiNotFound
import com.gitaistudio.idea.service.GitAiSettings
import com.gitaistudio.idea.service.RepoService
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.nio.file.Paths

/**
 * 切换当前编辑器的"AI 行级归因"gutter:对当前文件跑 `git-ai blame --json`(HEAD),
 * 把 AI 行标紫、人工行标蓝。再次触发关闭。这是 IDE 独有、桌面版做不到的差异化能力。
 */
class AnnotateAttributionAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.getData(CommonDataKeys.EDITOR) != null &&
            e.getData(CommonDataKeys.VIRTUAL_FILE) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return notify(project, GitAiBundle.message("action.annotate.noFile"))
        val vfile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return notify(project, GitAiBundle.message("action.annotate.noFile"))

        if (editor.getUserData(ACTIVE) == true) {
            editor.gutter.closeAllAnnotations()
            editor.putUserData(ACTIVE, false)
            return
        }

        val repo = RepoService.getInstance(project).currentRepoDir()
            ?: return notify(project, GitAiBundle.message("action.annotate.notRepo"))
        val rel = relativePath(repo, vfile)
            ?: return notify(project, GitAiBundle.message("action.annotate.notRepo"))
        val totalLines = editor.document.lineCount

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, GitAiBundle.message("action.annotate.running"), true) {
            private var result: Map<Int, LineAttribution>? = null
            private var error: String? = null

            override fun run(indicator: ProgressIndicator) {
                try {
                    val cli = GitAiCli.resolve(repo, GitAiSettings.getInstance().gitAiPath)
                    val r = cli.blameJson(rel, emptyList())
                    if (r.timedOut) { error = "git-ai blame timed out"; return }
                    if (!r.ok) { error = r.stderr.ifBlank { "exit ${r.exitCode}" }; return }
                    result = BlameAttributionSupport.parseLineAttributions(r.stdout, totalLines)
                } catch (ex: GitAiNotFound) {
                    error = "git-ai not found on PATH"
                } catch (ex: Throwable) {
                    error = ex.message ?: ex.toString()
                }
            }

            override fun onSuccess() {
                val map = result
                if (map == null) {
                    notify(project, GitAiBundle.message("action.annotate.failed", error ?: "unknown"))
                    return
                }
                ApplicationManager.getApplication().invokeLater {
                    editor.gutter.registerTextAnnotation(AiAttributionGutter(map) { editor.putUserData(ACTIVE, false) })
                    editor.putUserData(ACTIVE, true)
                }
            }
        })
    }

    private fun relativePath(repo: File, vfile: VirtualFile): String? {
        val repoPath = repo.toPath().toAbsolutePath().normalize()
        val filePath = Paths.get(vfile.path).toAbsolutePath().normalize()
        if (!filePath.startsWith(repoPath)) return null
        return repoPath.relativize(filePath).toString().replace('\\', '/')
    }

    private fun notify(project: Project, message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Git AI Studio")
            .createNotification(message, NotificationType.WARNING)
            .notify(project)
    }

    companion object {
        private val ACTIVE = Key.create<Boolean>("gitai.attribution.active")
    }
}
