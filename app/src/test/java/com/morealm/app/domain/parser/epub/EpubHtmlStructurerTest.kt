package com.morealm.app.domain.parser.epub

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 单测覆盖 [EpubHtmlStructurer.structuredFromBody] —— 「自解析 HTML」 Step 1 结构化器。
 *
 * 验证 jsoup AST → [ChapterBlock] 的语义保留正确性：
 *   - 段落 / 多级标题 / 图片各自切块
 *   - 嵌套容器 (div / section / article) 平展
 *   - 段内 img 拆分为前段 + Image + 后段
 *   - 行内强调标签 (em / strong / span) 文本累积进当前段（Step 2 之前不分样式）
 *   - 空白折叠 + 空段过滤
 */
class EpubHtmlStructurerTest {

    private fun parse(html: String) = EpubHtmlStructurer
        .structuredFromBody(Jsoup.parseBodyFragment(html).body())
        .blocks

    // ── 基础切块 ──────────────────────────────

    @Test
    fun `single paragraph emits one Paragraph block`() {
        val blocks = parse("<p>Hello world.</p>")
        assertEquals(1, blocks.size)
        assertEquals(ChapterBlock.Paragraph("Hello world."), blocks[0])
    }

    @Test
    fun `h1 emits Heading with level 1`() {
        val blocks = parse("<h1>第一章 序章</h1>")
        assertEquals(1, blocks.size)
        assertEquals(ChapterBlock.Heading(level = 1, text = "第一章 序章"), blocks[0])
    }

    @Test
    fun `h1 through h6 all levels round-trip`() {
        val html = """
            <h1>L1</h1><h2>L2</h2><h3>L3</h3><h4>L4</h4><h5>L5</h5><h6>L6</h6>
        """.trimIndent()
        val blocks = parse(html)
        assertEquals(6, blocks.size)
        blocks.forEachIndexed { i, block ->
            assertTrue("block $i should be Heading", block is ChapterBlock.Heading)
            assertEquals(i + 1, (block as ChapterBlock.Heading).level)
            assertEquals("L${i + 1}", block.text)
        }
    }

    @Test
    fun `image src emits Image block`() {
        val blocks = parse("""<img src="file:///cache/img1.jpg" />""")
        assertEquals(1, blocks.size)
        assertEquals(ChapterBlock.Image("file:///cache/img1.jpg"), blocks[0])
    }

    // ── 混合块切分 ────────────────────────────

    @Test
    fun `heading then paragraph emits two blocks in order`() {
        val blocks = parse("<h1>章节</h1><p>第一段。</p>")
        assertEquals(
            listOf(
                ChapterBlock.Heading(1, "章节"),
                ChapterBlock.Paragraph("第一段。"),
            ),
            blocks,
        )
    }

    @Test
    fun `multiple paragraphs emit in order`() {
        val blocks = parse("<p>段一</p><p>段二</p><p>段三</p>")
        assertEquals(
            listOf(
                ChapterBlock.Paragraph("段一"),
                ChapterBlock.Paragraph("段二"),
                ChapterBlock.Paragraph("段三"),
            ),
            blocks,
        )
    }

    @Test
    fun `paragraph with embedded image splits to text image text`() {
        val blocks = parse("""<p>前文 <img src="x.jpg" /> 后文</p>""")
        // <p>text <img/> more</p> 拆 3 块：Paragraph + Image + Paragraph
        // 见 EpubHtmlStructurer KDoc 「块切分规则」
        assertEquals(3, blocks.size)
        assertEquals(ChapterBlock.Paragraph("前文"), blocks[0])
        assertEquals(ChapterBlock.Image("x.jpg"), blocks[1])
        assertEquals(ChapterBlock.Paragraph("后文"), blocks[2])
    }

    @Test
    fun `paragraph with only image emits Image only`() {
        val blocks = parse("""<p><img src="full-page.jpg" /></p>""")
        // 整段只有 img：text 累积空，flush 不产出，只输出 Image
        assertEquals(listOf(ChapterBlock.Image("full-page.jpg")), blocks)
    }

    // ── 容器展开 ──────────────────────────────

    @Test
    fun `div wraps paragraph - recurses into container`() {
        val blocks = parse("<div><p>嵌套段</p></div>")
        assertEquals(listOf(ChapterBlock.Paragraph("嵌套段")), blocks)
    }

    @Test
    fun `nested section article div recurses correctly`() {
        val html = """
            <section>
              <article>
                <div>
                  <h2>标题</h2>
                  <p>正文段。</p>
                </div>
              </article>
            </section>
        """.trimIndent()
        val blocks = parse(html)
        assertEquals(
            listOf(
                ChapterBlock.Heading(2, "标题"),
                ChapterBlock.Paragraph("正文段。"),
            ),
            blocks,
        )
    }

