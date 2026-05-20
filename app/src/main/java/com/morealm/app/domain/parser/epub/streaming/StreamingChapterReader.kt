package com.morealm.app.domain.parser.epub.streaming

import com.morealm.app.domain.entity.BookChapter
import com.morealm.app.domain.parser.epub.ChapterBlock
import com.morealm.app.domain.parser.epub.StructuredChapterContent
import com.morealm.epub.Chapter
import com.morealm.epub.EpubBook
import com.morealm.epub.compat.RubyRewriteVisitor
import com.morealm.epub.compat.SvgImageRewriteVisitor
import com.morealm.epub.compat.TableMergeVisitor

/**
 * Streaming replacement for `EpubParser.readChapterStructured` —
 * walks one or more spine items via [com.morealm.epub.xhtml.XhtmlReader]
 * (push-based, allocation-light) instead of materializing a Jsoup DOM and
 * mutating it in place.
 *
 * Visitor pipeline, source → sink:
 *
 *   XhtmlReader
 *     → FragmentSliceVisitor     // drops events outside [startFragment, endFragment)
 *     → SvgImageRewriteVisitor   // <svg><image href/></svg> → <img src/>
 *     → RubyRewriteVisitor       // <ruby>base<rt>reading</rt></ruby> → "base(reading)"
 *     → TableMergeVisitor        // sibling tables joined into one paragraph (title pages)
 *     → ImgRewriteVisitor        // <img src> → <img src=file://cache/...> via [imgLookup]
 *     → ChapterBlockBuilder      // emits Heading / Paragraph / Image blocks
 *
 * The chain is rebuilt per spine item (visitor decorators are stateful) but
 * the [ChapterBlockBuilder] is shared, so cross-XHTML chapters concatenate
 * naturally.
 *
 * Cover-page short-circuit: if the chapter URL points at a cover / titlepage
 * XHTML and [coverLookup] returns a file URL, the reader emits a single
 * [ChapterBlock.Image] block and skips XHTML parsing entirely. This mirrors
 * the old `parseBody` + `COVER_IMAGE_SENTINEL` path without polluting the
 * visitor stack with cover marker bookkeeping.
 */
object StreamingChapterReader {

    /**
     * @param imgLookup Maps an `<img src>` (as authored in the XHTML, before
     *   URI resolution) to a viewer-renderable file URL. Return `null` to
     *   drop the image. Implementations typically resolve the src against
     *   the chapter href, look the resource up in the EPUB, copy to a cache
     *   dir, and return `file://...`.
     * @param coverLookup Called only when the requested chapter is detected
     *   as a cover/title page. Return the file URL of the book's cover
     *   bitmap, or `null` to fall through to ordinary XHTML parsing.
     */
    fun read(
        book: EpubBook,
        chapter: BookChapter,
        imgLookup: (src: String) -> String?,
        coverLookup: () -> String?,
    ): StructuredChapterContent {
        val targetHref = chapter.url.substringBeforeLast("#")
        if (targetHref.isEmpty()) return StructuredChapterContent(emptyList())

        if (isCoverPage(targetHref)) {
            val coverUrl = coverLookup()
            if (!coverUrl.isNullOrEmpty()) {
                return StructuredChapterContent(listOf(ChapterBlock.Image(coverUrl)))
            }
            // cover bytes 不可用：fall through，按 XHTML 流式解析（cover.xhtml 里可能本来就 link img）
        }

        val startFragment = chapter.url.substringAfter("#", "").takeIf { it.isNotEmpty() }
        val endFragment = chapter.nextUrl?.substringAfter("#", "")?.takeIf { it.isNotEmpty() }
        val nextHref = chapter.nextUrl?.substringBeforeLast("#")

        val spine = book.spine.items
        val startIdx = spine.indexOfFirst { it.href == targetHref }
        if (startIdx < 0) return StructuredChapterContent(emptyList())

        val builder = ChapterBlockBuilder()

        val firstItem = spine[startIdx]
        val firstEnd = endFragment?.takeIf { firstItem.href == nextHref }
        parseChapterTo(firstItem, builder, imgLookup, startFragment, firstEnd)

        // 跨 spine 拼接：当 chapter.nextUrl 指向不同 xhtml 时，把 [startIdx+1 .. nextHref)
        // 的 spine items 全部追加（startFragment 已用过，后续都从头）。
        if (nextHref == null || firstItem.href != nextHref) {
            for (i in (startIdx + 1) until spine.size) {
                val item = spine[i]
                if (nextHref != null && item.href == nextHref) {
                    if (endFragment != null) {
                        parseChapterTo(item, builder, imgLookup, null, endFragment)
                    }
                    break
                }
                parseChapterTo(item, builder, imgLookup, null, null)
            }
        }

        return builder.build()
    }

    private fun parseChapterTo(
        chapter: Chapter,
        builder: ChapterBlockBuilder,
        imgLookup: (String) -> String?,
        startFragment: String?,
        endFragment: String?,
    ) {
        val imgRewrite = ImgRewriteVisitor(builder, imgLookup)
        val tableMerge = TableMergeVisitor(imgRewrite)
        val rubyRewrite = RubyRewriteVisitor(tableMerge)
        val svgRewrite = SvgImageRewriteVisitor(rubyRewrite)
        val fragmentSlice = FragmentSliceVisitor(svgRewrite, startFragment, endFragment)
        chapter.streamTo(fragmentSlice)
    }

    /**
     * Cover-page heuristic — matches the old [com.morealm.app.domain.parser.EpubParser.isCoverPage]:
     * any spine item whose href contains `titlepage.xhtml` or `cover` (case-insensitive)
     * is treated as a cover-page candidate. Conservative on purpose; false
     * positives here are paid by one extra `coverLookup()` call that returns
     * null and falls back to normal XHTML parsing.
     */
    private fun isCoverPage(href: String): Boolean {
        val normalized = href.lowercase()
        return normalized.contains("titlepage.xhtml") || normalized.contains("cover")
    }
}
