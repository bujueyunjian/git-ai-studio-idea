package com.gitaistudio.idea.service

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap

/**
 * 单 commit 的 git-ai stats 结果缓存。键 = repoPath|sha|notesOid,
 * 失效信号是 refs/notes/ai 的 OID(打标变化即换 key,旧 entry 自然失效)。
 *
 * 桌面版用 SQLite 跨重启持久化;插件 v1 用会话内内存缓存(关 IDE 即清),
 * 对交互式使用足够;持久化留作后续增强。
 */
class StatsCache {
    private val map = ConcurrentHashMap<String, String>()

    fun get(key: String): JsonObject? =
        map[key]?.let { runCatching { JsonParser.parseString(it).asJsonObject }.getOrNull() }

    fun put(key: String, value: JsonObject) { map[key] = value.toString() }

    fun clear(): Int {
        val n = map.size
        map.clear()
        return n
    }

    companion object {
        fun getInstance(project: Project): StatsCache = project.getService(StatsCache::class.java)
    }
}
