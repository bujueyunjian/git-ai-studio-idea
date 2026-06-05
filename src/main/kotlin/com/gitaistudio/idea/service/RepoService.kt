package com.gitaistudio.idea.service

import com.gitaistudio.idea.cli.GitCli
import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import java.io.File

/**
 * 项目级当前仓库解析。IDE 里"仓库"天然就是打开的工程的 git 根,
 * 因此默认从 `project.basePath` 向上找 `.git`;用户也可经 select_repo 显式切到扫描发现的其它仓库。
 */
class RepoService(private val project: Project) {

    @Volatile private var selectedPath: String? = null

    fun currentRepoDir(): File? {
        selectedPath?.let { p -> File(p).takeIf { it.isDirectory }?.let { return it } }
        val base = project.basePath
        return base?.let { findGitRoot(File(it)) } ?: firstVcsGitRoot()
    }

    fun selectRepo(path: String): File? {
        val dir = File(path)
        if (!dir.isDirectory) return null
        val root = findGitRoot(dir) ?: dir
        selectedPath = root.absolutePath
        rememberRecent(root.absolutePath)
        return root
    }

    /** 构造前端 RepoEntry。无仓库返回 null(上层转 degraded 空态)。 */
    fun currentRepoEntry(): JsonObject? {
        val dir = currentRepoDir() ?: return null
        return repoEntry(dir)
    }

    fun repoEntry(dir: File): JsonObject {
        val git = GitCli.resolve(dir)
        val branch = git.revParseAbbrevHead().let { if (it.ok) it.stdout.trim() else "" }
        val head = git.revParseHead().let { if (it.ok) it.stdout.trim() else "" }
        val dirty = git.statusPorcelainZ().let { if (it.ok) it.stdout.isNotBlank() else null }
        return JsonObject().apply {
            addProperty("path", dir.absolutePath)
            addProperty("name", dir.name)
            addProperty("head_branch", branch)
            addProperty("head_sha", head)
            if (dirty == null) add("dirty", com.google.gson.JsonNull.INSTANCE) else addProperty("dirty", dirty)
            addProperty("has_git_ai_dir", File(dir, ".git/git-ai").exists() || File(dir, ".git-ai").exists())
            addProperty("working_logs_count", 0)
        }
    }

    private fun rememberRecent(path: String) {
        val settings = GitAiSettings.getInstance()
        val obj = settings.appSettings()
        val arr = obj.getAsJsonArray("recent_repos") ?: com.google.gson.JsonArray()
        val existing = arr.mapNotNull { it.asString }.filter { it != path }
        val merged = com.google.gson.JsonArray().apply {
            add(path)
            existing.take(19).forEach { add(it) }
        }
        obj.add("recent_repos", merged)
        obj.addProperty("last_repo", path)
        settings.saveAppSettings(obj)
    }

    private fun findGitRoot(start: File): File? {
        var cur: File? = start
        while (cur != null) {
            if (File(cur, ".git").exists()) return cur
            cur = cur.parentFile
        }
        return null
    }

    private fun firstVcsGitRoot(): File? {
        for (vcsRoot in ProjectLevelVcsManager.getInstance(project).allVcsRoots) {
            val gitRoot = findGitRoot(File(vcsRoot.path.path))
            if (gitRoot != null) return gitRoot
        }
        return null
    }

    companion object {
        fun getInstance(project: Project): RepoService = project.getService(RepoService::class.java)
    }
}
