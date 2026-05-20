package com.morealm.app.domain.parser.epub.streaming

import com.morealm.app.domain.parser.epub.ChapterBlock
import com.morealm.app.domain.parser.epub.StructuredChapterContent
import com.morealm.epub.compat.BlockVisitor
import com.morealm.epub.ir.TagId

/**
 * Terminal NodeVisitor that materializes the streamed XHTML into
 * [StructuredChapterContent] blocks. Sits at the bottom of the
 * StreamingChapterReader visitor stack.
 *
 * Delegates paragraph / heading / image segmentation to the upstream
 * [BlockVisitor] base class; this subclass only routes each emit hook into
 * the matching [ChapterBlock] data class. Image blocks with empty src are
 * dropped (ImgRewriteVisitor already filters most, but the safety net here
 * also covers callers that bypass that decorator).
 *
 * Reusable across multiple `Chapter.streamTo(...)` calls when assembling a
 * single logical chapter that spans multiple spine items: feed all spine
 * items into the same builder, then call [build] once at the end.
 */
class ChapterBlockBuilder : BlockVisitor() {

    private val blocks = ArrayList<ChapterBlock>()

    override fun emitHeading(level: Int, text: String) {
        if (text.isNotEmpty()) blocks.add(ChapterBlock.Heading(level.coerceIn(1, 6), text))
    }

    override fun emitParagraph(text: String) {
        if (text.isNotEmpty()) blocks.add(ChapterBlock.Paragraph(text))
    }

    override fun emitImage(src: String) {
        if (src.isNotEmpty()) blocks.add(ChapterBlock.Image(src))
    }

    fun build(): StructuredChapterContent {
        // 兜底：document 可能因 FragmentSliceVisitor STOP 或异常截断而没发出最外层
        // body close —— 触发一次 BODY close 让 BlockVisitor 把 paraBuf 残留 flush 掉。
        // 当 paraBuf 已空时这次调用是 no-op，多 emit 一次 close 不会重复出 block。
        onClose(TagId.BODY)
        return StructuredChapterContent(blocks.toList())
    }
}
