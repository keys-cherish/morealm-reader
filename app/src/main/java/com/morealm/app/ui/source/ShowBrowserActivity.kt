package com.morealm.app.ui.source

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.http.CookieStore
import com.morealm.app.domain.source.ShowBrowserSession

/**
 * 登录脚本 `loginExt.showBrowser(url, html, preloadJs)` 的交互式容器。
 *
 * 用途：Legado 书源里扫码登录 / 图形验证码 / 滑块验证类场景——脚本启一个浏览器让用户操作，
 * 操作完后脚本拿到最终 HTML 继续解析 token / cookie。MoRealm 原来用 [com.morealm.app.domain.http.BackstageWebView]
 * 是无 UI 后台抓取，不能和用户交互，这类源全挂。
 *
 * 与 [WebViewLoginScreen] 区别：
 *  - WebViewLoginScreen 走 [com.morealm.app.presentation.source.SourceLoginViewModel] 的
 *    `ShowWebView` 状态，是 "loginUi 为空直接 WebView 登录" 路径的一部分，UI 在 BookSource-
 *    ManageScreen 以 Compose Dialog 渲染。
 *  - 本 Activity 是**脚本主动发起**的：登录脚本跑到一半调用 `loginExt.showBrowser(url)` →
 *    挂起等待 → 本 Activity 弹出让用户操作 → 用户按"完成"→ 把页面 HTML + Cookie 送回脚本。
 *
 * 生命周期：
 *  - 脚本调 `showBrowser` 时通过 [launch] 启 Activity（必须带 `FLAG_ACTIVITY_NEW_TASK`，
 *    因为 [SourceLoginScriptApi][com.morealm.app.domain.source.SourceLoginScriptApi] 持有
 *    的是 Application Context）。
 *  - 用户按返回 / "完成" / 系统杀 Activity 时都会触发 emit 到 [ShowBrowserSession]，脚本
 *    协程据此醒来继续。即使是异常路径也保证 emit，避免脚本永久挂起。
 */
class ShowBrowserActivity : ComponentActivity() {

    /**
     * 最终要回传给脚本的 HTML。WebView `onPageFinished` 时通过 `evaluateJavascript(document.
     * documentElement.outerHTML)` 异步读一次，保存在这里；用户按"完成"时 emit 最新值。
     *
     * 没读到就 emit null，脚本按"登录失败"处理。
     */
    private var latestHtml: String? = null

    /**
     * 保证 emit 只跑一次。多重退出路径（按钮 / 系统返回 / onDestroy）都可能触发，重复 emit
     * 会往 RendezvousChannel 塞两次值，把下一个等待者误唤醒。
     */
    private var emitted = false

    private fun emitOnce(html: String?) {
        if (emitted) return
        emitted = true
        ShowBrowserSession.emit(html)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val url = intent.getStringExtra(EXTRA_URL)
        val html = intent.getStringExtra(EXTRA_HTML)
        val preloadJs = intent.getStringExtra(EXTRA_PRELOAD_JS)
        val sourceKey = intent.getStringExtra(EXTRA_SOURCE_KEY) ?: ""
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "登录"
        if (url.isNullOrBlank() && html.isNullOrBlank()) {
            AppLog.warn("ShowBrowser", "no url/html in intent, exiting")
            emitOnce(null)
            finish()
            return
        }

        setContent {
            ShowBrowserContent(
                title = title,
                url = url,
                html = html,
                preloadJs = preloadJs,
                sourceKey = sourceKey,
                onHtmlCaptured = { latestHtml = it },
                onDone = {
                    emitOnce(latestHtml)
                    finish()
                },
                onCancel = {
                    emitOnce(null)
                    finish()
                },
            )
        }
    }

