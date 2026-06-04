package com.gitaistudio.idea.update

import com.google.gson.JsonParser
import com.intellij.ide.BrowserUtil
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.util.io.HttpRequests
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 启动时检查插件自身是否有新版本:后台查 GitHub releases/latest,版本更新就弹 IDE 通知(带「下载更新」)。
 *
 * 隐私边界:这是**唯一**的外网调用,只取版本号(tag_name),不上传任何用户代码/归因数据(对齐桌面版 ADR-010)。
 * 仓库私有 / 网络不可达时静默 no-op(404/异常被吞),不打扰用户、不报错。
 */
class UpdateCheckActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        if (!CHECKED.compareAndSet(false, true)) return
        // 阻塞 HTTP 放到 pooled 线程,不占启动协程
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching { check(project) }.onFailure { LOG.debug("update check skipped: ${it.message}") }
        }
    }

    private fun check(project: Project) {
        val current = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))?.version ?: return
        val json = HttpRequests.request(LATEST_API)
            .connectTimeout(5000).readTimeout(5000)
            .accept("application/vnd.github+json")
            .readString()
        val obj = JsonParser.parseString(json).asJsonObject
        val tag = obj.get("tag_name")?.takeIf { !it.isJsonNull }?.asString ?: return
        val url = obj.get("html_url")?.takeIf { !it.isJsonNull }?.asString ?: RELEASES_URL
        if (!isNewer(tag.removePrefix("v"), current)) return
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Git AI Studio")
            .createNotification(
                "Git AI Studio $tag 可用",
                "当前 v$current。前往下载最新版。",
                NotificationType.INFORMATION,
            )
            .addAction(NotificationAction.createSimple("下载更新", Runnable { BrowserUtil.browse(url) }))
            .notify(project)
    }

    /** 纯数值 semver 比较:latest 是否高于 current(0.3.2 > 0.3.1)。 */
    private fun isNewer(latest: String, current: String): Boolean {
        fun parts(v: String) = v.split('.', '-').mapNotNull { it.toIntOrNull() }
        val l = parts(latest)
        val c = parts(current)
        for (i in 0 until maxOf(l.size, c.size)) {
            val a = l.getOrElse(i) { 0 }
            val b = c.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    companion object {
        private val LOG = Logger.getInstance(UpdateCheckActivity::class.java)
        private val CHECKED = AtomicBoolean(false)
        private const val PLUGIN_ID = "com.gitaistudio.idea"
        private const val LATEST_API = "https://api.github.com/repos/bujueyunjian/git-ai-studio-idea/releases/latest"
        private const val RELEASES_URL = "https://github.com/bujueyunjian/git-ai-studio-idea/releases"
    }
}
