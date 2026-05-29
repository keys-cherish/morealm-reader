package com.morealm.app.domain.render.layout

import com.morealm.epub.compat.ChapterBlockBuilder
import com.morealm.epub.compat.StructuredChapterContent
import com.morealm.epub.css.CssStylesheet
import com.morealm.epub.layout.parseTableMarker
import com.morealm.epub.xhtml.XhtmlReader
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **2026-05-29 回归** —— 聊天气泡式 EPUB（头像 `<td><img></td>` + 气泡
 * `<td><div class="kuang-hei">…</div></td>`）把 Container BOX 嵌进 table cell。
 *
 * 修前 bug：cell 解析（epub-layout MarkerParser.parseTableRow）只 stripBlockStyleMarker，不认
 * BOX marker → `__MOREALM_BOX_START__` / `__/MOREALM_BOX_HEADER__` / `__/MOREALM_BOX_END__`
 * 整段当 cell 文字，真机显示出 marker 原文。
 *
 * 本用例走真实 round-trip：epub-compat [ChapterBlockBuilder] 编码 flatten（含 cell 内 BOX marker）
 * → epub-layout [parseTableMarker] 解析（修复点），断言 cell.contentParagraphs **不残留** BOX
 * marker 且保留气泡正文。纯 JVM（无 Android API），不需 Robolectric。
 */
class TableCellBoxMarkerStripTest {

    private val css = """
        .kuang-hei { border: 2px solid #000; padding: 0.5em; }
    """.trimIndent()

    private fun flatten(bodyXhtml: String): String {
        val builder = ChapterBlockBuilder(
            stylesheets = listOf(CssStylesheet.parse(css)),
            rootFontSizePx = 16f,
            containingBlockWidthPx = 800f,
        )
        XhtmlReader.parse(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body>$bodyXhtml</body></html>",
            builder,
        )
        return builder.build().flattenToString()
    }

    @Test
    fun `cell 内气泡 BOX marker 不漏进 parsed cell 文字`() {
        // 气泡 div 含 2 个 <p>（multi-child）→ 触发 Container → flatten 出 BOX marker。
        val out = flatten(
            """<table><tr>
                 <td><img alt="avatar" src="p03.png" /></td>
                 <td><div class="kuang-hei"><p>气泡正文XYZ</p><p>第二句</p></div></td>
               </tr></table>""",
        )
        // 前置：cell 内气泡确实编码成 BOX marker（否则测不到剥离效果）。
        assertTrue(
            "前置：cell 内气泡应 emit BOX marker；got '$out'",
            StructuredChapterContent.BOX_START_MARKER in out,
        )
        val parsed = parseTableMarker(out)
        assertNotNull("table marker 应解析成功；flatten='$out'", parsed)
        val cellParas = parsed!!.rows.flatMap { it.cells }.flatMap { it.contentParagraphs }
        assertFalse(
            "cell 内容不应残留 BOX marker；got $cellParas",
            cellParas.any {
                StructuredChapterContent.BOX_START_MARKER in it ||
                    StructuredChapterContent.BOX_HEADER_END in it ||
                    StructuredChapterContent.BOX_END_MARKER in it
            },
        )
        assertTrue(
            "气泡正文应保留；got $cellParas",
            cellParas.any { "气泡正文XYZ" in it },
        )
    }
}
