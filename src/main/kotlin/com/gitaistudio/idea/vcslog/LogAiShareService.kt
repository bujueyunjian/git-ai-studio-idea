package com.gitaistudio.idea.vcslog

import com.gitaistudio.idea.cli.GitAiCli
import com.gitaistudio.idea.service.GitAiSettings
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * VCS Log「AI 占比」列的数据源。列的 getValue 在 EDT 同步调用,只能读内存缓存([cachedPct]);
 * 未命中时排队,去抖后在后台线程批量跑 `git-ai stats <sha>` 填缓存,完成后回调让 Log 表 repaint。
 *
 * 缓存按 sha 键(sha 变即内容变,天然失效);会话内内存缓存,关工程即清。
 */
@Service(Service.Level.PROJECT)
class LogAiShareService {

    /** sha -> AI 占比(0..100);-1 = 已算但无归因(merge/空/不可解析),不再重算。 */
    private val pct = ConcurrentHashMap<String, Int>()
    private val pending = LinkedHashSet<Pair<String, String>>() // (rootPath, sha)
    @Volatile private var scheduled = false
    @Volatile private var onUpdated: (() -> Unit)? = null

    /** EDT 安全:仅读内存。null = 尚未计算(调用方应 requestWarm)。 */
    fun cachedPct(sha: String): Int? = pct[sha]

    fun requestWarm(rootPath: String, sha: String, onUpdated: () -> Unit) {
        this.onUpdated = onUpdated
        if (pct.containsKey(sha)) return
        synchronized(pending) { pending.add(rootPath to sha) }
        scheduleFlush()
    }

    private fun scheduleFlush() {
        if (scheduled) return
        scheduled = true
        AppExecutorUtil.getAppScheduledExecutorService().schedule({ flush() }, 200, TimeUnit.MILLISECONDS)
    }

    private fun flush() {
        scheduled = false
        val batch = synchronized(pending) { pending.toList().also { pending.clear() } }
        if (batch.isEmpty()) return
        val explicitPath = GitAiSettings.getInstance().gitAiPath
        var changed = false
        batch.groupBy({ it.first }, { it.second }).forEach { (rootPath, shas) ->
            val cli = runCatching { GitAiCli.resolve(File(rootPath), explicitPath) }.getOrNull()
            if (cli == null) {
                shas.forEach { if (pct.putIfAbsent(it, -1) == null) changed = true }
                return@forEach
            }
            shas.forEach { sha ->
                if (pct.containsKey(sha)) return@forEach
                val r = cli.stats(sha)
                pct[sha] = if (r.ok) sharePct(r.stdout) else -1
                changed = true
            }
        }
        if (changed) onUpdated?.let { cb -> ApplicationManager.getApplication().invokeLater(cb) }
        // 期间可能又有新请求入队,补一次 flush
        if (synchronized(pending) { pending.isNotEmpty() }) scheduleFlush()
    }

    /** 解析 git-ai stats 输出 → AI 占比;total=human+unknown+ai,为 0 返 -1。 */
    private fun sharePct(stdout: String): Int {
        val o = runCatching { JsonParser.parseString(stdout.trim().ifBlank { "{}" }).asJsonObject }.getOrNull()
            ?: return -1
        fun n(k: String) = o.get(k)?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L
        val ai = n("ai_additions")
        val total = ai + n("human_additions") + n("unknown_additions")
        if (total <= 0L) return -1
        return ((ai * 100 + total / 2) / total).toInt()
    }

    companion object {
        fun getInstance(project: Project): LogAiShareService = project.getService(LogAiShareService::class.java)
    }
}
