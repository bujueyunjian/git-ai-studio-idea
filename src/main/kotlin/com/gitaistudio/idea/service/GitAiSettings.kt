package com.gitaistudio.idea.service

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * 应用级持久化设置。对应桌面版 `~/.git-ai-studio/config.json` 的全局定位
 * (scan_roots / recent_repos / 通知配置等跨项目共享)。
 *
 * 内部存两块:
 * - [State.gitAiPath]:用户显式指定的 git-ai 可执行路径(为空则走 PATH 自动解析)。
 * - [State.appSettingsJson]:前端 `AppSettings` 的序列化 JSON,作为前端设置的唯一真相源。
 *   缺字段时按 [DEFAULT_APP_SETTINGS] 深合并,容忍旧配置/部分写入。
 */
@State(name = "GitAiStudioSettings", storages = [Storage("git-ai-studio.xml")])
class GitAiSettings : PersistentStateComponent<GitAiSettings.State> {

    class State {
        @JvmField var gitAiPath: String = ""
        @JvmField var appSettingsJson: String = ""
    }

    private var state = State()

    override fun getState(): State = state
    override fun loadState(s: State) { state = s }

    val gitAiPath: String? get() = state.gitAiPath.ifBlank { null }

    fun setGitAiPath(path: String?) { state.gitAiPath = path.orEmpty() }

    /** 返回完整 AppSettings(已与默认值深合并),供 get_app_settings。 */
    fun appSettings(): JsonObject {
        val defaults = JsonParser.parseString(DEFAULT_APP_SETTINGS).asJsonObject
        val stored = runCatching { JsonParser.parseString(state.appSettingsJson).asJsonObject }.getOrNull()
        return if (stored == null) defaults else deepMerge(defaults, stored)
    }

    fun saveAppSettings(obj: JsonObject) { state.appSettingsJson = obj.toString() }

    private fun deepMerge(base: JsonObject, override: JsonObject): JsonObject {
        val out = base.deepCopy()
        for ((k, v) in override.entrySet()) {
            val bv = out.get(k)
            if (v.isJsonObject && bv != null && bv.isJsonObject) {
                out.add(k, deepMerge(bv.asJsonObject, v.asJsonObject))
            } else {
                out.add(k, v)
            }
        }
        return out
    }

    companion object {
        fun getInstance(): GitAiSettings =
            ApplicationManager.getApplication().getService(GitAiSettings::class.java)

        /** 满足前端 AppSettings 类型的默认值;桌面专属字段(pet/close_behavior)保留以兼容前端,但插件里不驱动行为。 */
        const val DEFAULT_APP_SETTINGS = """
        {
          "scan_roots": [],
          "recent_repos": [],
          "last_repo": null,
          "theme": null,
          "close_behavior": "exit",
          "notifications": {
            "cc_switch_auto_repair": false,
            "low_ai_share": {
              "enabled": false,
              "threshold_percent": null,
              "target_emails": [],
              "remind_interval_minutes": null,
              "dismiss_minutes": null,
              "realtime_enabled": null
            },
            "daemon_unhealthy_alert": false
          },
          "repo_setup_seen": false,
          "pet": {
            "enabled": false,
            "theme_id": null,
            "position": null,
            "size": null,
            "opacity": null,
            "alert_interval_sec": null
          },
          "aggregate_repos": []
        }
        """
    }
}
