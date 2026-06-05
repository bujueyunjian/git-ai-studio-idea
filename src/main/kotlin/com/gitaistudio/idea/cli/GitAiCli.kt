package com.gitaistudio.idea.cli

import com.google.gson.JsonObject
import java.io.File

/** git-ai 未找到。上层据此返回 degraded 空态(而非红错)。 */
class GitAiNotFound : RuntimeException("git-ai executable not found on PATH")

/** git-ai 子进程非零退出。携带退出码与 stderr,供上层 classify。 */
class GitAiFailed(val code: Int, val errText: String) :
    RuntimeException("git-ai exited with $code: $errText")

/**
 * 封装外部 `git-ai` CLI 的子命令调用。参数顺序、flag、超时严格对齐桌面版
 * `src-tauri/src/git_ai/`(git-ai 上游是唯一权威)。
 */
class GitAiCli(private val exe: String, private val repoDir: File?) {

    fun stats(sha: String?): ProcResult =
        run(buildList { add("stats"); if (!sha.isNullOrBlank()) add(sha); add("--json") }, 15_000)

    fun status(): ProcResult = run(listOf("status", "--json"), 15_000)

    /** squash 视角的区间归因(hook 覆盖率)。固有较慢,单独 180s 超时。 */
    fun statsRange(startSha: String, endSha: String): ProcResult =
        run(listOf("stats", "$startSha..$endSha", "--json"), 180_000)

    /** `git-ai blame <file> --json`;指定历史 ref 时走 git blame 兼容形态 `git-ai blame <ref> -- <file> --json`。 */
    fun blameJson(file: String, ranges: List<Pair<Int, Int>>, newestCommit: String): ProcResult {
        val args = buildList {
            add("blame")
            add("--json")
            ranges.forEach { (start, end) ->
                add("-L")
                add("$start,$end")
            }
            if (newestCommit.isNotBlank() && newestCommit != "HEAD") {
                add(newestCommit)
                add("--")
            }
            add(file)
        }
        return run(args, 45_000)
    }

    fun show(sha: String): ProcResult = run(listOf("show", sha.trim()), 15_000)

    fun whoami(): ProcResult =
        ProcessRunner.run(exe, listOf("whoami"), null, 10_000) // whoami 不绑定仓库

    fun version(): ProcResult = ProcessRunner.run(exe, listOf("--version"), null, 5_000)

    fun debug(): ProcResult = run(listOf("debug"), 30_000)

    /** 生效 ignore patterns。上游严格 JSON(deny_unknown_fields),两个数组字段必须给空数组。 */
    fun effectiveIgnorePatterns(): ProcResult {
        val payload = JsonObject().apply {
            add("user_patterns", com.google.gson.JsonArray())
            add("extra_patterns", com.google.gson.JsonArray())
        }
        return run(listOf("effective-ignore-patterns", "--json", payload.toString()), 5_000)
    }

    private fun run(args: List<String>, timeoutMs: Long): ProcResult =
        ProcessRunner.run(exe, args, repoDir, timeoutMs)

    companion object {
        /** 解析 git-ai;找不到抛 [GitAiNotFound]。 */
        fun resolve(repoDir: File?, explicitPath: String?): GitAiCli {
            val exe = ExecutableLocator.find("git-ai", explicitPath) ?: throw GitAiNotFound()
            return GitAiCli(exe, repoDir)
        }
    }
}