    @Test
    fun `blockquote treated as container - extracts text as Paragraph`() {
        val blocks = parse("<blockquote>引用文字</blockquote>")
        // Step 1 把 blockquote 当容器处理（不区分引用样式），文本直接成段
        assertEquals(listOf(ChapterBlock.Paragraph("引用文字")), blocks)
    }

    // ── 行内元素 ──────────────────────────────

    @Test
    fun `inline em and strong are flattened to text in Step 1`() {
        val blocks = parse("<p>普通<em>斜体</em>普通<strong>粗体</strong>结束。</p>")
        // Step 1 不分行内样式，所有文本汇成单段
        assertEquals(listOf(ChapterBlock.Paragraph("普通斜体普通粗体结束。")), blocks)
    }

    @Test
    fun `inline span with text is flattened`() {
        val blocks = parse("""<p>前<span class="highlight">高亮</span>后</p>""")
        assertEquals(listOf(ChapterBlock.Paragraph("前高亮后")), blocks)
    }

    @Test
    fun `nested inline within inline still flattens`() {
        val blocks = parse("<p>外<em>中<strong>内</strong>层</em>外</p>")
        assertEquals(listOf(ChapterBlock.Paragraph("外中内层外")), blocks)
    }

    // ── 空白与边界 ────────────────────────────

    @Test
    fun `empty paragraph is skipped`() {
        val blocks = parse("<p>   </p><p>有效段</p><p>\n\t</p>")
        assertEquals(listOf(ChapterBlock.Paragraph("有效段")), blocks)
    }

    @Test
    fun `whitespace runs are collapsed to single space`() {
        val blocks = parse("<p>多个    空格\n\n换行</p>")
        assertEquals(listOf(ChapterBlock.Paragraph("多个 空格 换行")), blocks)
    }

    @Test
    fun `br splits paragraph into two`() {
        val blocks = parse("<p>第一行<br/>第二行</p>")
        assertEquals(
            listOf(
                ChapterBlock.Paragraph("第一行"),
                ChapterBlock.Paragraph("第二行"),
            ),
            blocks,
        )
    }

    @Test
    fun `empty body emits no blocks`() {
        val blocks = parse("")
        assertTrue(blocks.isEmpty())
    }

    @Test
    fun `img with empty src is skipped`() {
        val blocks = parse("""<img src="" /><p>文本</p>""")
        assertEquals(listOf(ChapterBlock.Paragraph("文本")), blocks)
    }

    // ── StructuredChapterContent helpers ──────

    @Test
    fun `isEmpty true for empty blocks`() {
        assertTrue(StructuredChapterContent(emptyList()).isEmpty())
    }

    @Test
    fun `isEmpty true when all blocks are blank text`() {
        val content = StructuredChapterContent(
            listOf(
                ChapterBlock.Paragraph(""),
                ChapterBlock.Heading(1, "  "),
                ChapterBlock.Image(""),
            ),
        )
        assertTrue(content.isEmpty())
    }

    @Test
    fun `isEmpty false when at least one real block`() {
        val content = StructuredChapterContent(listOf(ChapterBlock.Paragraph("内容")))
        assertEquals(false, content.isEmpty())
    }

    @Test
    fun `totalChars counts paragraph and heading text`() {
        val content = StructuredChapterContent(
            listOf(
                ChapterBlock.Heading(1, "12345"),     // 5
                ChapterBlock.Paragraph("123456789"),  // 9
                ChapterBlock.Image("img.jpg"),        // 0 (images don't count)
                ChapterBlock.Paragraph("123"),        // 3
            ),
        )
        assertEquals(17, content.totalChars)
    }

    // ── Heading level coercion ────────────────

    @Test(expected = IllegalArgumentException::class)
    fun `Heading level out of range fails fast`() {
        ChapterBlock.Heading(level = 7, text = "x")
    }

    // ── 真实样本：EPUB 章节常见结构 ──────────

    @Test
    fun `realistic EPUB chapter with h1 plus multiple p and image`() {
        val html = """
            <section>
              <h1>第三章 邂逅</h1>
              <p>那天清晨，<em>她</em>走进了我的生活。</p>
              <p>窗外有阳光。</p>
              <p><img src="file:///cache/img-001.jpg" /></p>
              <p>故事就这样开始了。</p>
            </section>
        """.trimIndent()
        val blocks = parse(html)
        assertEquals(5, blocks.size)
        assertEquals(ChapterBlock.Heading(1, "第三章 邂逅"), blocks[0])
        assertEquals(ChapterBlock.Paragraph("那天清晨，她走进了我的生活。"), blocks[1])
        assertEquals(ChapterBlock.Paragraph("窗外有阳光。"), blocks[2])
        assertEquals(ChapterBlock.Image("file:///cache/img-001.jpg"), blocks[3])
        assertEquals(ChapterBlock.Paragraph("故事就这样开始了。"), blocks[4])
    }
}
