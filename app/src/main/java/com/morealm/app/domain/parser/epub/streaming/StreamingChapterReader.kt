package com.morealm.app.domain.parser.epub.streaming

import com.morealm.app.core.log.AppLog
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

    private const val TAG = "EpubStream"

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
        val rawTargetHref = chapter.url.substringBeforeLast("#")
        AppLog.info(TAG, "read enter url=${chapter.url} target=$rawTargetHref next=${chapter.nextUrl ?: "null"}")
        if (rawTargetHref.isEmpty()) {
            AppLog.warn(TAG, "read empty targetHref → empty blocks")
            return StructuredChapterContent(emptyList())
        }

        // chapter.url 是 ZIP 绝对路径（D.4 buildChapterListViaCore 输出的 toZipAbsHref
        // 与 me.ag2s Resource.href 对齐），但 epub-core spine.items[].href 是 OPF 相对路径。
        // 在 spine 查找前先 strip OPF dir 前缀，否则 indexOfFirst 永不匹配。
        val opfDir = book.opfPath.substringBeforeLast('/', "")
        val targetHref = stripOpfDir(opfDir, rawTargetHref)

        val startFragment = chapter.url.substringAfter("#", "").takeIf { it.isNotEmpty() }
        val endFragment = chapter.nextUrl?.substringAfter("#", "")?.takeIf { it.isNotEmpty() }
        val nextHref = chapter.nextUrl
            ?.substringBeforeLast("#")
            ?.let { stripOpfDir(opfDir, it) }

        val spine = book.spine.items
        val startIdx = spine.indexOfFirst { it.href == targetHref }
        AppLog.info(TAG, "read spine.size=${spine.size} startIdx=$startIdx opfDir='$opfDir' opfRelTarget='$targetHref' start=$startFragment end=$endFragment")
        if (startIdx < 0) {
            val preview = spine.take(8).map { it.href }
            AppLog.warn(TAG, "read spine miss target=$targetHref; first hrefs=$preview")
            // spine miss 时退到 cover lookup 兜底（少数 EPUB 把 cover xhtml 不放 spine
            // 而是单独 manifest item；此时退到 metadata.cover 至少能显示一张封面图）
            if (isCoverPage(targetHref)) {
                val coverUrl = coverLookup()
                if (!coverUrl.isNullOrEmpty()) {
                    AppLog.info(TAG, "read spine miss but cover-page fallback coverUrl=$coverUrl")
                    return StructuredChapterContent(listOf(ChapterBlock.Image(coverUrl)))
                }
            }
            return StructuredChapterContent(emptyList())
        }

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

        val result = builder.build()
        AppLog.info(TAG, "read done target=$targetHref blocks=${result.blocks.size}")
        return result
    }

    private fun parseChapterTo(
        chapter: Chapter,
        builder: ChapterBlockBuilder,
        imgLookup: (String) -> String?,
        startFragment: String?,
        endFragment: String?,
    ) {
        val bytesPreview = runCatching { chapter.bytes().size }.getOrNull()
        AppLog.info(TAG, "parseChapterTo href=${chapter.href} start=$startFragment end=$endFragment bytes=$bytesPreview")
        // XHTML 里 <img src> 通常是 chapter-relative（"cover.jpg" / "../Images/x.jpg"）。
        // ZIP 资源用 OPF-relative href 寻址，故先按 chapter.href 把 src resolve 成
        // OPF-relative 再交给 caller 的 imgLookup —— 与老 EpubParser.parseBody 内的
        // `URI(chapterHref).resolve(src)` 行为一致，回归 D.5b 切换前的相对路径解析。
        val chapterRelativeLookup: (String) -> String? = { rawSrc ->
            val resolved = resolveRelative(chapter.href, rawSrc)
            val out = imgLookup(resolved)
            AppLog.debug(TAG, "img raw=$rawSrc base=${chapter.href} resolved=$resolved → ${out ?: "null"}")
            out
        }
        val imgRewrite = ImgRewriteVisitor(builder, chapterRelativeLookup)
        // forwardImages=true：日文轻小说封面 / 标题页常用 table 排版（chibi 角色头像 +
        // 单字大字标题混在 table cell 里）。默认 false 会把 table 内的 img 全吞掉，
        // 导致用户看到的标题页只有文字没有图。开启后 img block 独立 emit，段落仍合并。
        val tableMerge = TableMergeVisitor(imgRewrite, forwardImages = true)
        val rubyRewrite = RubyRewriteVisitor(tableMerge)
        val svgRewrite = SvgImageRewriteVisitor(rubyRewrite)
        val fragmentSlice = FragmentSliceVisitor(svgRewrite, startFragment, endFragment)
        chapter.streamTo(fragmentSlice)
    }

    /**
     * Resolve [src] relative to [baseHref]; both are OPF-relative paths.
     * Mirrors the old `URI(chapterHref).resolve(src)` path used by
     * EpubParser.parseBody so the visitor lookup gets an OPF-relative href
     * regardless of how the original XHTML wrote the src.
     *
     * Examples (base = "Text/ch1.xhtml"):
     *   "cover.jpg"        → "Text/cover.jpg"
     *   "../Images/x.jpg"  → "Images/x.jpg"
     *   "/Images/x.jpg"    → "Images/x.jpg"   (absolute inside the EPUB)
     *   "http://..."       → unchanged       (external URL)
     *
     * Falls back to the raw src on any parse failure — the caller's lookup
     * then has a chance to handle it itself.
     */
    internal fun resolveRelative(baseHref: String, src: String): String {
        if (src.isEmpty()) return src
        return try {
            java.net.URLDecoder.decode(
                java.net.URI(baseHref).resolve(src).toString(),
                "UTF-8",
            )
        } catch (_: Exception) {
            src
        }
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

    /**
     * Strip OPF dir prefix from a ZIP-absolute href to match
     * [com.morealm.epub.Chapter.href] which uses OPF-relative paths.
     *
     * "OEBPS/Text/cover.xhtml" + opfDir "OEBPS" → "Text/cover.xhtml"
     * "Text/cover.xhtml" + opfDir "OEBPS" → "Text/cover.xhtml"  (idempotent)
     * "cover.xhtml" + opfDir "" → "cover.xhtml"                  (OPF in root)
     */
    private fun stripOpfDir(opfDir: String, href: String): String {
        if (opfDir.isEmpty()) return href
        val prefix = "$opfDir/"
        return if (href.startsWith(prefix)) href.removePrefix(prefix) else href
    }
}
