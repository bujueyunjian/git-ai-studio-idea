package com.gitaistudio.idea.bridge

import com.gitaistudio.idea.bridge.JsonUtil.bool
import com.gitaistudio.idea.bridge.JsonUtil.int
import com.gitaistudio.idea.bridge.JsonUtil.str
import com.gitaistudio.idea.bridge.JsonUtil.strArray
import com.gitaistudio.idea.agents.AgentHookDetector
import com.gitaistudio.idea.cli.GitAiCli
import com.gitaistudio.idea.cli.GitAiNotFound
import com.gitaistudio.idea.cli.GitCli
import com.gitaistudio.idea.cli.ProcResult
import com.gitaistudio.idea.service.GitAiSettings
import com.gitaistudio.idea.service.RepoService
import com.gitaistudio.idea.service.StatsCache
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * 复刻桌面版 Tauri command 表面(`src/lib/api.ts` 的 ~60 个命令),通过 shell 调 git-ai / git 实现。
 *
 * - 预期内空态(未选仓库、git-ai 未装、无 HEAD)→ 返回各命令约定的 degraded 变体;
 * - 真实故障 → 抛 [DispatchError],bridge 翻成 Err 字符串,前端弹红 toast;
 * - 绝不用 0 桶兜底掩盖失败的子进程(响亮失败)。
 */
