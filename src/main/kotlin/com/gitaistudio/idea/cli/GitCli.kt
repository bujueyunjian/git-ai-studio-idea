package com.gitaistudio.idea.cli

import java.io.File

/**
 * 直接调 `git` 的子命令(notes 读取、ref 校验、文件内容、分支等)。
 * 这些走原生 git 而非 git-ai —— git-ai 不暴露这些。参数对齐桌面版 commands 目录。
 */
class GitCli(private val exe: String, private val repoDir: File?) {

    fun revParseHead(): ProcResult = run(listOf("rev-parse", "HEAD"), 5_000)

    fun revParseAbbrevHead(): ProcResult = run(listOf("rev-parse", "--abbrev-ref", "HEAD"), 5_000)

    /** 校验 ref 指向一个 commit 对象(挡住 tag/tree/blob 误用)。退出码 0 = 有效。 */
    fun revParseVerifyCommit(ref: String): ProcResult =
        run(listOf("rev-parse", "--verify", "--quiet", "$ref^{commit}"), 5_000)

    /** refs/notes/ai 的 OID(区间缓存失效键)。无该 ref → 非零退出。 */
    fun notesRefOid(): ProcResult =
        run(listOf("rev-parse", "--verify", "--quiet", NOTES_REF), 5_000)

    fun notesList(): ProcResult = run(listOf("notes", "--ref", NOTES_REF, "list"), 10_000)

    fun notesShow(sha: String): ProcResult =
        run(listOf("notes", "--ref", NOTES_REF, "show", sha), 10_000)

    /** 最近 N 条提交,record/field 分隔的紧凑格式。 */
    fun logRecent(maxCount: Int, format: String): ProcResult =
        run(listOf("log", "-n$maxCount", "--format=$format", "HEAD"), 15_000)

    fun logNoWalk(format: String, shas: List<String>): ProcResult =
        run(buildList { add("log"); add("--no-walk"); add("--format=$format"); addAll(shas) }, 15_000)

    /** 单提交 changed 文件 + 状态字符。-m 对每个 parent 分别 diff(合并提交)。 */
    fun diffTreeNameStatus(sha: String): ProcResult =
        run(listOf("diff-tree", "--name-status", "-r", "-m", "--no-commit-id", sha), 15_000)

    fun lsTreeFiles(ref: String): ProcResult =
        run(listOf("ls-tree", "-r", "--name-only", ref), 15_000)

    /** 单提交每文件 新增/删除 行数:输出行 "<added>\t<deleted>\t<path>"(二进制为 "-\t-\t..")。 */
    fun diffTreeNumStat(sha: String): ProcResult =
        run(listOf("diff-tree", "--numstat", "-r", "-m", "--no-commit-id", sha), 15_000)

    fun showFileAtRef(ref: String, file: String): ProcResult =
        run(listOf("show", "$ref:$file"), 15_000)

    fun statusPorcelainZ(): ProcResult =
        run(listOf("status", "--porcelain=v1", "-z"), 10_000)

    fun branchList(): ProcResult =
        run(listOf("branch", "--format=%(HEAD)%09%(refname:short)%09%(objectname)"), 10_000)

    fun checkout(name: String): ProcResult = run(listOf("checkout", name), 30_000)

    fun configUserEmail(): ProcResult = run(listOf("config", "user.email"), 5_000)

    private fun run(args: List<String>, timeoutMs: Long): ProcResult =
        ProcessRunner.run(exe, args, repoDir, timeoutMs)

    companion object {
        const val NOTES_REF = "refs/notes/ai"

        fun resolve(repoDir: File?): GitCli {
            val exe = ExecutableLocator.find("git") ?: "git"
            return GitCli(exe, repoDir)
        }
    }
}
