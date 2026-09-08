package com.morealm.app.domain.source

import com.morealm.app.domain.http.CacheManager
import com.morealm.app.domain.http.newCallStrResponse
import com.morealm.app.domain.http.okHttpClient
import com.script.CompiledScript
import com.script.ScriptBindings
import com.script.rhino.RhinoScriptEngine
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.mozilla.javascript.Scriptable
import java.security.MessageDigest
import kotlin.coroutines.CoroutineContext

/**
 * Shared compiled code, isolated execution state. Login, URL rules and content rules use
 * the same loader; a library's mutable globals cannot leak into another source/request.
 */
class SourceScriptRuntime(
    private val loadRemote: (String, CoroutineContext?) -> String,
) {
    private val compiled = object : LinkedHashMap<String, CompiledScript>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CompiledScript>): Boolean =
            size > 32
    }

    fun createScope(
        bindings: ScriptBindings,
        library: String?,
        coroutineContext: CoroutineContext? = null,
    ): Scriptable {
        val scope = RhinoScriptEngine.getRuntimeScope(bindings)
        for (script in scripts(library, coroutineContext)) {
            val cached = synchronized(compiled) { compiled[script] }
            val code = cached ?: RhinoScriptEngine.compile(script).also {
                synchronized(compiled) { compiled[script] = it }
            }
            code.eval(scope, coroutineContext)
        }
        return scope
    }

    internal fun scripts(library: String?, context: CoroutineContext?): List<String> {
        val text = library?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        val manifest = if (text.startsWith("{")) {
            runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
        } else null
        if (manifest == null) return listOf(text)
        // Decode separately from downloads: an HTTP failure is not a reason to execute JSON as JS.
        return manifest.map { (name, value) ->
            val url = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: error("脚本库 $name 的地址必须是字符串")
            require(url.startsWith("https://") || url.startsWith("http://")) {
                "脚本库 $name 的地址必须使用 HTTP 或 HTTPS"
            }
            loadRemote(url, context).also { require(it.isNotBlank()) { "脚本库 $name 内容为空" } }
        }
    }

    companion object {
        val shared = SourceScriptRuntime { url, context ->
            val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val cacheKey = "source-script-$digest"
            CacheManager.get(cacheKey) ?: runBlocking(context ?: kotlin.coroutines.EmptyCoroutineContext) {
                val response = okHttpClient.newCallStrResponse { url(url) }
                check(response.code() in 200..299) { "加载脚本库失败：HTTP ${response.code()} ($url)" }
                val script = response.body?.takeIf { it.isNotBlank() }
                    ?: error("脚本库内容为空：$url")
                CacheManager.put(cacheKey, script, saveTime = 24 * 60 * 60)
                script
            }
        }
    }
}
