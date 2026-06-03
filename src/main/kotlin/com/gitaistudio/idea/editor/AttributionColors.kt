package com.gitaistudio.idea.editor

import com.intellij.openapi.editor.colors.ColorKey
import com.intellij.ui.JBColor
import java.awt.Color

/**
 * 行级归因颜色。**信息层锁死**:紫=AI、蓝=你(对齐桌面墨宠 ADR-011 的双色不变量,色盲下靠位置/文字冗余区分)。
 * 这两个色槽是固定语义,不开放为"换肤"——绝不允许改写"哪个色代表谁"。
 */
object AttributionColors {
    private val AI_PURPLE = JBColor(Color(0x7C6BD6), Color(0x9C8CF0))
    private val YOU_BLUE = JBColor(Color(0x3A8FB7), Color(0x5BB0D8))

    val AI: ColorKey = ColorKey.createColorKey("GITAI_LINE_AI", AI_PURPLE)
    val YOU: ColorKey = ColorKey.createColorKey("GITAI_LINE_YOU", YOU_BLUE)
}
