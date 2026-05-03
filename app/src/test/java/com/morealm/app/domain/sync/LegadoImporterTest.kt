package com.morealm.app.domain.sync

import com.morealm.app.domain.entity.BookFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Legado 一键搬家解析 + 字段映射回归测试。
 *
 * 不依赖真 DAO / DB —— 直接测 [LegadoImporter.parseZip]、[LegadoImporter.mapBook] 等
 * internal 函数。protect 三个层面：
 *
 *  1. **zip 解析鲁棒性**：未知 entry 不阻断、损坏 JSON 不阻断、空 zip 也能跑出空 ParsedBackup
 *  2. **字段映射保真**：Legado.Book.bookUrl / durChapterIndex / order / type 等关键字段
 *     不能在搬家时弄丢
 *  3. **Legado 升级容忍**：新增字段（如 `syncTime`、`originOrder` 等）不能 crash 解析；
 *     `Json { ignoreUnknownKeys=true }` 必须真生效
 */
class LegadoImporterTest {

    // ── parseZip 行为 ───────────────────────────────────────────────────────

    @Test
    fun `parseZip should handle empty zip without throwing`() {
        val zipBytes = makeZip(emptyMap())
        val parsed = LegadoImporter.parseZip(zipBytes)
        assertEquals(0, parsed.books.size)
        assertEquals(0, parsed.bookSources.size)
        assertTrue(parsed.skippedFiles.isEmpty())
    }

    @Test
    fun `parseZip should record unknown entries in skippedFiles`() {
        val zipBytes = makeZip(mapOf(
            "rssSources.json" to "[]",
            "config.xml" to "<map></map>",
            "themeConfig.json" to "[]",
            "what_is_this.json" to "{}",
        ))
        val parsed = LegadoImporter.parseZip(zipBytes)
        assertTrue("rssSources.json" in parsed.skippedFiles)
        assertTrue("config.xml" in parsed.skippedFiles)
        assertTrue("themeConfig.json" in parsed.skippedFiles)
        assertTrue("what_is_this.json" in parsed.skippedFiles)
    }

    @Test
    fun `parseZip should isolate corrupt JSON without dropping other sections`() {
        // bookshelf.json 故意写成残缺 JSON；bookSource.json 正常
        // 期望：books 为空（解码失败回退到 emptyList），bookSources 正常
        val zipBytes = makeZip(mapOf(
            "bookshelf.json" to "this is not json {",
            "bookSource.json" to """[{"bookSourceUrl":"http://x","bookSourceName":"X"}]""",
        ))
        val parsed = LegadoImporter.parseZip(zipBytes)
        assertEquals(0, parsed.books.size)
        assertEquals(1, parsed.bookSources.size)
        assertEquals("http://x", parsed.bookSources[0].bookSourceUrl)
    }

    @Test
    fun `parseZip should decode minimal Legado bookshelf entry`() {
        // 模拟 Legado.Book GSON 序列化的最小 JSON。注意 Legado 用字段名不是 SerialName，
        // 我们的 LegadoBookDto 字段名要严格对齐 Legado.Book Kotlin 字段名。
        val booksJson = """
            [{
                "bookUrl": "https://book.com/abc",
                "name": "三体",
                "author": "刘慈欣",
                "origin": "https://source.com",
                "originName": "起点",
                "intro": "硬科幻经典",
                "kind": "科幻",
                "totalChapterNum": 42,
                "durChapterIndex": 17,
                "durChapterPos": 1234,
                "durChapterTime": 1700000000000,
                "lastCheckCount": 3,
                "lastCheckTime": 1700001000000,
                "canUpdate": true,
                "order": 5,
                "type": 0
            }]
        """.trimIndent()
        val zipBytes = makeZip(mapOf("bookshelf.json" to booksJson))
        val parsed = LegadoImporter.parseZip(zipBytes)
        assertEquals(1, parsed.books.size)
        val dto = parsed.books[0]
        assertEquals("https://book.com/abc", dto.bookUrl)
        assertEquals("三体", dto.name)
        assertEquals("刘慈欣", dto.author)
        assertEquals(17, dto.durChapterIndex)
        assertEquals(1234, dto.durChapterPos)
        assertEquals(42, dto.totalChapterNum)
        assertEquals(3, dto.lastCheckCount)
        assertTrue(dto.canUpdate)
    }

