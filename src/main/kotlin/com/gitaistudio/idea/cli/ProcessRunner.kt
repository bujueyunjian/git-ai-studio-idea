package com.gitaistudio.idea.cli

import java.io.File
import java.util.concurrent.TimeUnit

data class ProcResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
) {
    val ok: Boolean get() = exitCode == 0 && !timedOut
}

/**
 * 子进程执行。强制 `LC_ALL=C` / `LANG=C`,保证 git / git-ai 的 stderr 用英文关键字,
 * 便于上层稳定匹配("does not exist"、"fatal:" 等),对齐桌面版 proc.rs 的做法。
 *
 * stdout / stderr 用独立线程并发读取,避免大输出(如 stats 区间分析)写满管道导致死锁。
 */
object ProcessRunner {

    fun run(
        exe: String,
        args: List<String>,
        workingDir: File?,
        timeoutMs: Long,
        stdin: String? = null,
    ): ProcResult {
        val pb = ProcessBuilder(buildList { add(exe); addAll(args) })
        if (workingDir != null) pb.directory(workingDir)
        configureEnv(pb)

        val proc = pb.start()

        if (stdin != null) {
            runCatching { proc.outputStream.use { it.write(stdin.toByteArray(Charsets.UTF_8)); it.flush() } }
        } else {
            runCatching { proc.outputStream.close() }
        }

        val out = StringBuilder()
        val err = StringBuilder()
        val outT = readerThread(proc.inputStream.bufferedReader(Charsets.UTF_8)) { out.append(it).append('\n') }
        val errT = readerThread(proc.errorStream.bufferedReader(Charsets.UTF_8)) { err.append(it).append('\n') }

        val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            proc.destroyForcibly()
            outT.join(500); errT.join(500)
            return ProcResult(-1, out.toString(), err.toString(), timedOut = true)
        }
        outT.join(2000); errT.join(2000)
        return ProcResult(proc.exitValue(), out.toString().trimEnd('\n'), err.toString().trimEnd('\n'), timedOut = false)
    }

    /**
     * 流式执行:每读到一行 stdout 即回调 [onLine](用于安装等长任务日志推送)。
     * 返回退出码;超时返回 -1。
     */
    fun runStreaming(
        exe: String,
        args: List<String>,
        workingDir: File?,
        timeoutMs: Long,
        onLine: (String) -> Unit,
    ): Int {
        val pb = ProcessBuilder(buildList { add(exe); addAll(args) })
        if (workingDir != null) pb.directory(workingDir)
        configureEnv(pb)
        pb.redirectErrorStream(true)

        val proc = pb.start()
        runCatching { proc.outputStream.close() }
        val t = readerThread(proc.inputStream.bufferedReader(Charsets.UTF_8), onLine)

        val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            proc.destroyForcibly()
            t.join(500)
            return -1
        }
        t.join(2000)
        return proc.exitValue()
    }

    private fun configureEnv(pb: ProcessBuilder) {
        val env = pb.environment()
        env["LC_ALL"] = "C"
        env["LANG"] = "C"
        ExecutableLocator.augmentPath(env)
    }

    private fun readerThread(reader: java.io.BufferedReader, onLine: (String) -> Unit): Thread {
        val t = Thread {
            reader.use { r ->
                var line = r.readLine()
                while (line != null) {
                    onLine(line)
                    line = r.readLine()
                }
            }
        }
        t.isDaemon = true
        t.start()
        return t
    }
}