class CommandDispatcher(
    private val project: Project,
    private val emit: (String, JsonElement) -> Unit,
) {
    private val settings get() = GitAiSettings.getInstance()
    private val repoService get() = RepoService.getInstance(project)
    private val cache get() = StatsCache.getInstance(project)

    fun dispatch(cmd: String, args: JsonObject): JsonElement = when (cmd) {
        "ping" -> JsonPrimitive("pong")
        "resolve_git_ai_path" -> resolveGitAiPath()

        "get_app_settings" -> appSettingsWithProjectRepo()
        "set_app_settings" -> applySettingsPatch(args)
        "get_auto_launch_status" -> JsonPrimitive(false)
        "set_auto_launch" -> JsonPrimitive(args.bool("enabled", false))

        "current_repo" -> repoService.currentRepoEntry() ?: JsonNull.INSTANCE
        "restore_last_repo" -> repoService.currentRepoEntry() ?: JsonNull.INSTANCE
        "select_repo" -> selectRepo(args)
        "current_git_user_email" -> currentGitUserEmail()
        "detect_dirty" -> detectDirty(args)
        "list_recent_repos" -> JsonUtil.arr(settings.appSettings().strArray("recent_repos").map { JsonPrimitive(it) })
        "list_scan_roots" -> JsonUtil.arr(settings.appSettings().strArray("scan_roots").map { JsonPrimitive(it) })
        "set_scan_roots" -> setScanRoots(args)
        "discover_repos" -> discoverRepos(args)
        "open_in_explorer" -> openInExplorer(args)
        "get_aggregate_repos" -> getAggregateRepos()
        "set_aggregate_repos" -> setAggregateRepos(args)

        "get_installed_version" -> getInstalledVersion()

        "list_recent_commits" -> listRecentCommits(args.int("maxCount", 50))
        "list_recent_commits_with_stats" -> listRecentCommitsWithStats(args.int("maxCount", 50))
        "get_commit_stats" -> getCommitStats(args.str("sha"))
        "get_commit_status" -> getCommitStatus()

        "get_history" -> getHistory(args.getAsJsonObject("range"))
        "get_aggregate_history" -> getAggregateHistory(args.getAsJsonObject("range"), args.bool("onlyMine", false))
        "get_aggregate_working_status" -> getAggregateWorkingStatus()
        "get_people_breakdown" -> getPeopleBreakdown(args.getAsJsonObject("range"))
        "get_range_summary" -> getRangeSummary(args.getAsJsonObject("range"))

        "list_ai_notes" -> listAiNotes()
        "show_ai_note" -> showAiNote(args.str("sha") ?: throw DispatchError("missing sha"))
        "list_changed_files_in_commit" -> listChangedFiles(args.str("sha") ?: throw DispatchError("missing sha"))
        "list_ai_lines_in_commit" -> listAiLines(args.str("sha") ?: throw DispatchError("missing sha"))

        "list_branches" -> listBranches()
        "checkout_branch" -> checkoutBranch(args.str("name") ?: throw DispatchError("missing name"))

        "list_files_at_head" -> listFilesAtRef(headRef(args.str("sha")))
        "list_files_at_ref" -> listFilesAtRef(args.str("ref") ?: "HEAD")
        "read_file_at_head" -> readFileAtRef(headRef(args.str("sha")), args.str("file") ?: throw DispatchError("missing file"))
        "read_file_at_ref" -> readFileAtRef(args.str("ref") ?: "HEAD", args.str("file") ?: throw DispatchError("missing file"))
        "get_blame" -> getBlame(headRef(args.str("sha") ?: "HEAD"), args.str("file") ?: throw DispatchError("missing file"), args)
        "get_blame_at_ref" -> getBlame(args.str("ref") ?: "HEAD", args.str("file") ?: throw DispatchError("missing file"), args)

        "get_whoami" -> getWhoami()
        "get_show_raw" -> getShowRaw(args.str("sha") ?: throw DispatchError("missing sha"))
        "list_effective_ignore_patterns" -> listEffectiveIgnorePatterns()

        "pick_directory" -> pickDirectory(args.str("title"))
        "notify" -> { showNotification(args.str("title").orEmpty(), args.str("body").orEmpty()); JsonNull.INSTANCE }

        "clear_stats_cache" -> JsonPrimitive(cache.clear())
        "invalidate_diagnostic_cache" -> JsonNull.INSTANCE
        "run_git_ai_debug_report" -> runGitAiDebugReport(args.str("jobId") ?: throw DispatchError("missing jobId"))
        "install_hooks_official" -> installHooks(args.str("jobId") ?: throw DispatchError("missing jobId"))
        "install_hooks_for_agent" -> installHooks(args.str("jobId") ?: throw DispatchError("missing jobId"))
        "diagnose_environment" -> diagnoseEnvironment()
        "check_agent_hooks" -> JsonNull.INSTANCE
        "get_hooks_status" -> JsonUtil.obj("mode" to AgentHookDetector.claudeHookMode())
        "read_claude_settings" -> readClaudeSettings()
        "list_settings_backups" -> listSettingsBackups()
        "get_git_ai_config" -> getGitAiConfig()
        "diagnose_git_ai_daemon" -> JsonUtil.obj("kind" to "idle")

        else -> throw DispatchError("Command not implemented in plugin v1: $cmd")
    }

    // ---------- 工具 / 仓库 ----------

    private fun resolveGitAiPath(): JsonElement {
        val path = com.gitaistudio.idea.cli.ExecutableLocator.find("git-ai", settings.gitAiPath)
        return JsonArray().apply { add(path != null); add(path ?: "") }
    }

    private fun gitAiOrNull(repoDir: File?): GitAiCli? =
        runCatching { GitAiCli.resolve(repoDir, settings.gitAiPath) }.getOrNull()

    private fun selectRepo(args: JsonObject): JsonElement {
        val path = args.str("path") ?: throw DispatchError("missing path")
        val dir = repoService.selectRepo(path) ?: throw DispatchError("Not a directory: $path")
        return repoService.repoEntry(dir)
    }

    private fun currentGitUserEmail(): JsonElement {
        val dir = repoService.currentRepoDir() ?: return JsonNull.INSTANCE
        val r = GitCli.resolve(dir).configUserEmail()
        return if (r.ok && r.stdout.isNotBlank()) JsonPrimitive(r.stdout.trim()) else JsonNull.INSTANCE
    }

    private fun detectDirty(args: JsonObject): JsonElement {
        val path = args.str("path") ?: return JsonNull.INSTANCE
        val r = GitCli.resolve(File(path)).statusPorcelainZ()
        return if (r.ok) JsonPrimitive(r.stdout.isNotBlank()) else JsonNull.INSTANCE
    }

    private fun setScanRoots(args: JsonObject): JsonElement {
        val obj = settings.appSettings()
        obj.add("scan_roots", JsonUtil.arr(args.strArray("roots").map { JsonPrimitive(it) }))
        settings.saveAppSettings(obj)
        return JsonNull.INSTANCE
    }

    private fun discoverRepos(args: JsonObject): JsonElement {
        val roots = args.strArray("roots")
        val maxDepth = args.int("maxDepth", 2)
        val found = LinkedHashSet<String>()
        roots.forEach { root -> scanForGit(File(root), maxDepth, found) }
        return JsonUtil.arr(found.mapNotNull { p -> runCatching { repoService.repoEntry(File(p)) }.getOrNull() })
    }

    private fun scanForGit(dir: File, depthLeft: Int, out: MutableSet<String>) {
        if (!dir.isDirectory) return
        if (File(dir, ".git").exists()) { out.add(dir.absolutePath); return }
        if (depthLeft <= 0) return
        dir.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.forEach { scanForGit(it, depthLeft - 1, out) }
    }

    private fun openInExplorer(args: JsonObject): JsonElement {
        val path = args.str("path") ?: throw DispatchError("missing path")
        RevealFileAction.openDirectory(File(path))
        return JsonNull.INSTANCE
    }

    private fun pickDirectory(title: String?): JsonElement {
        val ref = java.util.concurrent.atomic.AtomicReference<String?>(null)
        ApplicationManager.getApplication().invokeAndWait {
            val descriptor = com.intellij.openapi.fileChooser.FileChooserDescriptorFactory.createSingleFolderDescriptor()
            title?.let { descriptor.title = it }
            val chosen = com.intellij.openapi.fileChooser.FileChooser.chooseFile(descriptor, project, null)
            ref.set(chosen?.path)
        }
        return ref.get()?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE
    }

    private fun showNotification(title: String, body: String) {
        com.intellij.notification.NotificationGroupManager.getInstance()
            .getNotificationGroup("Git AI Studio")
            .createNotification(title, body, com.intellij.notification.NotificationType.INFORMATION)
            .notify(project)
    }

    private fun getAggregateRepos(): JsonElement {
        val paths = settings.appSettings().strArray("aggregate_repos")
        return JsonUtil.arr(paths.map { p ->
            val dir = File(p)
            val valid = dir.isDirectory && File(dir, ".git").exists()
            JsonUtil.obj(
                "path" to p,
                "valid" to valid,
                "entry" to if (valid) repoService.repoEntry(dir) else JsonNull.INSTANCE,
            )
        })
    }

    private fun setAggregateRepos(args: JsonObject): JsonElement {
        val obj = settings.appSettings()
        obj.add("aggregate_repos", JsonUtil.arr(args.strArray("repos").map { JsonPrimitive(it) }))
        settings.saveAppSettings(obj)
        return JsonNull.INSTANCE
    }

    /**
     * get_app_settings 之上叠加"当前项目仓库":首次运行 last_repo 为空时,把项目 git 根填进去,
     * 让首启引导向导(RepoSetupGuide 的 open 门控含 !last_repo)直接关闭——IDE 里仓库默认就是当前工程。
     * **不持久化**:config 是应用全局的,只在本次 payload 里合成,避免一个工程的路径泄漏到其它工程/窗口。
     */
    private fun appSettingsWithProjectRepo(): JsonObject {
        val obj = settings.appSettings()
        val lastRepo = obj.get("last_repo")
        if (lastRepo == null || lastRepo.isJsonNull) {
            repoService.currentRepoDir()?.let { obj.addProperty("last_repo", it.absolutePath) }
        }
        return obj
    }

    /** `git-ai debug` 流式诊断报告:逐行推到 logs://debug/<jobId>,完成后推 exit 事件,返回退出码。 */
    private fun runGitAiDebugReport(jobId: String): JsonElement {
        val exe = com.gitaistudio.idea.cli.ExecutableLocator.find("git-ai", settings.gitAiPath)
            ?: throw DispatchError("git-ai not found on PATH")
        val topic = "logs://debug/$jobId"
        val code = com.gitaistudio.idea.cli.ProcessRunner.runStreaming(
            exe, listOf("debug"), repoService.currentRepoDir(), 15_000,
        ) { line ->
            emit(topic, JsonUtil.obj("stream" to "stdout", "line" to line, "ts" to System.currentTimeMillis()))
        }
        val exit = if (code == -1) {
            JsonUtil.obj("stream" to "exit", "code" to -1, "timeout" to true, "ts" to System.currentTimeMillis())
        } else {
            JsonUtil.obj("stream" to "exit", "code" to code, "ts" to System.currentTimeMillis())
        }
        emit(topic, exit)
        return JsonPrimitive(code)
    }

    /**
     * 官方安装 hook:流式跑幂等的 `git-ai install`,逐行推到 hooks://<jobId>/log,返回退出码。
     * Diagnostic 页的「修复缺失 hook」按钮即调它(只 await 退出码,不监听流;流事件仅为未来留口)。
     * install_hooks_for_agent 复用本实现 —— 上游 `git-ai install` 不支持按 agent 过滤,本就是整体幂等安装。
     */
    private fun installHooks(jobId: String): JsonElement {
        val exe = com.gitaistudio.idea.cli.ExecutableLocator.find("git-ai", settings.gitAiPath)
            ?: throw DispatchError("git-ai not found on PATH")
        val topic = "hooks://$jobId/log"
        val code = com.gitaistudio.idea.cli.ProcessRunner.runStreaming(
            exe, listOf("install"), repoService.currentRepoDir(), 120_000,
        ) { line ->
            emit(topic, JsonUtil.obj("stream" to "stdout", "line" to line, "ts" to System.currentTimeMillis()))
        }
        val exit = if (code == -1) {
            JsonUtil.obj("stream" to "exit", "code" to -1, "timeout" to true, "ts" to System.currentTimeMillis())
        } else {
            JsonUtil.obj("stream" to "exit", "code" to code, "ts" to System.currentTimeMillis())
        }
        emit(topic, exit)
        return JsonPrimitive(code)
    }

    /** 读 ~/.claude/settings.json 概览(Hooks 页 settings 概览卡 + 查看原文)。纯文件读,无子进程。 */
    private fun readClaudeSettings(): JsonElement {
        val f = File(System.getProperty("user.home").orEmpty(), ".claude/settings.json")
        val exists = f.isFile
        val raw = if (exists) runCatching { f.readText() }.getOrNull() else null
        return JsonUtil.obj(
            "path" to f.path,
            "exists" to exists,
            "raw_size" to (raw?.toByteArray(Charsets.UTF_8)?.size ?: 0),
            "raw" to (raw?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE),
            "mode" to AgentHookDetector.claudeHookMode(),
        )
    }

    /** Claude settings 备份列表(~/.git-ai-studio/backups/claude-settings-<ms>.json)。无目录返空,绝不抛错。 */
    private fun listSettingsBackups(): JsonElement {
        val dir = File(System.getProperty("user.home").orEmpty(), ".git-ai-studio/backups")
        val arr = JsonArray()
        if (dir.isDirectory) {
            dir.listFiles { f -> f.isFile && f.name.startsWith("claude-settings-") && f.name.endsWith(".json") }
                ?.sortedByDescending { it.name }
                ?.forEach { f ->
                    val ms = f.name.removePrefix("claude-settings-").removeSuffix(".json").toLongOrNull()
                        ?: f.lastModified()
                    arr.add(JsonUtil.obj("path" to f.path, "at_unix_ms" to ms))
                }
        }
        return arr
    }

    /** 读 ~/.git-ai/config.json(Settings 自动更新只读卡)。缺文件返默认值,初始态不算错误。 */
    private fun getGitAiConfig(): JsonElement {
        val f = File(System.getProperty("user.home").orEmpty(), ".git-ai/config.json")
        val obj = if (f.isFile) {
            runCatching { JsonParser.parseString(f.readText()).asJsonObject }.getOrNull() ?: JsonObject()
        } else {
            JsonObject()
        }
        if (!obj.has("disable_auto_updates")) obj.addProperty("disable_auto_updates", false)
        if (!obj.has("update_channel") || obj.get("update_channel").isJsonNull) obj.addProperty("update_channel", "stable")
        return obj
    }

    private fun applySettingsPatch(patch: JsonObject): JsonElement {
        val obj = settings.appSettings()
        patch.str("theme")?.let { obj.addProperty("theme", it) }
        patch.get("scan_roots")?.takeIf { it.isJsonArray }?.let { obj.add("scan_roots", it) }
        patch.str("close_behavior")?.let { obj.addProperty("close_behavior", it) }
        val notif = obj.getAsJsonObject("notifications")
        val low = notif.getAsJsonObject("low_ai_share")
        patch.get("cc_switch_auto_repair")?.let { notif.add("cc_switch_auto_repair", it) }
        patch.get("daemon_unhealthy_alert")?.let { notif.add("daemon_unhealthy_alert", it) }
        patch.get("low_ai_share_enabled")?.let { low.add("enabled", it) }
        patch.get("low_ai_share_threshold_percent")?.let { low.add("threshold_percent", it) }
        patch.get("low_ai_share_target_emails")?.let { low.add("target_emails", it) }
        patch.get("low_ai_share_remind_interval_minutes")?.let { low.add("remind_interval_minutes", it) }
        patch.get("low_ai_share_dismiss_minutes")?.let { low.add("dismiss_minutes", it) }
        patch.get("low_ai_share_realtime_enabled")?.let { low.add("realtime_enabled", it) }
        patch.get("repo_setup_seen")?.let { obj.add("repo_setup_seen", it) }
        settings.saveAppSettings(obj)
        return settings.appSettings()
    }

    private fun getInstalledVersion(): JsonElement {
        val path = com.gitaistudio.idea.cli.ExecutableLocator.find("git-ai", settings.gitAiPath)
            ?: return JsonUtil.obj("installed" to false, "version" to null, "binary_path" to null)
        val r = GitAiCli(path, null).version()
        val version = Regex("""\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?""").find(r.stdout)?.value
        return JsonUtil.obj("installed" to true, "version" to version, "binary_path" to path)
    }

    // ---------- 提交 / 归因 ----------

    private fun headRef(sha: String?): String = sha?.takeIf { it.isNotBlank() } ?: "HEAD"

    private fun getCommitStats(sha: String?): JsonElement {
        val dir = repoService.currentRepoDir() ?: return degraded("repo_missing", "reason")
        val gitAi = gitAiOrNull(dir) ?: return degraded("git_ai_missing", "reason")
        val git = GitCli.resolve(dir)
        if (!git.revParseHead().ok) return degraded("no_head", "reason")
        val target = sha?.takeIf { it.isNotBlank() } ?: git.revParseHead().stdout.trim()
        val r = gitAi.stats(target)
        if (!r.ok && r.timedOut) throw DispatchError("git-ai stats timed out")
        val stats = parseAiStats(r)
        val isMerge = isMergeCommit(git, target)
        val total = totalAdditions(stats)
        return JsonUtil.obj(
            "status" to "ok",
            "view" to statsView("commit", target, isMerge, stats, total),
        )
    }

    private fun getCommitStatus(): JsonElement {
        val dir = repoService.currentRepoDir() ?: return degraded("repo_missing", "reason")
        val gitAi = gitAiOrNull(dir) ?: return degraded("git_ai_missing", "reason")
        val r = gitAi.status()
        if (r.timedOut) throw DispatchError("git-ai status timed out")
        val root = parseJsonObjectOrEmpty(r.stdout)
        val stats = normalizeAiStats(root.getAsJsonObject("stats") ?: JsonObject())
        val total = totalAdditions(stats)
        return JsonUtil.obj(
            "status" to "ok",
            "view" to statsView("working", null, false, stats, total),
        )
    }

    private fun statsView(kind: String, sha: String?, isMerge: Boolean, stats: JsonObject, total: Long): JsonObject =
        JsonUtil.obj(
            "kind" to kind,
            "commit_sha" to (sha?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE),
            "is_merge" to isMerge,
            "stats" to stats,
            "total_additions" to total,
            "note_kind" to (deriveNoteKind(stats, total, isMerge)?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE),
        )

    private fun listRecentCommits(maxCount: Int): JsonElement {
        val dir = repoService.currentRepoDir() ?: return JsonArray()
        val commits = listCommits(dir, maxCount).first
        return JsonUtil.arr(commits.map { it.toBrief() })
    }

    private fun listRecentCommitsWithStats(maxCount: Int): JsonElement {
        val dir = repoService.currentRepoDir() ?: return degraded("repo_missing", "reason")
        val gitAi = gitAiOrNull(dir) ?: return degraded("git_ai_missing", "reason")
        val (commits, truncated) = listCommits(dir, maxCount)
        val notesOid = notesOid(dir)
        val failed = JsonArray()
        var hits = 0
        val rows = commits.map { c ->
            val (stats, cached, ok) = commitStats(dir, gitAi, c.sha, notesOid)
            if (cached) hits++
            if (!ok) failed.add(c.sha)
            val total = totalAdditions(stats)
            JsonUtil.obj(
                "sha" to c.sha, "short" to c.short, "authored_at" to c.authoredAt,
                "author_name" to c.authorName, "author_email" to c.authorEmail,
                "subject" to c.subject, "is_merge" to c.isMerge, "stats" to stats,
                "note_kind" to (deriveNoteKind(stats, total, c.isMerge)?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE),
            )
        }
        return JsonUtil.obj(
            "status" to "ok",
            "payload" to JsonUtil.obj(
                "commits" to JsonUtil.arr(rows),
                "failed_shas" to failed,
                "truncated" to truncated,
                "cache_hits" to hits,
            ),
        )
    }

    private fun getHistory(range: JsonObject): JsonElement {
        val dir = repoService.currentRepoDir() ?: return degraded("repo_missing", "reason")
        val gitAi = gitAiOrNull(dir) ?: return degraded("git_ai_missing", "reason")
        val start = System.currentTimeMillis()
        val (startMs, endMs) = resolveWindow(range)
        val (all, truncated) = listCommits(dir, HARD_CAP)
        val inWindow = all.filter { it.committerMs in startMs..endMs }
        val notesOid = notesOid(dir)
        val failed = JsonArray()
        var hits = 0
        val perCommit = JsonArray()
        val buckets = sortedMapOf<String, LongArray>() // date -> [human, unknown, ai, count]
        for (c in inWindow) {
            val (stats, cached, ok) = commitStats(dir, gitAi, c.sha, notesOid)
            if (cached) hits++
            if (!ok) failed.add(c.sha)
            perCommit.add(JsonUtil.obj(
                "sha" to c.sha, "short" to c.short, "authored_at" to c.authoredAt,
                "is_merge" to c.isMerge, "stats" to stats,
            ))
            val date = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(c.committerMs), ZONE).toString()
            val b = buckets.getOrPut(date) { LongArray(4) }
            b[0] += stats.longOf("human_additions"); b[1] += stats.longOf("unknown_additions")
            b[2] += stats.longOf("ai_additions"); b[3] += 1
        }
        val dailyBuckets = JsonUtil.arr(buckets.map { (d, b) ->
            JsonUtil.obj("date" to d, "human_additions" to b[0], "unknown_additions" to b[1],
                "ai_additions" to b[2], "commit_count" to b[3])
        })
        return JsonUtil.obj(
            "status" to "ok",
            "payload" to JsonUtil.obj(
                "range" to range, "range_start_unix_ms" to startMs, "range_end_unix_ms" to endMs,
                "total_commits_in_window" to inWindow.size, "per_commit" to perCommit,
                "daily_buckets" to dailyBuckets, "cache_hits" to hits, "cached_repo_total" to all.size,
                "failed_shas" to failed, "truncated" to truncated,
                "took_ms" to (System.currentTimeMillis() - start),
            ),
        )
    }

    /** 跨仓聚合:无显式聚合集时退化为 [当前仓库],使 IDE 内 Dashboard 默认即可用。 */
    private fun getAggregateHistory(range: JsonObject, onlyMine: Boolean): JsonElement {
        val configured = settings.appSettings().strArray("aggregate_repos")
            .map { File(it) }.filter { it.isDirectory }
        val repos = configured.ifEmpty { listOfNotNull(repoService.currentRepoDir()) }
        if (repos.isEmpty()) return degraded("no_repos_selected", "reason")
        val start = System.currentTimeMillis()
        val (startMs, endMs) = resolveWindow(range)
        val myEmail = if (onlyMine) repoService.currentRepoDir()
            ?.let { GitCli.resolve(it).configUserEmail() }?.takeIf { it.ok }?.stdout?.trim()?.lowercase() else null
        val perCommit = JsonArray()
        val failedRepos = JsonArray()
        val failedShas = JsonArray()
        val truncatedRepos = JsonArray()
        val buckets = sortedMapOf<String, LongArray>()
        var hits = 0
        for (repo in repos) {
            val gitAi = gitAiOrNull(repo)
            if (gitAi == null) { failedRepos.add(JsonUtil.obj("repo_path" to repo.absolutePath, "reason" to "git-ai not found")); continue }
            val (all, truncated) = listCommits(repo, HARD_CAP)
            if (truncated) truncatedRepos.add(repo.absolutePath)
            val notesOid = notesOid(repo)
            val inWindow = all.filter { it.committerMs in startMs..endMs && (myEmail == null || it.authorEmail.lowercase() == myEmail) }
            for (c in inWindow) {
                val (stats, cached, ok) = commitStats(repo, gitAi, c.sha, notesOid)
                if (cached) hits++
                if (!ok) failedShas.add(JsonUtil.obj("repo_path" to repo.absolutePath, "sha" to c.sha))
                perCommit.add(JsonUtil.obj(
                    "repo_path" to repo.absolutePath, "sha" to c.sha, "short" to c.short,
                    "authored_at" to c.authoredAt, "is_merge" to c.isMerge, "stats" to stats,
                ))
                val date = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(c.committerMs), ZONE).toString()
                val b = buckets.getOrPut(date) { LongArray(4) }
                b[0] += stats.longOf("human_additions"); b[1] += stats.longOf("unknown_additions")
                b[2] += stats.longOf("ai_additions"); b[3] += 1
            }
        }
        val dailyBuckets = JsonUtil.arr(buckets.map { (d, b) ->
            JsonUtil.obj("date" to d, "human_additions" to b[0], "unknown_additions" to b[1],
                "ai_additions" to b[2], "commit_count" to b[3])
        })
        return JsonUtil.obj(
            "status" to "ok",
            "payload" to JsonUtil.obj(
                "range" to range, "range_start_unix_ms" to startMs, "range_end_unix_ms" to endMs,
                "total_commits_in_window" to perCommit.size(), "per_commit" to perCommit,
                "daily_buckets" to dailyBuckets, "cache_hits" to hits,
                "failed_repos" to failedRepos, "failed_shas" to failedShas, "truncated_repos" to truncatedRepos,
                "took_ms" to (System.currentTimeMillis() - start),
            ),
        )
    }

    private fun getAggregateWorkingStatus(): JsonElement {
        val configured = settings.appSettings().strArray("aggregate_repos")
            .map { File(it) }.filter { it.isDirectory }
        val repos = configured.ifEmpty { listOfNotNull(repoService.currentRepoDir()) }
        val start = System.currentTimeMillis()
        val perRepo = JsonArray()
        val failedRepos = JsonArray()
        var human = 0L; var unknown = 0L; var ai = 0L; var withChanges = 0
        for (repo in repos) {
            val gitAi = gitAiOrNull(repo)
            if (gitAi == null) { failedRepos.add(JsonUtil.obj("repo_path" to repo.absolutePath, "reason" to "git-ai not found")); continue }
            val r = gitAi.status()
            if (r.timedOut) { failedRepos.add(JsonUtil.obj("repo_path" to repo.absolutePath, "reason" to "git-ai status timed out")); continue }
            val stats = normalizeAiStats(parseJsonObjectOrEmpty(r.stdout).getAsJsonObject("stats") ?: JsonObject())
            val h = stats.longOf("human_additions"); val u = stats.longOf("unknown_additions"); val a = stats.longOf("ai_additions")
            if (h + u + a > 0) {
                withChanges++
                human += h; unknown += u; ai += a
                perRepo.add(JsonUtil.obj("repo_path" to repo.absolutePath, "human_additions" to h, "unknown_additions" to u, "ai_additions" to a))
            }
        }
        return JsonUtil.obj(
            "repos_with_changes" to withChanges, "human_additions" to human, "unknown_additions" to unknown,
            "ai_additions" to ai, "per_repo" to perRepo, "failed_repos" to failedRepos,
            "took_ms" to (System.currentTimeMillis() - start),
        )
    }

    private fun getPeopleBreakdown(range: JsonObject): JsonElement {
        val dir = repoService.currentRepoDir() ?: return degraded("repo_missing", "reason")
        val gitAi = gitAiOrNull(dir) ?: return degraded("git_ai_missing", "reason")
        val start = System.currentTimeMillis()
        val (startMs, endMs) = resolveWindow(range)
        val (all, truncated) = listCommits(dir, HARD_CAP)
        val inWindow = all.filter { it.committerMs in startMs..endMs }
        val notesOid = notesOid(dir)
        val failed = JsonArray()
        var hits = 0
        data class Acc(var name: String, var email: String, var commits: Int, var h: Long, var u: Long, var ai: Long, val refs: JsonArray)
        val byKey = LinkedHashMap<String, Acc>()
        for (c in inWindow) {
            val (stats, cached, ok) = commitStats(dir, gitAi, c.sha, notesOid)
            if (cached) hits++
            if (!ok) failed.add(c.sha)
            val key = c.authorEmail.lowercase()
            val acc = byKey.getOrPut(key) { Acc(c.authorName, c.authorEmail, 0, 0, 0, 0, JsonArray()) }
            acc.commits++
            val h = stats.longOf("human_additions"); val u = stats.longOf("unknown_additions"); val a = stats.longOf("ai_additions")
            acc.h += h; acc.u += u; acc.ai += a
            acc.refs.add(JsonUtil.obj(
                "sha" to c.sha, "short" to c.short, "authored_at" to c.authoredAt, "subject" to c.subject,
                "is_merge" to c.isMerge, "ai_additions" to a, "human_additions" to h, "unknown_additions" to u,
            ))
        }
        var gC = 0; var gH = 0L; var gU = 0L; var gAi = 0L
        val rows = byKey.toSortedMap().map { (key, acc) ->
            gC += acc.commits; gH += acc.h; gU += acc.u; gAi += acc.ai
            JsonUtil.obj(
                "identity_key" to key, "author_name" to acc.name, "author_email" to acc.email,
                "commits" to acc.commits, "human_additions" to acc.h, "unknown_additions" to acc.u,
                "ai_additions" to acc.ai, "total_additions" to (acc.h + acc.u + acc.ai), "commit_refs" to acc.refs,
            )
        }
        return JsonUtil.obj(
            "status" to "ok",
            "payload" to JsonUtil.obj(
                "range" to range, "range_start_unix_ms" to startMs, "range_end_unix_ms" to endMs,
                "rows" to JsonUtil.arr(rows),
                "grand_total" to JsonUtil.obj("commits" to gC, "human_additions" to gH, "unknown_additions" to gU,
                    "ai_additions" to gAi, "total_additions" to (gH + gU + gAi)),
                "failed_shas" to failed, "truncated" to truncated, "cache_hits" to hits,
                "took_ms" to (System.currentTimeMillis() - start),
            ),
        )
    }

    private fun getRangeSummary(range: JsonObject): JsonElement {
        val dir = repoService.currentRepoDir() ?: return degraded("repo_missing", "reason")
        val gitAi = gitAiOrNull(dir) ?: return degraded("git_ai_missing", "reason")
        val (startMs, endMs) = resolveWindow(range)
        val (all, _) = listCommits(dir, HARD_CAP)
        val inWindow = all.filter { it.committerMs in startMs..endMs }
        if (inWindow.isEmpty()) return degraded("repo_missing", "reason")
        val newest = inWindow.first().sha
        val oldest = inWindow.last().sha
        val r = gitAi.statsRange("$oldest^", newest)
        if (r.timedOut) throw DispatchError("git-ai stats (range) timed out")
        val root = parseJsonObjectOrEmpty(r.stdout)
        val rangeStats = normalizeAiStats(root.getAsJsonObject("range_stats") ?: JsonObject())
        val authorship = root.getAsJsonObject("authorship_stats") ?: JsonObject()
        return JsonUtil.obj(
            "status" to "ok",
            "range_summary" to JsonUtil.obj("authorship_stats" to authorship, "range_stats" to rangeStats),
        )
    }

    // ---------- Notes / Diff ----------

    private fun listAiNotes(): JsonElement {
        val dir = repoService.currentRepoDir() ?: return JsonUtil.obj("commits" to JsonArray(), "unreachable" to JsonArray())
        val git = GitCli.resolve(dir)
        val r = git.notesList()
        if (!r.ok) return JsonUtil.obj("commits" to JsonArray(), "unreachable" to JsonArray())
        val shas = r.stdout.lines().mapNotNull { it.trim().split(" ").getOrNull(1) }.distinct()
        val commits = JsonArray()
        val unreachable = JsonArray()
        shas.forEach { sha ->
            val log = git.logNoWalk("%H%x1f%h%x1f%cI%x1f%an%x1f%ae%x1f%s%x1f%P", listOf(sha))
            if (log.ok && log.stdout.isNotBlank()) {
                parseCommitLine(log.stdout.trim())?.let { commits.add(it.toBrief()) }
            } else unreachable.add(sha)
        }
        return JsonUtil.obj("commits" to commits, "unreachable" to unreachable)
    }

    private fun showAiNote(sha: String): JsonElement {
        val dir = repoService.currentRepoDir() ?: throw DispatchError("No repository")
        val git = GitCli.resolve(dir)
        if (!git.revParseVerifyCommit(sha).ok) return JsonUtil.obj("status" to "unreachable", "sha" to sha)
        val r = git.notesShow(sha)
        if (!r.ok) {
            return if (r.stderr.contains("no note", ignoreCase = true) || r.stdout.isBlank())
                JsonUtil.obj("status" to "no_note", "sha" to sha)
            else throw DispatchError("git notes show failed: ${r.stderr.ifBlank { "exit ${r.exitCode}" }}")
        }
        val parsed = runCatching { JsonParser.parseString(r.stdout) }.getOrNull()
        return JsonUtil.obj("status" to "ok", "sha" to sha, "log" to (parsed ?: JsonNull.INSTANCE), "raw" to r.stdout)
    }

    private fun listChangedFiles(sha: String): JsonElement {
        val dir = repoService.currentRepoDir() ?: throw DispatchError("No repository")
        val r = GitCli.resolve(dir).diffTreeNameStatus(sha)
        if (!r.ok) throw DispatchError("git diff-tree failed: ${r.stderr.ifBlank { "exit ${r.exitCode}" }}")
        val files = JsonArray()
        val seen = HashSet<String>()
        r.stdout.lines().forEach { line ->
            val parts = line.trim().split('\t')
            if (parts.size >= 2) {
                val status = parts[0].firstOrNull()?.toString() ?: "M"
                val path = parts.last()
                if (seen.add(path)) files.add(JsonUtil.obj("path" to path, "status" to status))
            }
        }
        return JsonUtil.obj("files" to files)
    }

    private fun listAiLines(sha: String): JsonElement {
        val dir = repoService.currentRepoDir() ?: throw DispatchError("No repository")
        val git = GitCli.resolve(dir)
        val note = git.notesShow(sha)
        val byFile = JsonObject()
        if (note.ok && note.stdout.isNotBlank()) {
            runCatching { JsonParser.parseString(note.stdout).asJsonObject }.getOrNull()?.let { log ->
                log.getAsJsonArray("attestations")?.forEach { att ->
                    val a = att.asJsonObject
                    val file = a.str("file_path") ?: a.str("file") ?: return@forEach
                    val lines = JsonArray()
                    a.getAsJsonArray("line_ranges")?.forEach { lr ->
                        expandRanges(lr.asString).forEach { lines.add(it) }
                    }
                    byFile.add(file, lines)
                }
            }
        }
        return JsonUtil.obj("files" to byFile)
    }

    // ---------- 分支 / 文件 ----------

    private fun listBranches(): JsonElement {
        val dir = repoService.currentRepoDir() ?: return JsonUtil.obj("branches" to JsonArray(), "current" to JsonNull.INSTANCE)
        val r = GitCli.resolve(dir).branchList()
        val branches = JsonArray()
        var current: String? = null
        if (r.ok) r.stdout.lines().filter { it.isNotBlank() }.forEach { line ->
            val parts = line.split('\t')
            val isHead = parts.getOrNull(0) == "*"
            val name = parts.getOrNull(1).orEmpty()
            val sha = parts.getOrNull(2).orEmpty()
            if (name.isNotBlank()) {
                if (isHead) current = name
                branches.add(JsonUtil.obj("name" to name, "sha" to sha, "is_current" to isHead))
            }
        }
        return JsonUtil.obj("branches" to branches, "current" to (current?.let { JsonPrimitive(it) } ?: JsonNull.INSTANCE))
    }

    private fun checkoutBranch(name: String): JsonElement {
        val dir = repoService.currentRepoDir() ?: throw DispatchError("No repository")
        val r = GitCli.resolve(dir).checkout(name)
        return if (r.ok) JsonUtil.obj("status" to "ok", "branch" to name)
        else JsonUtil.obj("status" to "error", "message" to r.stderr.ifBlank { "checkout failed" })
    }

    private fun listFilesAtRef(ref: String): JsonElement {
        val dir = repoService.currentRepoDir() ?: return JsonUtil.obj("files" to JsonArray(), "truncated" to false)
        val r = GitCli.resolve(dir).lsTreeFiles(ref)
        if (!r.ok) throw DispatchError("git ls-tree failed: ${r.stderr.ifBlank { "exit ${r.exitCode}" }}")
        val lines = r.stdout.lines().filter { it.isNotBlank() }
        val truncated = lines.size > FILES_CAP
        return JsonUtil.obj("files" to JsonUtil.arr(lines.take(FILES_CAP).map { JsonPrimitive(it) }), "truncated" to truncated)
    }

    /** ReadFileResult = {status:"ok", text, size} | {status:"degraded", reason}。前端 CodeMirror 读 text 渲染。 */
    private fun readFileAtRef(ref: String, file: String): JsonElement {
        val dir = repoService.currentRepoDir() ?: return blameDegraded("repo_missing")
        val r = GitCli.resolve(dir).showFileAtRef(ref, file)
        if (!r.ok) return blameDegraded("file_not_in_head", JsonUtil.obj("file" to file))
        val content = r.stdout
        if (content.take(8000).any { it.code == 0 }) return blameDegraded("file_binary")
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_FILE_BYTES) {
            return blameDegraded("file_too_large", JsonUtil.obj("size" to bytes.size, "limit" to MAX_FILE_BYTES))
        }
        return JsonUtil.obj("status" to "ok", "text" to content, "size" to bytes.size)
    }

    /** BlameResult = {status:"ok", payload: BlamePayload} | {status:"degraded", reason}。 */
    private fun getBlame(ref: String, file: String, args: JsonObject): JsonElement {
        val dir = repoService.currentRepoDir() ?: return blameDegraded("repo_missing")
        val gitAi = gitAiOrNull(dir) ?: return blameDegraded("git_ai_missing")
        val ranges = args.get("ranges")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull {
            val pair = it.asJsonArray
            if (pair.size() == 2) pair[0].asInt to pair[1].asInt else null
        } ?: emptyList()
        val r = gitAi.blameAnalysis(file, ranges, ref)
        if (r.timedOut) throw DispatchError("git-ai blame-analysis timed out")
        if (!r.ok) return blameDegraded("file_not_in_head", JsonUtil.obj("file" to file))
        return JsonUtil.obj("status" to "ok", "payload" to transformBlame(parseJsonObjectOrEmpty(r.stdout)))
    }

    /** Blame/ReadFile 的 degraded 包装:{status:"degraded", reason:{kind, ...extra}}。 */
    private fun blameDegraded(kind: String, extra: JsonObject? = null): JsonObject {
        val reason = extra ?: JsonObject()
        reason.addProperty("kind", kind)
        return JsonUtil.obj("status" to "degraded", "reason" to reason)
    }

    private fun getWhoami(): JsonElement {
        val gitAi = gitAiOrNull(null) ?: return JsonUtil.obj("authenticated" to false, "raw" to "")
        val r = gitAi.whoami()
        val map = JsonObject()
        r.stdout.lines().forEach { line ->
            val idx = line.indexOf(':')
            if (idx > 0) map.addProperty(line.substring(0, idx).trim().lowercase().replace(' ', '_'), line.substring(idx + 1).trim())
        }
        return JsonUtil.obj("authenticated" to r.ok, "fields" to map, "raw" to r.stdout)
    }

    private fun getShowRaw(sha: String): JsonElement {
        val dir = repoService.currentRepoDir() ?: throw DispatchError("No repository")
        val gitAi = gitAiOrNull(dir) ?: throw DispatchError("git-ai not found")
        val r = gitAi.show(sha)
        if (!r.ok) throw DispatchError("git-ai show failed: ${r.stderr.ifBlank { "exit ${r.exitCode}" }}")
        return JsonUtil.obj("sha" to sha, "raw" to r.stdout)
    }

    private fun listEffectiveIgnorePatterns(): JsonElement {
        val dir = repoService.currentRepoDir() ?: return degraded("repo_missing", "reason")
        val gitAi = gitAiOrNull(dir) ?: return degraded("git_ai_missing", "reason")
        val r = gitAi.effectiveIgnorePatterns()
        if (!r.ok) throw DispatchError("git-ai effective-ignore-patterns failed: ${r.stderr.ifBlank { "exit ${r.exitCode}" }}")
        val patterns = parseJsonObjectOrEmpty(r.stdout).getAsJsonArray("patterns") ?: JsonArray()
        return JsonUtil.obj("status" to "ok", "payload" to JsonUtil.obj("repo_path" to dir.absolutePath, "patterns" to patterns))
    }

    private fun diagnoseEnvironment(): JsonElement {
        val startT = System.currentTimeMillis()
        val dir = repoService.currentRepoDir()
        val gitAi = gitAiOrNull(dir)
        if (gitAi == null) {
            return JsonUtil.obj(
                "generated_at_unix_ms" to System.currentTimeMillis(),
                "took_ms" to (System.currentTimeMillis() - startT),
                "repo" to (dir?.let { repoService.repoEntry(it) } ?: JsonNull.INSTANCE),
                "report" to JsonUtil.obj("ok" to false, "sections" to JsonArray(), "raw" to ""),
                "agents" to JsonArray(),
                "degraded" to JsonUtil.obj("reason" to "git_ai_missing"),
            )
        }
        val dbg = gitAi.debug()
        val version = Regex("""\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?""").find(gitAi.version().stdout)?.value
        return JsonUtil.obj(
            "generated_at_unix_ms" to System.currentTimeMillis(),
            "took_ms" to (System.currentTimeMillis() - startT),
            "repo" to (dir?.let { repoService.repoEntry(it) } ?: JsonNull.INSTANCE),
            "report" to JsonUtil.obj(
                "ok" to dbg.ok,
                "git_ai_version" to (version ?: ""),
                "sections" to JsonArray(),
                "raw" to dbg.stdout.ifBlank { dbg.stderr },
            ),
            "agents" to AgentHookDetector.detectAll(),
            "degraded" to JsonNull.INSTANCE,
        )
    }

    // ---------- 内部:stats 解析 / commit 列表 / 时间窗口 ----------

    private data class CommitMeta(
        val sha: String, val short: String, val authoredAt: String,
        val authorName: String, val authorEmail: String, val subject: String,
        val parents: List<String>, val committerMs: Long,
    ) {
        val isMerge get() = parents.size > 1
        fun toBrief(): JsonObject = JsonUtil.obj(
            "sha" to sha, "short" to short, "authored_at" to authoredAt,
            "author_name" to authorName, "author_email" to authorEmail, "subject" to subject,
            "parents" to JsonUtil.arr(parents.map { JsonPrimitive(it) }), "is_merge" to isMerge,
        )
    }

    private fun listCommits(dir: File, maxCount: Int): Pair<List<CommitMeta>, Boolean> {
        val r = GitCli.resolve(dir).logRecent(maxCount, LOG_FORMAT)
        if (!r.ok) return emptyList<CommitMeta>() to false
        val list = r.stdout.split('\n').mapNotNull { rec -> parseCommitLine(rec.trim()) }
        return list to (list.size >= maxCount)
    }

    private fun parseCommitLine(rec: String): CommitMeta? {
        if (rec.isBlank()) return null
        val f = rec.split('\u001F')
        if (f.size < 7) return null
        val parents = f[6].trim().split(' ').filter { it.isNotBlank() }
        val ms = runCatching { OffsetDateTime.parse(f[2].trim()).toInstant().toEpochMilli() }.getOrDefault(0L)
        return CommitMeta(f[0].trim(), f[1].trim(), f[2].trim(), f[3].trim(), f[4].trim(), f[5], parents, ms)
    }

    private fun commitStats(dir: File, gitAi: GitAiCli, sha: String, notesOid: String): Triple<JsonObject, Boolean, Boolean> {
        val key = "${dir.absolutePath}|$sha|$notesOid"
        cache.get(key)?.let { return Triple(it, true, true) }
        val r = gitAi.stats(sha)
        if (r.timedOut) return Triple(normalizeAiStats(JsonObject()), false, false)
        if (!r.ok && r.stdout.isBlank()) return Triple(normalizeAiStats(JsonObject()), false, false)
        val stats = parseAiStats(r)
        cache.put(key, stats)
        return Triple(stats, false, true)
    }

    private fun notesOid(dir: File): String =
        GitCli.resolve(dir).notesRefOid().let { if (it.ok) it.stdout.trim() else "no-notes" }

    private fun parseAiStats(r: ProcResult): JsonObject =
        normalizeAiStats(parseJsonObjectOrEmpty(r.stdout))

    private fun parseJsonObjectOrEmpty(s: String): JsonObject =
        runCatching { JsonParser.parseString(s.trim().ifBlank { "{}" }).asJsonObject }.getOrDefault(JsonObject())

    private fun normalizeAiStats(o: JsonObject): JsonObject = JsonUtil.obj(
        "human_additions" to o.longOf("human_additions"),
        "unknown_additions" to o.longOf("unknown_additions"),
        "ai_additions" to o.longOf("ai_additions"),
        "ai_accepted" to o.longOf("ai_accepted"),
        "git_diff_deleted_lines" to o.longOf("git_diff_deleted_lines"),
        "git_diff_added_lines" to o.longOf("git_diff_added_lines"),
        "tool_model_breakdown" to (o.getAsJsonObject("tool_model_breakdown") ?: JsonObject()),
    )

    private fun totalAdditions(stats: JsonObject): Long =
        stats.longOf("human_additions") + stats.longOf("unknown_additions") + stats.longOf("ai_additions")

    private fun deriveNoteKind(stats: JsonObject, total: Long, isMerge: Boolean): String? {
        if (isMerge) return "merge"
        if (total == 0L) return "empty_additions"
        val noAi = stats.longOf("ai_additions") == 0L && stats.longOf("ai_accepted") == 0L
        if (noAi && stats.longOf("unknown_additions") > 0L) return "working_logs_missing"
        return null
    }

    private fun isMergeCommit(git: GitCli, sha: String): Boolean {
        val r = git.logNoWalk("%P", listOf(sha))
        return r.ok && r.stdout.trim().split(' ').filter { it.isNotBlank() }.size > 1
    }

    private fun expandRanges(spec: String): List<Int> = buildList {
        spec.split(',').forEach { part ->
            val t = part.trim()
            if (t.contains('-')) {
                val (a, b) = t.split('-').map { it.trim().toIntOrNull() ?: return@forEach }
                for (i in a..b) add(i)
            } else t.toIntOrNull()?.let { add(it) }
        }
    }

    /** git-ai blame-analysis 结果 → 前端 BlamePayload。AI 行(value 是 prompt hash)压缩为连续区间。 */
    private fun transformBlame(result: JsonObject): JsonObject {
        val lineAuthors = result.getAsJsonObject("line_authors") ?: JsonObject()
        val promptRecords = result.getAsJsonObject("prompt_records") ?: JsonObject()
        val aiByLine = sortedMapOf<Int, String>()
        for ((k, v) in lineAuthors.entrySet()) {
            val line = k.toIntOrNull() ?: continue
            val author = v.asString
            if (promptRecords.has(author)) aiByLine[line] = author
        }
        val lines = JsonObject()
        var runStart = -1; var prev = -1; var prevId: String? = null
        fun flush() {
            if (runStart > 0 && prevId != null) {
                val keyName = if (runStart == prev) "$runStart" else "$runStart-$prev"
                lines.addProperty(keyName, prevId)
            }
        }
        for ((line, id) in aiByLine) {
            if (id == prevId && line == prev + 1) { prev = line }
            else { flush(); runStart = line; prev = line; prevId = id }
        }
        flush()
        return JsonUtil.obj(
            "lines" to lines,
            "prompts" to promptRecords,
            "metadata" to (result.getAsJsonObject("metadata") ?: JsonObject()),
            "hunks" to (result.getAsJsonArray("blame_hunks") ?: JsonArray()),
        )
    }

    private fun resolveWindow(range: JsonObject?): Pair<Long, Long> {
        val now = System.currentTimeMillis()
        if (range == null) return (now - 30L * 86_400_000) to now
        val kind = range.str("kind") ?: "last_n_days"
        val today = LocalDate.now(ZONE)
        fun startOfDay(d: LocalDate) = d.atStartOfDay(ZONE).toInstant().toEpochMilli()
        fun endOfDay(d: LocalDate) = d.atTime(23, 59, 59, 999_000_000).atZone(ZONE).toInstant().toEpochMilli()
        return when (kind) {
            "today" -> startOfDay(today) to now
            "yesterday" -> startOfDay(today.minusDays(1)) to endOfDay(today.minusDays(1))
            "this_week" -> startOfDay(today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))) to now
            "last_week" -> {
                val mon = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1)
                startOfDay(mon) to endOfDay(mon.plusDays(6))
            }
            "this_month" -> startOfDay(today.withDayOfMonth(1)) to now
            "last_month" -> {
                val first = today.withDayOfMonth(1).minusMonths(1)
                startOfDay(first) to endOfDay(first.with(TemporalAdjusters.lastDayOfMonth()))
            }
            "last_n_days" -> {
                val days = range.int("days", 30).toLong()
                (now - days * 86_400_000) to now
            }
            "custom" -> (range.get("start_unix_ms")?.asLong ?: (now - 30L * 86_400_000)) to (range.get("end_unix_ms")?.asLong ?: now)
            else -> (now - 30L * 86_400_000) to now
        }
    }

    private fun degraded(kind: String, key: String): JsonObject =
        JsonUtil.obj("status" to "degraded", "reason" to JsonUtil.obj("kind" to kind))

    private fun JsonObject.longOf(key: String): Long =
        get(key)?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asLong ?: 0L

    companion object {
        private const val HARD_CAP = 500
        private const val FILES_CAP = 50_000
        private const val MAX_FILE_BYTES = 512 * 1024
        private val ZONE: ZoneId = ZoneId.systemDefault()
        // sha, short, committer ISO, author name, author email, subject, parents
        private const val LOG_FORMAT = "%H%x1f%h%x1f%cI%x1f%an%x1f%ae%x1f%s%x1f%P"
    }
}
