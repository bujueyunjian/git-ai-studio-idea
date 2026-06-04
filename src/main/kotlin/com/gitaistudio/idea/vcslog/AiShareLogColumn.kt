package com.gitaistudio.idea.vcslog

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ColoredTableCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.vcs.log.ui.table.GraphTableModel
import com.intellij.vcs.log.ui.table.VcsLogGraphTable
import com.intellij.vcs.log.ui.table.column.VcsLogCustomColumn
import java.awt.Color
import javax.swing.JTable
import javax.swing.table.TableCellRenderer

/**
 * IntelliJ 原生 Git Log 里的「AI 占比」列:把提交级 AI 归因放进开发者最常看历史的地方。
 *
 * getValue 在 EDT 同步调用 → 只读 [LogAiShareService] 的内存缓存;未命中即排队后台预热,
 * 算完 repaint 让本列重渲染(绝不在 EDT 内 shell git-ai)。
 */
class AiShareLogColumn : VcsLogCustomColumn<String> {

    override val id: String = "GitAiStudio.AiShare"
    override val localizedName: String = "AI"
    override val isDynamic: Boolean = true

    @Volatile private var table: VcsLogGraphTable? = null

    override fun getValue(model: GraphTableModel, row: Int): String? {
        val idx = model.getId(row) ?: return null
        val logData = model.logData
        val commitId = logData.getCommitId(idx) ?: return null
        val sha = commitId.hash.asString()
        val rootPath = commitId.root.path
        val svc = LogAiShareService.getInstance(logData.project)
        val p = svc.cachedPct(sha) ?: run {
            svc.requestWarm(rootPath, sha) { table?.repaint() }
            return null
        }
        return if (p < 0) "" else "AI $p%"
    }

    override fun getStubValue(model: GraphTableModel): String = ""

    override fun createTableCellRenderer(table: VcsLogGraphTable): TableCellRenderer {
        this.table = table
        return AiShareCellRenderer()
    }

    override fun isAvailable(project: Project, roots: Collection<VirtualFile>): Boolean = true

    private class AiShareCellRenderer : ColoredTableCellRenderer() {
        override fun customizeCellRenderer(
            table: JTable,
            value: Any?,
            selected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ) {
            val s = value as? String ?: return
            if (s.isEmpty()) return
            // AI 占比用紫(对齐 ADR-011 信息层锁色:紫=AI)
            append(s, SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, AI_PURPLE))
        }

        companion object {
            private val AI_PURPLE: Color = JBColor(Color(0x7C6BD6), Color(0x9C8CF0))
        }
    }
}
