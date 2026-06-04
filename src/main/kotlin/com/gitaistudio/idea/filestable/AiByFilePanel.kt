package com.gitaistudio.idea.filestable

import com.gitaistudio.idea.cli.GitCli
import com.gitaistudio.idea.service.RepoService
import com.google.gson.JsonParser
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.SwingConstants
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/**
 * 原生「按文件 AI 占比」面板(IDE 风格 JBTable):对当前 HEAD 提交,列出每个改动文件的 AI 行 / 新增 / AI%。
 * 双击打开文件。数据在后台线程算(git diff numstat + git notes 归因),EDT 不阻塞。
 *
 * 口径:AI 行取自该提交 git-notes 归因的 attestation 行范围;新增取自 `git diff-tree --numstat`;
 * AI% = AI 行 / 新增(clamp 0..100)。BOUNDED 到一个提交的文件,算得起、不卡(pantheon 决策)。
 */
class AiByFilePanel(private val project: Project) : BorderLayoutPanel() {

    private data class FileRow(val path: String, val aiLines: Int, val added: Int, val pct: Int?)

    private val model = FilesModel()
    private val table = JBTable(model)
    private val shaLabel = JBLabel("")

    init {
        table.setShowGrid(false)
        table.rowHeight = JBUI.scale(22)
        table.columnModel.getColumn(COL_PCT).cellRenderer = PctRenderer()
        table.columnModel.getColumn(COL_AI).cellRenderer = rightAligned()
        table.columnModel.getColumn(COL_ADDED).cellRenderer = rightAligned()
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) openSelected()
            }
        })

        val top = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(4)))
        val refresh = JButton("刷新").apply { addActionListener { reload() } }
        top.add(refresh)
        top.add(shaLabel)

        addToTop(top)
        addToCenter(JBScrollPane(table))
        reload()
    }

    private fun openSelected() {
        val viewRow = table.selectedRow.takeIf { it >= 0 } ?: return
        val path = model.rowAt(table.convertRowIndexToModel(viewRow)).path
        val repo = RepoService.getInstance(project).currentRepoDir() ?: return
        val vf = LocalFileSystem.getInstance().findFileByPath("${repo.path}/$path") ?: return
        FileEditorManager.getInstance(project).openFile(vf, true)
    }

    private fun reload() {
        shaLabel.text = "计算中…"
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Computing per-file AI share", true) {
            private var rows: List<FileRow> = emptyList()
            private var sha = ""
            override fun run(indicator: ProgressIndicator) {
                val repo = RepoService.getInstance(project).currentRepoDir() ?: return
                val git = GitCli.resolve(repo)
                val head = git.revParseHead()
                if (!head.ok) return
                sha = head.stdout.trim()
                rows = computeRows(git, sha)
            }
            override fun onSuccess() {
                model.setRows(rows)
                shaLabel.text = if (sha.isBlank()) "无 HEAD" else "HEAD ${sha.take(7)} · ${rows.size} 个文件"
            }
        })
    }

    private fun computeRows(git: GitCli, sha: String): List<FileRow> {
        val added = HashMap<String, Int>()
        git.diffTreeNumStat(sha).takeIf { it.ok }?.stdout?.lineSequence()?.forEach { line ->
            val p = line.split('\t')
            if (p.size >= 3) added[p[2]] = (added[p[2]] ?: 0) + (p[0].toIntOrNull() ?: 0)
        }
        val aiLines = HashMap<String, Int>()
        val note = git.notesShow(sha)
        if (note.ok && note.stdout.isNotBlank()) {
            runCatching { JsonParser.parseString(note.stdout).asJsonObject }.getOrNull()
                ?.getAsJsonArray("attestations")?.forEach { att ->
                    val a = att.asJsonObject
                    val file = a.get("file_path")?.takeIf { !it.isJsonNull }?.asString
                        ?: a.get("file")?.takeIf { !it.isJsonNull }?.asString ?: return@forEach
                    var cnt = 0
                    a.getAsJsonArray("line_ranges")?.forEach { lr -> cnt += countRange(lr.asString) }
                    aiLines[file] = (aiLines[file] ?: 0) + cnt
                }
        }
        val files = (added.keys + aiLines.keys).toSortedSet()
        return files.map { f ->
            val ai = aiLines[f] ?: 0
            val add = added[f] ?: 0
            val pct = if (add > 0) ((ai * 100 + add / 2) / add).coerceIn(0, 100) else null
            FileRow(f, ai, add, pct)
        }.sortedWith(compareByDescending<FileRow> { it.pct ?: -1 }.thenBy { it.path })
    }

    /** "1-10,15" → 行数(不展开列表)。 */
    private fun countRange(spec: String): Int {
        var n = 0
        spec.split(',').forEach { part ->
            val t = part.trim()
            if (t.contains('-')) {
                val ab = t.split('-')
                val a = ab.getOrNull(0)?.trim()?.toIntOrNull()
                val b = ab.getOrNull(1)?.trim()?.toIntOrNull()
                if (a != null && b != null && b >= a) n += (b - a + 1)
            } else if (t.toIntOrNull() != null) {
                n += 1
            }
        }
        return n
    }

    private fun rightAligned(): DefaultTableCellRenderer =
        DefaultTableCellRenderer().apply { horizontalAlignment = SwingConstants.RIGHT }

    private inner class FilesModel : AbstractTableModel() {
        private var rows: List<FileRow> = emptyList()
        fun setRows(r: List<FileRow>) { rows = r; fireTableDataChanged() }
        fun rowAt(i: Int): FileRow = rows[i]
        override fun getRowCount() = rows.size
        override fun getColumnCount() = 4
        override fun getColumnName(c: Int) = when (c) {
            COL_FILE -> "文件"; COL_AI -> "AI 行"; COL_ADDED -> "新增"; else -> "AI%"
        }
        override fun getValueAt(r: Int, c: Int): Any {
            val row = rows[r]
            return when (c) {
                COL_FILE -> row.path
                COL_AI -> row.aiLines
                COL_ADDED -> row.added
                else -> row.pct?.let { "$it%" } ?: "—"
            }
        }
    }

    private class PctRenderer : DefaultTableCellRenderer() {
        init { horizontalAlignment = SwingConstants.RIGHT }
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, selected: Boolean, focus: Boolean, row: Int, col: Int,
        ): Component {
            val c = super.getTableCellRendererComponent(table, value, selected, focus, row, col)
            if (!selected) foreground = if (value == "—") JBColor.GRAY else AI_PURPLE
            return c
        }
        companion object {
            private val AI_PURPLE = JBColor(Color(0x7C6BD6), Color(0x9C8CF0))
        }
    }

    companion object {
        private const val COL_FILE = 0
        private const val COL_AI = 1
        private const val COL_ADDED = 2
        private const val COL_PCT = 3
    }
}
