package com.morealm.app.domain.source

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.annotation.Keep
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.entity.BookSource
import com.morealm.app.domain.http.BackstageWebView
import kotlinx.coroutines.runBlocking

/**
 * 登录脚本副绑定 API。注入到登录 JS scope 的 key = `loginExt`，配合通用扩展 `java`
 * (= JsExtensions) 一起暴露给登录脚本调用。
 *
 * 设计选择：副绑定而非整体替换 `java`
 * ---------------------------------
 * Legado 的 SourceLoginJsExtensions 是 `class extends RssJsExtensions extends JsExtensions`，
 * 整体替换 `java` 时既保留通用能力又叠加登录扩展。MoRealm 的 [com.morealm.app.domain.analyzeRule.JsExtensions]
 * 是 Kotlin `object`，不能继承——若整体替换 `java` 则丢失 90+ 个通用方法。所以走副绑定
 * 路径，登录脚本里把 `java.upLoginData(...)` 改写为 `loginExt.upLoginData(...)` 即可。
 *
 * 桥接路径
 * --------
 * 由 [com.morealm.app.presentation.source.SourceLoginViewModel] 在 `login / runActionJs /
 * parseLoginUi` 三条路径上构造实例并塞到 `extraBindings`。脚本端调用反向通道时，本类透过
 * 构造时传入的 [onUpUiData] / [onReUiView] lambda emit 到 ViewModel 的 SharedFlow，再由 UI
 * 层 collect 应用到表单。
 */
@Keep
@Suppress("unused")
class SourceLoginScriptApi(
    private val ctx: Context,
    private val source: BookSource,
    /** 脚本调 `loginExt.upLoginData({...})` 时回调，UI 层在 SharedFlow 上 collect 后 patch 表单。 */
    private val onUpUiData: (Map<String, String>) -> Unit = {},
    /** 脚本调 `loginExt.reLoginView()` 时回调，UI 层重建表单（true = 增量，false = 全量）。 */
    private val onReUiView: (Boolean) -> Unit = {},
) {
    /**
     * 增量更新表单字段。脚本：`loginExt.upLoginData({ "captcha": "data:image/png;base64,..." })`
     *
     * 入参类型 [Any] 是为了容纳 Rhino 把 JS 对象映射回的多种 Java 类型（NativeObject / 普通
     * Map / NativeJavaObject 包装）。只在确实是 Map<*,*> 时取值，否则静默忽略防止脚本传错
     * 类型炸掉登录流程。
     */
    fun upLoginData(data: Any?) {
        if (data !is Map<*, *>) return
        val converted = HashMap<String, String>(data.size)
        for ((k, v) in data) {
            if (k == null || v == null) continue
            converted[k.toString()] = v.toString()
        }
        if (converted.isNotEmpty()) onUpUiData(converted)
    }

    /**
     * 强制 UI 重建。`deltaUp = true` 表示增量重建（仅替换变化的 row），false 表示全量重建。
     * MoRealm 当前 SourceLoginDialog 不区分两者，统一全量；保留参数以兼容 Legado 脚本签名。
     */
    @JvmOverloads
    fun reLoginView(deltaUp: Boolean = false) = onReUiView(deltaUp)

    /**
     * 复制文本到系统剪贴板。脚本：`loginExt.copyText(token)`，常用于把登录得到的 cookie /
     * token 提示用户粘贴到其他 App。空字符串静默忽略，错误只 log 不抛——脚本不应因剪贴板
     * 失败而中断登录流程。
     */
    fun copyText(text: String?) {
        if (text.isNullOrBlank()) return
        try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("login", text))
        } catch (e: Exception) {
            AppLog.warn("LoginApi", "copyText failed: ${e.message}")
        }
    }

    /**
     * 后台 WebView 打开 [url] / 渲染 [html]，等加载完拿 cookie 持久化到 CookieStore（key
     * 用 source URL）。完成后返回拿到的页面 HTML 给脚本继续处理。
     *
     * 与 Legado [SourceLoginJsExtensions.showBrowser] 行为差异：Legado 弹一个 BottomWebViewDialog
     * 让用户**视觉交互**（输验证码 / 滑滑块）后再回写 cookie；MoRealm 当前是**无 UI**的
     * 后台 WebView，能覆盖「自动跑 JS 拿 token / 写 cookie」类的源（占多数）；需要用户
     * 交互完成登录的源（图形验证码 / 扫码登录）暂不支持，后续做交互式 WebView Activity 后补齐。
     *
     * @param preloadJs 页面加载后追加执行的 JS（用于注入辅助逻辑）；null 时只跑默认抓 HTML
     * @param config Legado 兼容参数（暂未使用，保留签名）
     */
    @JvmOverloads
    fun showBrowser(
        url: String?,
        html: String? = null,
        preloadJs: String? = null,
        @Suppress("UNUSED_PARAMETER") config: String? = null,
    ): String? {
        if (url.isNullOrBlank() && html.isNullOrBlank()) return null
        return try {
            // runBlocking 在 IO 协程里安全：BackstageWebView 内部走 main looper Handler，
            // 不会与当前 IO 线程死锁。登录脚本通常需要同步获得 HTML 后判断结果。
            runBlocking {
                BackstageWebView(
                    url = url,
                    html = html,
                    javaScript = preloadJs,
                    tag = source.bookSourceUrl,
                    persistCookie = true,
                ).getStrResponse().body
            }
        } catch (e: Exception) {
            AppLog.warn("LoginApi", "showBrowser failed: ${e.message?.take(80)}")
            null
        }
    }

    // ── refresh* / clearTtsCache：暂为 no-op，留 hook 后续接 EventBus / TTS 缓存 ──
    //
    // Legado 通过 EventBus 通知书架 / 阅读器 / 听书页刷新。MoRealm 没有同款总线，
    // 多数登录脚本调这些是为了「让 UI 立刻反映新 cookie」，但 MoRealm 的 cookie 已
    // 通过 CookieStore 落库，下一次 source 调用自然会带上，所以 no-op 影响很小——
    // 用户重新进章节即可。后续若用户报告"登录后书架未刷新"再接事件总线。

    fun refreshBookInfo() {
        AppLog.warn("LoginApi", "refreshBookInfo: 暂未实现（cookie 已落库，下次请求自动生效）")
    }

    fun refreshBookToc() {
        AppLog.warn("LoginApi", "refreshBookToc: 暂未实现")
    }

    fun refreshContent() {
        AppLog.warn("LoginApi", "refreshContent: 暂未实现")
    }

    fun refreshExplore() {
        AppLog.warn("LoginApi", "refreshExplore: 暂未实现")
    }

    /** 清 HttpTTS 缓存。MoRealm 当前不支持 HttpTTS 类型源，no-op。 */
    fun clearTtsCache() {
        AppLog.warn("LoginApi", "clearTtsCache: HttpTTS 暂不支持")
    }
}
