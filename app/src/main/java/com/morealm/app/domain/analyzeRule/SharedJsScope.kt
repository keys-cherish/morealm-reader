package com.morealm.app.domain.analyzeRule

import com.morealm.app.domain.http.CacheManager
import com.morealm.app.domain.http.okHttpClient
import com.morealm.app.domain.http.newCallStrResponse
import com.script.ScriptBindings
import com.script.rhino.RhinoScriptEngine
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import java.lang.ref.WeakReference
import java.security.MessageDigest
import kotlin.coroutines.CoroutineContext

/**
 * JS共享作用域 - 支持书源 jsLib 字段
 * jsLib 可以是：
 *   1. 直接的JS代码字符串
 *   2. JSON对象 {"name": "https://xxx/lib.js"} — 从URL下载JS并执行
 * 编译后的作用域会被缓存，避免重复编译
 */
object SharedJsScope {

    private val scopeMap = hashMapOf<String, WeakReference<Scriptable>>()
    private val jsonParser = Json { ignoreUnknownKeys = true; isLenient = true }

    fun getScope(jsLib: String?, coroutineContext: CoroutineContext? = null): Scriptable? {
        if (jsLib.isNullOrBlank()) return null

        val key = md5(jsLib)
        var scope = scopeMap[key]?.get()
        if (scope != null) return scope

        scope = RhinoScriptEngine.getRuntimeScope(ScriptBindings())

        if (jsLib.trimStart().startsWith("{")) {
            // JSON格式: {"name": "url"} — 下载并执行每个URL的JS
            try {
                val jsMap = jsonParser.decodeFromString<Map<String, String>>(jsLib)
                jsMap.values.forEach { value ->
                    if (value.startsWith("http://") || value.startsWith("https://")) {
                        val cacheKey = "jsLib_${md5(value)}"
                        var js = CacheManager.get(cacheKey)
                        if (js == null) {
                            js = runBlocking {
                                okHttpClient.newCallStrResponse { url(value) }.body
                            }
                            if (js != null) {
                                CacheManager.put(cacheKey, js)
                            }
                        }
                        if (!js.isNullOrBlank()) {
                            RhinoScriptEngine.eval(js, scope, coroutineContext)
                        }
                    }
                }
            } catch (_: Exception) {
                // 解析失败，当作普通JS执行
                RhinoScriptEngine.eval(jsLib, scope, coroutineContext)
            }
        } else {
            RhinoScriptEngine.eval(jsLib, scope, coroutineContext)
        }

        if (scope is ScriptableObject) {
            scope.sealObject()
        }
        scopeMap[key] = WeakReference(scope)
        return scope
    }

    fun remove(jsLib: String?) {
        if (jsLib.isNullOrBlank()) return
        scopeMap.remove(md5(jsLib))
    }

    private fun md5(str: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(str.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