    @Test
    fun `parseZip should ignore unknown future Legado fields`() {
        // Legado 升级后给 Book 加了新字段；我们的 DTO 没有它，但解析不应该 crash。
        val booksJson = """
            [{
                "bookUrl": "u",
                "name": "n",
                "author": "a",
                "syncTime": 999999,
                "futurePropertyXyz": "ABC",
                "nestedFuture": { "k": "v" }
            }]
        """.trimIndent()
        val zipBytes = makeZip(mapOf("bookshelf.json" to booksJson))
        val parsed = LegadoImporter.parseZip(zipBytes)
        assertEquals(1, parsed.books.size)
        assertEquals("u", parsed.books[0].bookUrl)
        assertEquals("n", parsed.books[0].name)
    }

    // ── mapper: Book ────────────────────────────────────────────────────────

    @Test
    fun `mapBook should preserve identity-critical fields`() {
        val dto = LegadoImporter.LegadoBookDto(
            bookUrl = "https://x.com/b1",
            name = "Foo",
            author = "Bar",
            origin = "https://src.com",
            durChapterIndex = 10,
            durChapterPos = 200,
            durChapterTime = 1700000000000L,
            totalChapterNum = 100,
            lastCheckCount = 5,
            lastCheckTime = 1700001000000L,
            canUpdate = false,
            order = 7,
            kind = "Fantasy",
            customTag = "fav",
        )
        val book = LegadoImporter.mapBook(dto)
        assertEquals("https://x.com/b1", book.id)              // bookUrl → id
        assertEquals("https://x.com/b1", book.bookUrl)          // bookUrl 也填 bookUrl
        assertEquals("Foo", book.title)                         // name → title
        assertEquals("Bar", book.author)
        assertEquals(10, book.lastReadChapter)                  // durChapterIndex → lastReadChapter
        assertEquals(200, book.lastReadPosition)                // durChapterPos → lastReadPosition
        assertEquals(100, book.totalChapters)
        assertEquals(5, book.lastCheckCount)                    // v16 字段对齐
        assertEquals(1700001000000L, book.lastCheckTime)
        assertFalse(book.canUpdate)
        assertEquals(7, book.sortOrder)                         // order → sortOrder
        assertEquals("Fantasy", book.category)                  // kind → category
        assertEquals("Fantasy", book.kind)                       // 同时填 kind
        assertEquals("fav", book.customTag)
        assertEquals("https://src.com", book.sourceId)
        assertEquals("https://src.com", book.origin)
    }

    @Test
    fun `mapBook should compute readProgress from durChapterIndex over totalChapterNum`() {
        val dto = LegadoImporter.LegadoBookDto(
            bookUrl = "u", name = "n",
            durChapterIndex = 25, totalChapterNum = 100,
        )
        val book = LegadoImporter.mapBook(dto)
        assertEquals(0.25f, book.readProgress, 0.001f)
    }

    @Test
    fun `mapBook readProgress should be 0 when totalChapters is 0`() {
        val dto = LegadoImporter.LegadoBookDto(
            bookUrl = "u", name = "n",
            durChapterIndex = 5, totalChapterNum = 0,
        )
        val book = LegadoImporter.mapBook(dto)
        assertEquals(0f, book.readProgress, 0.001f)
    }

    @Test
    fun `mapBook should treat group=0 as no folder`() {
        val dto = LegadoImporter.LegadoBookDto(bookUrl = "u", name = "n", group = 0)
        assertNull(LegadoImporter.mapBook(dto).folderId)

        val dto2 = LegadoImporter.LegadoBookDto(bookUrl = "u", name = "n", group = 12345L)
        assertEquals("12345", LegadoImporter.mapBook(dto2).folderId)
    }

    @Test
    fun `mapBook should map type bitmask to BookFormat`() {
        // type=0 → 在线 web
        assertEquals(BookFormat.WEB, LegadoImporter.mapBook(
            LegadoImporter.LegadoBookDto(bookUrl = "u", name = "n", type = 0)
        ).format)
        // type=8 (webBook bit) → WEB
        assertEquals(BookFormat.WEB, LegadoImporter.mapBook(
            LegadoImporter.LegadoBookDto(bookUrl = "u", name = "n", type = 8)
        ).format)
        // type=16 (local epub bit) → EPUB
        assertEquals(BookFormat.EPUB, LegadoImporter.mapBook(
            LegadoImporter.LegadoBookDto(bookUrl = "u", name = "n", type = 16)
        ).format)
    }

