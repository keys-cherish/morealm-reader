package com.morealm.app.domain.render

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [ScrollParagraph] + [TextChapter.toScrollParagraphs] +
 * [loadingScrollParagraph].
 *
 * 覆盖目标：
 *   1. 单页单段：基本 key/contentType/totalHeight/linePositions 正确性
 *   2. 单页多段：段顺序 + key 唯一
 *   3. 跨页段合并：丢弃 page-break 间隙、linePositions 视觉连续
 *   4. 章首段识别：保留 page0 的 paddingTop、命中 CHAPTER_TITLE
 *   5. 图片段：命中 IMAGE 类型
 *   6. 空章 / paragraphNum=0 行：边界鲁棒
 *   7. LOADING 工厂：占位段属性
 *
 * 关键设计：测试用 plain TextLine + TextPage + TextChapter 直接构造，不依赖
 * 排版器（[TextMeasure] 等），保证测试稳定且无 Android UI 副作用。
 */
@RunWith(RobolectricTestRunner::class)
class ScrollParagraphTest {

    /**
     * 构造一个 [TextLine]。直接 set 字段而不走 [TextLine.upTopBottom]，避免引入
     * Paint.FontMetrics 依赖；段构建逻辑只用到 lineTop/lineBottom/paragraphNum/
     * chapterPosition/columns/isParagraphEnd/isChapterNum/isTitle，全部可直接赋值。
     */
    private fun line(
        paragraphNum: Int,
        top: Float,
        bottom: Float,
        chapterPosition: Int = 0,
        text: String = "x",
        isParagraphEnd: Boolean = false,
        isChapterNum: Boolean = false,
        isTitle: Boolean = false,
        addImageColumn: Boolean = false,
    ): TextLine = TextLine(
        text = text,
        isTitle = isTitle,
        isParagraphEnd = isParagraphEnd,
        isChapterNum = isChapterNum,
    ).apply {
        this.paragraphNum = paragraphNum
        this.lineTop = top
        this.lineBottom = bottom
        this.lineBase = bottom - 2f
        this.chapterPosition = chapterPosition
        // 加 1 个 column 让 charSize > 0；charSize 算法（PageLayout.kt:149）从 columns 累计
        addColumn(TextColumn(charData = text, start = 0f, end = 10f))
        if (addImageColumn) {
            addColumn(ImageColumn(start = 10f, end = 60f, src = "test://image.png"))
        }
    }

    private fun page(paddingTop: Int, vararg lines: TextLine): TextPage =
        TextPage(paddingTop = paddingTop).apply {
            lines.forEach { addLine(it) }
            // height 用最后一行的 lineBottom 模拟 upRenderHeight()
            height = lines.lastOrNull()?.lineBottom ?: 0f
            isCompleted = true
        }

    private fun chapter(chapterIdx: Int, vararg pages: TextPage): TextChapter =
        TextChapter(chapterIndex = chapterIdx, title = "ch$chapterIdx", chaptersSize = 10).apply {
            pages.forEach { addPage(it) }
            isCompleted = true
        }

    // ── 1. 单页单段 ──

    @Test
    fun `single page single paragraph - key contentType height all correct`() {
        val ch = chapter(
            chapterIdx = 0,
            page(
                paddingTop = 100,
                line(paragraphNum = 1, top = 0f, bottom = 30f, chapterPosition = 0),
                line(paragraphNum = 1, top = 30f, bottom = 60f, chapterPosition = 5, isParagraphEnd = true),
            ),
        )
        val paragraphs = ch.toScrollParagraphs()
        assertEquals(1, paragraphs.size)
        val p = paragraphs[0]
        assertEquals("0-1-0", p.key)
        assertEquals(0, p.chapterIndex)
        assertEquals(1, p.paragraphNum)
        assertEquals(2, p.lines.size)
        assertEquals(100f, p.paddingTop, 0.001f)
        // totalHeight = paddingTop(100) + line1Height(30) + line2Height(30) = 160
        assertEquals(160f, p.totalHeight, 0.001f)
        // linePositions: 第一行 top = paddingTop(100), 第二行 top = 100 + 30 = 130
        assertArrayEquals(floatArrayOf(100f, 130f), p.linePositions, 0.001f)
        assertEquals(0, p.firstChapterPosition)
        // charSize = 1 (line1) + 1 (line2) + 1 (isParagraphEnd) = 3
        assertEquals(3, p.charSize)
        // 章首段非 title/chapterNum → NORMAL 还是 CHAPTER_TITLE？
        // 当前实现：章首段 + 含 isChapterNum 或 isTitle 才算 CHAPTER_TITLE，
        // 这里两条 line 都是普通文字 → NORMAL
        assertEquals(ScrollParagraphType.NORMAL, p.contentType)
    }

