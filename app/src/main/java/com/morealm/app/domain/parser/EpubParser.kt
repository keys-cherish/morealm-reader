package com.morealm.app.domain.parser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.morealm.app.domain.entity.BookChapter
import com.morealm.app.core.log.AppLog
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

    private val charset: Charset = Charsets.UTF_8

    // ── Import (metadata + cover) ────────────────────────

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
            )
            val coverPath = extractCoverFromBook(context, uri, book)
            EpubImportResult(metadata, coverPath)
        } ?: EpubImportResult()
    }

    fun extractCover(context: Context, uri: Uri): String? {
        return withEpubBook(context, uri) { book -> extractCoverFromBook(context, uri, book) }
    }

    private fun extractCoverFromBook(context: Context, uri: Uri, book: EpubBook): String? {
        val coverImage = book.coverImage ?: return null
        return try {
            val cacheDir = File(context.cacheDir, "epub_covers/${uri.hashCode()}")
            cacheDir.mkdirs()
            val file = File(cacheDir, "cover.jpg")
            if (file.exists()) return file.absolutePath
            coverImage.inputStream.use { input ->
                val bitmap = BitmapFactory.decodeStream(input) ?: return null
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
                bitmap.recycle()
            }
            file.absolutePath
        } catch (e: Exception) {
            AppLog.warn("EpubParser", "Cover extraction failed: ${e.message}")
            null
        }
    }

    // ── Chapter list ─────────────────────────────────────

    fun parseChapters(context: Context, uri: Uri): List<BookChapter> {
        return withEpubBook(context, uri) { book -> buildChapterList(uri.toString(), book) }
            ?: emptyList()
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
        for (ref in refs) {
            if (ref.resource != null) {
                chapters.add(BookChapter(
                    id = "", bookId = bookId, index = 0,
                    title = ref.title.orEmpty().ifBlank { "未命名章节" },
                    url = ref.completeHref,
                ))
            }
            if (!ref.children.isNullOrEmpty()) {
                parseTocRefs(bookId, ref.children, chapters)
            }
        }
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

        // Check disk cache
        val cached = readCachedChapter(context, uri, targetHref)
        if (cached != null) return cached

        // Read via epublib random access
        val content = withEpubBook(context, uri) { book ->
            val contents = book.contents ?: return@withEpubBook ""
            val nextHref = chapter.nextUrl?.substringBeforeLast("#")
            val startFragment = chapter.url.substringAfter("#", "").takeIf { it.isNotEmpty() }

            val elements = org.jsoup.select.Elements()
            // Use href index map for O(1) start lookup instead of linear scan
            val startIdx = hrefIndexMap?.get(targetHref) ?: contents.indexOfFirst { it.href == targetHref }
            if (startIdx < 0) return@withEpubBook ""

            elements.add(parseBody(contents[startIdx], startFragment, null))
            if (nextHref == null || contents[startIdx].href != nextHref) {
                for (i in (startIdx + 1) until contents.size) {
                    val res = contents[i]
                    if (nextHref != null && res.href == nextHref) break
                    elements.add(parseBody(res, null, null))
                }
            }
            processContent(elements, context, uri, targetHref, book)
        } ?: ""

        if (content.isNotEmpty()) writeCachedChapter(context, uri, targetHref, content)
        return content
    }
    // ── Body parsing & image rewriting ─────────────────

    private fun parseBody(res: Resource, startFragment: String?, endFragment: String?): org.jsoup.nodes.Element {
        // Don't short-circuit cover pages — let them go through normal parsing
        // so images get properly extracted and resolved
        var body = Jsoup.parse(String(res.data, charset)).body()
        body.select("script, style").remove()

        var html = body.outerHtml()
        if (!startFragment.isNullOrBlank()) {
            body.getElementById(startFragment)?.outerHtml()?.let { tag ->
                val tagStart = tag.substringBefore("\n")
                html = tagStart + html.substringAfter(tagStart)
            }
        }
        if (!endFragment.isNullOrBlank() && endFragment != startFragment) {
            body.getElementById(endFragment)?.outerHtml()?.let { tag ->
                val tagStart = tag.substringBefore("\n")
                html = html.substringBefore(tagStart)
            }
        }
        if (html != body.outerHtml()) body = Jsoup.parse(html).body()

        // Convert SVG <image> to <img>
        body.select("image").forEach { el ->
            el.tagName("img")
            el.attr("src", el.attr("xlink:href"))
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
        return body
    }

    private fun processContent(
        elements: org.jsoup.select.Elements,
        context: Context, uri: Uri, chapterHref: String,
        book: me.ag2s.epublib.domain.EpubBook? = null,
    ): String {
        elements.select("title").remove()
        elements.select("[style*=display:none]").remove()
        // Strip ruby annotations (rp/rt) for cleaner text
        elements.select("rp, rt").remove()
        elements.select("img").forEach { img ->
            val src = img.attr("src")
            if (src.isBlank()) return@forEach
            val cached = extractImageFromBook(context, uri, src, book)
            if (cached != null) {
                img.attr("src", "file://${cached.absolutePath}")
            } else {
                img.removeAttr("src")
            }
            img.removeAttr("style"); img.removeAttr("width"); img.removeAttr("height")
        }
        var html = elements.outerHtml()
        // Unescape HTML entities that may have been double-encoded
        if (html.contains("&amp;") || html.contains("&lt;")) {
            html = html.replace("&lt;img", "&lt; img")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
        }
        return html
    }

    // ── Image extraction (reuses book instance when available) ──

    private fun extractImageFromBook(
        context: Context, epubUri: Uri, imagePath: String,
        book: me.ag2s.epublib.domain.EpubBook? = null,
    ): File? {
        val normalized = imagePath.replace('\\', '/')
        val cacheDir = File(context.cacheDir, "epub_images/${epubUri.hashCode()}")
        val cachedFile = File(cacheDir, normalized.replace('/', '_'))
        if (cachedFile.exists()) return cachedFile

        // Try from provided book instance first (fast, no re-open)
        if (book != null) {
            val resource = book.resources?.getByHref(normalized)
                ?: book.resources?.getByHref(java.net.URLDecoder.decode(normalized, "UTF-8"))
            if (resource != null) {
                cacheDir.mkdirs()
                cachedFile.writeBytes(resource.data)
                return cachedFile
            }
        }

        // Fallback: open a new book instance
        return withEpubBook(context, epubUri) { b ->
            val res = b.resources?.getByHref(normalized)
                ?: b.resources?.getByHref(java.net.URLDecoder.decode(normalized, "UTF-8"))
            if (res != null) { cacheDir.mkdirs(); cachedFile.writeBytes(res.data); cachedFile }
            else null
        }
    }

    fun clearImageCache(context: Context, epubUri: Uri) {
        File(context.cacheDir, "epub_images/${epubUri.hashCode()}").deleteRecursively()
        File(context.cacheDir, "epub_covers/${epubUri.hashCode()}").deleteRecursively()
    }

    fun clearCache(context: Context, epubUri: Uri) {
        clearImageCache(context, epubUri)
        File(context.cacheDir, "epub_chapters/${epubUri.hashCode()}").deleteRecursively()
    }

    // ── Chapter cache ────────────────────────────────────

    private fun readCachedChapter(context: Context, epubUri: Uri, path: String): String? {
        val f = chapterCacheFile(context, epubUri, path)
        return if (f.exists()) f.readText() else null
    }

    private fun writeCachedChapter(context: Context, epubUri: Uri, path: String, content: String) {
        try { chapterCacheFile(context, epubUri, path).apply { parentFile?.mkdirs(); writeText(content) } }
        catch (_: Exception) {}
    }

    private fun chapterCacheFile(context: Context, epubUri: Uri, path: String): File =
        File(context.cacheDir, "epub_chapters/${epubUri.hashCode()}/${path.replace('/', '_')}.html")

    /**
     * Pre-cache nearby chapters only (not the entire book).
     * For large EPUBs (1GB+), caching all chapters at once would be too slow
     * and consume excessive disk space. Instead, cache at most 20 chapters
     * around the current reading position. The reader calls this again
     * when the user navigates to a new area.
     */
    fun preCacheChapters(context: Context, uri: Uri, chapters: List<BookChapter>, aroundIndex: Int = 0) {
        val start = (aroundIndex - 5).coerceAtLeast(0)
        val end = (aroundIndex + 20).coerceAtMost(chapters.size)
        val nearby = chapters.subList(start, end)
        val uncached = nearby.filter { ch ->
            ch.url.isNotEmpty() && !chapterCacheFile(context, uri, ch.url.substringBeforeLast("#")).exists()
        }
        if (uncached.isEmpty()) return
        withEpubBook(context, uri) { book ->
            for (ch in uncached) {
                val href = ch.url.substringBeforeLast("#")
                val content = readChapterFromBook(book, ch, context, uri)
                if (content.isNotEmpty()) writeCachedChapter(context, uri, href, content)
            }
        }
    }

    private fun readChapterFromBook(
        book: EpubBook, chapter: BookChapter, context: Context, uri: Uri,
    ): String {
        val targetHref = chapter.url.substringBeforeLast("#")
        val contents = book.contents ?: return ""
        val nextHref = chapter.nextUrl?.substringBeforeLast("#")
        val startFragment = chapter.url.substringAfter("#", "").takeIf { it.isNotEmpty() }
        val elements = org.jsoup.select.Elements()
        var found = false
        for (res in contents) {
            if (!found) {
                if (res.href != targetHref) continue
                found = true
                elements.add(parseBody(res, startFragment, null))
                if (nextHref != null && res.href == nextHref) break
                continue
            }
            if (nextHref == null || res.href != nextHref) {
                elements.add(parseBody(res, null, null))
            } else break
        }
        return processContent(elements, context, uri, targetHref, book)
    }

    // ── Cached EpubBook instance (like Legado's eFile pattern) ──

    private var cachedUri: String? = null
    private var cachedBook: EpubBook? = null
    private var cachedPfd: ParcelFileDescriptor? = null
    // href → index lookup for O(1) content resource finding
    private var hrefIndexMap: Map<String, Int>? = null

    @Synchronized
    private fun <T> withEpubBook(context: Context, uri: Uri, block: (EpubBook) -> T): T? {
        return withEpubBookInternal(context, uri, block, retried = false)
    }

    private fun <T> withEpubBookInternal(
        context: Context, uri: Uri, block: (EpubBook) -> T, retried: Boolean,
    ): T? {
        val uriStr = uri.toString()
        // Reuse cached instance if same book
        val book = if (uriStr == cachedUri && cachedBook != null) {
            cachedBook!!
        } else {
            openFreshBook(context, uri) ?: return null
        }
        return try {
            block(book)
        } catch (e: Exception) {
            val msg = e.message ?: ""
            // EBADF = stale file descriptor (PFD was closed by releaseCache).
            // Invalidate cache and retry once with a fresh PFD.
            if (!retried && (msg.contains("EBADF") || msg.contains("Bad file descriptor"))) {
                AppLog.warn("EpubParser", "Stale fd detected, re-opening: ${e.message}")
                invalidateCacheLocked()
                return withEpubBookInternal(context, uri, block, retried = true)
            }
            AppLog.error("EpubParser", "EpubBook operation failed: ${e.message}")
            null
        }
    }

    private fun openFreshBook(context: Context, uri: Uri): EpubBook? {
        // Close previous
        invalidateCacheLocked()
        return try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
            val zipFile = AndroidZipFile(pfd, uri.lastPathSegment ?: "book.epub")
            val newBook = EpubReader().readEpubLazy(zipFile, "utf-8")
            cachedPfd = pfd
            cachedBook = newBook
            cachedUri = uri.toString()
            // Build href→index map for O(1) content lookup
            hrefIndexMap = newBook.contents?.mapIndexed { i, res -> res.href to i }?.toMap()
            newBook
        } catch (e: Exception) {
            AppLog.error("EpubParser", "Failed to open EPUB: ${e.message}")
            null
        }
    }

    private fun invalidateCacheLocked() {
        try { cachedPfd?.close() } catch (_: Exception) {}
        cachedPfd = null
        cachedBook = null
        cachedUri = null
        hrefIndexMap = null
    }

    /** Release cached book (call when reader closes) */
    @Synchronized
    fun releaseCache() {
        invalidateCacheLocked()
    }
}
