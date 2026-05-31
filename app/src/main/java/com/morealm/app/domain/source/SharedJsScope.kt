package com.morealm.app.domain.source

import androidx.collection.LruCache
import com.morealm.app.core.log.AppLog
import com.script.ScriptBindings
import com.script.rhino.RhinoScriptEngine
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import java.lang.ref.WeakReference
import java.security.MessageDigest

/**
 * jsLib 共享作用域 —— 与参考实现的共享 JS 作用域行为对齐。
 *
 * 书源 `jsLib` 字段里的 JS 代码会被预执行一次到独立 Scriptable scope 中，
 * 声明的 `var / function` 挂在这个 scope 上；随后 [BookSource.evalJS] 把
 * ScriptBindings 的 prototype 指向该 scope，loginJs / actionJs 就能按原型链
 * 读到 `host` 这类 jsLib 变量。
 *
 * 当前版本不支持 JSON urls 格式 jsLib（{name: remoteUrl}），遇到则静默跳过，
 * 后续如需可加 OkHttp 下载 + 磁盘缓存。纯字符串 jsLib 按 MD5 缓存结果，
 * 避免每次 evalJS 都重新 parse。
 */
object SharedJsScope {

    private const val TAG = "SharedJsScope"
    private val cache = LruCache<String, WeakReference<Scriptable>>(16)

    fun getScope(jsLib: String?): Scriptable? {
        if (jsLib.isNullOrBlank()) return null
        val trimmed = jsLib.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            AppLog.warn(TAG, "JSON urls 格式 jsLib 暂不支持，忽略")
            return null
        }
        val key = md5(trimmed)
        cache[key]?.get()?.let { return it }
        val scope = try {
            buildScope(trimmed)
        } catch (e: Exception) {
            AppLog.warn(TAG, "jsLib eval 失败：${e.message?.take(120)}")
            return null
        }
        cache.put(key, WeakReference(scope))
        return scope
    }

    private fun buildScope(js: String): Scriptable {
        val scope = RhinoScriptEngine.getRuntimeScope(ScriptBindings())
        RhinoScriptEngine.eval(js, scope)
        // 阻止隐式全局变量创建（函数内漏 var 直接报错），与 Legado 保持一致
        (scope as? ScriptableObject)?.preventExtensions()
        return scope
    }

    private fun md5(s: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
