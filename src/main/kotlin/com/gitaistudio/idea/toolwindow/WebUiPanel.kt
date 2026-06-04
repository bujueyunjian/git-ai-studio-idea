package com.gitaistudio.idea.toolwindow

import com.gitaistudio.idea.bridge.CommandDispatcher
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.ui.components.BorderLayoutPanel
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter

/**
 * 工具窗口主面板:JCEF 里跑复用的 git-ai-studio React 前端,Kotlin 后端经 JS 桥提供 ~60 个命令。
 *
 * 桥协议(JS ↔ Kotlin,全异步):
 * - JS→Kotlin:`window.__gitaiSend(JSON.stringify({type,id,cmd,args}))`
 * - Kotlin→JS:`window.__gitaiReceive({type:'response',id,ok,data|error})` / `{type:'event',channel,payload}`
 * - bootstrap 注入前 JS 端的发送暂存在 `window.__gitaiQueue`,注入后冲刷,规避加载时序竞争。
 *
 * IDE 明暗主题映射到前端:注入时切 `documentElement.dark` class + `data-gitai-theme`,并监听 LAF 变化实时同步。
 */
class WebUiPanel(private val project: Project) : BorderLayoutPanel(), Disposable {

    private val gson = Gson()
    private var browser: JBCefBrowser? = null
    private var jsQuery: JBCefJSQuery? = null
    private var dispatcher: CommandDispatcher? = null

    init {
        project.putUserData(PANEL_KEY, this)
        if (!JBCefApp_isSupported()) {
            addToCenter(JBLabel(com.gitaistudio.idea.GitAiBundle.message("toolwindow.jcef.unsupported")))
        } else {
            WebSchemeHandlerFactory.ensureRegistered()

            val b = JBCefBrowser.createBuilder().setOffScreenRendering(false).build()
            browser = b
            Disposer.register(this, b)

            val q = JBCefJSQuery.create(b as JBCefBrowserBase)
            jsQuery = q
            Disposer.register(this, q)
            q.addHandler { raw -> handleInbound(raw); JBCefJSQuery.Response(null) }

            dispatcher = CommandDispatcher(project) { channel, payload -> pushEvent(channel, payload) }

            b.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
                override fun onLoadEnd(cefBrowser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                    if (frame?.isMain == true) injectBootstrap()
                }
            }, b.cefBrowser)

            ApplicationManager.getApplication().messageBus.connect(this)
                .subscribe(LafManagerListener.TOPIC, LafManagerListener { applyTheme() })

            addToCenter(b.component)
            b.loadURL(WebSchemeHandlerFactory.INDEX_URL)
        }
    }

    private fun handleInbound(raw: String) {
        val msg = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return
        when (msg.get("type")?.asString) {
            "invoke" -> {
                val id = msg.get("id")?.asString ?: return
                val cmd = msg.get("cmd")?.asString.orEmpty()
                val args = msg.getAsJsonObject("args") ?: JsonObject()
                ApplicationManager.getApplication().executeOnPooledThread {
                    val resp = JsonObject()
                    resp.addProperty("type", "response")
                    resp.addProperty("id", id)
                    try {
                        val data = dispatcher!!.dispatch(cmd, args)
                        resp.addProperty("ok", true)
                        resp.add("data", data)
                    } catch (e: Throwable) {
                        resp.addProperty("ok", false)
                        resp.addProperty("error", e.message ?: e.toString())
                    }
                    exec("window.__gitaiReceive && window.__gitaiReceive(${gson.toJson(resp)});")
                }
            }
            // emit / subscribe:事件由命令侧主动推送,这里无需处理
            else -> Unit
        }
    }

    fun pushEvent(channel: String, payload: JsonElement) {
        val ev = JsonObject().apply {
            addProperty("type", "event")
            addProperty("channel", channel)
            add("payload", payload)
        }
        exec("window.__gitaiReceive && window.__gitaiReceive(${gson.toJson(ev)});")
    }

    private fun injectBootstrap() {
        val query = jsQuery ?: return
        val version = pluginVersion()
        val dark = currentThemeIsDark()
        val js = buildString {
            append("(function(){")
            append("window.__GITAI_PLUGIN_VERSION__=").append(gson.toJson(version)).append(";")
            append("window.__GITAI_HOST__='idea';")
            append("window.__gitaiSend=function(payload){").append(query.inject("payload")).append("};")
            append("if(Array.isArray(window.__gitaiQueue)){var q=window.__gitaiQueue.slice();window.__gitaiQueue=[];for(var i=0;i<q.length;i++){window.__gitaiSend(q[i]);}}")
            append("document.documentElement.classList.toggle('dark',").append(dark).append(");")
            append("document.documentElement.setAttribute('data-gitai-theme',").append(if (dark) "'dark'" else "'light'").append(");")
            append("})();")
        }
        exec(js)
    }

    private fun applyTheme() {
        val dark = currentThemeIsDark()
        exec(
            "document.documentElement.classList.toggle('dark',$dark);" +
                "document.documentElement.setAttribute('data-gitai-theme',${if (dark) "'dark'" else "'light'"});",
        )
    }

    private fun exec(js: String) {
        val b = browser ?: return
        b.cefBrowser.executeJavaScript(js, b.cefBrowser.url ?: WebSchemeHandlerFactory.INDEX_URL, 0)
    }

    private fun currentThemeIsDark(): Boolean = !JBColor.isBright()

    private fun pluginVersion(): String =
        PluginManagerCore.getPlugin(PluginId.getId("com.gitaistudio.idea"))?.version ?: "0.1.0"

    /** 让原生侧(编辑器右键动作)驱动复用的 React 应用导航:直接切 hash,RouterProvider 监听 hashchange。 */
    fun navigateTo(hash: String) {
        exec("window.location.hash = ${gson.toJson(hash)};")
    }

    /** JCEF 支持探测;独立小函数,便于 init 里读起来顺。 */
    private fun JBCefApp_isSupported(): Boolean = com.intellij.ui.jcef.JBCefApp.isSupported()

    override fun dispose() {
        if (project.getUserData(PANEL_KEY) === this) project.putUserData(PANEL_KEY, null)
    }

    companion object {
        private val PANEL_KEY = Key.create<WebUiPanel>("gitai.webui.panel")

        /** 打开 Git AI Studio 工具窗口并导航到指定 hash 路由(由编辑器/项目视图右键动作调用)。 */
        fun openWebviewAt(project: Project, hash: String) {
            val tw = ToolWindowManager.getInstance(project).getToolWindow("Git AI Studio") ?: return
            tw.activate({ project.getUserData(PANEL_KEY)?.navigateTo(hash) }, true)
        }
    }
}
