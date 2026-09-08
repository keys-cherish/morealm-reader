package com.morealm.app.domain.source

import android.app.Application
import com.script.ScriptBindings
import com.script.rhino.RhinoScriptEngine
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [28])
class SourceScriptRuntimeTest {
    @Test fun `library manifest order is retained and functions see request bindings`() {
        val fetched = mutableListOf<String>()
        val runtime = SourceScriptRuntime { url, _ ->
            fetched += url
            if (url.endsWith("base.js")) "var base = 7;" else "function answer(){return base + value;}"
        }
        val bindings = ScriptBindings().apply { put("value", 5) }
        val scope = runtime.createScope(bindings,
            """{"base":"https://test.invalid/base.js","api":"https://test.invalid/api.js"}""")
        assertEquals(listOf("https://test.invalid/base.js", "https://test.invalid/api.js"), fetched)
        assertEquals(12.0, (RhinoScriptEngine.eval("answer()", scope) as Number).toDouble(), 0.0)
    }

    @Test fun `cached code cannot share mutable library globals between evaluations`() {
        val runtime = SourceScriptRuntime { _, _ -> error("unexpected download") }
        val code = "var state = {count: 0}; function next(){return ++state.count;}"
        val first = runtime.createScope(ScriptBindings(), code)
        val second = runtime.createScope(ScriptBindings(), code)
        assertEquals(1.0, (RhinoScriptEngine.eval("next()", first) as Number).toDouble(), 0.0)
        assertEquals(2.0, (RhinoScriptEngine.eval("next()", first) as Number).toDouble(), 0.0)
        assertEquals(1.0, (RhinoScriptEngine.eval("next()", second) as Number).toDouble(), 0.0)
    }

    @Test fun `download errors retain their cause and are never executed as JavaScript`() {
        val failure = IllegalStateException("HTTP 503")
        val runtime = SourceScriptRuntime { _, _ -> throw failure }
        try {
            runtime.createScope(ScriptBindings(), """{"lib":"https://test.invalid/lib.js"}""")
            fail("Expected download failure")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual)
        }
    }
}
