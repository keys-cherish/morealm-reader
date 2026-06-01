package com.morealm.app.domain.render.layout

import android.text.TextPaint
import com.morealm.app.domain.render.PaintLayoutMeasurer
import com.morealm.epub.compat.BlockStyle
import com.morealm.epub.compat.StructuredChapterContent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * **2026-05-29 横线对齐（#2 + #3）** —— introduction `.jj { border-bottom }` 标题横线贴文字视觉底
 * + 紧随其后的负 margin 副标题（古风悬疑）clamp 到横线下方的排版几何单测。
 *
 * 用 SIZE marker（em 段）+ encodeBlockStyle（border-bottom / 负 margin）构造真实 flatten 输入，
 * 与 emit 端字节对齐（同 [ScrollLayoutEngineCoalesceTest] 手法）。
 *
 * **Robolectric fake paint 注意**：char width=0、line height=0（textHeight=0），所以**不**断言
 * 像素几何（横线绝对 Y / 文字底与行底差值），那些由真机灰盒覆盖。这里断言**逻辑层不变量**：
 * em 段算出 textBottomRel（非 em 段为 NaN）；clamp 在「上一行 bottom-only border」时触发、
 * 无 border 时**不**触发（窄门不误伤封面 poster 负 margin 叠层）。
 */
@RunWith(RobolectricTestRunner::class)
class ScrollLayoutEngineBorderBottomTest {

    private lateinit var contentPaint: TextPaint
    private lateinit var titlePaint: TextPaint

    @Before
    fun setup() {
        contentPaint = TextPaint().apply { textSize = 48f }
        titlePaint = TextPaint().apply { textSize = 72f }
    }

    private fun engine() = ScrollLayoutEngine(
        viewWidth = 1080,
        viewHeight = 2200,
        paddingLeft = 40,
        paddingRight = 40,
        paddingTop = 60,
        paddingBottom = 60,
        titleMeasurer = PaintLayoutMeasurer(titlePaint),
        contentMeasurer = PaintLayoutMeasurer(contentPaint),
        lineSpacingExtra = 1.2f,
        paragraphSpacing = 8,
    )

    /** SIZE marker：`SIZE_START<hundreds>SIZE_DELIM<text>SIZE_END`（hundreds=150 → sizeScale 1.5）。 */
    private fun sizeSpan(hundreds: Int, text: String): String =
        StructuredChapterContent.SIZE_START + "$hundreds" + StructuredChapterContent.SIZE_DELIM +
            text + StructuredChapterContent.SIZE_END

    /** BLOCK_STYLE marker 前缀包裹一段 body。 */
    private fun withBlockStyle(style: BlockStyle, body: String): String =
        StructuredChapterContent.BLOCK_STYLE_MARKER +
            StructuredChapterContent.encodeBlockStyle(style) +
            StructuredChapterContent.BLOCK_STYLE_END + body

    private fun ScrollChapterLayout.lines(): List<ScrollLine> = pages.flatMap { it.lines }

    @Test
    fun `em 段算出 textBottomRel 正文段为 NaN`() {
        val eng = engine()
        // 段1：em1.5 标题；段2：普通正文
        val content = sizeSpan(150, "内容简介") + "\n" + "普通正文"
        val lines = eng.layoutChapter(0, "T", content, omitChapterTitleBlock = true).lines()

        val emLine = lines.first { it.text == "内容简介" }
        assertFalse("em 段应算出 textBottomRel（非 NaN）", emLine.textBottomRel.isNaN())
        assertTrue("textBottomRel 为正", emLine.textBottomRel > 0f)
        // 文字视觉底 = scaledTextSize×0.8 + scaledDescent；descent ≥ 0 → 下界 = textSize×scale×0.8
        assertTrue(
            "textBottomRel 应 ≥ scaledTextSize×0.8（baseline 部分）",
            emLine.textBottomRel >= contentPaint.textSize * 1.5f * 0.8f - 0.01f,
        )

        val plainLine = lines.first { it.text == "普通正文" }
        assertTrue("非 em 正文段 textBottomRel 应为 NaN（消费方回退 lineBottom）", plainLine.textBottomRel.isNaN())
    }

    @Test
    fun `bottom-only border 段后负 margin 段被 clamp 到横线底边下方`() {
        val eng = engine()
        // paddingBottomPx 非 0 —— 复现第一版漏算 padding×scale 导致横线仍切副标题顶（古风）的 bug。
        val padBottom = 10f
        val borderW = 2f
        val titleStyle = BlockStyle(
            borderBottomColor = 0xFFC27C88.toInt(),
            borderBottomWidthPx = borderW,
            paddingBottomPx = padBottom,
        )
        val subStyle = BlockStyle(marginTopPx = -60f)
        val content = withBlockStyle(titleStyle, sizeSpan(150, "内容简介")) + "\n" +
            withBlockStyle(subStyle, "古风悬疑")
        val lines = eng.layoutChapter(0, "T", content, omitChapterTitleBlock = true).lines()

        val titleLine = lines.first { it.text == "内容简介" }
        val subLine = lines.first { it.text == "古风悬疑" }
        assertFalse("前置：标题 em 段应有 textBottomRel", titleLine.textBottomRel.isNaN())
        // 横线实际绘制底边（与 drawer 同口径）= lineTop + textBottomRel + (padB + bw)×scale，
        // scale = contentPaint.textSize/16f。副标题顶必须 ≥ 此值才不被横线切——第一版只加 bw、
        // 漏 padB×scale，副标题顶落在横线上方被切（古风重叠），此断言对旧 clamp 会失败。
        val scale = contentPaint.textSize / 16f
        val borderFloor = titleLine.lineTop + titleLine.textBottomRel + (padBottom + borderW) * scale
        assertTrue(
            "副标题应被 clamp 到横线底边下方：subTop=${subLine.lineTop} 应 ≥ $borderFloor",
            subLine.lineTop >= borderFloor,
        )
    }

    @Test
    fun `无 border 段后负 margin 段不 clamp（保留叠层不误伤封面 poster）`() {
        val eng = engine()
        // 与上一 case 唯一差异：标题段无 border-bottom。clamp 窄门要求「上一行 bottom-only border」，
        // 此处不满足 → 负 margin 原样生效（副标题上提），验证不误伤故意叠层场景。
        val titleStyle = BlockStyle()  // 无任何 border
        val subStyle = BlockStyle(marginTopPx = -60f)
        val content = withBlockStyle(titleStyle, sizeSpan(150, "内容简介")) + "\n" +
            withBlockStyle(subStyle, "古风悬疑")
        val lines = eng.layoutChapter(0, "T", content, omitChapterTitleBlock = true).lines()

        val titleLine = lines.first { it.text == "内容简介" }
        val subLine = lines.first { it.text == "古风悬疑" }
        assertTrue(
            "无 border 时负 margin 不被 clamp，副标题应上提到标题文字视觉底之上：" +
                "subTop=${subLine.lineTop} 应 < ${titleLine.lineTop + titleLine.textBottomRel}",
            subLine.lineTop < titleLine.lineTop + titleLine.textBottomRel,
        )
    }
}
