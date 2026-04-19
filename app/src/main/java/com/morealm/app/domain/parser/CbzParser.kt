package com.morealm.app.domain.parser

import android.content.Context
import android.net.Uri
import com.morealm.app.domain.entity.BookChapter
import java.io.File
import java.util.zip.ZipInputStream

/**
 * CBZ (Comic Book ZIP) parser.
 * Each image in the ZIP becomes a "chapter" (page).
 * Images are extracted to cache and displayed in the WebView.
 */
object CbzParser {

    private val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")

    fun parseChapters(context: Context, uri: Uri): List<BookChapter> {
        val bookId = uri.toString()
        val pages = mutableListOf<String>()

        context.contentResolver.openInputStream(uri)?.use { stream ->
            val zip = ZipInputStream(stream)
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                val ext = name.substringAfterLast('.', "").lowercase()
                if (!entry.isDirectory && ext in imageExtensions) {
                    pages.add(name)
                }
                entry = zip.nextEntry
            }
        }

        // Sort pages naturally (page001, page002, ...)
        pages.sortWith(NaturalStringComparator)

        return pages.mapIndexed { index, path ->
            val pageName = path.substringAfterLast('/').substringBeforeLast('.')
            BookChapter(
                id = "${bookId}_$index",
                bookId = bookId,
                index = index,
                title = "第${index + 1}页 $pageName",
                url = path,
            )
        }
    }
// APPEND_MARKER

    fun readChapter(context: Context, uri: Uri, chapter: BookChapter): String {
        val imagePath = chapter.url
        if (imagePath.isEmpty()) return ""

        val cachedFile = getOrExtractImage(context, uri, imagePath)
        return if (cachedFile != null) {
            """<div style="text-align:center;padding:0;margin:0">
<img src="file://${cachedFile.absolutePath}" style="max-width:100%;max-height:100vh;object-fit:contain;margin:0 auto;display:block">
</div>"""
        } else {
            "<p>图片加载失败: $imagePath</p>"
        }
    }

    private fun getOrExtractImage(context: Context, cbzUri: Uri, imagePath: String): File? {
        val cacheDir = File(context.cacheDir, "cbz_images/${cbzUri.hashCode()}")
        val cachedFile = File(cacheDir, imagePath.replace('/', '_').replace('\\', '_'))

        if (cachedFile.exists()) return cachedFile

        try {
            context.contentResolver.openInputStream(cbzUri)?.use { stream ->
                val zip = ZipInputStream(stream)
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == imagePath) {
                        cacheDir.mkdirs()
                        cachedFile.outputStream().use { out -> zip.copyTo(out, 8192) }
                        return cachedFile
                    }
                    entry = zip.nextEntry
                }
            }
        } catch (_: Exception) {}
        return null
    }

    /** Pre-extract all images for faster page turns */
    fun preCacheImages(context: Context, uri: Uri, chapters: List<BookChapter>) {
        val cacheDir = File(context.cacheDir, "cbz_images/${uri.hashCode()}")
        val uncached = chapters.filter { ch ->
            val f = File(cacheDir, ch.url.replace('/', '_').replace('\\', '_'))
            !f.exists()
        }.map { it.url }.toSet()
        if (uncached.isEmpty()) return

        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val zip = ZipInputStream(stream)
                var entry = zip.nextEntry
                var found = 0
                while (entry != null) {
                    if (entry.name in uncached) {
                        cacheDir.mkdirs()
                        val f = File(cacheDir, entry.name.replace('/', '_').replace('\\', '_'))
                        f.outputStream().use { out -> zip.copyTo(out, 8192) }
                        found++
                        if (found >= uncached.size) break
                    }
                    entry = zip.nextEntry
                }
            }
        } catch (_: Exception) {}
    }

    /** Natural string comparator for sorting filenames */
    private object NaturalStringComparator : Comparator<String> {
        private val numPattern = Regex("(\\d+)")
        override fun compare(a: String, b: String): Int {
            val partsA = numPattern.split(a)
            val partsB = numPattern.split(b)
            val numsA = numPattern.findAll(a).map { it.value }.toList()
            val numsB = numPattern.findAll(b).map { it.value }.toList()
            val max = maxOf(partsA.size + numsA.size, partsB.size + numsB.size)
            var ia = 0; var na = 0; var ib = 0; var nb = 0
            for (i in 0 until max) {
                if (i % 2 == 1) {
                    val numA = numsA.getOrNull(na)?.toLongOrNull() ?: -1L
                    val numB = numsB.getOrNull(nb)?.toLongOrNull() ?: -1L
                    na++; nb++
                    val cmp = numA.compareTo(numB)
                    if (cmp != 0) return cmp
                } else {
                    val pa = partsA.getOrElse(ia) { "" }
                    val pb = partsB.getOrElse(ib) { "" }
                    ia++; ib++
                    val cmp = pa.compareTo(pb, ignoreCase = true)
                    if (cmp != 0) return cmp
                }
            }
            return a.compareTo(b, ignoreCase = true)
        }
    }
}