    // ── 2. 单页多段 ──

    @Test
    fun `single page multiple paragraphs - keys unique, order preserved`() {
        val ch = chapter(
            chapterIdx = 0,
            page(
                paddingTop = 0,
                line(paragraphNum = 1, top = 0f, bottom = 30f, isParagraphEnd = true),
                line(paragraphNum = 2, top = 30f, bottom = 60f, isParagraphEnd = true),
                line(paragraphNum = 3, top = 60f, bottom = 90f, isParagraphEnd = true),
            ),
        )
        val paragraphs = ch.toScrollParagraphs()
        assertEquals(3, paragraphs.size)
        assertEquals("0-1-0", paragraphs[0].key)
        assertEquals("0-2-0", paragraphs[1].key)
        assertEquals("0-3-0", paragraphs[2].key)
        // 第一段保留 paddingTop（章首），后续段 paddingTop = 0
        assertEquals(0f, paragraphs[0].paddingTop, 0.001f)
        assertEquals(0f, paragraphs[1].paddingTop, 0.001f)
        assertEquals(0f, paragraphs[2].paddingTop, 0.001f)
    }

    // ── 3. 跨页段合并 ──

    @Test
    fun `cross page paragraph - page break gap discarded for visual continuity`() {
        // page 0：两行属同一段，line.lineBottom = 60
        // page 1：第三行延续同一段，page1 paddingTop=200 是 page-local（应被丢弃，
        //         否则段内出现"突然 200px 空白"，违背瀑布流无缝衔接）
        val ch = chapter(
            chapterIdx = 0,
            page(
                paddingTop = 0,
                line(paragraphNum = 1, top = 0f, bottom = 30f),
                line(paragraphNum = 1, top = 30f, bottom = 60f),
            ),
            page(
                paddingTop = 200,
                line(paragraphNum = 1, top = 200f, bottom = 230f, isParagraphEnd = true),
            ),
        )
        val paragraphs = ch.toScrollParagraphs()
        assertEquals(1, paragraphs.size)
        val p = paragraphs[0]
        assertEquals(3, p.lines.size)
        // totalHeight = 0(paddingTop) + 30 + 30 + 30 = 90
        // 关键：跨页时丢弃 page-break gap（200px），第三行直接接在第二行底部 60→90
        assertEquals(90f, p.totalHeight, 0.001f)
        // linePositions：连续 0/30/60，没有 200 跳变
        assertArrayEquals(floatArrayOf(0f, 30f, 60f), p.linePositions, 0.001f)
    }

    @Test
    fun `cross page paragraph - same page line spacing preserved`() {
        // 同页内 line1(0-30) 和 line2(50-80) 之间有 20px 行间空白，构建后应保留。
        val ch = chapter(
            chapterIdx = 0,
            page(
                paddingTop = 0,
                line(paragraphNum = 1, top = 0f, bottom = 30f),
                line(paragraphNum = 1, top = 50f, bottom = 80f, isParagraphEnd = true),
            ),
        )
        val p = ch.toScrollParagraphs()[0]
        // totalHeight = paddingTop(0) + line1Height(30) + lineGap(20) + line2Height(30) = 80
        assertEquals(80f, p.totalHeight, 0.001f)
        // linePositions：line1 在 0，line2 在 30+20=50（保留同页行间距）
        assertArrayEquals(floatArrayOf(0f, 50f), p.linePositions, 0.001f)
    }

    // ── 4. 章首段识别 ──

    @Test
    fun `chapter title paragraph - contentType is CHAPTER_TITLE`() {
        val ch = chapter(
            chapterIdx = 0,
            page(
                paddingTop = 50,
                line(
                    paragraphNum = 1,
                    top = 0f,
                    bottom = 80f,
                    isChapterNum = true,
                    isParagraphEnd = true,
                    text = "第一章",
                ),
            ),
        )
        val p = ch.toScrollParagraphs()[0]
        assertEquals(ScrollParagraphType.CHAPTER_TITLE, p.contentType)
        assertEquals(50f, p.paddingTop, 0.001f)
        assertTrue("应识别为章首段", p.isChapterFirst)
    }

    @Test
    fun `non-chapter-start title-flagged paragraph stays NORMAL`() {
        // 章首已被段 1 占用，段 2 即使含 isTitle 也不该升级为 CHAPTER_TITLE
        // （章中标题应作为 NORMAL 走，避免 LazyColumn contentType 复用 bucket 污染）
        val ch = chapter(
            chapterIdx = 0,
            page(
                paddingTop = 0,
                line(paragraphNum = 1, top = 0f, bottom = 30f, isParagraphEnd = true),
                line(paragraphNum = 2, top = 30f, bottom = 60f, isTitle = true, isParagraphEnd = true),
            ),
        )
        val paragraphs = ch.toScrollParagraphs()
        assertEquals(ScrollParagraphType.NORMAL, paragraphs[0].contentType)
        assertEquals(ScrollParagraphType.NORMAL, paragraphs[1].contentType)
    }

