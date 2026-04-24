package com.morealm.app.domain.http

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.net.http.SslError
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.apache.commons.text.StringEscapeUtils
import java.lang.ref.WeakReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 后台WebView - 用于需要JS渲染的书源页面
 * 在主线程创建WebView，加载页面后通过JS获取渲染后的HTML
 */
class BackstageWebView(
    private val url: String? = null,
    private val html: String? = null,
    private val encode: String? = null,
    private val tag: String? = null,
    private val headerMap: Map<String, String>? = null,
    private val sourceRegex: String? = null,
    private val overrideUrlRegex: String? = null,
    private val javaScript: String? = null,
    private val delayTime: Long = 0,
) {

    private val mHandler = Handler(Looper.getMainLooper())
    private var callback: Callback? = null
    private var mWebView: WebView? = null

    suspend fun getStrResponse(): StrResponse = withTimeout(60000L) {
        suspendCancellableCoroutine { block ->
            block.invokeOnCancellation {
                mHandler.post { destroy() }
            }
            callback = object : Callback() {
                override fun onResult(response: StrResponse) {
                    if (!block.isCompleted) block.resume(response)
                }
                override fun onError(error: Throwable) {
                    if (!block.isCompleted) block.resumeWithException(error)
                }
            }
            mHandler.post {
                try {
                    load()
                } catch (error: Throwable) {
                    if (!block.isCompleted) block.resumeWithException(error)
                }
            }
        }
    }

    private fun getEncoding(): String = encode ?: "utf-8"

    private fun load() {
        val webView = createWebView()
        mWebView = webView
        try {
            when {
                !html.isNullOrEmpty() -> if (url.isNullOrEmpty()) {
                    webView.loadData(html, "text/html", getEncoding())
                } else {
                    webView.loadDataWithBaseURL(url, html, "text/html", getEncoding(), url)
                }
                else -> if (headerMap == null) {
                    webView.loadUrl(url!!)
                } else {
                    webView.loadUrl(url!!, headerMap)
                }
            }
        } catch (e: Exception) {
            callback?.onError(e)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val webView = WebView(com.morealm.app.MoRealmApp.instance)
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.blockNetworkImage = true
        settings.userAgentString = headerMap?.get("User-Agent")
            ?: "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        if (sourceRegex.isNullOrBlank() && overrideUrlRegex.isNullOrBlank()) {
            webView.webViewClient = HtmlWebViewClient()
        } else {
            webView.webViewClient = SnifferWebClient()
        }
        return webView
    }

    private fun destroy() {
        mWebView?.destroy()
        mWebView = null
    }

    private fun getJs(): String = javaScript?.takeIf { it.isNotEmpty() } ?: JS

    private fun setCookie(url: String) {
        tag?.let {
            val cookie = android.webkit.CookieManager.getInstance().getCookie(url)
            if (!cookie.isNullOrBlank()) {
                CookieStore.setCookie(it, cookie)
            }
        }
    }

    private inner class HtmlWebViewClient : WebViewClient() {
        private var runnable: EvalJsRunnable? = null

        override fun onPageFinished(view: WebView, url: String) {
            setCookie(url)
            if (runnable == null) {
                runnable = EvalJsRunnable(view, url, getJs())
            }
            mHandler.removeCallbacks(runnable!!)
            mHandler.postDelayed(runnable!!, 1000 + delayTime)
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
            handler?.proceed()
        }

        private inner class EvalJsRunnable(
            webView: WebView,
            private val url: String,
            private val mJavaScript: String,
        ) : Runnable {
            var retry = 0
            private val mWebView: WeakReference<WebView> = WeakReference(webView)
            override fun run() {
                mWebView.get()?.evaluateJavascript(mJavaScript) { handleResult(it) }
            }

            private fun handleResult(result: String) {
                if (result.isNotEmpty() && result != "null") {
                    val content = StringEscapeUtils.unescapeJson(result)
                        .replace(quoteRegex, "")
                    try {
                        callback?.onResult(StrResponse(url, content))
                    } catch (e: Exception) {
                        callback?.onError(e)
                    }
                    mHandler.post { destroy() }
                    return
                }
                if (retry > 30) {
                    callback?.onError(Exception("WebView JS执行超时"))
                    mHandler.post { destroy() }
                    return
                }
                retry++
                mHandler.postDelayed(this@EvalJsRunnable, 1000)
            }
        }
    }

    private inner class SnifferWebClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            overrideUrlRegex?.let {
                val requestUrl = request.url.toString()
                if (requestUrl.matches(it.toRegex())) {
                    try {
                        callback?.onResult(StrResponse(url!!, requestUrl))
                    } catch (e: Exception) {
                        callback?.onError(e)
                    }
                    mHandler.post { destroy() }
                    return true
                }
            }
            return super.shouldOverrideUrlLoading(view, request)
        }

        override fun onLoadResource(view: WebView, resUrl: String) {
            sourceRegex?.let {
                if (resUrl.matches(it.toRegex())) {
                    try {
                        callback?.onResult(StrResponse(url!!, resUrl))
                    } catch (e: Exception) {
                        callback?.onError(e)
                    }
                    mHandler.post { destroy() }
                }
            }
        }

        override fun onPageFinished(webView: WebView, url: String) {
            setCookie(url)
            if (!javaScript.isNullOrEmpty()) {
                mHandler.postDelayed({
                    webView.loadUrl("javascript:$javaScript")
                }, 1000L + delayTime)
            }
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
            handler?.proceed()
        }
    }

    companion object {
        const val JS = "document.documentElement.outerHTML"
        private val quoteRegex = "^\"|\"$".toRegex()
    }

    abstract class Callback {
        abstract fun onResult(response: StrResponse)
        abstract fun onError(error: Throwable)
    }
}
