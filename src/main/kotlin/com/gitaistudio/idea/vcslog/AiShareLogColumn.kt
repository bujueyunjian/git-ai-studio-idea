package com.gitaistudio.idea.vcslog

import com.intellij.openapi.diagnostic.Logger
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

    // 默认在 Log 里可见(否则用户需手动到列头菜单勾选才出现)
    override fun isEnabledByDefault(): Boolean = true

    @Volatile private var table: VcsLogGraphTable? = null

    override fun getValue(model: GraphTableModel, row: Int): String? {
        return try {
            val project = model.logData.project
            val (sha, rootPath) = shaAndRoot(model, row) ?: return null
            val svc = LogAiShareService.getInstance(project)
            val p = svc.cachedPct(sha)
            when {
                p == null -> {
                    svc.requestWarm(rootPath, sha) { table?.repaint() }
                    null
                }
                p < 0 -> ""
                else -> "AI $p%"
            }
        } catch (t: Throwable) {
            LOG.warn("AI log column getValue failed at row $row", t)
            null
        }
    }

    /** 优先用较稳的 getCommitMetadata 取 sha+root;失败回退 getId + logData.getCommitId(内部 API,跨版本易变)。 */
    private fun shaAndRoot(model: GraphTableModel, row: Int): Pair<String, String>? {
        runCatching {
            val m = model.getCommitMetadata(row, false)
            if (m != null) {
                val root = model.getRootAtRow(row) ?: m.root
                return m.id.asString() to root.path
            }
        }
        runCatching {
            val idx = model.getId(row) ?: return null
            val cid = model.logData.getCommitId(idx) ?: return null
            return cid.hash.asString() to cid.root.path
        }
        return null
    }

    override fun getStubValue(model: GraphTableModel): String = ""

    override fun createTableCellRenderer(table: VcsLogGraphTable): TableCellRenderer {
        this.table = table
        return AiShareCellRenderer()
    }

    override fun isAvailable(project: Project, roots: Collection<VirtualFile>): Boolean = true

    companion object {
        private val LOG = Logger.getInstance(AiShareLogColumn::class.java)
    }

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
