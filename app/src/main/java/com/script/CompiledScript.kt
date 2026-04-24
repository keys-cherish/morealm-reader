package com.script

import org.mozilla.javascript.Scriptable
import kotlin.coroutines.CoroutineContext

abstract class CompiledScript {
    abstract fun getEngine(): ScriptEngine

    @Throws(ScriptException::class)
    fun eval(context: ScriptContext): Any? =
        eval(getEngine().getRuntimeScope(context), null)

    @Throws(ScriptException::class)
    fun eval(scope: Scriptable): Any? = eval(scope, null)

    @Throws(ScriptException::class)
    abstract fun eval(scope: Scriptable, coroutineContext: CoroutineContext?): Any?

    @Throws(ScriptException::class)
    abstract suspend fun evalSuspend(scope: Scriptable): Any?

    @Throws(ScriptException::class)
    fun eval(bindings: Bindings?): Any? = if (bindings == null) {
        eval()
    } else {
        eval(getEngine().getScriptContext(bindings))
    }

    @Throws(ScriptException::class)
    fun eval(): Any? = eval(getEngine().context)
}