    override fun onDestroy() {
        // 兜底：用户按 Home / 系统杀 / 异常退出时 finish() 不一定被调用。这里保证脚本
        // 不会永久挂起（RendezvousChannel 的 receive 没 timeout）。
        emitOnce(null)
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_URL = "url"
        private const val EXTRA_HTML = "html"
        private const val EXTRA_PRELOAD_JS = "preloadJs"
        private const val EXTRA_SOURCE_KEY = "sourceKey"
        private const val EXTRA_TITLE = "title"

        /**
         * 从 [com.morealm.app.domain.source.SourceLoginScriptApi.showBrowser] 启动。
         * 要求 [context] 来源不限，内部加 `FLAG_ACTIVITY_NEW_TASK`——脚本通常从 IO 协程里
         * 持有的是 Application Context。
         */
        fun launch(
            context: Context,
            url: String?,
            html: String?,
            preloadJs: String?,
            sourceKey: String,
            title: String,
        ) {
            val intent = Intent(context, ShowBrowserActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_HTML, html)
                putExtra(EXTRA_PRELOAD_JS, preloadJs)
                putExtra(EXTRA_SOURCE_KEY, sourceKey)
                putExtra(EXTRA_TITLE, title)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ShowBrowserContent(
    title: String,
    url: String?,
    html: String?,
    preloadJs: String?,
    sourceKey: String,
    onHtmlCaptured: (String?) -> Unit,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }

    BackHandler(enabled = canGoBack) {
        webView?.goBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "取消")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        // 抓一次最新 HTML 后再退。evaluateJavascript 回调异步，先 capture
                        // 再 onDone 可能读到空；WebView 持续在 onPageFinished 里 capture，
                        // 这里再抓一次只是抢时效，即便回调晚于 finish 也无害——脚本拿到的
                        // 就是上一轮 capture 的值，仍比 null 好。
                        webView?.evaluateJavascript(
                            "document.documentElement.outerHTML"
                        ) { raw ->
                            onHtmlCaptured(unquoteJsString(raw))
                        }
                        onDone()
                    }) {
                        Icon(Icons.Default.Check, contentDescription = "完成")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (progress in 1..99) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            useWideViewPort = true
                            loadWithOverviewMode = true
                        }
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, u: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, u, favicon)
                                canGoBack = view?.canGoBack() == true
                                persistCookie(view, sourceKey)
                            }

                            override fun onPageFinished(view: WebView?, u: String?) {
                                super.onPageFinished(view, u)
                                canGoBack = view?.canGoBack() == true
                                persistCookie(view, sourceKey)
                                if (!preloadJs.isNullOrBlank()) {
                                    view?.evaluateJavascript(preloadJs, null)
                                }
                                // 持续抓 HTML，确保"完成"按钮按下那一刻 latestHtml 是新的。
                                view?.evaluateJavascript("document.documentElement.outerHTML") { raw ->
                                    onHtmlCaptured(unquoteJsString(raw))
                                }
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest
                            ): Boolean {
                                val scheme = request.url.scheme
                                // http/https 在 WebView 内加载；其他 scheme（intent://, weixin://）
                                // 交给系统处理，但这里直接拦截不跳外部 App，避免登录流程被中断。
                                return scheme != "http" && scheme != "https"
                            }

                            @SuppressLint("WebViewClientOnReceivedSslError")
                            override fun onReceivedSslError(
                                view: WebView?,
                                handler: SslErrorHandler?,
                                error: SslError?
                            ) {
                                handler?.proceed()
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress
                            }
                        }
                        when {
                            !html.isNullOrBlank() && !url.isNullOrBlank() ->
                                loadDataWithBaseURL(url, html, "text/html", "utf-8", url)
                            !html.isNullOrBlank() -> loadData(html, "text/html", "utf-8")
                            !url.isNullOrBlank() -> loadUrl(url)
                        }
                        webView = this
                    }
                },
                onRelease = { it.destroy() },
            )
        }
    }
}

private fun persistCookie(view: WebView?, sourceKey: String) {
    if (view == null || sourceKey.isBlank()) return
    val raw = CookieManager.getInstance().getCookie(view.url ?: return)
    if (!raw.isNullOrBlank()) {
        CookieStore.setCookie(sourceKey, raw)
    }
}

/**
 * `evaluateJavascript` 回调的结果是**再次 JSON 编码**的字符串，例如 `"<html>...</html>"`
 * 带首尾引号、里面的换行是 `\n` 字面量。这里简单做一次反转：剥引号 + 常见转义还原。
 *
 * 完整 JSON 解析有点重，对脚本用途（正则匹配 token）来说字面量和真换行等效，简版够了。
 */
private fun unquoteJsString(raw: String?): String? {
    if (raw.isNullOrBlank() || raw == "null") return null
    var s = raw
    if (s.startsWith("\"") && s.endsWith("\"")) {
        s = s.substring(1, s.length - 1)
    }
    return s
        .replace("\\\"", "\"")
        .replace("\\n", "\n")
        .replace("\\r", "\r")
        .replace("\\t", "\t")
        .replace("\\\\", "\\")
}
