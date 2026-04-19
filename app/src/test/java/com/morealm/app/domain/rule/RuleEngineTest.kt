package com.morealm.app.domain.rule

import org.junit.Assert.*
import org.junit.Test

class RuleEngineTest {

    @Test
    fun `CSS selector extracts text`() {
        val engine = RuleEngine()
        engine.setContent("<div><p class='title'>Hello World</p></div>")
        assertEquals("Hello World", engine.getString("p.title"))
    }

    @Test
    fun `CSS selector with @text suffix`() {
        val engine = RuleEngine()
        engine.setContent("<a href='http://example.com'>Link</a>")
        assertEquals("Link", engine.getString("a@text"))
    }

    @Test
    fun `CSS selector with @href suffix`() {
        val engine = RuleEngine()
        engine.setContent("<a href='http://example.com'>Link</a>")
        assertEquals("http://example.com", engine.getString("a@href"))
    }

    @Test
    fun `XPath selector`() {
        val engine = RuleEngine()
        engine.setContent("<div><span>XPath Test</span></div>")
        val result = engine.getString("@XPath://span/text()")
        assertTrue(result.contains("XPath Test"))
    }

    @Test
    fun `JSONPath selector`() {
        val engine = RuleEngine()
        engine.setContent("""{"name":"MoRealm","version":1}""")
        assertEquals("MoRealm", engine.getString("$.name"))
    }

    @Test
    fun `JSONPath array`() {
        val engine = RuleEngine()
        engine.setContent("""{"items":[{"title":"A"},{"title":"B"}]}""")
        val list = engine.getStringList("$.items[*].title")
        assertEquals(listOf("A", "B"), list)
    }

    @Test
    fun `regex replacement suffix`() {
        val engine = RuleEngine()
        engine.setContent("<p>Price: $99.99</p>")
        val result = engine.getString("p@text##\\$##￥")
        assertEquals("Price: ￥99.99", result)
    }

    @Test
    fun `AND combinator concatenates`() {
        val engine = RuleEngine()
        engine.setContent("<span class='a'>Hello</span><span class='b'>World</span>")
        val result = engine.getString("span.a@text&&span.b@text")
        assertEquals("HelloWorld", result)
    }

    @Test
    fun `OR combinator returns first match`() {
        val engine = RuleEngine()
        engine.setContent("<p>Found</p>")
        assertEquals("Found", engine.getString("span@text||p@text"))
    }

    @Test
    fun `getElements returns list`() {
        val engine = RuleEngine()
        engine.setContent("<ul><li>A</li><li>B</li><li>C</li></ul>")
        val elements = engine.getElements("li")
        assertEquals(3, elements.size)
    }

    @Test
    fun `createChild parses element`() {
        val engine = RuleEngine()
        engine.setContent("<div><p class='name'>Book1</p><p class='author'>Author1</p></div>")
        val elements = engine.getElements("div")
        val child = engine.createChild(elements[0])
        assertEquals("Book1", child.getString("p.name"))
        assertEquals("Author1", child.getString("p.author"))
    }

    @Test
    fun `empty rule returns empty`() {
        val engine = RuleEngine()
        engine.setContent("<p>test</p>")
        assertEquals("", engine.getString(""))
    }

    @Test
    fun `auto-detect JSON content`() {
        val engine = RuleEngine()
        engine.setContent("""[{"id":1},{"id":2}]""")
        val list = engine.getStringList("$[*].id")
        assertEquals(2, list.size)
    }
}
