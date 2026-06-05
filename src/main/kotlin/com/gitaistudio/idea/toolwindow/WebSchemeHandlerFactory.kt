package com.gitaistudio.idea.toolwindow

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.ui.jcef.JBCefApp
import org.cef.CefApp
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefCallback
import org.cef.callback.CefSchemeHandlerFactory
import org.cef.handler.CefResourceHandler
import org.cef.misc.IntRef
import org.cef.misc.StringRef
import org.cef.network.CefRequest
import org.cef.network.CefResponse
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 把内置 React 构建以 `http://gitaistudio/<path>` 提供给 JCEF。资源取自插件 classpath 的 `/web/`。
 * 自研 hash router(ADR-001)只靠 URL 哈希,无需服务端路由回退。
 *
 * 直接实现 [CefResourceHandler](与 WebUiPanel 用的 CefLoadHandlerAdapter 同在确认可用的 org.cef.*),
 * 不依赖 JBCefStreamResourceHandler(2024.3 不一定暴露)。
 */
class WebSchemeHandlerFactory : CefSchemeHandlerFactory {

    override fun create(
        browser: CefBrowser?,
        frame: CefFrame?,
        schemeName: String?,
        request: CefRequest?,
    ): CefResourceHandler {
        val path = extractPath(request?.url).ifBlank { "index.html" }
        return WebResourceHandler(path)
    }

    private fun extractPath(url: String?): String {
        if (url.isNullOrBlank()) return ""
        var rest = if (url.startsWith("$HOST/")) url.substring(HOST.length + 1) else url.substringAfter(HOST)
        rest = rest.substringBefore('?').substringBefore('#').trimStart('/')
        return rest
    }

    companion object {
        const val SCHEME = "http"
        const val DOMAIN = "gitaistudio"
        const val HOST = "$SCHEME://$DOMAIN"
        const val INDEX_URL = "$HOST/index.html"

        private val registered = AtomicBoolean(false)

        /** 运行时插件版本(plugin.xml 的 version 由构建期 patch 自 gradle.properties 的 pluginVersion)。 */
        fun pluginVersion(): String =
            PluginManagerCore.getPlugin(PluginId.getId("com.gitaistudio.idea"))?.version ?: ""

        /** 全局注册一次。 */
        fun ensureRegistered() {
            if (!JBCefApp.isSupported()) return
            if (registered.compareAndSet(false, true)) {
                JBCefApp.getInstance() // 确保 CefApp 已初始化
                CefApp.getInstance().registerSchemeHandlerFactory(SCHEME, DOMAIN, WebSchemeHandlerFactory())
            }
        }
    }
}

/** 从插件资源 `/web/` 流式回应单个请求。 */
private class WebResourceHandler(private val path: String) : CefResourceHandler {
    private var data: ByteArray = ByteArray(0)
    private var offset = 0
    private var mime = "application/octet-stream"

    override fun processRequest(request: CefRequest?, callback: CefCallback): Boolean {
        val bytes = javaClass.getResourceAsStream("/web/$path")?.readBytes()
            ?: javaClass.getResourceAsStream("/web/index.html")?.readBytes()
        if (bytes == null) {
            callback.cancel()
            return false
        }
        // index.html 在回流前注入启动全局:前端 useEffect/运行时读取这些全局的时机
        // 早于 load 级的 onLoadEnd(injectBootstrap);只有 serve 期写入才能保证任意读取点都已就绪
        data = if (path == "index.html") injectStartupGlobals(bytes) else bytes
        mime = mimeFor(path)
        callback.Continue()
        return true
    }

    /** 把插件版本与宿主标识以 <script> 注入 <head> 起始处。 */
    private fun injectStartupGlobals(bytes: ByteArray): ByteArray {
        val version = WebSchemeHandlerFactory.pluginVersion().replace("\\", "\\\\").replace("\"", "\\\"")
        val script = "<script>window.__GITAI_PLUGIN_VERSION__=\"$version\";window.__GITAI_HOST__=\"idea\";</script>"
        val html = String(bytes, Charsets.UTF_8)
        val idx = html.indexOf("<head>")
        if (idx < 0) return bytes
        return (html.substring(0, idx + "<head>".length) + script + html.substring(idx + "<head>".length))
            .toByteArray(Charsets.UTF_8)
    }

    override fun getResponseHeaders(response: CefResponse, responseLength: IntRef, redirectUrl: StringRef) {
        response.mimeType = mime
        response.status = 200
        responseLength.set(data.size)
    }

    override fun readResponse(dataOut: ByteArray, bytesToRead: Int, bytesRead: IntRef, callback: CefCallback): Boolean {
        if (offset >= data.size) {
            bytesRead.set(0)
            return false
        }
        val n = minOf(bytesToRead, data.size - offset)
        System.arraycopy(data, offset, dataOut, 0, n)
        offset += n
        bytesRead.set(n)
        return true
    }

    override fun cancel() {}

    private fun mimeFor(p: String): String = when (p.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html"
        "js", "mjs" -> "text/javascript"
        "css" -> "text/css"
        "json", "map" -> "application/json"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "ico" -> "image/x-icon"
        "woff2" -> "font/woff2"
        "woff" -> "font/woff"
        "ttf" -> "font/ttf"
        "wasm" -> "application/wasm"
        else -> "application/octet-stream"
    }
}
