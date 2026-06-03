package com.gitaistudio.idea

import com.intellij.DynamicBundle
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.GitAiStudioBundle"

/** IDE 原生字符串(动作名/通知/工具窗口)的 i18n 访问器。webview 内的文案走前端自己的 i18next。 */
object GitAiBundle : DynamicBundle(BUNDLE) {
    @JvmStatic
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String =
        getMessage(key, *params)
}