    // ── 5. 图片段 ──

    @Test
    fun `paragraph with image column - contentType is IMAGE`() {
        val ch = chapter(
            chapterIdx = 0,
            page(
                paddingTop = 0,
                line(
                    paragraphNum = 1,
                    top = 0f,
                    bottom = 200f,
                    addImageColumn = true,
                    isParagraphEnd = true,
                ),
            ),
        )
        val p = ch.toScrollParagraphs()[0]
        assertEquals(ScrollParagraphType.IMAGE, p.contentType)
    }

    // ── 6. 边界鲁棒 ──

    @Test
    fun `empty chapter returns empty list`() {
        val ch = chapter(chapterIdx = 0)
        assertTrue(ch.toScrollParagraphs().isEmpty())
    }

    @Test
    fun `lines with paragraphNum equal to zero are skipped`() {
        // paragraphNum <= 0 是占位 / 错误状态，应被过滤，不形成段
        val ch = chapter(
            chapterIdx = 0,
            page(
                paddingTop = 0,
                line(paragraphNum = 0, top = 0f, bottom = 30f),
                line(paragraphNum = 1, top = 30f, bottom = 60f, isParagraphEnd = true),
            ),
        )
        val paragraphs = ch.toScrollParagraphs()
        assertEquals(1, paragraphs.size)
        assertEquals("0-1-0", paragraphs[0].key)
        assertEquals(1, paragraphs[0].lines.size)
    }

    @Test
    fun `cross chapter keys are unique`() {
        // 不同 chapter 的同 paragraphNum 段应有不同 key（跨章窗口拼接安全性）
        val ch0 = chapter(
            chapterIdx = 0,
            page(0, line(paragraphNum = 1, top = 0f, bottom = 30f, isParagraphEnd = true)),
        )
        val ch1 = chapter(
            chapterIdx = 1,
            page(0, line(paragraphNum = 1, top = 0f, bottom = 30f, isParagraphEnd = true)),
        )
        val key0 = ch0.toScrollParagraphs()[0].key
        val key1 = ch1.toScrollParagraphs()[0].key
        assertNotEquals(key0, key1)
        assertEquals("0-1-0", key0)
        assertEquals("1-1-0", key1)
    }

    // ── 7. LOADING 占位段 ──

    @Test
    fun `loadingScrollParagraph factory creates valid placeholder`() {
        val p = loadingScrollParagraph(chapterIndex = 5)
        assertEquals(ScrollParagraphType.LOADING, p.contentType)
        assertEquals(5, p.chapterIndex)
        assertEquals("loading-5-0", p.key)
        assertEquals(0, p.paragraphNum)
        assertTrue("LOADING 段无 line", p.lines.isEmpty())
        assertEquals(0, p.linePositions.size)
        // 默认 placeholderHeight = 600f
        assertEquals(600f, p.totalHeight, 0.001f)
        assertTrue("LOADING 段应能被识别", p.isLoadingPlaceholder)
    }

    @Test
    fun `loadingScrollParagraph custom placeholder height respected`() {
        val p = loadingScrollParagraph(chapterIndex = 2, placeholderHeight = 1200f)
        assertEquals(1200f, p.totalHeight, 0.001f)
    }

    // ── 8. 段间距兜底（解决瀑布流「段内 ≈ 段间」视觉问题）──

    /**
     * 模拟 MoRealm 默认配置 (lineSpacingExtra=2.0, paragraphSpacing=8) 的几何：
     * - textHeight = 30f
     * - 段内行间距 = (lineSpacingExtra - 1) × textHeight = 30f
     * - 原段间 = (1.0 + 0.8) × textHeight = 54f （来自 ChapterProvider 几何）
     * - 段内首行 lineTop=0, lineBottom=30；段内次行 lineTop=60(=0+30+30), lineBottom=90
     * - 段尾后下段首行 lineTop=144(=60+30+54), lineBottom=174
     *
     * 兜底前 tailSpacing=54（段间 1.8× 段内）；
     * 兜底后 tailSpacing=max(54, 30 × 2.0)=60（段间 2.0× 段内）→ totalHeight 增加 6px。
     */
    @Test
    fun `paragraph gap is boosted to at least 2x intra-line gap when too tight`() {
        val ch = chapter(
            chapterIdx = 0,
            page(
                paddingTop = 0,
                line(paragraphNum = 1, top = 0f, bottom = 30f),
                line(paragraphNum = 1, top = 60f, bottom = 90f, isParagraphEnd = true),
                line(paragraphNum = 2, top = 144f, bottom = 174f, isParagraphEnd = true),
            ),
        )
        val paragraphs = ch.toScrollParagraphs()
        assertEquals(2, paragraphs.size)
        val p1 = paragraphs[0]
        // 段 1 的 baseHeight = paddingTop(0) + 30 + 30 + 30 = 90
        // 兜底后 tailSpacing = max(54, 30×2.0) = 60，
        // totalHeight = 90 + 60 = 150
        assertEquals(60f, p1.paragraphSpacingAfter, 0.001f)
        assertEquals(150f, p1.totalHeight, 0.001f)
    }

