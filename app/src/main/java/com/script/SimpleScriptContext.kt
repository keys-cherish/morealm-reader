package com.script

import java.io.InputStreamReader
import java.io.PrintWriter
import java.io.Reader
import java.io.Writer

open class SimpleScriptContext(
    private var engineScope: Bindings = SimpleBindings(),
    override var writer: Writer = PrintWriter(System.out, true),
    override var reader: Reader = InputStreamReader(System.`in`),
    override var errorWriter: Writer = PrintWriter(System.err, true)
) : ScriptContext {
    private var globalScope: Bindings? = null

    override val scopes: List<Int>
        get() = SCOPE_ORDER

    override fun setBindings(bindings: Bindings?, scope: Int) {
        when (scope) {
            ScriptContext.ENGINE_SCOPE -> {
                engineScope = bindings
                    ?: throw NullPointerException("Engine scope cannot be null.")
            }

            ScriptContext.GLOBAL_SCOPE -> globalScope = bindings
            else -> invalidScope(scope)
        }
    }

    override fun getAttribute(name: String): Any? {
        if (engineScope.containsKey(name)) return engineScope[name]
        val global = globalScope
        return if (global != null && global.containsKey(name)) global[name] else null
    }

    override fun getAttribute(name: String, scope: Int): Any? = when (scope) {
        ScriptContext.ENGINE_SCOPE -> engineScope[name]
        ScriptContext.GLOBAL_SCOPE -> globalScope?.get(name)
        else -> invalidScope(scope)
    }

    override fun removeAttribute(name: String, scope: Int): Any? = when (scope) {
        ScriptContext.ENGINE_SCOPE -> engineScope.remove(name)
        ScriptContext.GLOBAL_SCOPE -> globalScope?.remove(name)
        else -> invalidScope(scope)
    }

    override fun setAttribute(name: String, value: Any?, scope: Int) {
        when (scope) {
            ScriptContext.ENGINE_SCOPE -> engineScope[name] = value
            ScriptContext.GLOBAL_SCOPE -> globalScope?.set(name, value)
            else -> invalidScope(scope)
        }
    }

    override fun getAttributesScope(name: String): Int = when {
        engineScope.containsKey(name) -> ScriptContext.ENGINE_SCOPE
        globalScope?.containsKey(name) == true -> ScriptContext.GLOBAL_SCOPE
        else -> -1
    }

    override fun getBindings(scope: Int): Bindings? = when (scope) {
        ScriptContext.ENGINE_SCOPE -> engineScope
        ScriptContext.GLOBAL_SCOPE -> globalScope
        else -> invalidScope(scope)
    }

    private fun invalidScope(scope: Int): Nothing {
        throw IllegalArgumentException("Invalid scope value: $scope")
    }

    companion object {
        private val SCOPE_ORDER = listOf(
            ScriptContext.ENGINE_SCOPE,
            ScriptContext.GLOBAL_SCOPE
        )
    }
}
