package com.morealm.app.domain.parser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.morealm.app.domain.entity.BookChapter
import com.morealm.app.core.log.AppLog
import com.morealm.epub.compat.ChapterBlock
import com.morealm.epub.compat.ChapterReader
import com.morealm.epub.compat.StructuredChapterContent
import org.jsoup.Jsoup
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.Charset

data class EpubMetadata(
    val title: String = "",
    val author: String = "",
    val description: String = "",
    val language: String = "",
    val publisher: String = "",
    val subject: String = "",
    val opfPath: String = "",
)

data class EpubImportResult(
    val metadata: EpubMetadata = EpubMetadata(),
    val coverPath: String? = null,
)

/**
 * EPUB parser backed by epublib (random-access ZIP via ParcelFileDescriptor).
 *
 * Key advantage over ZipInputStream: direct entry access without scanning.
 * A 5MB EPUB import takes ~100ms instead of ~10s.
 */
object EpubParser {

    private const val COVER_IMAGE_SENTINEL = "cover.jpeg"
    private const val COVER_IMAGE_MARKER = "data-morealm-cover"
    // v4 bump：v3 cache key 用 targetHref（去掉 #fragment）→ 同 xhtml 多 navPoint
    // 共用 cache 文件 → 第二个 navPoint 起永远返回首次内容（用户报"无论跳哪章都显示首章"
    // 的根因，2026-05-18）。v4 起 cache key 用 chapter.url 完整 url 含 fragment，
    // 旧 v3 cache 全部失效，第一次打开重新解析。
    // v9 = LocalBookParser.isEmptyChapter 阈值放宽（< 8 → < 1）。之前某 EPUB toc
    // 嵌套人物名 "样本人物"3 char 被误判 empty 兜底；现允许任意 trim 后非空内容
    // 通过。v8 cache 内某些 chapter 已被错存为占位字符串，bump 失效。
    // v15：P3-5b Step 2c text-align + text-indent 解析 + ta=/ti= 编码。还修
    // ChapterBlockBuilder effectiveBlockStyle 改为逐层 merge，让 kuang1+p.center 的
    // bg/border + textAlign 共存（之前 lastOrNull 让 p.center 整套覆盖 div.kuang1 装饰）。
    // v16：P3-5b Step 2c char-level color：flattenToString 多色 RichText 用 SOH/STX/ETX
    // 控制字符内嵌 per-span color marker。bump 强制重 flatten 让「为美好的」多色 cover 字
    // 在每个字符位置生效。
    // v17：P0 fix — TableMergeVisitor 之前吞掉 table 内 `<span>` 的 onOpen/onClose 让
    // cascade 跑不到 table 内字符上 → 所有 span isPlain → emit Paragraph 而非 RichText →
    // cache 里 0 marker（用户 21:10 实测 sample='为美好的\n<img...' 验证）。修了 visitor 后
    // 还得 bump 让 v16 失效重新生成，否则 cache hit 永远拿到老 string。
    // v18：A4a inline image 模型 — RichSpan sealed 替代 StyledSpan，inline `<img>` 改 emit
    // InlineImageSpan 不再切独立 block。flattenToString 输出新 EOT/ENQ/ACK marker + U+FFFC
    // 占位（cache 里会出现 `<src>￼`）。bump 让 v17 cache 失效重写。
    // 注意：A4a 单独 deploy 时 ScrollLayoutEngine 还没解析新 marker → inline image 临时
    // 显示成 U+FFFC ▯ 占位字符；A4b 实现 marker 解析 + atom 排版后恢复。
    // v19：A4b regression fix — isInlineImageContext() 之前把 TagId.P 当成 inline phrasing
    // tag 误判 → EPUB 的 `<p><img/></p>` 标准 block-image 习惯（cover / 人物简介头像 / 章
    // 首插画）全被缩成 1.5 字宽（用户 2026-05-22 反馈：cover 变小、chibi 头像消失）。
    // 移除 P 触发条件后 `<p><img/></p>` 单图段恢复 block；段内文字+图混排（paraBuf 非空）
    // 仍走 inline 分支。bump 让 v18 误判 cache 失效重写。
    // v20：A4c sizeScale 通路 — flattenToString 编码 VT/FF/SO sizeScale marker（嵌套
    // 在 SOH/STX/ETX color 内）+ ScrollLayoutEngine.parseInlineMarkers 解析 → emit atoms
    // 路径携带 sizeScale → drawByAtoms 缩放 paint.textSize。让 SampleLN `em25/em30/em35`
    // 标题大字号生效。bump 让 v19 cache 失效重 flatten 出新 marker。
    // v21：C1/C2 chapter bg image 通路 — epub-compat 解析 `<body class="qmpN">` cascade
    // background-image → flattenToString 加 __MOREALM_CH_BG__<src>__/MOREALM_CH_BG__
    // header marker → ScrollLayoutEngine.layoutChapter strip + 提取 src 写到 ScrollChapterLayout
    // .chapterBgImageSrc 字段。让某 EPUB / 仙侠类章节级背景图能透传到 reader。bump 让 v20
    // cache 失效重 flatten 出 chapter bg marker。
    private const val CHAPTER_CACHE_DIR = "epub_chapters_v21"
    private val charset: Charset = Charsets.UTF_8

