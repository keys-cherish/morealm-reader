package com.script

import org.mozilla.javascript.Context
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.ScriptableObject
import com.script.rhino.RhinoScriptEngine

/**
 * JS bindings 容器 — 持有注入到 Rhino 脚本执行上下文的变量。
 *
 * 与 Legado 对齐：prototype 指向 [RhinoScriptEngine] 单例的 [com.script.rhino.RhinoTopLevel]，
 * 而非 [Context.initStandardObjects]。
 *
 * [RhinoTopLevel] 继承自 [org.mozilla.javascript.ImporterTopLevel]，内置
 * `importClass` / `importPackage` 等全局函数，书源 JS 里写
 * `importClass(Packages.java.net.URL)` 能正常工作。
 *
 * 注意：[RhinoScriptEngine] 是 Kotlin `object`，其 `init` 块先于任何成员访问执行，
 * 所以 [topLevel] 在首次构造 ScriptBindings 时必定已初始化。
 */
class ScriptBindings : NativeObject() {

    companion object {
        /**
         * 所有 ScriptBindings 实例共享同一个 prototype — [RhinoScriptEngine.topLevel]。
         * 与 Legado 行为一致：RhinoTopLevel 继承 ImporterTopLevel，
         * 提供 importClass / importPackage 等 JS 全局函数。
         */
        private val rhinoTopLevel: ScriptableObject by lazy {
            val cx = Context.enter()
            try {
                RhinoScriptEngine.topLevel
            } finally {
                Context.exit()
            }
        }
    }

    init {
        prototype = rhinoTopLevel
    }

    operator fun set(key: String, value: Any?) {
        Context.enter()
        try {
            put(key, this, Context.javaToJS(value, this))
        } finally {
            Context.exit()
        }
    }

    operator fun set(index: Int, value: Any?) {
        Context.enter()
        try {
            put(index, this, Context.javaToJS(value, this))
        } finally {
            Context.exit()
        }
    }

    fun put(key: String, value: Any?) {
        set(key, value)
    }
}
