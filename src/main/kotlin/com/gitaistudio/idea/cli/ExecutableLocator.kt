package com.gitaistudio.idea.cli

import java.io.File

/**
 * 定位 git-ai / git 可执行文件。
 *
 * macOS / Linux 下 GUI 进程(IDE)拿到的 PATH 往往只有系统目录,
 * 找不到装在 ~/.local/bin、~/.cargo/bin、/opt/homebrew/bin 等处的 git-ai,
 * 因此这里统一在调用前把这些常见安装位补进 PATH,并提供绝对路径解析。
 */
object ExecutableLocator {
    private val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    private val extraDirs: List<String> by lazy {
        val home = System.getProperty("user.home").orEmpty()
        if (isWindows) {
            listOf(
                "$home\\.local\\bin",
                "$home\\.cargo\\bin",
                "$home\\AppData\\Local\\Programs\\git-ai",
            )
        } else {
            listOf(
                "$home/.local/bin",
                "/usr/local/bin",
                "/opt/homebrew/bin",
                "$home/.cargo/bin",
                "$home/bin",
                "/usr/bin",
            )
        }
    }

    /** 把常见安装位补进给定环境的 PATH(去重,原有优先)。 */
    fun augmentPath(env: MutableMap<String, String>) {
        val sep = File.pathSeparator
        val current = (env["PATH"] ?: System.getenv("PATH").orEmpty())
            .split(sep)
            .filter { it.isNotBlank() }
        val merged = (current + extraDirs).distinct().joinToString(sep)
        env["PATH"] = merged
    }

    /**
     * 解析可执行文件绝对路径。
     * @param explicit 用户在设置里显式指定的路径(优先,若存在且可执行)
     * @return 绝对路径;找不到返回 null
     */
    fun find(name: String, explicit: String? = null): String? {
        explicit?.takeIf { it.isNotBlank() }?.let {
            val f = File(it)
            if (f.isFile && f.canExecute()) return f.absolutePath
        }
        val candidates = if (isWindows) listOf("$name.exe", "$name.cmd", name) else listOf(name)
        val dirs = (System.getenv("PATH").orEmpty().split(File.pathSeparator) + extraDirs)
            .filter { it.isNotBlank() }
            .distinct()
        for (dir in dirs) {
            for (c in candidates) {
                val f = File(dir, c)
                if (f.isFile && f.canExecute()) return f.absolutePath
            }
        }
        return null
    }
}