    @Test
    fun `mapBook should prefer customIntro over intro for description`() {
        val a = LegadoImporter.mapBook(LegadoImporter.LegadoBookDto(
            bookUrl = "u", name = "n",
            intro = "原始简介", customIntro = "用户改的"
        ))
        assertEquals("用户改的", a.description)

        val b = LegadoImporter.mapBook(LegadoImporter.LegadoBookDto(
            bookUrl = "u", name = "n",
            intro = "原始简介", customIntro = ""
        ))
        assertEquals("原始简介", b.description)
    }

    // ── mapper: BookGroup ────────────────────────────────────────────────────

    @Test
    fun `mapBookGroup should convert groupId Long to id String`() {
        val dto = LegadoImporter.LegadoBookGroupDto(
            groupId = 0xDEADBEEFL,
            groupName = "我的收藏",
            order = 3,
        )
        val g = LegadoImporter.mapBookGroup(dto)
        assertEquals("3735928559", g.id) // 0xDEADBEEF as decimal
        assertEquals("我的收藏", g.name)
        assertEquals(3, g.sortOrder)
        assertFalse(g.auto) // 用户从 Legado 搬来的视为手动
    }

    // ── mapper: ReplaceRule ──────────────────────────────────────────────────

    @Test
    fun `mapReplaceRule should preserve regex semantic and toggle`() {
        val dto = LegadoImporter.LegadoReplaceRuleDto(
            id = 42L,
            name = "去广告",
            pattern = "广告.*?结束",
            replacement = "",
            isRegex = true,
            isEnabled = true,
            scopeContent = true,
            scopeTitle = false,
            timeoutMillisecond = 5000L,
            sortOrder = 10,
        )
        val rule = LegadoImporter.mapReplaceRule(dto)
        assertEquals("42", rule.id)
        assertEquals("去广告", rule.name)
        assertEquals("广告.*?结束", rule.pattern)
        assertTrue(rule.isRegex)
        assertTrue(rule.enabled)
        assertEquals(5000, rule.timeoutMs)
        assertEquals(10, rule.sortOrder)
    }

    @Test
    fun `mapReplaceRule should clamp huge timeout to Int max`() {
        val dto = LegadoImporter.LegadoReplaceRuleDto(
            id = 1L, pattern = "x",
            timeoutMillisecond = Long.MAX_VALUE,
        )
        val rule = LegadoImporter.mapReplaceRule(dto)
        assertEquals(Int.MAX_VALUE, rule.timeoutMs)
    }

    // ── mapper: HttpTts ──────────────────────────────────────────────────────

    @Test
    fun `mapHttpTts should preserve identity and configuration fields`() {
        val dto = LegadoImporter.LegadoHttpTtsDto(
            id = 1234L,
            name = "我的引擎",
            url = "https://tts.com/{{speakText}}",
            contentType = "audio/mpeg",
            concurrentRate = "1/1000",
            loginUrl = "https://login.com",
            header = """{"Authorization":"Bearer abc"}""",
            lastUpdateTime = 1700000000000L,
        )
        val tts = LegadoImporter.mapHttpTts(dto)
        assertEquals(1234L, tts.id)
        assertEquals("我的引擎", tts.name)
        assertEquals("https://tts.com/{{speakText}}", tts.url)
        assertEquals("audio/mpeg", tts.contentType)
        assertEquals("1/1000", tts.concurrentRate)
        assertEquals("""{"Authorization":"Bearer abc"}""", tts.header)
        assertTrue(tts.enabled)
    }

    @Test
    fun `mapHttpTts should fallback lastUpdateTime when source is 0`() {
        val before = System.currentTimeMillis()
        val tts = LegadoImporter.mapHttpTts(
            LegadoImporter.LegadoHttpTtsDto(id = 1, name = "n", lastUpdateTime = 0)
        )
        val after = System.currentTimeMillis()
        assertTrue(tts.lastUpdateTime in before..after)
    }

    // ── 综合：endend-to-end zip parse + sequence ────────────────────────────

