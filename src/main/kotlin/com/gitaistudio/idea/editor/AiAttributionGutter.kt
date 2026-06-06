package com.gitaistudio.idea.editor

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.TextAnnotationGutterProvider
import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.openapi.editor.colors.EditorFontType
import java.awt.Color

/** 单行归因信息(0-based 行号映射)。 */
data class LineAttribution(val isAi: Boolean, val agent: String?, val promptId: String?)

/**
 * 编辑器行号槽的 AI 归因列。AI 行标短模型名(紫,如 gpt-5.5),人工行标 "·"(蓝),未知行留空。
 * hover 显示完整 tool::model / 归因说明。颜色语义锁死(见 [AttributionColors])。
 */
class AiAttributionGutter(
    private val byLine: Map<Int, LineAttribution>,
    private val onClose: () -> Unit,
) : TextAnnotationGutterProvider {

    override fun getLineText(line: Int, editor: Editor): String {
        val info = byLine[line] ?: return ""
        return if (info.isAi) BlameAttributionSupport.shortModelName(info.agent) else "·"
    }

    override fun getToolTip(line: Int, editor: Editor): String? {
        val info = byLine[line] ?: return null
        return if (info.isAi) {
            val agent = info.agent?.takeIf { it.isNotBlank() } ?: "AI"
            "AI-authored — agent: $agent"
        } else {
            "Human-authored"
        }
    }

    override fun getStyle(line: Int, editor: Editor): EditorFontType = EditorFontType.PLAIN

    override fun getColor(line: Int, editor: Editor): ColorKey? {
        val info = byLine[line] ?: return null
        return if (info.isAi) AttributionColors.AI else AttributionColors.YOU
    }

    override fun getBgColor(line: Int, editor: Editor): Color? = null

    override fun getPopupActions(line: Int, editor: Editor): List<AnAction> = emptyList()

    override fun gutterClosed() {
        onClose()
    }
}
