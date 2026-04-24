package com.script

import org.mozilla.javascript.Scriptable
import java.io.Reader
import java.io.StringReader
import kotlin.coroutines.CoroutineContext

abstract class AbstractScriptEngine(
    val bindings: Bindings = SimpleBindings()
) : ScriptEngine {
    override var context: ScriptContext = SimpleScriptContext(bindings)

    override fun getBindings(scope: Int): Bindings? = context.getBindings(scope)

    override fun setBindings(bindings: Bindings?, scope: Int) {
        context.setBindings(bindings, scope)
    }

    override fun put(name: String, value: Any?) {
        context.setAttribute(name, value, ScriptContext.ENGINE_SCOPE)
    }

    override operator fun get(name: String): Any? = context.getAttribute(name)

    override fun eval(reader: Reader, scope: Scriptable): Any? =
        eval(reader, scope, null)

    override suspend fun evalSuspend(script: String, scope: Scriptable): Any? =
        evalSuspend(StringReader(script), scope)

    override fun eval(script: String, scope: Scriptable): Any? =
        eval(script, scope, null)

    @Throws(ScriptException::class)
    override fun eval(reader: Reader, context: ScriptContext): Any? =
        eval(reader, getRuntimeScope(context), null)

    override fun eval(script: String, scope: Scriptable, coroutineContext: CoroutineContext?): Any? =
        eval(StringReader(script), scope, coroutineContext)

    @Throws(ScriptException::class)
    override fun eval(reader: Reader, bindings: Bindings): Any? =
        eval(reader, getScriptContext(bindings))

    @Throws(ScriptException::class)
    override fun eval(script: String, bindings: Bindings): Any? =
        eval(script, getScriptContext(bindings))

    @Throws(ScriptException::class)
    override fun eval(script: String, bindings: ScriptBindings): Any? =
        eval(script, getRuntimeScope(bindings), null)

    @Throws(ScriptException::class)
    override fun eval(reader: Reader): Any? = eval(reader, context)

    @Throws(ScriptException::class)
    override fun eval(script: String): Any? = eval(script, context)

    @Throws(ScriptException::class)
    override fun eval(script: String, context: ScriptContext): Any? =
        eval(StringReader(script), context)

    override fun getScriptContext(bindings: Bindings): ScriptContext {
        val scopedContext = SimpleScriptContext(bindings)
        scopedContext.setBindings(
            context.getBindings(ScriptContext.GLOBAL_SCOPE),
            ScriptContext.GLOBAL_SCOPE
        )
        scopedContext.reader = context.reader
        scopedContext.writer = context.writer
        scopedContext.errorWriter = context.errorWriter
        return scopedContext
    }
}
