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

    /**
     * Node 系 CLI(npm / claude / codex)的常见全局安装位:nvm / volta / bun / npm prefix / Homebrew。
     * 对齐桌面版 227e6e8 的「固定目录二级解析」——PATH 镜像有盲区(nvm 早退、fish 默认 shell 不 source rc),
     * 补这些目录既能让 find 命中已装 CLI,又能让子进程(npm/claude 的 #!/usr/bin/env node)找到同源 node。
     * 仅返回真实存在的目录。
     */
    fun nodeToolDirs(): List<String> {
        val home = System.getProperty("user.home").orEmpty()
        val raw = if (isWindows) {
            listOfNotNull(
                System.getenv("APPDATA")?.let { "$it\\npm" },
                System.getenv("LOCALAPPDATA")?.let { "$it\\Volta\\bin" },
                System.getenv("NVM_SYMLINK"),
                "C:\\Program Files\\nodejs",
            )
        } else {
            buildList {
                add("$home/.local/bin")
                add("$home/.npm-global/bin")
                add("$home/.volta/bin")
                add("$home/.bun/bin")
                addAll(nvmVersionBins(home))
                add("/opt/homebrew/bin")
                add("/usr/local/bin")
            }
        }
        return raw.filter { File(it).isDirectory }
    }

    /** ~/.nvm/versions/node/<ver>/bin,版本字典序降序(让新版 v22 排在 v18 前)。无 nvm 返空。 */
    private fun nvmVersionBins(home: String): List<String> {
        val dir = File("$home/.nvm/versions/node")
        val subs = dir.listFiles { f -> f.isDirectory } ?: return emptyList()
        return subs.map { it.name }.sorted().reversed().map { "$home/.nvm/versions/node/$it/bin" }
    }

    /** 把常见安装位补进给定环境的 PATH(去重,原有优先)。 */
    fun augmentPath(env: MutableMap<String, String>) {
        val sep = File.pathSeparator
        val current = (env["PATH"] ?: System.getenv("PATH").orEmpty())
            .split(sep)
            .filter { it.isNotBlank() }
        val merged = (current + extraDirs + nodeToolDirs()).distinct().joinToString(sep)
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
        val dirs = (System.getenv("PATH").orEmpty().split(File.pathSeparator) + extraDirs + nodeToolDirs())
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
