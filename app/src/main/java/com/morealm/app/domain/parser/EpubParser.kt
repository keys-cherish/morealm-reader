package com.morealm.app.domain.parser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.morealm.app.domain.entity.BookChapter
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.parser.epub.ChapterBlock
import com.morealm.app.domain.parser.epub.EpubHtmlStructurer
import com.morealm.app.domain.parser.epub.StructuredChapterContent
import me.ag2s.epublib.domain.EpubBook
import me.ag2s.epublib.domain.Resource
import me.ag2s.epublib.domain.TOCReference
import me.ag2s.epublib.epub.EpubReader
import me.ag2s.epublib.util.zip.AndroidZipFile
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
    private const val CHAPTER_CACHE_DIR = "epub_chapters_v4"
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

    fun extractMetadataAndCover(context: Context, uri: Uri): EpubImportResult {
        return withEpubBook(context, uri) { book ->
            val meta = book.metadata
            val metadata = EpubMetadata(
                title = meta.firstTitle.orEmpty(),
                author = meta.authors.firstOrNull()?.toString()
                    ?.replace("^, |, $".toRegex(), "").orEmpty(),
                description = meta.descriptions.firstOrNull()?.let {
                    if (it.contains('<')) Jsoup.parse(it).text() else it
                }.orEmpty(),
                subject = runCatching {
                    // dc:subject is a list of free-text genre/category tags. Join non-blank
                    // entries so a book with multiple subjects (玄幻 + 修真) feeds all signals
                    // into the auto-grouping classifier.
                    meta.subjects?.filter { it.isNotBlank() }?.joinToString(",").orEmpty()
                }.getOrDefault(""),
            )
            val coverPath = extractCoverFromBook(context, uri, book)
            EpubImportResult(metadata, coverPath)
        } ?: EpubImportResult()
    }

    fun extractCover(context: Context, uri: Uri): String? {
        return withEpubBook(context, uri) { book -> extractCoverFromBook(context, uri, book) }
    }

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
        return withEpubBook(context, uri) { book -> isComicByResources(book) } ?: false
    }

    private fun isComicByResources(book: EpubBook): Boolean {
        // ── Level 1: EPUB3 规范元数据降维打击 (O(1) OPF 解析) ──
        //
        // 漫画 EPUB 自己会在 OPF 里声明，命中任一直接 100% 判 Comic：
        //   - `<meta property="rendition:layout">pre-paginated</meta>` → 固定布局
        //   - `<dc:type>comic | manga | graphic novel</dc:type>` → 类型标记
        val opf = readOpfHints(book)
        if (opf.renditionLayout == "pre-paginated") {
            AppLog.info("EpubParser", "detectIsComic → Comic (OPF rendition:layout=pre-paginated)")
            return true
        }
        if (opf.dcType.orEmpty().let { "comic" in it || "manga" in it || "graphic novel" in it }) {
            AppLog.info("EpubParser", "detectIsComic → Comic (OPF dc:type=${opf.dcType})")
            return true
        }

        // ── Level 2: OPF 结构指纹 (野生自制 EPUB 不写规范字段) ──
        //
        // 统计 N_img / N_html / Size_html_total 三个数 → 结构指纹判定。完全不依赖
        // 任何语言关键词，纯靠打包工具留下的「数量 + 字节」指纹。详见
        // [classifyByStructure] 三道指纹定义。
        val all = book.resources?.getAll() ?: return false
        if (all.isEmpty()) return false
        var nImg = 0
        var nHtml = 0
        var htmlTotalBytes = 0L
        for (res in all) {
            val mt = res.mediaType?.toString().orEmpty()
            when {
                mt.startsWith("image/") -> nImg++
                isDocumentMediaType(mt) -> {
                    nHtml++
                    htmlTotalBytes += res.size.coerceAtLeast(0L)
                }
            }
        }
        return classifyByStructure(nHtml, nImg, htmlTotalBytes)
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

    /** OPF 规范元数据"探针"。读 OPF 字节用正则提取关键字段，无 XML 依赖。 */
    private data class OpfHints(
        val renditionLayout: String?,    // "pre-paginated" / "reflowable" / null
        val pageProgression: String?,    // "rtl" / "ltr" / null
        val dcType: String?,             // "comic" / "manga" / "graphic novel" / null
        val title: String?,              // dc:title
        val publisher: String?,          // dc:publisher
        val subjects: List<String>,      // dc:subject (可多个)
        val primaryWritingMode: String?, // <meta name="primary-writing-mode" content="vertical-rl"> (Calibre 日轻小说)
        val coverImageHref: String?,     // EPUB3 <item properties="cover-image" href="..."> (epublib 不识别此字段)
    )

    private fun readOpfHints(book: EpubBook): OpfHints {
        val empty = OpfHints(null, null, null, null, null, emptyList(), null, null)
        return try {
            // OPF mediaType 标准为 "application/oebps-package+xml"
            val opfRes = book.resources?.getAll()?.firstOrNull {
                (it.mediaType?.toString().orEmpty()) == "application/oebps-package+xml"
            } ?: return empty
            val xml = String(opfRes.data, charset)
            OpfHints(
                renditionLayout = renditionLayoutRegex.find(xml)?.groupValues?.getOrNull(1)?.trim()?.lowercase()?.takeIf { it.isNotBlank() },
                pageProgression = pageProgRegex.find(xml)?.groupValues?.getOrNull(1)?.trim()?.lowercase()?.takeIf { it.isNotBlank() },
                dcType = dcTypeRegex.find(xml)?.groupValues?.getOrNull(1)?.trim()?.lowercase()?.takeIf { it.isNotBlank() },
                title = dcTitleRegex.find(xml)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() },
                publisher = dcPublisherRegex.find(xml)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() },
                subjects = dcSubjectRegex.findAll(xml).map { it.groupValues[1].trim() }.filter { it.isNotBlank() }.toList(),
                primaryWritingMode = primaryWritingModeRegex.find(xml)?.groupValues?.getOrNull(1)?.trim()?.lowercase()?.takeIf { it.isNotBlank() },
                coverImageHref = findCoverImageHrefInOpf(xml),
            )
        } catch (e: Exception) {
            AppLog.warn("EpubParser", "readOpfHints failed: ${e.message}")
            empty
        }
    }

    /**
     * 解析 OPF 找 EPUB3 `<item properties="cover-image" href="...">`。
     * 属性顺序不固定，所以两步法：先抓所有 `<item ...>` 标签，再筛 properties 含
     * `cover-image` 的项提取 href。比单条复杂 regex 鲁棒。
     */
    private fun findCoverImageHrefInOpf(xml: String): String? {
        for (match in opfItemRegex.findAll(xml)) {
            val attrs = match.groupValues[1]
            if (!coverImagePropertyRegex.containsMatchIn(attrs)) continue
            val href = hrefAttrRegex.find(attrs)?.groupValues?.getOrNull(1)
            if (!href.isNullOrBlank()) return href
        }
        return null
    }

    private val opfItemRegex = Regex("""<item\b([^>]*)>""", RegexOption.IGNORE_CASE)
    private val coverImagePropertyRegex = Regex(
        """properties\s*=\s*["'][^"']*\bcover-image\b[^"']*["']""",
        RegexOption.IGNORE_CASE,
    )
    private val hrefAttrRegex = Regex(
        """\bhref\s*=\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )

    private val renditionLayoutRegex = Regex(
        """<meta\s+[^>]*property\s*=\s*["']rendition:layout["'][^>]*>\s*([^<]+?)\s*</meta>""",
        RegexOption.IGNORE_CASE,
    )
    private val pageProgRegex = Regex(
        """<spine\b[^>]*\bpage-progression-direction\s*=\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val dcTypeRegex = Regex(
        """<dc:type\b[^>]*>\s*([^<]+?)\s*</dc:type>""",
        RegexOption.IGNORE_CASE,
    )
    private val dcTitleRegex = Regex(
        """<dc:title\b[^>]*>\s*([^<]+?)\s*</dc:title>""",
        RegexOption.IGNORE_CASE,
    )
    private val dcPublisherRegex = Regex(
        """<dc:publisher\b[^>]*>\s*([^<]+?)\s*</dc:publisher>""",
        RegexOption.IGNORE_CASE,
    )
    private val dcSubjectRegex = Regex(
        """<dc:subject\b[^>]*>\s*([^<]+?)\s*</dc:subject>""",
        RegexOption.IGNORE_CASE,
    )
    private val primaryWritingModeRegex = Regex(
        """<meta\s+[^>]*name\s*=\s*["']primary-writing-mode["'][^>]*content\s*=\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )

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
        return withEpubBook(context, uri) { book ->
            val hrefs = collectImageHrefsBySpine(book)
            if (hrefs.isEmpty()) return@withEpubBook null
            val hash = uri.toString().hashCode().toString()
            AppLog.info("EpubParser", "activateComicImages hash=$hash images=${hrefs.size}")
            hash to hrefs
        }
    }

    private fun collectImageHrefsBySpine(book: EpubBook): List<String> {
        val all = book.resources?.getAll() ?: return emptyList()

        // ── Fast path：manifest 中 mediaType image 资源按 href 字典序 ──
        //
        // 漫画 EPUB 文件名通常是 p0001.jpg / image_0001.jpg / 0001.jpeg —— 字典序基本
        // 等于阅读序。跳过 spine xhtml parse（200 章 × ZIP IO + Jsoup ≈ 5-30s）让
        // activate 从 ~15s 降到 < 100ms。这是 ComicReader 黑屏（其实是 loading 转圈
        // 卡几十秒）的真正修法。
        //
        // trade-off：字典序对**非数字命名**的 EPUB 顺序可能错（罕见，主要是命名不
        // 规整的欧美漫）。出现误排再加 spine xhtml parse 校正层。
        val byMediaType = all
            .filter { (it.mediaType?.toString().orEmpty()).startsWith("image/") }
            .map { it.href }
        if (byMediaType.isNotEmpty()) return byMediaType.sorted()

        // ── Fallback 1：mediaType 异常（少数老压制把图片标成 application/octet-stream）──
        // 按扩展名识别，依然字典序。
        val byExt = all
            .map { it.href }
            .filter { it.matches(imageExtRegex) }
        if (byExt.isNotEmpty()) return byExt.sorted()

        // ── Fallback 2：最后兜底，原 spine xhtml parse 路径（慢但 100% 准） ──
        val seen = LinkedHashSet<String>()
        val spineRefs = book.spine?.spineReferences ?: emptyList()
        for (sref in spineRefs) {
            val res = sref.resource ?: continue
            val mt = res.mediaType?.toString().orEmpty()
            when {
                mt.startsWith("image/") -> seen.add(res.href)
                "xhtml" in mt || mt.endsWith("/html") -> appendImageHrefsFromXhtml(res, seen)
            }
        }
        return seen.toList()
    }

    private val imageExtRegex = Regex(".*\\.(?:jpg|jpeg|png|webp|gif|bmp)$", RegexOption.IGNORE_CASE)

    private fun appendImageHrefsFromXhtml(res: Resource, out: LinkedHashSet<String>) {
        try {
            val body = String(res.data, charset)
            val doc = Jsoup.parse(body)
            for (img in doc.select("img")) {
                val src = img.attr("src").ifEmpty { img.attr("xlink:href") }
                if (src.isBlank()) continue
                out.add(resolveRelativeHref(res.href, src))
            }
            // 兼容 SVG-wrapped image（许多日漫 EPUB 把封面/分章页用 svg image 包）
            for (svgImage in doc.select("svg image")) {
                val href = svgImage.attr("xlink:href").ifEmpty { svgImage.attr("href") }
                if (href.isBlank()) continue
                out.add(resolveRelativeHref(res.href, href))
            }
        } catch (e: Exception) {
            AppLog.warn("EpubParser", "appendImageHrefs failed on ${res.href}: ${e.message}")
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

    private fun extractCoverFromBook(context: Context, uri: Uri, book: EpubBook): String? {
        // book.coverImage 来自 OPF `<meta name="cover" content="...">` 显式声明。
        // 大量漫画 EPUB（特别是日漫扫描版）缺这个 meta，coverImage 直接返回 null ——
        // 退到 spine 头几项第一张 img（最常见就是封面页）；再不行 fallback 到 manifest
        // 首个 image/* 资源。比「书架上一律灰底无封面」体验好得多。
        val coverImage = book.coverImage ?: findFallbackCoverResource(book) ?: return null
        val cacheDir = File(context.cacheDir, "epub_covers/${uri.hashCode()}")
        val file = File(cacheDir, "cover.jpg")
        if (file.exists()) return file.absolutePath
        return try {
            cacheDir.mkdirs()
            // 拿原字节 —— LazyResource.data 触发一次 ZIP entry 读，但比两次 inputStream 便宜
            val bytes = coverImage.data ?: return null
            decodeAndWriteScaledCover(bytes, file)
        } catch (oom: OutOfMemoryError) {
            AppLog.warn("EpubParser", "Cover OOM: ${oom.message}")
            System.gc()
            null
        } catch (e: Exception) {
            AppLog.warn("EpubParser", "Cover extraction failed: ${e.message}")
            null
        }
    }

    /**
     * `book.coverImage` 为 null 时的多级回退：
     *
     * 1. **OPF `properties="cover-image"`** (EPUB3 规范) —— epublib 只识别 EPUB2
     *    的 `<meta name="cover">` 和 `<reference type="cover">`，EPUB3 用 manifest
     *    item properties 声明的封面被它漏识别。这里自己解析 OPF 补上。
     *    用户实测：「哈利波特与魔法石」EPUB3 命中此分支。
     *
     * 2. **spine 前 [SPINE_COVER_SCAN_LIMIT] 项 + 文件名启发式** —— spine 首项常是
     *    `titlepage.xhtml` / `cover.xhtml`，其中第一张 img 是封面。但有些 EPUB 首项
     *    是「制作说明 / 水印页」（用户截图见过），所以仅匹配文件名含 cover/title 的
     *    spine 项取其内嵌图，避免拿到水印页。
     *
     * 3. **manifest 中任一 image** —— 最后兜底，顺序不稳。
     */
    private fun findFallbackCoverResource(book: EpubBook): Resource? {
        val opf = readOpfHints(book)

        // ── 1. OPF properties="cover-image" (EPUB3 规范) ──
        opf.coverImageHref?.let { rawHref ->
            // href 通常是相对 OPF 的路径；epublib 的 resources 索引 key 是 manifest href
            val candidates = listOf(
                rawHref,
                runCatching { URLDecoder.decode(rawHref, "UTF-8") }.getOrNull().orEmpty(),
                rawHref.substringAfter('/'),  // 去掉可能的 "OEBPS/" 前缀
            ).filter { it.isNotBlank() }.distinct()
            for (h in candidates) {
                val res = book.resources?.getByHref(h)
                if (res != null) {
                    AppLog.info("EpubParser", "Cover via OPF cover-image properties: $h")
                    return res
                }
            }
        }

        // ── 2. spine 前几项 (仅文件名含 cover/title 的 xhtml 才取其内嵌图) ──
        val spineRefs = book.spine?.spineReferences.orEmpty()
        for (sref in spineRefs.take(SPINE_COVER_SCAN_LIMIT)) {
            val res = sref.resource ?: continue
            val mt = res.mediaType?.toString().orEmpty()
            when {
                mt.startsWith("image/") -> return res
                "xhtml" in mt || mt.endsWith("/html") -> {
                    val lowerHref = res.href.lowercase()
                    val isLikelyCoverPage = "cover" in lowerHref || "title" in lowerHref
                    if (!isLikelyCoverPage) continue  // 跳过非封面页 (如制作说明/水印页)
                    val img = firstImageHrefInXhtml(res) ?: continue
                    val target = book.resources?.getByHref(img)
                        ?: book.resources?.getByHref(URLDecoder.decode(img, "UTF-8"))
                    if (target != null) {
                        AppLog.info("EpubParser", "Cover via spine page name match: ${res.href}")
                        return target
                    }
                }
            }
        }

        // ── 3. manifest 中任一 image (兜底) ──
        val all = book.resources?.getAll() ?: return null
        return all.firstOrNull { (it.mediaType?.toString().orEmpty()).startsWith("image/") }?.also {
            AppLog.info("EpubParser", "Cover fallback to manifest first image: ${it.href}")
        }
    }

    private fun firstImageHrefInXhtml(xhtmlRes: Resource): String? {
        return try {
            val body = String(xhtmlRes.data, charset)
            val doc = Jsoup.parse(body)
            val raw = doc.select("img").firstOrNull()?.attr("src")?.takeIf { it.isNotBlank() }
                ?: doc.select("svg image").firstOrNull()?.let {
                    it.attr("xlink:href").ifEmpty { it.attr("href") }
                }?.takeIf { it.isNotBlank() }
                ?: return null
            resolveRelativeHref(xhtmlRes.href, raw)
        } catch (_: Exception) {
            null
        }
    }

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
     */
    private fun buildChapterListViaCore(
        bookId: String,
        book: com.morealm.epub.EpubBook,
    ): List<BookChapter> {
        val chapters = mutableListOf<BookChapter>()
        val toc = book.toc

        if (toc.isEmpty()) {
            book.spine.items.forEachIndexed { i, chapter ->
                val title = chapter.title?.takeIf { it.isNotBlank() }
                    ?: tryExtractTitleViaCore(chapter)
                    ?: if (i == 0) "封面" else "第${i + 1}章"
                chapters.add(
                    BookChapter(
                        id = "${bookId}_$i", bookId = bookId, index = i,
                        title = title, url = chapter.href,
                    ),
                )
            }
        } else {
            parseFirstPagesViaCore(bookId, book, toc, chapters)
            parseTocRefsViaCore(bookId, toc, chapters)
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
                    id = "", bookId = bookId, index = 0, title = title, url = chapter.href,
                ),
            )
        }
    }

    private fun parseTocRefsViaCore(
        bookId: String,
        refs: List<com.morealm.epub.ncx.TocEntry>,
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
                    addOrMerge(withIndent, ref.src)
                }
                if (ref.children.isNotEmpty()) recurse(ref.children, depth + 1)
            }
        }
        recurse(refs, depth = 0)
    }

    private fun tryExtractTitleViaCore(chapter: com.morealm.epub.Chapter): String? {
        return try {
            val text = chapter.bytes().decodeToString()
            Jsoup.parse(text).select("title").text().takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun buildChapterList(bookId: String, book: EpubBook): List<BookChapter> {
        val chapters = mutableListOf<BookChapter>()
        val refs = book.tableOfContents?.tocReferences

        if (refs.isNullOrEmpty()) {
            // No TOC — fall back to spine
            book.spine?.spineReferences?.forEachIndexed { i, spineRef ->
                val res = spineRef.resource ?: return@forEachIndexed
                val title = res.title?.takeIf { it.isNotBlank() }
                    ?: tryExtractTitle(res)
                    ?: if (i == 0) "封面" else "第${i + 1}章"
                chapters.add(BookChapter(
                    id = "${bookId}_$i", bookId = bookId, index = i,
                    title = title, url = res.href,
                ))
            }
        } else {
            // Parse first pages before TOC
            parseFirstPages(bookId, book, refs, chapters)
            // Parse TOC recursively
            parseTocRefs(bookId, refs, chapters)
            // Re-index
            chapters.forEachIndexed { i, ch ->
                chapters[i] = ch.copy(id = "${bookId}_$i", index = i)
            }
        }

        // Link chapters: each stores the next chapter's URL for content boundary detection
        for (i in 0 until chapters.size - 1) {
            chapters[i] = chapters[i].copy(nextUrl = chapters[i + 1].url)
        }
        return chapters
    }

    private fun parseFirstPages(
        bookId: String, book: EpubBook,
        refs: List<TOCReference>, chapters: MutableList<BookChapter>,
    ) {
        val contents = book.contents ?: return
        val firstRef = refs.firstOrNull { it.resource != null } ?: return
        val firstHref = firstRef.completeHref.substringBeforeLast("#")
        for (res in contents) {
            if (!res.mediaType.toString().contains("htm")) continue
            if (res.href == firstHref) break
            val title = res.title?.takeIf { it.isNotBlank() } ?: tryExtractTitle(res) ?: "--卷首--"
            chapters.add(BookChapter(
                id = "", bookId = bookId, index = 0, title = title, url = res.href,
            ))
        }
    }

    private fun parseTocRefs(
        bookId: String, refs: List<TOCReference>, chapters: MutableList<BookChapter>,
    ) {
        // ── 父+子 navPoint 去重 ──
        //
        // EPUB TOC 常见结构：
        //   <navPoint title="第三章 新的起飞" src="ch3.xhtml#sec_3">
        //     <navPoint title="第三章" src="ch3.xhtml#sec_3"/>
        //     <navPoint title="新的起飞" src="ch3.xhtml#sec_3a"/>
        //   </navPoint>
        // 老逻辑递归把 3 个全 add → 用户目录看到「第三章 新的起飞 / 第三章 / 新的起飞」
        // 三条并列（截图 12 报告的 bug）。
        //
        // 修法：按 completeHref（含 fragment）去重，重复时**保留 title 最长**的那条 ——
        // 通常父 navPoint title 是「第三章 新的起飞」（最完整描述），子是单独词组。
        // 全局 map 跨整个递归共享，确保所有层级一起去重。
        //
        // ── 父+子语义保留（嵌套层级缩进） ──
        //
        // 大型 EPUB 常用嵌套 navPoint 表达「卷-章」、「分册-人物-描述」等层级，例如《某 EPUB》：
        //   <navPoint title="某 EPUB人物志" src="juese.xhtml">
        //     <navPoint title="样本人物" src="part0.xhtml"/>
        //     <navPoint title="宁姚"   src="part1.xhtml"/>
        //   </navPoint>
        // 各 href 不同 → 不会被上面的去重折叠 → 子节点全部平铺成同级 → 用户失去父子语义。
        //
        // 与 PDF outline 缩进策略一致：title 前加 `"  ".repeat(depth)` 字面缩进，
        // depth ≤ 6 防过头。零 schema 改动。
        val seenByHref = HashMap<String, Int>() // href → index in chapters
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
                    )
                )
            }
        }
        fun recurse(rs: List<TOCReference>, depth: Int) {
            val prefix = "  ".repeat(depth.coerceAtMost(6))
            for (ref in rs) {
                if (ref.resource != null) {
                    val rawTitle = ref.title.orEmpty()
                    val withIndent = if (rawTitle.isBlank()) rawTitle else prefix + rawTitle
                    addOrMerge(withIndent, ref.completeHref)
                }
                if (!ref.children.isNullOrEmpty()) recurse(ref.children, depth + 1)
            }
        }
        recurse(refs, depth = 0)
    }

    private fun tryExtractTitle(res: Resource): String? {
        return try {
            val doc = Jsoup.parse(String(res.data, charset))
            doc.select("title").text().takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }
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

        // Read via epublib random access
        val content = withEpubBook(context, uri) { book ->
            val contents = book.contents ?: return@withEpubBook ""
            val nextHref = chapter.nextUrl?.substringBeforeLast("#")
            val startFragment = chapter.url.substringAfter("#", "").takeIf { it.isNotEmpty() }
            val endFragment = chapter.nextUrl?.substringAfter("#", "")?.takeIf { it.isNotEmpty() }

            val elements = org.jsoup.select.Elements()
            // Use href index map for O(1) start lookup instead of linear scan
            val startIdx = hrefIndexFor(uri)?.get(targetHref) ?: contents.indexOfFirst { it.href == targetHref }
            if (startIdx < 0) return@withEpubBook ""

            elements.add(parseBody(contents[startIdx], startFragment, endFragment.takeIf { contents[startIdx].href == nextHref }))
            if (nextHref == null || contents[startIdx].href != nextHref) {
                for (i in (startIdx + 1) until contents.size) {
                    val res = contents[i]
                    if (nextHref != null && res.href == nextHref) {
                        if (endFragment != null) elements.add(parseBody(res, null, endFragment))
                        break
                    }
                    elements.add(parseBody(res, null, null))
                }
            }
            processContent(elements, context, uri, targetHref, book)
        } ?: ""

        if (content.isNotEmpty()) writeCachedChapter(context, uri, cacheKey, content)
        return content
    }
    // ── Body parsing & image rewriting ─────────────────

    private fun parseBody(res: Resource, startFragment: String?, endFragment: String?): org.jsoup.nodes.Element {
        if (isCoverPage(res.href)) {
            return Jsoup.parseBodyFragment("<img src=\"$COVER_IMAGE_SENTINEL\" $COVER_IMAGE_MARKER=\"true\" />").body()
        }

        var body = Jsoup.parse(String(res.data, charset)).body()
        body.select("script, style").remove()

        // Convert SVG <image> to <img> (many Japanese EPUBs wrap cover in SVG)
        body.select("image").forEach { el ->
            el.tagName("img")
            val href = el.attr("xlink:href").ifEmpty { el.attr("href") }
            if (href.isNotEmpty()) el.attr("src", href)
        }
        // Convert SVG-wrapped images: extract <img> from <svg> containers
        body.select("svg").forEach { svg ->
            val img = svg.selectFirst("img")
            if (img != null) {
                svg.replaceWith(img)
                return@forEach
            }
            // 没 img 的 SVG 通常是注释 / 批注 / 章节装饰之类的内联图标 ——
            // 「制作说明」EPUB 把「注」「批」用 SVG 圆圈+text 画成小徽标。
            // 之前的 outerHtml + HtmlFormatter.notImgHtmlRegex 会剥掉 svg/text
            // 标签但**保留 text 内的文字**，结果「注」「批」字独立成段渲染成
            // 屏幕级超大字（图2 bug）。
            //
            // 修法：SVG 里有 <text> → 替换成方括号包裹的小文字（[注] / [批]），
            // 让 reader 能展现「这里是注释徽标」语义但不破坏 layout；
            // SVG 里啥文本都没（纯几何 path 图标）→ 整个移除，避免空 SVG 漏出。
            //
            // 后续 Phase 2 可改成专用 footnote token + Compose 层弹窗交互（图3 友商效果）。
            val texts = svg.select("text")
            if (texts.isNotEmpty()) {
                val joined = texts.joinToString("") { it.text() }.trim()
                if (joined.isNotBlank()) {
                    val short = joined.take(4)  // 限长 4 字符够覆盖 注/批/作/序 这种
                    svg.replaceWith(
                        org.jsoup.nodes.TextNode("[$short]"),
                    )
                    return@forEach
                }
            }
            svg.remove()
        }
        // Resolve relative image paths
        body.select("img").forEach { img ->
            val src = img.attr("src").trim()
            if (src.isNotEmpty()) {
                try {
                    val resolved = URLDecoder.decode(URI(res.href).resolve(src).toString(), "UTF-8")
                    img.attr("src", resolved)
                } catch (_: Exception) {}
            }
        }

        AppLog.debug("EpubParser", "parseBody href=${res.href} imgs=${body.select("img").size} html=${body.outerHtml().take(300)}")

        // ── Fragment 切割（DOM walk，替代脆弱的字符串切割） ──
        //
        // 老实现用 body.outerHtml() + substringBefore/After 来切 fragment 边界：
        //   - 拿 startFragment 元素的 outerHtml 第一行作为 anchor 字符串
        //   - 在整个 body html 中找该字符串切前/后
        // 问题：当 anchor 字符串不唯一（同 class/同标签前缀多次出现），或者元素本身
        // 没有换行（一行内 inline），切割会**错位或完全失败**。失败时整段 html
        // 原样返回 → 同一 xhtml 内有多个 fragment 章节时，**每个都显示整文相同内容**
        // —— 用户截图 13/14 报告「同一章不同节都是同一内容 + 反复加载」的根因。
        //
        // 新实现：基于 body 的直接子节点边界，按 DOM 文档顺序裁切：
        //   1. 把 fragment id 元素往上 climb 到 body 的直接子节点（"anchor child"）
        //   2. 收集 [startAnchor .. endAnchor) 之间的子节点，深拷贝到新 body
        //   3. fragment 没找到时保守返回整 body（不报错）—— 个别 EPUB 把 anchor 放在
        //      <a name="..."> 而不是 id，与 getElementById 不匹配，宁可显示重复内容
        //      也好过显示空白
        return sliceBodyByFragments(body, startFragment, endFragment)
    }

    /**
     * 按 fragment id 裁切 body 的子节点范围。详见 [parseBody] 的注释。
     */
    private fun sliceBodyByFragments(
        body: org.jsoup.nodes.Element,
        startFragment: String?,
        endFragment: String?,
    ): org.jsoup.nodes.Element {
        if (startFragment.isNullOrBlank() && endFragment.isNullOrBlank()) return body
        val startEl = startFragment?.takeIf { it.isNotBlank() }?.let { body.getElementById(it) }
        val endEl = endFragment?.takeIf { it.isNotBlank() && it != startFragment }
            ?.let { body.getElementById(it) }
        // 两个 fragment 都解析失败 → 保守返回整 body
        if (startEl == null && endEl == null && (startFragment != null || endFragment != null)) {
            return body
        }
        val startAnchor = startEl?.let { ancestorChildOf(body, it) }
        val endAnchor = endEl?.let { ancestorChildOf(body, it) }
        val newBody = org.jsoup.nodes.Element("body")
        // copy body 上的 class / lang 等属性，避免下游 CSS 失配
        for (attr in body.attributes()) newBody.attr(attr.key, attr.value)

        var include = (startAnchor == null)
        for (child in body.children()) {
            if (!include && child === startAnchor) include = true
            if (include && child === endAnchor) break
            if (include) newBody.appendChild(child.clone())
        }
        // 如果 newBody 是空的（startAnchor 在树深处但 climb 没找到合法子节点），
        // 回退到 body 整段；避免章节渲染成空白。
        return if (newBody.children().isEmpty()) body else newBody
    }

    /** 把 [el] 沿 parent 链 climb 到 [body] 的直接子节点；找不到返回 null。 */
    private fun ancestorChildOf(
        body: org.jsoup.nodes.Element,
        el: org.jsoup.nodes.Element,
    ): org.jsoup.nodes.Element? {
        var cur: org.jsoup.nodes.Element? = el
        while (cur != null && cur.parent() !== body) cur = cur.parent()
        return cur
    }

    private fun processContent(
        elements: org.jsoup.select.Elements,
        context: Context, uri: Uri, chapterHref: String,
        book: me.ag2s.epublib.domain.EpubBook? = null,
    ): String {
        sanitizeAndRewriteImages(elements, context, uri, chapterHref, book)
        return formatKeepImg(elements.outerHtml())
    }

    /**
     * 新「自解析 HTML」路径 —— 输出结构化 [StructuredChapterContent]（段落 / 标题 / 图片），
     * 不再走 [formatKeepImg] 那套把 `<p>` / `<h1>` 全压成 `\n` 丢失语义的纯文本路径。
     *
     * 与 [readChapter] 共享 [parseBody] + [sanitizeAndRewriteImages] 前置流水（href 解析、
     * SVG 转 img、ruby 注音内联、img 路径改写到 `file://` 缓存），只在最后一步**叉路**：
     * 老路径 → `formatKeepImg(elements.outerHtml())`；新路径 → 结构化遍历输出 [ChapterBlock]。
     *
     * **不复用 [readCachedChapter] / [writeCachedChapter] 的纯文本磁盘缓存** —— 那条缓存
     * 的格式是 [formatKeepImg] 的产物（HTML 标签已剥光只剩文本 + `<img>`），无法反推
     * 段落 / 标题语义。本方法走纯解析（每次 jsoup 走一遍），如未来出性能瓶颈可单独
     * 加结构化缓存（序列化 [StructuredChapterContent] 到磁盘）。
     */
    fun readChapterStructured(
        context: Context, uri: Uri, chapter: BookChapter,
    ): StructuredChapterContent {
        val targetHref = chapter.url.substringBeforeLast("#")
        if (targetHref.isEmpty()) return StructuredChapterContent(emptyList())

        return withEpubBook(context, uri) { book ->
            val contents = book.contents ?: return@withEpubBook StructuredChapterContent(emptyList())
            val nextHref = chapter.nextUrl?.substringBeforeLast("#")
            val startFragment = chapter.url.substringAfter("#", "").takeIf { it.isNotEmpty() }
            val endFragment = chapter.nextUrl?.substringAfter("#", "")?.takeIf { it.isNotEmpty() }

            val elements = org.jsoup.select.Elements()
            val startIdx = hrefIndexFor(uri)?.get(targetHref) ?: contents.indexOfFirst { it.href == targetHref }
            if (startIdx < 0) return@withEpubBook StructuredChapterContent(emptyList())

            elements.add(parseBody(contents[startIdx], startFragment, endFragment.takeIf { contents[startIdx].href == nextHref }))
            if (nextHref == null || contents[startIdx].href != nextHref) {
                for (i in (startIdx + 1) until contents.size) {
                    val res = contents[i]
                    if (nextHref != null && res.href == nextHref) {
                        if (endFragment != null) elements.add(parseBody(res, null, endFragment))
                        break
                    }
                    elements.add(parseBody(res, null, null))
                }
            }

            sanitizeAndRewriteImages(elements, context, uri, targetHref, book)
            // 多个 body （跨 XHTML 文件拼接）—— 把每个 body 的结构化结果按顺序合并
            val allBlocks = ArrayList<ChapterBlock>()
            elements.forEach { body ->
                allBlocks.addAll(EpubHtmlStructurer.structuredFromBody(body).blocks)
            }
            StructuredChapterContent(allBlocks)
        } ?: StructuredChapterContent(emptyList())
    }

    /**
     * DOM 净化 + 图片资源解析（共用 helper） —— [processContent] 和 [readChapterStructured]
     * 都依赖这套预处理：
     *
     *   1. 移除 `<title>` / `display:none` 节点（不展示给读者）
     *   2. Ruby 注音内联化：`<ruby>八奈見<rt>やなみ</rt></ruby>` → `八奈見（やなみ）`
     *      （Phase 1 简化方案；Phase 2 会升级为上方小字 ruby 渲染。详见保留的旧实现注释）
     *   3. 同一章重复 cover img 去重（多 OPF item 都标了 cover 的极端 case）
     *   4. `<img src>` 改写为 `file://` 绝对路径，指向 epub_chapters_v3 缓存里抽出的图片
     *
     * 该方法**原地修改** [elements]（jsoup DOM 是可变树）。调用方拿到的 elements 状态
     * 已经净化完毕，可以直接走老路径 outerHtml 或新路径 [EpubHtmlStructurer]。
     */
    private fun sanitizeAndRewriteImages(
        elements: org.jsoup.select.Elements,
        context: Context, uri: Uri, chapterHref: String,
        book: me.ag2s.epublib.domain.EpubBook? = null,
    ) {
        elements.select("title").remove()
        elements.select("[style*=display:none]").remove()
        // ── 振假名 Phase 1：保留 rt 文字（之前直接 select("rp, rt").remove() 把读音
        //    一律删掉，日文 EPUB 用户看不到「八奈見(やなみ)」这种 ふりがな 注音）。
        //
        //    Ruby DOM 结构有多种：
        //      <ruby>八奈見<rt>やなみ</rt></ruby>
        //      <ruby><rb>八奈見</rb><rt>やなみ</rt></ruby>
        //      <ruby>八<rt>や</rt>奈<rt>な</rt>見<rt>み</rt></ruby>  ← 字符级拆分
        //    通用算法：先把所有 rt 文字 join 起来，再把 rp（括号占位符 () ）删掉避免
        //    双层括号，剩下的 ruby 内容就是「底字」。底字 + 全角括号 + rt = 内联输出。
        //
        //    Phase 2 PageLayout 会识别 (?<base>..)(?<rt>..) 这种内联标记并升级为
        //    上方 ruby 小字渲染（参考图1 静读天下日文）。Phase 1 先让 ruby 文字
        //    至少出现，比之前完全不显示强。
        elements.select("ruby").forEach { ruby ->
            ruby.select("rp").remove()
            val rts = ruby.select("rt").joinToString("") { it.text() }
            ruby.select("rt").remove()
            val baseText = ruby.text()  // 剩下的全部就是底字（去掉 rt/rp 后）
            val joined = if (rts.isNotBlank() && baseText.isNotBlank()) {
                "$baseText（$rts）"
            } else {
                baseText
            }
            ruby.replaceWith(org.jsoup.nodes.TextNode(joined))
        }
        // 兜底：游离的 rp/rt（不在 ruby 内的孤儿节点）依然移除
        elements.select("rp, rt").remove()
        elements.select("img[$COVER_IMAGE_MARKER]").forEachIndexed { i, img ->
            if (i > 0) img.remove()
        }
        val imgEls = elements.select("img")
        val imgT0 = System.currentTimeMillis()
        AppLog.debug("EpubParser",
            "sanitizeAndRewriteImages href=$chapterHref imgs=${imgEls.size} availMem=${availMb()}MB")
        imgEls.forEach { img ->
            val src = img.attr("src")
            if (src.isBlank()) return@forEach
            val cached = extractImageFromBook(context, uri, src, book)
            if (cached != null) {
                img.attr("src", "file://${cached.absolutePath}")
            } else {
                img.removeAttr("src")
            }
            img.removeAttr("style"); img.removeAttr("width"); img.removeAttr("height"); img.removeAttr(COVER_IMAGE_MARKER)
        }
        AppLog.debug("EpubParser",
            "sanitizeAndRewriteImages href=$chapterHref imgs done in ${System.currentTimeMillis() - imgT0}ms availMem=${availMb()}MB")
    }

    private fun formatKeepImg(html: String?): String {
        html ?: return ""
        val keepImgHtml = formatHtml(html, notImgHtmlRegex)
        val builder = StringBuilder(keepImgHtml.length)
        var appendPos = 0
        for (match in formatImageRegex.findAll(keepImgHtml)) {
            builder.append(keepImgHtml, appendPos, match.range.first)
            val src = match.groups[1]?.value ?: match.groups[2]?.value.orEmpty()
            if (src.isNotBlank()) {
                builder.append("<img src=\"").append(src).append("\">")
            }
            appendPos = match.range.last + 1
        }
        if (appendPos < keepImgHtml.length) {
            builder.append(keepImgHtml, appendPos, keepImgHtml.length)
        }
        return builder.toString()
    }

    private fun formatHtml(html: String?, otherRegex: Regex): String {
        html ?: return ""
        val text = html
            .replace(commentRegex, "")
            .replace(nbspRegex, " ")
            .replace(espRegex, " ")
            .replace(noPrintRegex, "")
            .replace(blockOpenHtmlRegex, "")
            .replace(blockCloseHtmlRegex, "\n")
            .replace(otherRegex, "")
            .lines()
            .joinToString("\n") { line -> line.trim() }
            .trim()
        return mergeVerticalTextRuns(text)
    }

    private fun mergeVerticalTextRuns(text: String): String {
        val lines = text.lines()
        val result = ArrayList<String>(lines.size)
        val run = ArrayList<String>()

        fun flushRun() {
            if (run.size >= 3) {
                result.add(run.joinToString("") { it.trim() })
            } else {
                result.addAll(run)
            }
            run.clear()
        }

        for (line in lines) {
            val trimmed = line.trim()
            if (isSingleDisplayCharLine(trimmed)) {
                run.add(trimmed)
            } else {
                flushRun()
                result.add(line)
            }
        }
        flushRun()
        return result.joinToString("\n").trim()
    }

    private fun isSingleDisplayCharLine(line: String): Boolean {
        if (line.isBlank() || line.startsWith("<img", ignoreCase = true)) return false
        return line.codePointCount(0, line.length) == 1
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

    private fun extractImageFromBook(
        context: Context, epubUri: Uri, imagePath: String,
        book: me.ag2s.epublib.domain.EpubBook? = null,
    ): File? {
        val normalized = imagePath.replace('\\', '/')
        val cacheDir = File(context.cacheDir, "epub_images/${epubUri.hashCode()}")
        val cachedFile = File(cacheDir, normalized.replace('/', '_'))
        if (cachedFile.exists()) return cachedFile

        if (normalized == COVER_IMAGE_SENTINEL && book?.coverImage != null) {
            cacheDir.mkdirs()
            writeImageCompressed(book.coverImage.data, cachedFile)
            return cachedFile
        }

        // Try from provided book instance first (fast, no re-open)
        if (book != null) {
            val resource = book.resources?.getByHref(normalized)
                ?: book.resources?.getByHref(java.net.URLDecoder.decode(normalized, "UTF-8"))
            if (resource != null) {
                cacheDir.mkdirs()
                writeImageCompressed(resource.data, cachedFile)
                return cachedFile
            }
        }

        // Fallback: open a new book instance
        return withEpubBook(context, epubUri) { b ->
            if (normalized == COVER_IMAGE_SENTINEL && b.coverImage != null) {
                cacheDir.mkdirs()
                writeImageCompressed(b.coverImage.data, cachedFile)
                return@withEpubBook cachedFile
            }
            val res = b.resources?.getByHref(normalized)
                ?: b.resources?.getByHref(java.net.URLDecoder.decode(normalized, "UTF-8"))
            if (res != null) { cacheDir.mkdirs(); writeImageCompressed(res.data, cachedFile); cachedFile }
            else null
        }
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
        withEpubBook(context, uri) { book ->
            for (ch in uncached) {
                val content = readChapterFromBook(book, ch, context, uri)
                if (content.isNotEmpty()) writeCachedChapter(context, uri, ch.url, content)
            }
        }
        AppLog.info(
            "EpubParser",
            "preCacheChapters done: cached=${uncached.size} elapsed=${System.currentTimeMillis() - t0}ms",
        )
    }

    private fun readChapterFromBook(
        book: EpubBook, chapter: BookChapter, context: Context, uri: Uri,
    ): String {
        val targetHref = chapter.url.substringBeforeLast("#")
        val contents = book.contents ?: return ""
        val nextHref = chapter.nextUrl?.substringBeforeLast("#")
        val startFragment = chapter.url.substringAfter("#", "").takeIf { it.isNotEmpty() }
        val endFragment = chapter.nextUrl?.substringAfter("#", "")?.takeIf { it.isNotEmpty() }
        val elements = org.jsoup.select.Elements()
        var found = false
        for (res in contents) {
            if (!found) {
                if (res.href != targetHref) continue
                found = true
                elements.add(parseBody(res, startFragment, endFragment.takeIf { res.href == nextHref }))
                if (nextHref != null && res.href == nextHref) break
                continue
            }
            if (nextHref == null || res.href != nextHref) {
                elements.add(parseBody(res, null, null))
            } else {
                if (endFragment != null) elements.add(parseBody(res, null, endFragment))
                break
            }
        }
        return processContent(elements, context, uri, targetHref, book)
    }

    // ── Per-URI EpubBook 缓存 + 锁（替代原单例 cachedBook + @Synchronized 大锁） ──
    //
    // 原架构：单一 cachedBook + 整个 EpubParser 全局 @Synchronized → 两本 EPUB 并发时
    // 必然串行；第二本进来 invalidate 第一本 PFD，互相挡到死锁式黑屏（已复现）。
    //
    // 新架构：cache[uriStr] → BookCache，locks[uriStr] → Any() 各自一把锁。不同 uri
    // 真并发；同一 uri 串行（保护 cachedBook / PFD 一致性）。LRU 限 [MAX_CACHED_BOOKS]
    // 本，按 atime evict，PFD 一并 close。EBADF retry 路径保留（cache 被 evict /
    // releaseCache 后用户仍持有 stale book reference 的边界情况）。

    private const val MAX_CACHED_BOOKS = 3

    private class BookCache(
        val pfd: ParcelFileDescriptor,
        val book: EpubBook,
        val hrefIndexMap: Map<String, Int>?,
        @Volatile var atime: Long = System.currentTimeMillis(),
    )

    private val cache = java.util.concurrent.ConcurrentHashMap<String, BookCache>()
    private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    private fun lockFor(uriStr: String): Any = locks.computeIfAbsent(uriStr) { Any() }

    private fun hrefIndexFor(uri: Uri): Map<String, Int>? = cache[uri.toString()]?.hrefIndexMap

    private fun <T> withEpubBook(context: Context, uri: Uri, block: (EpubBook) -> T): T? =
        withEpubBookInternal(context, uri, block, retried = false)

    private fun <T> withEpubBookInternal(
        context: Context, uri: Uri, block: (EpubBook) -> T, retried: Boolean,
    ): T? {
        val uriStr = uri.toString()
        val lock = lockFor(uriStr)
        return synchronized(lock) {
            val bookCache = cache[uriStr] ?: openFreshBook(context, uri)?.also { fresh ->
                cache[uriStr] = fresh
                evictIfOver(except = uriStr)
            } ?: return@synchronized null

            bookCache.atime = System.currentTimeMillis()
            try {
                block(bookCache.book)
            } catch (e: Exception) {
                val msg = e.message ?: ""
                // EBADF = stale file descriptor。可能因 releaseCache 关 PFD、或 LRU evict
                // 后 stale book 引用被使用。invalidate 当前 uri + 再尝试一次（kotlin
                // synchronized 用 Java monitor 可重入，递归 acquire OK）。
                if (!retried && (msg.contains("EBADF") || msg.contains("Bad file descriptor"))) {
                    AppLog.warn("EpubParser", "Stale fd for $uriStr, retrying once")
                    invalidateCache(uriStr)
                    withEpubBookInternal(context, uri, block, retried = true)
                } else {
                    AppLog.error("EpubParser", "EpubBook op failed $uriStr: ${e.message}")
                    null
                }
            }
        }
    }

    private fun openFreshBook(context: Context, uri: Uri): BookCache? {
        return try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
            val zipFile = AndroidZipFile(pfd, uri.lastPathSegment ?: "book.epub")
            val newBook = EpubReader().readEpubLazy(zipFile, "utf-8")
            val hrefIndex = newBook.contents?.mapIndexed { i, res -> res.href to i }?.toMap()
            BookCache(pfd = pfd, book = newBook, hrefIndexMap = hrefIndex)
        } catch (e: Exception) {
            AppLog.error("EpubParser", "Failed to open EPUB: ${e.message}")
            null
        }
    }

    /**
     * 把当前 uri 的 cache 失效（关 PFD）。已持有锁的 caller 调用。其他线程持 stale
     * book 引用会在下次 IO 时 EBADF，由各自 withEpubBook 的 retry 路径吸收。
     */
    private fun invalidateCache(uriStr: String) {
        cache.remove(uriStr)?.let { try { it.pfd.close() } catch (_: Exception) {} }
    }

    /**
     * LRU eviction：cache.size 超过 [MAX_CACHED_BOOKS] 时，删除最旧的（按 atime），
     * `except` 通常是刚刚 put 的当前 uri 避免自删。竞态接受：被删的 PFD 可能正被
     * 其他线程用，对方下次 IO 拿 EBADF 走 retry 重开，功能正确仅多一次 IO。
     */
    private fun evictIfOver(except: String) {
        if (cache.size <= MAX_CACHED_BOOKS) return
        val eldest = cache.entries
            .filter { it.key != except }
            .minByOrNull { it.value.atime } ?: return
        cache.remove(eldest.key)?.let { try { it.pfd.close() } catch (_: Exception) {} }
        AppLog.info("EpubParser", "Evicted LRU cache: ${eldest.key.takeLast(60)}")
    }

    /** Release all cached books (call when reader closes globally). */
    fun releaseCache() {
        val snapshot = cache.values.toList()
        cache.clear()
        locks.clear()
        snapshot.forEach { try { it.pfd.close() } catch (_: Exception) {} }
    }
}
