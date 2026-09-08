package com.morealm.app.domain.analyzeRule

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [28])
class SourceRuleBridgeTest {
    @Test fun `library and current element bridge compose in a search name rule`() {
        val source = com.morealm.app.domain.entity.BookSource(
            bookSourceUrl = "https://test.invalid/",
            bookSourceName = "Bridge fixture",
            jsLib = "function normalizeTitle(t){return String(t).trim();}",
        )
        val item = org.jsoup.Jsoup.parse("<a class='book' href='/book.html'>测试书</a>").selectFirst("a")!!
        val rule = AnalyzeRule(source = source).setContent(item, "https://test.invalid/")
        rule.setRedirectUrl("https://test.invalid/search")
        assertEquals("测试书", rule.getString("""@js:java.setContent(result);normalizeTitle(java.getString("text"))"""))
        assertEquals("https://test.invalid/book.html", rule.getString("href", isUrl = true))
    }

    @Test fun `getString extracts current content through JavaScript bridge`() {
        val rule = AnalyzeRule().setContent("<div class='title'>书名</div>", "https://test.invalid/")
        assertEquals("书名", rule.evalJS("java.getString('.title@text')").toString())
    }

    @Test fun `getStringList exposes all matches to aggregate rules`() {
        val rule = AnalyzeRule().setContent("<b>甲</b><b>乙</b>", "https://test.invalid/")
        assertEquals("甲,乙", rule.evalJS("java.getStringList('b@text').toArray().join(',')").toString())
    }

    @Test fun `nested runtime context restores outer parser after leaving`() {
        val first = AnalyzeRule().setContent("<p>甲</p>")
        val second = AnalyzeRule().setContent("<p>乙</p>")
        JsExtensions.withRuntimeContext(null, analyzeRule = first) {
            assertEquals("甲", JsExtensions.getString("p@text"))
            JsExtensions.withRuntimeContext(null, analyzeRule = second) {
                assertEquals("乙", JsExtensions.getString("p@text"))
            }
            assertEquals("甲", JsExtensions.getString("p@text"))
        }
    }
}
