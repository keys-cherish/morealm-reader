package com.morealm.app.domain.render

import com.morealm.epub.compat.StructuredChapterContent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-07-26 回归：好讀繁体 EPUB 正文渲染出 `__MOREALM_BLOCK_STYLE__…` 字面量。
 *
 * 根因链：整章一个 `<div>` + `<br/>` 分段 + `body{text-align:justify}` → 每段都带
 * BlockStyle marker；用户「断行合并」类替换规则（行尾汉字并入下一行）把行首 marker 挤到
 * 行中；ScrollLayoutEngine 字符串路径只在 `startsWith` 时剥 marker → marker 画进正文。
 *
 * 门控保证替换规则永不作用于带 marker 的 wire 串。
 */
class WireMarkerGuardTest {

    @Test
    fun `plain chapter text is not treated as wire content`() {
        assertFalse(WireMarkerGuard.containsWireMarkers(""))
        assertFalse(WireMarkerGuard.containsWireMarkers("　　第八章 齐国往事\n　　寻常巷陌。"))
        // 普通 HTML 正文（网络书路径）不含协议 marker
        assertFalse(WireMarkerGuard.containsWireMarkers("<p>正文一段</p><p>正文二段</p>"))
    }

    @Test
    fun `block style marker is detected anywhere in the content`() {
        val marker = StructuredChapterContent.BLOCK_STYLE_MARKER
        assertTrue(WireMarkerGuard.containsWireMarkers(marker + "ta=JUSTIFY" + "正文"))
        // 关键：被替换规则挤到行中的 marker 同样要认出来（这正是事故形态）
        assertTrue(WireMarkerGuard.containsWireMarkers("典藏版" + marker + "ta=JUSTIFY__卷一"))
    }

    @Test
    fun `table and box markers are detected`() {
        assertTrue(WireMarkerGuard.containsWireMarkers(StructuredChapterContent.TABLE_START + "x"))
        assertTrue(WireMarkerGuard.containsWireMarkers(StructuredChapterContent.BOX_START_MARKER + "x"))
    }

    @Test
    fun `control character markers are detected`() {
        assertTrue(
            WireMarkerGuard.containsWireMarkers(
                StructuredChapterContent.HEADING_LEVEL_START + "2" +
                    StructuredChapterContent.HEADING_LEVEL_END + "标题",
            ),
        )
        assertTrue(
            WireMarkerGuard.containsWireMarkers(
                "正文" + StructuredChapterContent.INLINE_IMG_START + "img.png",
            ),
        )
        assertTrue(WireMarkerGuard.containsWireMarkers(StructuredChapterContent.HR_MARKER))
    }
}