    /**
     * Legado 默认配置 (lineSpacingExtra=1.2, paragraphSpacing=8) 的几何：
     * - textHeight = 30
     * - 段内行间距 = 0.2 × 30 = 6f
     * - 原段间 = 1.0 × 30 = 30f （5× 段内）
     *
     * 5× 已远超兜底 2×，不应触发补偿——保留原值 30f。
     */
    @Test
    fun `paragraph gap unchanged when already exceeds 2x intra-line gap`() {
        val ch = chapter(
            chapterIdx = 0,
            page(
                paddingTop = 0,
                line(paragraphNum = 1, top = 0f, bottom = 30f),
                line(paragraphNum = 1, top = 36f, bottom = 66f, isParagraphEnd = true),
                line(paragraphNum = 2, top = 96f, bottom = 126f, isParagraphEnd = true),
            ),
        )
        val paragraphs = ch.toScrollParagraphs()
        // 段间几何 = next.lineTop(96) - curr.lineBottom(66) = 30；段内 = 6
        // 30 > 6×2=12，不兜底；tailSpacing 保留 30
        assertEquals(30f, paragraphs[0].paragraphSpacingAfter, 0.001f)
    }

    /**
     * 单行段无法本段算 intraLineGap，应回落到「整章首个能算出的 intraLineGap」。
     *
     * 段 1 单行（无 intraGap），段 2 双行（intraGap=30），段 3 单行：
     * 章级 fallback intraGap = 30，段 1 段间应被兜底到 ≥ 60。
     */
    @Test
    fun `single-line paragraph falls back to chapter intra-line gap for boost`() {
        val ch = chapter(
            chapterIdx = 0,
            page(
                paddingTop = 0,
                // 段 1：单行
                line(paragraphNum = 1, top = 0f, bottom = 30f, isParagraphEnd = true),
                // 段 2：双行（intraGap=30），紧贴段 1
                line(paragraphNum = 2, top = 30f, bottom = 60f),
                line(paragraphNum = 2, top = 90f, bottom = 120f, isParagraphEnd = true),
                // 段 3：单行
                line(paragraphNum = 3, top = 120f, bottom = 150f, isParagraphEnd = true),
            ),
        )
        val paragraphs = ch.toScrollParagraphs()
        assertEquals(3, paragraphs.size)
        // 段 1 段间几何 = 30 - 30 = 0；章 fallback intraGap = 30；
        // 兜底 → 60
        assertEquals(60f, paragraphs[0].paragraphSpacingAfter, 0.001f)
        // 段 2 段间几何 = 120 - 120 = 0；本段 intraGap = 30；
        // 兜底 → 60
        assertEquals(60f, paragraphs[1].paragraphSpacingAfter, 0.001f)
        // 段 3 是章末段（next == null），保留 0f 不兜底
        assertEquals(0f, paragraphs[2].paragraphSpacingAfter, 0.001f)
    }

    @Test
    fun `chapter last paragraph never gets gap boost`() {
        // 章末段 next==null tailSpacing=0f，避免和下一章 paddingTop 叠加产生异常空白
        val ch = chapter(
            chapterIdx = 0,
            page(
                paddingTop = 0,
                line(paragraphNum = 1, top = 0f, bottom = 30f),
                line(paragraphNum = 1, top = 60f, bottom = 90f, isParagraphEnd = true),
            ),
        )
        val p = ch.toScrollParagraphs()[0]
        assertEquals(0f, p.paragraphSpacingAfter, 0.001f)
        // totalHeight 不含 tailSpacing
        assertEquals(90f, p.totalHeight, 0.001f)
    }

    // ── 8. immutable 契约 ──

    @Test
    fun `lines list reference is shared - not deep copied`() {
        // 性能契约：lines 共享 TextPage.lines 中的 TextLine 实例，避免内存翻倍
        val originalLine = line(paragraphNum = 1, top = 0f, bottom = 30f, isParagraphEnd = true)
        val ch = chapter(
            chapterIdx = 0,
            page(0, originalLine),
        )
        val p = ch.toScrollParagraphs()[0]
        // 共享引用：物理同一对象
        assertTrue("lines 应共享 TextLine 引用，避免内存翻倍", p.lines[0] === originalLine)
    }
}
