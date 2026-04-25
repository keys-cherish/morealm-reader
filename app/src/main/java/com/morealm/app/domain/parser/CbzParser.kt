package com.morealm.app.domain.parser

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.entity.BookChapter
import me.ag2s.epublib.util.zip.AndroidZipFile
import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import me.zhanghai.android.libarchive.ArchiveException
import java.io.File
import java.io.FileDescriptor
import java.io.InterruptedIOException
import java.nio.ByteBuffer

/**
 * Comic archive parser.
 *
 * ZIP/CBZ uses AndroidZipFile for central-directory random access. RAR/CBR/7Z
 * uses libarchive for broad compatibility, while still extracting only the
 * requested page or a small nearby window to cache.
 */
object CbzParser {

    private const val TAG = "ComicArchiveParser"
    private const val LIBARCHIVE_BUFFER_SIZE = 64 * 1024
    private const val MAX_PRE_CACHE_PAGES = 5

    private val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "avif")
    private val zipExtensions = setOf("zip", "cbz")
    private val libArchiveExtensions = setOf("rar", "cbr", "7z")

    fun parseChapters(context: Context, uri: Uri): List<BookChapter> {
        val bookId = uri.toString()
        val entries = openArchive(context, uri).use { archive ->
            archive.listImageEntries()
        }

        return entries.mapIndexed { index, path ->
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

    private fun getOrExtractImage(context: Context, archiveUri: Uri, imagePath: String): File? {
        val cacheDir = getCacheDir(context, archiveUri)
        val cachedFile = File(cacheDir, imagePath.toCacheFileName())
        if (cachedFile.exists()) return cachedFile

        return try {
            openArchive(context, archiveUri).use { archive ->
                archive.extract(imagePath, cachedFile)
            }
            cachedFile.takeIf { it.exists() && it.length() > 0L }
        } catch (e: Exception) {
            AppLog.warn(TAG, "Failed to extract $imagePath: ${e.message}")
            null
        }
    }

    /**
     * Pre-extract nearby images only. For huge comic archives, keep this small
     * to avoid heavy disk and decode pressure.
     */
    fun preCacheImages(context: Context, uri: Uri, chapters: List<BookChapter>, aroundIndex: Int = 0) {
        if (chapters.isEmpty()) return
        val start = aroundIndex.coerceAtLeast(0)
        val end = (start + MAX_PRE_CACHE_PAGES).coerceAtMost(chapters.size)
        val cacheDir = getCacheDir(context, uri)
        val uncached = chapters.subList(start, end)
            .map { it.url }
            .filter { path -> !File(cacheDir, path.toCacheFileName()).exists() }
            .toSet()
        if (uncached.isEmpty()) return

        try {
            openArchive(context, uri).use { archive ->
                archive.extractAll(uncached, cacheDir)
            }
        } catch (e: Exception) {
            AppLog.warn(TAG, "Pre-cache failed: ${e.message}")
        }
    }

    private fun openArchive(context: Context, uri: Uri): ComicArchive {
        val name = uri.lastPathSegment ?: ""
        val ext = name.substringAfterLast('.', "").lowercase()
        return if (ext in zipExtensions) {
            ZipComicArchive(context, uri, name.ifBlank { "comic.cbz" })
        } else if (ext in libArchiveExtensions) {
            LibArchiveComicArchive(context, uri)
        } else {
            runCatching { ZipComicArchive(context, uri, name.ifBlank { "comic.zip" }) }
                .getOrElse { LibArchiveComicArchive(context, uri) }
        }
    }

    private fun getCacheDir(context: Context, uri: Uri): File =
        File(context.cacheDir, "comic_pages/${uri.hashCode()}")

    private fun String.isImageEntry(): Boolean {
        val cleanName = substringBefore('?').substringBefore('#')
        val ext = cleanName.substringAfterLast('.', "").lowercase()
        return ext in imageExtensions
    }

    private fun String.toCacheFileName(): String =
        replace('/', '_').replace('\\', '_').replace(':', '_')

    private interface ComicArchive : AutoCloseable {
        fun listImageEntries(): List<String>
        fun extract(entryName: String, outFile: File)
        fun extractAll(entryNames: Set<String>, cacheDir: File)
    }

    private class ZipComicArchive(
        context: Context,
        uri: Uri,
        name: String,
    ) : ComicArchive {
        private val pfd: ParcelFileDescriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalArgumentException("Unable to open archive: $uri")
        private val zipFile = AndroidZipFile(pfd, name)

        override fun listImageEntries(): List<String> {
            val names = mutableListOf<String>()
            val entries = zipFile.entries() ?: return emptyList()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = entry.name
                if (name.isImageEntry()) names.add(name)
            }
            names.sortWith(NaturalStringComparator)
            return names
        }

        override fun extract(entryName: String, outFile: File) {
            val entry = zipFile.getEntry(entryName) ?: return
            outFile.parentFile?.mkdirs()
            zipFile.getInputStream(entry).use { input ->
                outFile.outputStream().use { output -> input.copyTo(output, LIBARCHIVE_BUFFER_SIZE) }
            }
        }

        override fun extractAll(entryNames: Set<String>, cacheDir: File) {
            entryNames.forEach { entryName ->
                val outFile = File(cacheDir, entryName.toCacheFileName())
                if (!outFile.exists()) extract(entryName, outFile)
            }
        }

        override fun close() {
            runCatching { zipFile.close() }
        }
    }

    private class LibArchiveComicArchive(
        context: Context,
        private val uri: Uri,
    ) : ComicArchive {
        private val pfd: ParcelFileDescriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IllegalArgumentException("Unable to open archive: $uri")

        override fun listImageEntries(): List<String> = withArchive { archive ->
            val names = mutableListOf<String>()
            var entry: Long
            while (Archive.readNextHeader(archive).also { entry = it } != 0L) {
                val name = getEntryName(entry) ?: continue
                val stat = ArchiveEntry.stat(entry)
                if (!stat.isDirectory() && name.isImageEntry()) names.add(name)
            }
            names.sortWith(NaturalStringComparator)
            names
        }

        override fun extract(entryName: String, outFile: File) {
            extractAll(setOf(entryName), outFile.parentFile ?: return)
        }

        override fun extractAll(entryNames: Set<String>, cacheDir: File) {
            if (entryNames.isEmpty()) return
            withArchive { archive ->
                val pending = entryNames.toMutableSet()
                var entry = Archive.readNextHeader(archive)
                while (pending.isNotEmpty() && entry != 0L) {
                    val name = getEntryName(entry)
                    val stat = ArchiveEntry.stat(entry)
                    if (name != null && !stat.isDirectory() && name in pending) {
                        cacheDir.mkdirs()
                        val outFile = File(cacheDir, name.toCacheFileName())
                        ParcelFileDescriptor.open(
                            outFile,
                            ParcelFileDescriptor.MODE_CREATE or
                                ParcelFileDescriptor.MODE_TRUNCATE or
                                ParcelFileDescriptor.MODE_WRITE_ONLY,
                        ).use { outPfd ->
                            Archive.readDataIntoFd(archive, outPfd.fd)
                        }
                        pending.remove(name)
                    }
                    entry = Archive.readNextHeader(archive)
                }
            }
        }

        private inline fun <T> withArchive(block: (Long) -> T): T {
            val archive = openLibArchive(pfd)
            return try {
                block(archive)
            } finally {
                Archive.free(archive)
                runCatching { Os.lseek(pfd.fileDescriptor, 0L, OsConstants.SEEK_SET) }
            }
        }

        override fun close() {
            runCatching { pfd.close() }
        }

        private fun openLibArchive(pfd: ParcelFileDescriptor): Long {
            val archive = Archive.readNew()
            var successful = false
            try {
                Archive.readSupportFilterAll(archive)
                Archive.readSupportFormatAll(archive)
                Archive.readSetCallbackData(archive, pfd.fileDescriptor)
                val buffer = ByteBuffer.allocateDirect(LIBARCHIVE_BUFFER_SIZE)
                Archive.readSetReadCallback<Any>(archive) { _, fd ->
                    buffer.clear()
                    try {
                        Os.read(fd as FileDescriptor, buffer)
                    } catch (e: Exception) {
                        throw ArchiveException(Archive.ERRNO_FATAL, "Os.read", e)
                    }
                    buffer.flip()
                    buffer
                }
                Archive.readSetSkipCallback<Any>(archive) { _, fd, request ->
                    try {
                        Os.lseek(fd as FileDescriptor, request, OsConstants.SEEK_CUR)
                    } catch (e: Exception) {
                        throw ArchiveException(Archive.ERRNO_FATAL, "Os.lseek", e)
                    }
                    request
                }
                Archive.readSetSeekCallback<Any>(archive) { _, fd, offset, whence ->
                    try {
                        Os.lseek(fd as FileDescriptor, offset, whence)
                    } catch (e: Exception) {
                        throw ArchiveException(Archive.ERRNO_FATAL, "Os.lseek", e)
                    }
                }
                Archive.readOpen1(archive)
                successful = true
                return archive
            } catch (e: InterruptedIOException) {
                throw ArchiveException(Archive.ERRNO_FATAL, "open archive", e)
            } finally {
                if (!successful) Archive.free(archive)
            }
        }

        private fun getEntryName(entry: Long): String? =
            ArchiveEntry.pathnameUtf8(entry) ?: ArchiveEntry.pathname(entry)?.toString(Charsets.UTF_8)

        private fun ArchiveEntry.StructStat.isDirectory(): Boolean =
            (stMode and OsConstants.S_IFMT) == OsConstants.S_IFDIR
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
            var partA = 0
            var numberA = 0
            var partB = 0
            var numberB = 0
            for (i in 0 until max) {
                if (i % 2 == 1) {
                    val numA = numsA.getOrNull(numberA)?.toLongOrNull() ?: -1L
                    val numB = numsB.getOrNull(numberB)?.toLongOrNull() ?: -1L
                    numberA++
                    numberB++
                    val cmp = numA.compareTo(numB)
                    if (cmp != 0) return cmp
                } else {
                    val textA = partsA.getOrElse(partA) { "" }
                    val textB = partsB.getOrElse(partB) { "" }
                    partA++
                    partB++
                    val cmp = textA.compareTo(textB, ignoreCase = true)
                    if (cmp != 0) return cmp
                }
            }
            return a.compareTo(b, ignoreCase = true)
        }
    }
}
