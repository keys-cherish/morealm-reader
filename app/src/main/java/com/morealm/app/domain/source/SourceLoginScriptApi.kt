package com.morealm.app.domain.source

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.annotation.Keep
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.entity.BookSource
import kotlinx.coroutines.runBlocking

/**
 * 登录脚本副绑定 API。注入到登录 JS scope 的 key = `loginExt`，配合通用扩展 `java`
 * (= JsExtensions) 一起暴露给登录脚本调用。
 *
 * 兼容 Legado 原生书源脚本
 * -----------------------
 * Legado 的 SourceLoginJsExtensions 把登录扩展直接挂在 `java` 上，脚本里写
 * `java.upLoginData(...)`。MoRealm 的 [com.morealm.app.domain.analyzeRule.JsExtensions]
 * 是 Kotlin `object`（运行时不可继承 / 不可动态加方法），所以没法复刻这一点。
 * 折中方案：
 *  - `loginExt` 是本类实例，登录专属方法都在这里
 *  - [LEGACY_JAVA_COMPAT_PRELUDE] 是一段 prelude JS，在登录脚本执行前跑一遍，通过 JS
 *    原型链把 `loginExt` 的方法合并到 `java` 上（不改原始 `java` / JsExtensions）。
 *    效果：Legado 脚本 `java.upLoginData(...)` 零改动跑通；同时 `java.ajax(...)` 之类
 *    的通用扩展继续走原型链 fallback 到 JsExtensions。
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
     * 启交互式浏览器让用户完成登录 / 验证，返回最终页面 HTML 给脚本继续处理。
     *
     * 对齐 Legado `SourceLoginJsExtensions.showBrowser`：弹 WebView 让用户在真实 UI 里扫码、
     * 输验证码、滑滑块，关闭时把最新 HTML 回传。MoRealm 走独立 Activity 实现，见
     * [com.morealm.app.ui.source.ShowBrowserActivity]。
     *
     * 并发：同一时刻只能开一个（[ShowBrowserSession] 内的 Mutex 串行化）；脚本短时间连调
     * 两次会自然排队。
     *
     * 错误路径：
     *  - 用户取消 / 系统杀 Activity / 超时 → 收到 null，返回 null 让脚本分支按失败处理
     *  - Activity 启动失败（权限缺失等）→ 捕获异常返回 null，不抛到脚本
     *
     * @param preloadJs 页面 onPageFinished 后追加执行的 JS
     * @param config Legado 兼容参数（未使用，保留签名）
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
            com.morealm.app.ui.source.ShowBrowserActivity.launch(
                context = ctx,
                url = url,
                html = html,
                preloadJs = preloadJs,
                sourceKey = source.getKey(),
                title = "登录 ${source.bookSourceName}",
            )
            // runBlocking 在脚本所处的 IO 协程里安全：Activity 的 emit 来自 main 线程，
            // 这里等 RendezvousChannel 的 receive，等待期间不阻塞主线程。超时兜底避免
            // Activity 极端情况下 emit 丢失导致脚本永挂（Activity.onDestroy 已有兜底 emit，
            // 这层只是第二道保险）。
            runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(5 * 60_000L) {
                    ShowBrowserSession.awaitResult()
                }
            }
        } catch (e: Exception) {
            AppLog.warn("LoginApi", "showBrowser failed: ${e.message?.take(80)}")
            null
        }
    }

    // ── refresh* ──
    //
    // 登录脚本调 `java.refreshXxx()`（via prelude 转发）或 `loginExt.refreshXxx()`
    // 时，经 [SourceLoginRefreshBus] 发无参事件。对应页面的 ViewModel 订阅后重拉
    // 数据。MoRealm cookie 已经通过 CookieStore 落库，事件只是"让 UI 立刻刷新"，
    // 没有订阅方时静默 drop 也不影响登录正确性（下次打开相关页重新请求即可）。

    fun refreshBookInfo() {
        SourceLoginRefreshBus.emitBookInfo()
    }

    fun refreshBookToc() {
        SourceLoginRefreshBus.emitBookToc()
    }

    fun refreshContent() {
        SourceLoginRefreshBus.emitContent()
    }

    fun refreshExplore() {
        SourceLoginRefreshBus.emitExplore()
    }

    /** 清 HttpTTS 缓存。MoRealm 当前不支持 HttpTTS 类型源，no-op。 */
    fun clearTtsCache() {
        AppLog.warn("LoginApi", "clearTtsCache: HttpTTS 暂不支持")
    }

    companion object {
        /**
         * 兼容 Legado 原生脚本：`java.upLoginData(...)` / `java.reLoginView(...)` /
         * `java.showBrowser(...)` 等登录专属调用。
         *
         * 在登录相关 JS 执行前拼到脚本最前面，用 JS 代理把 `java` 当作主对象，命中时
         * 优先转发到 `loginExt`，未命中再走原 `java`（= JsExtensions）。这样既不动
         * Kotlin 单例，也让老书源零改动跑通。
         *
         * 原理：脚本内以 `var java = __javaCompat` 影子覆盖 binding 的 `java` —— 局部
         * 作用域的 var 覆盖 Rhino scope 同名变量，不影响外层 binding。
         *
         * 覆盖的方法清单与 [SourceLoginScriptApi] 一一对应，增加新方法时务必补列，否则
         * Legado 脚本调不到。
         */
        const val LEGACY_JAVA_COMPAT_PRELUDE: String =
            "(function(){" +
                "if(typeof loginExt==='undefined'||loginExt==null)return;" +
                "var _orig=java;" +
                "var names=['upLoginData','reLoginView','copyText','showBrowser'," +
                "'refreshBookInfo','refreshBookToc','refreshContent','refreshExplore'," +
                "'clearTtsCache'];" +
                "var proxy={};" +
                "for(var k in _orig){try{proxy[k]=_orig[k];}catch(e){}}" +
                "for(var i=0;i<names.length;i++){(function(n){" +
                "proxy[n]=function(){return loginExt[n].apply(loginExt,arguments);};" +
                "})(names[i]);}" +
                "java=proxy;" +
            "})();\n"
    }
}