    private val nbspRegex = Regex("(&nbsp;)+", RegexOption.IGNORE_CASE)
    private val espRegex = Regex("(&ensp;|&emsp;)", RegexOption.IGNORE_CASE)
    private val noPrintRegex = Regex("(&thinsp;|&zwnj;|&zwj;|\u2009|\u200C|\u200D)", RegexOption.IGNORE_CASE)
    private val blockOpenHtmlRegex = Regex("""<(?:body|section|article|div|p|h\d|li|dd|dl)[^>]*>""", RegexOption.IGNORE_CASE)
    private val blockCloseHtmlRegex = Regex("""</(?:body|section|article|div|p|h\d|li|dd|dl)>|<br\s*/?>|<hr\s*/?>""", RegexOption.IGNORE_CASE)
    private val commentRegex = Regex("""<!--[\s\S]*?-->""")
    private val notImgHtmlRegex = Regex("""</?(?!img)[a-zA-Z]+(?=[ >])[^<>]*>""", RegexOption.IGNORE_CASE)
    private val formatImageRegex = Regex(
        """<img[^>]*\s(?:data-src|src)\s*=\s*['"]([^'">]+)['"][^>]*>|<img[^>]*\sdata-[^=>]*=\s*['"]([^'">]*)['"][^>]*>""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 导入阶段一次性提取所有元数据 + cover + isComic。
     *
     * 与依次调用 [extractMetadataAndCover] + [detectIsComic] 等价但只占 1 次
     * [withEpubBook]（即 1 次 openFreshBook + 1 次 ZIP 打开 + 1 次 OPF 解析）。
     *
     * 大量 EPUB 并发导入时差别巨大：原方案 2 × N 次 PFD 打开 + per-uri LRU cache (限 3 本)
     * 在 4 并发场景下反复 evict → cache miss → 重开 PFD，反复 churn。合并后 1 × N 次开 PFD，
     * 完成后 cache 命中率高得多。
     */
    fun extractAllForImport(context: Context, uri: Uri): ImportBundle {
        return EpubCoreBridge.withCoreBook(context, uri) { book ->
            val m = book.metadata
            val metadata = EpubMetadata(
                title = m.title,
                author = m.creators.firstOrNull().orEmpty().replace("^, |, $".toRegex(), ""),
                description = m.description.let { d ->
                    if (d.contains('<')) Jsoup.parse(d).text() else d
                },
                subject = m.subjects.filter { it.isNotBlank() }.joinToString(","),
                language = m.language,
                publisher = m.publisher,
                opfPath = book.opfPath,
            )
            val coverPath = extractCoverViaCore(context, uri, book)
            val isComic = isComicViaCore(book)
            ImportBundle(metadata, coverPath, isComic)
        } ?: ImportBundle()
    }

    /**
     * 用 epub-core 拿封面字节并写到 cacheDir/epub_covers/{uri.hashCode()}/cover.jpg。
     *
     * 优先级：
     * 1. [com.morealm.epub.Metadata.coverHref] —— epub-core 已合并 EPUB2 `<meta name="cover">`
     *    与 EPUB3 `properties="cover-image"` 两种来源
     * 2. spine 前 [SPINE_COVER_SCAN_LIMIT] 项文件名含 cover/title 的 xhtml，取其首张 img
     * 3. manifest 任一 image 资源兜底
     */
    private fun extractCoverViaCore(context: Context, uri: Uri, book: com.morealm.epub.EpubBook): String? {
        val coverHref = book.metadata.coverHref ?: findFallbackCoverHrefViaCore(book) ?: return null
        val cacheDir = File(context.cacheDir, "epub_covers/${uri.hashCode()}")
        val file = File(cacheDir, "cover.jpg")
        if (file.exists()) return file.absolutePath
        return try {
            cacheDir.mkdirs()
            val bytes = book.resource(coverHref) ?: return null
            decodeAndWriteScaledCover(bytes, file)
        } catch (oom: OutOfMemoryError) {
            AppLog.warn("EpubParser", "Cover OOM via core: ${oom.message}")
            System.gc()
            null
        } catch (e: Exception) {
            AppLog.warn("EpubParser", "Cover via core failed: ${e.message}")
            null
        }
    }

    private fun findFallbackCoverHrefViaCore(book: com.morealm.epub.EpubBook): String? {
        // 1. manifest properties="cover-image"（EPUB 3）—— epub-core 已合并到 metadata.coverHref，
        //    走到这里 = metadata.coverHref 为空但 manifest 仍可能声明。再扫一遍以防万一。
        val coverItem = book.opfPackage.manifest.firstOrNull { it.hasProperty("cover-image") }
        if (coverItem != null) {
            AppLog.info("EpubParser", "Cover via manifest cover-image properties: ${coverItem.href}")
            return coverItem.href
        }
        // 2. spine 前 N 项 + 文件名启发式
        val spineLimit = book.spine.size.coerceAtMost(SPINE_COVER_SCAN_LIMIT)
        for (i in 0 until spineLimit) {
            val ch = book.spine[i]
            val lowerHref = ch.href.lowercase()
            val isLikelyCover = "cover" in lowerHref || "title" in lowerHref
            if (!isLikelyCover) continue
            val img = firstImageHrefInXhtmlBytes(ch.bytes()) ?: continue
            val resolved = resolveImageInManifest(book, ch.href, img) ?: continue
            AppLog.info("EpubParser", "Cover via spine page name match: ${ch.href} → $resolved")
            return resolved
        }
        // 3. manifest 任一 image 兜底
        val anyImage = book.opfPackage.manifest.firstOrNull { it.mediaType.startsWith("image/") }
        if (anyImage != null) {
            AppLog.info("EpubParser", "Cover via manifest any-image fallback: ${anyImage.href}")
            return anyImage.href
        }
        return null
    }

    private fun firstImageHrefInXhtmlBytes(bytes: ByteArray): String? {
        return try {
            val text = bytes.decodeToString()
            val img = Jsoup.parse(text).select("img").firstOrNull() ?: return null
            img.attr("src").ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveImageInManifest(
        book: com.morealm.epub.EpubBook,
        chapterHref: String,
        imgHref: String,
    ): String? {
        // 1. 直接命中 manifest
        if (book.opfPackage.manifest.any { it.href == imgHref }) return imgHref
        // 2. URL decode 再试（中日韩文件名）
        val decoded = runCatching { URLDecoder.decode(imgHref, "UTF-8") }.getOrNull().orEmpty()
        if (decoded.isNotBlank() && book.opfPackage.manifest.any { it.href == decoded }) return decoded
        // 3. 相对路径解析（章节 xhtml 父目录 + img 相对路径）
        val baseDir = chapterHref.substringBeforeLast('/', "")
        val resolved = if (baseDir.isEmpty()) imgHref else "$baseDir/$imgHref"
        if (book.opfPackage.manifest.any { it.href == resolved }) return resolved
        return null
    }

    /**
     * 用 epub-core 判定漫画。算法与 [isComicByResources] 完全一致，只是数据源换成
     * [com.morealm.epub.opf.ManifestItem] + [com.morealm.epub.EpubBook.resourceSize]。
     */
    private fun isComicViaCore(book: com.morealm.epub.EpubBook): Boolean {
        // Level 1: rendition:layout = pre-paginated
        if (book.rendition.layout == com.morealm.epub.opf.RenditionLayout.PrePaginated) {
            AppLog.info("EpubParser", "detectIsComic → Comic (rendition.layout=pre-paginated)")
            return true
        }

        // Level 2: 结构指纹
        var nImg = 0
        var nHtml = 0
        var htmlTotalBytes = 0L
        for (item in book.opfPackage.manifest) {
            val mt = item.mediaType
            when {
                mt.startsWith("image/") -> nImg++
                isDocumentMediaType(mt) -> {
                    nHtml++
                    htmlTotalBytes += (book.resourceSize(item.href) ?: 0L).coerceAtLeast(0L)
                }
            }
        }
        return classifyByStructure(nHtml, nImg, htmlTotalBytes)
    }

    data class ImportBundle(
        val metadata: EpubMetadata = EpubMetadata(),
        val coverPath: String? = null,
        val isComic: Boolean = false,
    )

    /**
     * 判定 EPUB 是否为漫画。算法：图片资源字节占比（图片 / (图片+xhtml/html)）≥ 0.8。
     *
     * 与 MOBI 漫画判定（[ComicBookDetector.detectMobi]）算法一致 —— 按字节比例而非数量比例。
     * 字节比例对「文字 + 少量大插图」「漫画 + 少量纯文字章节」两类边界 case 都更准。
     *
     * 性能：复用 [withEpubBook] 拿到 lazy 解析的 EpubBook 实例，遍历 resources 只读
     * mediaType + LazyResource.cachedSize（来自 ZIP central directory，**无需解压字节**）。
     * 50MB 漫画 EPUB 整个调用通常 < 200ms。
     */
    fun detectIsComic(context: Context, uri: Uri): Boolean {
        return EpubCoreBridge.withCoreBook(context, uri) { book -> isComicViaCore(book) } ?: false
    }

    /**
     * 结构指纹判定（pure function，internal 便于单测）。
     *
     * 三道指纹（不依赖关键词 / 语言）：
     *
     * **指纹 1 — 一页一档**：每张漫画图对应一个壳子 xhtml，N_html ≈ N_img。
     * 命中条件：`N_img ≥ MIN_COMIC_IMAGE_COUNT && N_html/N_img ∈ [0.8, 1.2]`
     *
     * **指纹 2 — Webtoon 长图滚动**：漫画切片塞进少数 xhtml（一话一个 xhtml 含 N 张图），
     * N_html << N_img，每图均摊 html 字节量极少（全是 `<img>` 标签没文字）。
     * 命中条件：`N_html < N_img && htmlTotalBytes/N_img < TINY_HTML_PER_IMG_THRESHOLD`
     *
     * **样本量保护**：`N_img < MIN_COMIC_IMAGE_COUNT (10)` 直接判 Novel —— 文字小说
     * 带几张彩页不应误判漫画。「魔女の旅々 N_img=15、N_html=200+」属于这种 case：
     * 指纹 1 ratio=13 不在范围、指纹 2 N_html>N_img → fall-through 判 Novel ✓
     */
    internal fun classifyByStructure(nHtml: Int, nImg: Int, htmlTotalBytes: Long): Boolean {
        if (nImg < MIN_COMIC_IMAGE_COUNT) {
            AppLog.info(
                "EpubParser",
                "detectIsComic → Novel (nImg=$nImg < $MIN_COMIC_IMAGE_COUNT, sample too small)",
            )
            return false
        }
        val avgHtmlPerImg = if (nImg > 0) htmlTotalBytes / nImg else 0L

        // 指纹 1：一页一档 (N_html ≈ N_img AND 每个 html 是包图骨架，非文字章节)
        //
        // 双条件防御「短篇小说 5 章 + 5 插图 ratio=1.0」误判：文字章节每章几十 KB，
        // 漫画骨架 html `<body><img/></body>` 1-5KB。avgHtmlPerImg < 6KB 即判骨架。
        if (nHtml > 0) {
            val ratio = nHtml.toDouble() / nImg
            if (ratio in PAGE_PER_IMAGE_LOW..PAGE_PER_IMAGE_HIGH &&
                avgHtmlPerImg < WRAPPER_HTML_BYTES_THRESHOLD
            ) {
                AppLog.info(
                    "EpubParser",
                    "detectIsComic → Comic (fp-1 page-per-image nHtml=$nHtml nImg=$nImg " +
                        "ratio=${"%.2f".format(ratio)} avgHtml/img=${avgHtmlPerImg}B)",
                )
                return true
            }
        }
        // 指纹 2：Webtoon 长图滚动 (N_html << N_img AND 每图分摊 html 极小)
        if (nHtml < nImg && avgHtmlPerImg < TINY_HTML_PER_IMG_THRESHOLD) {
            AppLog.info(
                "EpubParser",
                "detectIsComic → Comic (fp-2 webtoon nHtml=$nHtml nImg=$nImg avgHtml/img=${avgHtmlPerImg}B)",
            )
            return true
        }
        AppLog.info(
            "EpubParser",
            "detectIsComic → Novel (fall-through nHtml=$nHtml nImg=$nImg htmlBytes=${htmlTotalBytes}B " +
                "avgHtml/img=${avgHtmlPerImg}B)",
        )
        return false
    }

    /**
     * Pure function：按 (mediaType, size) 列表判定是否漫画。internal 兼容旧单测，
     * 内部归纳为 (nHtml, nImg, htmlTotalBytes) 三元组后调 [classifyByStructure]。
     */
    internal fun isComicByMediaBytes(items: Iterable<Pair<String, Long>>): Boolean {
        var nImg = 0
        var nHtml = 0
        var htmlTotalBytes = 0L
        for ((mt, size) in items) {
            when {
                mt.startsWith("image/") -> nImg++
                isDocumentMediaType(mt) -> {
                    nHtml++
                    htmlTotalBytes += size.coerceAtLeast(0L)
                }
            }
        }
        return classifyByStructure(nHtml, nImg, htmlTotalBytes)
    }

    /**
     * 是否算文档资源（应计入 textBytes）。
     *
     * 包含：
     * - `application/xhtml+xml`、`text/html` (主流 EPUB3)
     * - `text/x-oeb1-document`、`application/oeb1+xml`、`application/x-dtbook+xml` (老 OEB)
     * - `text/plain`、`application/xml` (兜底)
     *
     * 排除：css / js / 字体 / 音视频 / NCX / OPF / image (image 走另一分支)
     */
    private fun isDocumentMediaType(mt: String): Boolean {
        if (mt.isBlank()) return false
        val lower = mt.lowercase()
        // 显式排除非正文资源
        if ("css" in lower || "javascript" in lower) return false
        if (lower.startsWith("font/") || "font-" in lower || "opentype" in lower || "truetype" in lower) return false
        if (lower.startsWith("audio/") || lower.startsWith("video/")) return false
        if ("dtbncx" in lower || "oebps-package" in lower || "ncx" in lower) return false
        // 正向匹配
        return "xhtml" in lower ||
            lower.endsWith("/html") ||
            "oeb1" in lower ||
            "dtbook" in lower ||
            lower == "text/plain" ||
            lower == "application/xml"
    }

    /**
     * 漫画判定的图片绝对数量下限（样本量保护）。任何 N_img 低于此值的 EPUB 直接判
     * Novel —— 绝对样本量不足无法可靠判断。5 是覆盖**短篇绘本/画集**的边界
     * （单卷绘本通常 5-30 图），低于 5 张就只能当文字 + 几张配图处理。
     */
    internal const val MIN_COMIC_IMAGE_COUNT = 5

    /** 指纹 1「一页一档」N_html / N_img 比例范围下限 / 上限。 */
    internal const val PAGE_PER_IMAGE_LOW = 0.8
    internal const val PAGE_PER_IMAGE_HIGH = 1.2

    /**
     * 指纹 1 的辅助阈值：每张图分摊的 wrapper html 字节上限。
     *
     * 漫画包图骨架 html `<html><body><img src="..."/></body></html>` 实际 1-5KB；
     * 文字章节每章 10-100KB。设 6KB 作为分水岭——拒绝「短篇小说恰好 N_html≈N_img
     * 但 html 含正文」的伪 1:1 误判（如 5 章短篇 + 5 张插图）。
     */
    internal const val WRAPPER_HTML_BYTES_THRESHOLD = 6_144L

    /**
     * 指纹 2「Webtoon 长图」每张图分摊的 html 字节上限。html 文件如果只含
     * `<img src="..."/>` 标签（无文字内容），平均 200-400B；超过 500B 大概率有正文。
     */
    internal const val TINY_HTML_PER_IMG_THRESHOLD = 500L

    // ── 漫画图片资源（供 EpubComicResourceLoader 调用） ──

    /**
     * 收集 EPUB 漫画的图片资源序列。按 spine 顺序 + 每个 xhtml 章节内 img 出现顺序，
     * 用 [LinkedHashSet] 去重。spine 为空或没产生任何 image 时 fallback 到 manifest
     * 顺序（不稳定但保证「至少能读到图」）。
     *
     * 返回 (hash, ordered hrefs)。hash 用 uri 的 hashCode 字符串，与
     * [MobiResourceLoader] 一致策略；同一进程同一文件命中同一 hash → registry 反查 OK。
     */
    fun activateComicImages(context: Context, uri: Uri): Pair<String, List<String>>? {
        return EpubCoreBridge.withCoreBook(context, uri) { book ->
            val hrefs = collectImageHrefsBySpineViaCore(book)
            if (hrefs.isEmpty()) return@withCoreBook null
            val hash = uri.toString().hashCode().toString()
            AppLog.info("EpubParser", "activateComicImages hash=$hash images=${hrefs.size}")
            hash to hrefs
        }
    }

    /**
     * 用 epub-core 数据源收集漫画图片 href（字典序）。算法与老 [collectImageHrefsBySpine]
     * 等价：先按 manifest mediaType image 字典序排序（漫画文件名 p0001 / 0001 字典序 ≈
     * 阅读序），mediaType 异常时按扩展名兜底，最后是 spine xhtml parse 兜底。
     *
     * 输出 href 是 OPF 相对路径，下游 [readResourceBytes] (D.3 epub-core) 用 book.resource(href)
     * 取字节。
     */
    private fun collectImageHrefsBySpineViaCore(book: com.morealm.epub.EpubBook): List<String> {
        // Fast path：manifest mediaType image 字典序
        val byMediaType = book.opfPackage.manifest
            .asSequence()
            .filter { it.mediaType.startsWith("image/") }
            .map { it.href }
            .toList()
        if (byMediaType.isNotEmpty()) return byMediaType.sorted()

        // Fallback 1：按扩展名识别（少数老压制 mediaType=application/octet-stream）
        val byExt = book.opfPackage.manifest
            .asSequence()
            .map { it.href }
            .filter { it.matches(imageExtRegex) }
            .toList()
        if (byExt.isNotEmpty()) return byExt.sorted()

        // Fallback 2：spine xhtml parse 兜底（慢但准）
        val seen = LinkedHashSet<String>()
        for (chapter in book.spine.items) {
            val mtype = book.opfPackage.byId[chapter.id]?.mediaType.orEmpty()
            when {
                mtype.startsWith("image/") -> seen.add(chapter.href)
                "xhtml" in mtype || mtype.endsWith("/html") -> {
                    appendImageHrefsFromXhtmlBytes(chapter.href, chapter.bytes(), seen)
                }
            }
        }
        return seen.toList()
    }

    private val imageExtRegex = Regex(".*\\.(?:jpg|jpeg|png|webp|gif|bmp)$", RegexOption.IGNORE_CASE)

    private fun appendImageHrefsFromXhtmlBytes(
        baseHref: String,
        bytes: ByteArray,
        out: LinkedHashSet<String>,
    ) {
        try {
            val body = bytes.decodeToString()
            val doc = Jsoup.parse(body)
            for (img in doc.select("img")) {
                val src = img.attr("src").ifEmpty { img.attr("xlink:href") }
                if (src.isBlank()) continue
                out.add(resolveRelativeHref(baseHref, src))
            }
            for (svgImage in doc.select("svg image")) {
                val href = svgImage.attr("xlink:href").ifEmpty { svgImage.attr("href") }
                if (href.isBlank()) continue
                out.add(resolveRelativeHref(baseHref, href))
            }
        } catch (e: Exception) {
            AppLog.warn("EpubParser", "appendImageHrefs failed on $baseHref: ${e.message}")
        }
    }

    private fun resolveRelativeHref(baseHref: String, target: String): String = try {
        URLDecoder.decode(URI(baseHref).resolve(target).toString(), "UTF-8")
    } catch (_: Exception) {
        target
    }

    /** 按 href 读资源原字节。失败返回 null。供 [EpubComicResourceLoader.readBytes] 使用。 */
    fun readResourceBytes(context: Context, uri: Uri, href: String): ByteArray? {
        return EpubCoreBridge.withCoreBook(context, uri) { book ->
            try {
                book.resource(href) ?: book.resource(
                    runCatching { URLDecoder.decode(href, "UTF-8") }.getOrNull().orEmpty(),
                )
            } catch (e: Exception) {
                AppLog.warn("EpubParser", "readResourceBytes failed href=$href: ${e.message}")
                null
            }
        }
    }

    /**
     * 封面缩略图目标宽度。书架格子约 200dp，3x retina = 600px 已是上限；
     * 不下采样会让漫画封面（常见 2000-4000px 宽）以 ARGB_8888 解码到 24-96MB Bitmap，
     * 老设备 GC 风暴 + 可能 OOM —— 50MB 漫画 EPUB 一直卡在「导入中」的根因之一。
     */
    private const val MAX_COVER_WIDTH = 600
    private const val COVER_JPEG_QUALITY = 85

    private const val SPINE_COVER_SCAN_LIMIT = 3

    /**
     * bounds decode → power-of-2 inSampleSize → 精细 scale → JPEG 85 压盘。
     * 与 [writeImageCompressed] 同款防御链路；SVG / 异常字节返回 null（让上层 fallback）。
     */
    private fun decodeAndWriteScaledCover(bytes: ByteArray, target: File): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val origWidth = bounds.outWidth
        if (origWidth <= 0) return null

        var sample = 1
        while (origWidth / (sample * 2) >= MAX_COVER_WIDTH) sample *= 2
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts) ?: return null
        val finalBmp = if (bmp.width > MAX_COVER_WIDTH) {
            val newH = (bmp.height.toLong() * MAX_COVER_WIDTH / bmp.width).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(bmp, MAX_COVER_WIDTH, newH, true)
            if (scaled !== bmp) bmp.recycle()
            scaled
        } else bmp
        FileOutputStream(target).use { out ->
            finalBmp.compress(Bitmap.CompressFormat.JPEG, COVER_JPEG_QUALITY, out)
        }
        finalBmp.recycle()
        return target.absolutePath
    }

    // ── Chapter list ─────────────────────────────────────

    fun parseChapters(context: Context, uri: Uri): List<BookChapter> {
        return EpubCoreBridge.withCoreBook(context, uri) { book ->
            buildChapterListViaCore(uri.toString(), book)
        } ?: emptyList()
    }

    /**
     * 用 epub-core 数据源构建章节列表。算法与 [buildChapterList] 完全等价：
     * 1. 无 toc → spine 顺序回退（封面/第N章）
     * 2. 有 toc → spine 内 toc 之前的当卷首 + toc 递归 + 父子去重保留更长 title +
     *    嵌套层级缩进
     *
     * **chapter.url 用 ZIP 绝对路径**（OPF dir 前缀 + epub-core item.href）—— 与
     * 老 [buildChapterList] 输出的 legacy upstream lib Resource.href 格式保持一致，让现有
     * [readChapter] / [readChapterFromBook] 老路径继续 work。原因：legacy upstream lib
     * 的 `PackageDocumentReader.fixHrefs` 被注释掉了（不裁 ZIP 前缀），所以
     * Resource.href = "OEBPS/Text/cover.xhtml"；而 epub-core Chapter.href =
     * "Text/cover.xhtml"（OPF 相对，标准 EPUB 行为）。两者直接平移会让老路径
     * 失配。统一用 ZIP 绝对路径绕开 legacy upstream lib 这个特性差异。
     */
    private fun buildChapterListViaCore(
        bookId: String,
        book: com.morealm.epub.EpubBook,
    ): List<BookChapter> {
        val chapters = mutableListOf<BookChapter>()
        val toc = book.toc
        val opfDir = book.opfPath.substringBeforeLast('/', "")

        if (toc.isEmpty()) {
            book.spine.items.forEachIndexed { i, chapter ->
                val title = chapter.title?.takeIf { it.isNotBlank() }
                    ?: tryExtractTitleViaCore(chapter)
                    ?: if (i == 0) "封面" else "第${i + 1}章"
                chapters.add(
                    BookChapter(
                        id = "${bookId}_$i", bookId = bookId, index = i,
                        title = title, url = toZipAbsHref(opfDir, chapter.href),
                    ),
                )
            }
        } else {
            parseFirstPagesViaCore(bookId, book, toc, opfDir, chapters)
            parseTocRefsViaCore(bookId, toc, opfDir, chapters)
            chapters.forEachIndexed { i, ch ->
                chapters[i] = ch.copy(id = "${bookId}_$i", index = i)
            }
        }

        for (i in 0 until chapters.size - 1) {
            chapters[i] = chapters[i].copy(nextUrl = chapters[i + 1].url)
        }
        return chapters
    }

    private fun parseFirstPagesViaCore(
        bookId: String,
        book: com.morealm.epub.EpubBook,
        toc: List<com.morealm.epub.ncx.TocEntry>,
        opfDir: String,
        chapters: MutableList<BookChapter>,
    ) {
        val firstEntry = toc.firstOrNull { it.src.isNotBlank() } ?: return
        val firstHref = firstEntry.src.substringBeforeLast("#")
        for (chapter in book.spine.items) {
            val mtype = book.opfPackage.byId[chapter.id]?.mediaType.orEmpty()
            if (!mtype.contains("htm")) continue
            if (chapter.href == firstHref) break
            val title = chapter.title?.takeIf { it.isNotBlank() }
                ?: tryExtractTitleViaCore(chapter) ?: "--卷首--"
            chapters.add(
                BookChapter(
                    id = "", bookId = bookId, index = 0, title = title,
                    url = toZipAbsHref(opfDir, chapter.href),
                ),
            )
        }
    }

    private fun parseTocRefsViaCore(
        bookId: String,
        refs: List<com.morealm.epub.ncx.TocEntry>,
        opfDir: String,
        chapters: MutableList<BookChapter>,
    ) {
        // 父子去重（按完整 src 含 fragment 当 key，保留 title 最长那条）+
        // 嵌套层级缩进 prefix（与旧 [parseTocRefs] 同款算法）。
        val seenByHref = HashMap<String, Int>()
        fun addOrMerge(title: String, href: String) {
            val existingIdx = seenByHref[href]
            if (existingIdx != null) {
                val ex = chapters[existingIdx]
                if (title.length > ex.title.length) {
                    chapters[existingIdx] = ex.copy(title = title.ifBlank { ex.title })
                }
            } else {
                seenByHref[href] = chapters.size
                chapters.add(
                    BookChapter(
                        id = "", bookId = bookId, index = 0,
                        title = title.ifBlank { "未命名章节" },
                        url = href,
                    ),
                )
            }
        }
        fun recurse(rs: List<com.morealm.epub.ncx.TocEntry>, depth: Int) {
            val prefix = "  ".repeat(depth.coerceAtMost(6))
            for (ref in rs) {
                if (ref.src.isNotBlank()) {
                    val raw = ref.label
                    val withIndent = if (raw.isBlank()) raw else prefix + raw
                    addOrMerge(withIndent, toZipAbsHref(opfDir, ref.src))
                }
                if (ref.children.isNotEmpty()) recurse(ref.children, depth + 1)
            }
        }
        recurse(refs, depth = 0)
    }

    /**
     * 把 OPF 相对 href（epub-core item.href / toc src）拼成 ZIP 绝对路径。
     * 跟 legacy upstream lib 老路径的 Resource.href 格式对齐（OPF 在子目录时前缀 = OPF dir）。
     *
     * 不做 ".." normalize —— legacy upstream lib 现状也是直接拼接，保持 byte-for-byte 一致。
     * fragment（"#xxx"）原样保留在末尾。
     */
    private fun toZipAbsHref(opfDir: String, href: String): String =
        if (opfDir.isEmpty()) href else "$opfDir/$href"

    private fun tryExtractTitleViaCore(chapter: com.morealm.epub.Chapter): String? {
        return try {
            val text = chapter.bytes().decodeToString()
            Jsoup.parse(text).select("title").text().takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    // ── Chapter content ──────────────────────────────────

    fun readChapter(context: Context, uri: Uri, chapter: BookChapter): String {
        val targetHref = chapter.url.substringBeforeLast("#")
        if (targetHref.isEmpty()) return ""
        // cache key 用 chapter.url 完整 url（含 fragment）—— 同 xhtml 多 navPoint 时
        // #fragment 决定截取范围，纯 targetHref 当 key 会让所有 navPoint 共享同一缓存。
        val cacheKey = chapter.url

        // Check disk cache
        val cached = readCachedChapter(context, uri, cacheKey)
        if (cached != null) return cached

        // L1.5 桥接：内部走自研 streaming（epub-core + visitor chain），最后 flatten
        // 成 String 喂给当前 reader 字符串排版层。对外签名不变，渲染层 / 4 个翻页动画
        // / 其他 format 全部零影响。legacy upstream lib parseBody + sanitizeAndRewriteImages +
        // formatKeepImg 老链在 readChapter 路径下线（preCacheChapters 老路径暂留）。
        val structured = readChapterStructured(context, uri, chapter)
        val content = if (structured.isEmpty()) "" else structured.flattenToString()

        // P3-5b Step 2c diag：标题/cover 等多色 RichText 章 flatten 后应该含 SOH(0x01) marker
        val hasSpanMarker = content.contains('')
        if (hasSpanMarker) {
            com.morealm.app.core.log.AppLog.info(
                "P3-5b/CharColor",
                "EpubParser writing cache w/ SPAN_COLOR markers chapter='${chapter.title}' len=${content.length}",
            )
        } else {
            AppLog.debug(
                "P3-5b/CharColor",
                "EpubParser flatten NO span markers chapter='${chapter.title}' len=${content.length} " +
                    "blocks=${structured.blocks.size}",
            )
        }

        if (content.isNotEmpty()) writeCachedChapter(context, uri, cacheKey, content)
        return content
    }
    // ── Structured chapter parsing (streaming via epub-core) ─────────────

    fun readChapterStructured(
        context: Context, uri: Uri, chapter: BookChapter,
    ): StructuredChapterContent {
        val targetHref = chapter.url.substringBeforeLast("#")
        AppLog.info("EpubParser", "readChapterStructured enter uri=$uri title=${chapter.title} url=${chapter.url}")
        if (targetHref.isEmpty()) {
            AppLog.warn("EpubParser", "readChapterStructured empty targetHref → empty")
            return StructuredChapterContent(emptyList())
        }

        val result = EpubCoreBridge.withCoreBook(context, uri) { book ->
            val opfDir = book.opfPath.substringBeforeLast('/', "")
            val rawTarget = chapter.url.substringBeforeLast("#")
            val opfRelTarget = if (opfDir.isNotEmpty() && rawTarget.startsWith("$opfDir/")) {
                rawTarget.removePrefix("$opfDir/")
            } else rawTarget
            val spineMatch = book.spine.items.indexOfFirst { it.href == opfRelTarget }
            AppLog.info(
                "EpubParser",
                "readChapterStructured book opened spine.size=${book.spine.size} " +
                    "opfDir='$opfDir' opfRelTarget='$opfRelTarget' spineMatch=$spineMatch " +
                    "cover=${book.metadata.coverHref ?: "null"}",
            )
            if (spineMatch < 0) {
                val preview = book.spine.items.take(8).map { it.href }
                AppLog.warn("EpubParser", "spine MISS for '$opfRelTarget'; first 8 spine hrefs=$preview")
            }
            val imgLookup: (String) -> String? = { src ->
                extractImageFromCoreBook(context, uri, src, book)?.let { "file://${it.absolutePath}" }
            }
            val coverLookup: () -> String? = {
                extractCoverFromCoreBook(context, uri, book)?.let { "file://${it.absolutePath}" }
            }
            // P3-5b Phase 2b：切到 tree-based readTree —— 走 Chapter.parse() + 全 CSS cascade
            // （class / id selectors / inline / @media），把 BlockStyle / StyledSpan 真实
            // 数据填到 StructuredChapterContent。flattenToString 暂仍丢富数据，等 Phase 3
            // ChapterProvider 接 layoutFromBlocks 才能让 kuang1 装饰可见。
            // rootFontSizePx=16f 固定值：BlockStyle 单位是"设计像素"，渲染层后续按用户
            // 字号倍率缩放。这样 cache 不因用户改字号失效。
            ChapterReader.readTree(book, chapter.url, chapter.nextUrl, imgLookup, coverLookup, rootFontSizePx = 16f)
        }
        if (result == null) {
            AppLog.warn("EpubParser", "readChapterStructured withCoreBook returned null (book open failed)")
            return StructuredChapterContent(emptyList())
        }
        AppLog.info(
            "EpubParser",
            "readChapterStructured done title='${chapter.title}' blocks=${result.blocks.size} " +
                "isEmpty=${result.isEmpty()} totalChars=${result.totalChars}",
        )
        return result
    }

    /**
     * 从 epub-core [com.morealm.epub.EpubBook] 拿章节图片字节、压缩落盘到
     * `epub_images/{uri.hash}/{src.replace('/','_')}` cache（与老
     * [extractImageFromBook] 同 cache key 规则，复用历史 cache）。
     *
     * 不处理 cover sentinel — cover 经 [extractCoverFromCoreBook] 单独路径。
     */
    private fun extractImageFromCoreBook(
        context: Context,
        epubUri: Uri,
        imagePath: String,
        book: com.morealm.epub.EpubBook,
    ): File? {
        val normalized = imagePath.replace('\\', '/')
        val cacheDir = File(context.cacheDir, "epub_images/${epubUri.hashCode()}")
        val cachedFile = File(cacheDir, normalized.replace('/', '_'))
        if (cachedFile.exists()) {
            AppLog.debug("EpubParser", "extractImg cache-hit $normalized")
            return cachedFile
        }

        val direct = book.resource(normalized)
        val decoded = if (direct == null) {
            runCatching { book.resource(URLDecoder.decode(normalized, "UTF-8")) }.getOrNull()
        } else null
        val bytes = direct ?: decoded
        if (bytes == null) {
            AppLog.warn("EpubParser", "extractImg miss $normalized (decoded variant also null)")
            return null
        }

        cacheDir.mkdirs()
        writeImageCompressed(bytes, cachedFile)
        AppLog.debug("EpubParser", "extractImg ok $normalized ${bytes.size}B → ${cachedFile.absolutePath}")
        return cachedFile
    }

    /**
     * 从 epub-core [com.morealm.epub.EpubBook] 拿 cover 字节
     * （[com.morealm.epub.Metadata.coverHref] → [com.morealm.epub.EpubBook.resourceByZipName]），
     * 压缩落盘到 `epub_images/{uri.hash}/cover_{zipPath.replace('/','_')}`。
     *
     * 不和普通 img cache 冲突：filename 前缀 `cover_` 隔离。
     */
    private fun extractCoverFromCoreBook(
        context: Context,
        epubUri: Uri,
        book: com.morealm.epub.EpubBook,
    ): File? {
        val coverHref = book.metadata.coverHref?.takeIf { it.isNotEmpty() }
        if (coverHref == null) {
            AppLog.warn("EpubParser", "extractCover metadata.coverHref is null")
            return null
        }
        val cacheDir = File(context.cacheDir, "epub_images/${epubUri.hashCode()}")
        val cachedFile = File(cacheDir, "cover_${coverHref.replace('/', '_')}")
        if (cachedFile.exists()) {
            AppLog.debug("EpubParser", "extractCover cache-hit $coverHref")
            return cachedFile
        }

        // epub-core Metadata.coverHref 实际填的是 OPF 相对路径（"Images/cover.jpg"），
        // 文档写的是 ZIP 绝对路径其实不准。先用 resource() 走 PathUtil.resolve 拼 OPF 前缀；
        // 若仍 miss（个别 EPUB coverHref 真填的是 ZIP 绝对路径如 "OEBPS/Images/cover.jpg"），
        // 兜底再走 resourceByZipName。
        val bytes = book.resource(coverHref) ?: book.resourceByZipName(coverHref)
        if (bytes == null) {
            AppLog.warn("EpubParser", "extractCover miss coverHref=$coverHref (both opf-rel and zip-abs)")
            return null
        }
        cacheDir.mkdirs()
        writeImageCompressed(bytes, cachedFile)
        AppLog.debug("EpubParser", "extractCover ok $coverHref ${bytes.size}B")
        return cachedFile
    }

    // ── Image extraction (reuses book instance when available) ──

    /**
     * 屏幕级显示宽度上限。Android 主流手机宽度 1080-1440 px；EPUB 插图常见 2000-3500 px。
     * 缩到这里可显著降低后续 BitmapFactory.decode 时的内存峰值（4MB ARGB_8888 → 1.5MB）。
     */
    private const val MAX_IMAGE_WIDTH = 1280
    private const val JPEG_QUALITY = 88

    /**
     * 原图字节小于此阈值时直接写原文件，跳过解码 + bounds 检测 + 重压。
     *
     * 阈值历史：
     *  - v1.3：300KB —— 覆盖普通插图 EPUB
     *  - v1.3.1：768KB —— 漫画 EPUB 单页图常 400KB-700KB（如《镖人》699px×988px），
     *    300KB 阈值挡不住它们，每张都走 bounds decode + 写盘（即使最终判定 raw-smallpx
     *    回写原字节），100+ 张图 zip seek + bounds decode 累计 5-15 秒。
     *    提到 768KB 让大部分漫画图直接 short-circuit。
     *
     * 安全性：
     *  - reader 渲染时 Coil 自带 downsample，不会因为原图大就把整张 ARGB_8888 加载进内存
     *  - 跳过的只是导入时的预压缩；用户视觉感知 0 差异（漫画用户反而希望保留高清）
     */
    private const val SMALL_IMAGE_BYTES_SKIP = 768 * 1024

    /**
     * 把图片字节缓存到磁盘。原图宽度 > [MAX_IMAGE_WIDTH] 时走"解码 → 下采样 → JPEG 重压"路径；
     * 否则原字节直写。
     *
     * 失败兜底（如非位图格式 / 解码异常）：写原字节，不破坏既有功能。
     *
     * 适合大型精品 EPUB（69MB 《某 EPUB》量级）—— 70+ 张 1-2MB 插图压缩后 cacheDir 占用降 80%，
     * 翻页时 Bitmap 解码内存压力降 4x。
     */
    private fun writeImageCompressed(bytes: ByteArray, target: File) {
        val t0 = System.currentTimeMillis()
        val srcSize = bytes.size
        try {
            // 小图直接写盘 —— 不触发任何 Bitmap 分配，避免 GC 抖动 / OOM
            if (srcSize < SMALL_IMAGE_BYTES_SKIP) {
                target.writeBytes(bytes)
                AppLog.debug("EpubParser",
                    "img raw-small ${target.name} ${srcSize}B in ${System.currentTimeMillis()-t0}ms")
                return
            }
            // Decode bounds only —— 不分配 Bitmap，仅拿原尺寸
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val origWidth = bounds.outWidth
            val origHeight = bounds.outHeight
            if (origWidth <= 0) {
                target.writeBytes(bytes)
                AppLog.debug("EpubParser",
                    "img raw-nodecode ${target.name} ${srcSize}B in ${System.currentTimeMillis()-t0}ms")
                return
            }
            if (origWidth <= MAX_IMAGE_WIDTH) {
                target.writeBytes(bytes)
                AppLog.debug("EpubParser",
                    "img raw-smallpx ${target.name} ${origWidth}x${origHeight} ${srcSize}B in ${System.currentTimeMillis()-t0}ms")
                return
            }
            // power-of-2 下采样：让 inSampleSize 解出的宽度 ≥ MAX_IMAGE_WIDTH，再精细 scale
            var sample = 1
            while (origWidth / (sample * 2) >= MAX_IMAGE_WIDTH) sample *= 2
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
            if (bmp == null) {
                target.writeBytes(bytes)
                AppLog.debug("EpubParser",
                    "img raw-decodenull ${target.name} ${srcSize}B in ${System.currentTimeMillis()-t0}ms")
                return
            }
            val finalBmp = if (bmp.width > MAX_IMAGE_WIDTH) {
                val newH = (bmp.height.toLong() * MAX_IMAGE_WIDTH / bmp.width).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(bmp, MAX_IMAGE_WIDTH, newH, true)
                if (scaled !== bmp) bmp.recycle()
                scaled
            } else bmp
            FileOutputStream(target).use { out ->
                finalBmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            val outSize = target.length()
            finalBmp.recycle()
            AppLog.debug("EpubParser",
                "img compressed ${target.name} ${origWidth}x${origHeight} sample=$sample " +
                    "${srcSize}B->${outSize}B in ${System.currentTimeMillis()-t0}ms")
        } catch (oom: OutOfMemoryError) {
            // 关键保护：解码大图触发 OOM 时不让整个章节渲染 crash
            AppLog.warn("EpubParser",
                "img OOM ${target.name} ${srcSize}B → fallback raw; available heap ${availMb()}MB", oom)
            System.gc()
            try { target.writeBytes(bytes) } catch (_: Throwable) {}
        } catch (t: Throwable) {
            AppLog.warn("EpubParser",
                "img compress failed ${target.name} ${srcSize}B: ${t.message}", t)
            try { target.writeBytes(bytes) } catch (_: Throwable) {}
        }
    }

    private fun availMb(): Long {
        val rt = Runtime.getRuntime()
        return (rt.maxMemory() - rt.totalMemory() + rt.freeMemory()) / 1024 / 1024
    }

    private fun isCoverPage(href: String): Boolean {
        val normalized = href.lowercase()
        return normalized.contains("titlepage.xhtml") || normalized.contains("cover")
    }

    fun clearImageCache(context: Context, epubUri: Uri) {
        File(context.cacheDir, "epub_images/${epubUri.hashCode()}").deleteRecursively()
        File(context.cacheDir, "epub_covers/${epubUri.hashCode()}").deleteRecursively()
    }

    fun clearCache(context: Context, epubUri: Uri) {
        clearImageCache(context, epubUri)
        File(context.cacheDir, "$CHAPTER_CACHE_DIR/${epubUri.hashCode()}").deleteRecursively()
        File(context.cacheDir, "epub_chapters/${epubUri.hashCode()}").deleteRecursively()
    }

    // ── Chapter cache ────────────────────────────────────

    private fun readCachedChapter(context: Context, epubUri: Uri, path: String): String? {
        val f = chapterCacheFile(context, epubUri, path)
        if (!f.exists()) return null
        val text = f.readText()
        if (isStaleChapterCache(path, text)) {
            f.delete()
            return null
        }
        return text
    }

    private fun writeCachedChapter(context: Context, epubUri: Uri, path: String, content: String) {
        try { chapterCacheFile(context, epubUri, path).apply { parentFile?.mkdirs(); writeText(content) } }
        catch (_: Exception) {}
    }

    private fun chapterCacheFile(context: Context, epubUri: Uri, path: String): File =
        // 同时 escape '/' 和 '#'：path 现在含 #fragment（区分同 xhtml 多 navPoint）。
        // '#' 在文件系统多数 OK，但稳妥起见转 '_at_' 避坑。
        File(context.cacheDir, "$CHAPTER_CACHE_DIR/${epubUri.hashCode()}/${path.replace("/", "_").replace("#", "_at_")}.html")

    private fun isStaleChapterCache(path: String, text: String): Boolean {
        if (text.contains(COVER_IMAGE_MARKER)) return true
        if (text.contains("<body", ignoreCase = true) || text.contains("<p", ignoreCase = true) || text.contains("<div", ignoreCase = true)) return true
        return text.length < 200 && !text.contains("<img") && isCoverPage(path)
    }

    /**
     * Pre-cache nearby chapters only (not the entire book).
     *
     * 窗口取 [aroundIndex-1, aroundIndex+3]（共 5 章）。漫画 EPUB 单章动辄 10-30 张图，
     * 每张图都要 epublib zip seek + bounds decode + 磁盘写，14 章漫画全本预缓存能
     * 跑 5-15 秒——用户感受就是「开了书页面卡着不动 / IO 抢资源 / OOM」。
     *
     * 缩小窗口能让首次 prefetch 在 1-2 秒内完成；用户翻页接近窗口边界时
     * （LazyScrollSection 已经接通 onNearTop / onNearBottom），ChapterWindowSource
     * 会触发新章节按需 fetch，再次调本函数扩展窗口。
     *
     * 与之前对比：
     *   旧：[aroundIndex-5, aroundIndex+20] = 26 章 → 漫画 EPUB 预热全部
     *   新：[aroundIndex-1, aroundIndex+3] = 5 章 → 漫画 EPUB 只热当前 + 后 3 章
     */
    fun preCacheChapters(context: Context, uri: Uri, chapters: List<BookChapter>, aroundIndex: Int = 0) {
        val start = (aroundIndex - 1).coerceAtLeast(0)
        val end = (aroundIndex + 4).coerceAtMost(chapters.size)
        val nearby = chapters.subList(start, end)
        val uncached = nearby.filter { ch ->
            // cache key 与 readChapter 对齐：完整 chapter.url 含 fragment。
            ch.url.isNotEmpty() && !chapterCacheFile(context, uri, ch.url).exists()
        }
        if (uncached.isEmpty()) return
        val t0 = System.currentTimeMillis()
        AppLog.info(
            "EpubParser",
            "preCacheChapters start: around=$aroundIndex window=[$start..${end - 1}]" +
                " total=${chapters.size} uncached=${uncached.size}",
        )
        // L1.5：直接调主入口 readChapter，内部走 streaming + 写 cache。
        // 老 readChapterFromBook + withEpubBook(legacy upstream lib) 路径在 preCache 也下线 —— 不再写
        // 老 formatKeepImg 格式到新 v5 cache，避免格式漂移。readChapter 自带 cache hit check，
        // 对已 cached 章节 short-circuit。
        for (ch in uncached) {
            readChapter(context, uri, ch)
        }
        AppLog.info(
            "EpubParser",
            "preCacheChapters done: cached=${uncached.size} elapsed=${System.currentTimeMillis() - t0}ms",
        )
    }

    /** Release all cached EpubCoreBridge books (call when reader closes globally). */
    fun releaseCache() {
        EpubCoreBridge.closeAll()
    }
}
