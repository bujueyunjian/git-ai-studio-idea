package com.gitaistudio.idea.editor

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.nio.file.Paths

/** 编辑器/项目视图动作的共享小工具:仓库相对路径解析 + IDE 气泡通知。 */
object GitAiActionSupport {

    /** 计算 [vfile] 相对 [repo] 根的 POSIX 路径;不在仓库内返回 null。 */
    fun relativePath(repo: File, vfile: VirtualFile): String? {
        val repoPath = repo.toPath().toAbsolutePath().normalize()
        val filePath = Paths.get(vfile.path).toAbsolutePath().normalize()
        if (!filePath.startsWith(repoPath)) return null
        return repoPath.relativize(filePath).toString().replace('\\', '/')
    }

    fun warn(project: Project, message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Git AI Studio")
            .createNotification(message, NotificationType.WARNING)
            .notify(project)
    }
}