    @Test
    fun `parseZip should fan out a realistic multi-section Legado zip`() {
        val zipBytes = makeZip(mapOf(
            "bookshelf.json" to """[{"bookUrl":"u1","name":"book1","author":"a"}]""",
            "bookSource.json" to """[{"bookSourceUrl":"https://s","bookSourceName":"S"}]""",
            "bookGroup.json" to """[{"groupId":1,"groupName":"分组A","order":0,"show":true,"enableRefresh":true}]""",
            "bookmark.json" to """[{"time":1,"bookName":"book1","bookAuthor":"a","chapterIndex":2,"chapterPos":3,"chapterName":"c","bookText":"t","content":"n"}]""",
            "replaceRule.json" to """[{"id":1,"name":"r","pattern":"p","replacement":"","scopeContent":true,"isEnabled":true,"isRegex":true,"timeoutMillisecond":3000,"sortOrder":0}]""",
            "httpTTS.json" to """[{"id":1,"name":"e","url":"https://tts/x"}]""",
            // 另外混进一些不支持的 entry，验证 skippedFiles
            "rssSources.json" to "[]",
            "config.xml" to "<map></map>",
        ))

        val parsed = LegadoImporter.parseZip(zipBytes)
        assertEquals(1, parsed.books.size)
        assertEquals(1, parsed.bookSources.size)
        assertEquals(1, parsed.bookGroups.size)
        assertEquals(1, parsed.bookmarks.size)
        assertEquals(1, parsed.replaceRules.size)
        assertEquals(1, parsed.httpTts.size)
        assertTrue("rssSources.json" in parsed.skippedFiles)
        assertTrue("config.xml" in parsed.skippedFiles)

        // 确保每段字段也能正常解
        assertEquals("分组A", parsed.bookGroups[0].groupName)
        assertEquals("book1", parsed.bookmarks[0].bookName)
        assertEquals(3, parsed.bookmarks[0].chapterPos)
    }

    // ── mapper: SearchKeyword ────────────────────────────────────────────────

    @Test
    fun `parseZip should decode searchHistory entries 1to1`() {
        val json = """
            [
              {"word":"三体","usage":12,"lastUseTime":1700000000000},
              {"word":"凡人修仙","usage":3,"lastUseTime":1700001000000}
            ]
        """.trimIndent()
        val parsed = LegadoImporter.parseZip(makeZip(mapOf("searchHistory.json" to json)))
        assertEquals(2, parsed.searchHistory.size)
        assertEquals("三体", parsed.searchHistory[0].word)
        assertEquals(12, parsed.searchHistory[0].usage)
        assertEquals(1700000000000L, parsed.searchHistory[0].lastUseTime)
        // searchHistory.json 之前在 skipped 名单里；现在不应再出现在 skippedFiles
        assertFalse(parsed.skippedFiles.contains("searchHistory.json"))
    }

    @Test
    fun `mapSearchKeyword should preserve word, usage, lastUseTime`() {
        val dto = LegadoImporter.LegadoSearchKeywordDto(
            word = "斗破苍穹",
            usage = 7,
            lastUseTime = 1700002000000L,
        )
        val kw = LegadoImporter.mapSearchKeyword(dto)
        assertEquals("斗破苍穹", kw.word)
        assertEquals(7, kw.usage)
        assertEquals(1700002000000L, kw.lastUseTime)
    }

    @Test
    fun `mapSearchKeyword should clamp usage to at least 1`() {
        // Legado 老数据偶有 usage=0；按 SearchKeyword 表的排序约定要 ≥1
        val kw = LegadoImporter.mapSearchKeyword(
            LegadoImporter.LegadoSearchKeywordDto(word = "x", usage = 0, lastUseTime = 1L)
        )
        assertEquals(1, kw.usage)
    }

    @Test
    fun `mapSearchKeyword should fallback lastUseTime when source is 0`() {
        val before = System.currentTimeMillis()
        val kw = LegadoImporter.mapSearchKeyword(
            LegadoImporter.LegadoSearchKeywordDto(word = "y", usage = 1, lastUseTime = 0L)
        )
        val after = System.currentTimeMillis()
        assertTrue(kw.lastUseTime in before..after)
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** 在内存里构造 zip 字节，每个 entry name → 文本内容。 */
    private fun makeZip(entries: Map<String, String>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            for ((name, content) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }
}
