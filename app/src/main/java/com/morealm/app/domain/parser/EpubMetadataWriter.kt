package com.morealm.app.domain.parser

import android.content.Context
import android.net.Uri
import com.morealm.app.core.log.AppLog
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Writes modified metadata back into an EPUB file.
 *
 * Strategy: copy all ZIP entries to a temp file, replacing the OPF entry
 * with updated metadata. Then overwrite the original via ContentResolver.
 */
object EpubMetadataWriter {

    data class MetadataUpdate(
        val title: String? = null,
        val author: String? = null,
        val description: String? = null,
        val language: String? = null,
        val publisher: String? = null,
    )

    /**
     * Update EPUB metadata in-place. Returns true on success.
     * Works with SAF URIs — reads via ContentResolver, writes back via output stream.
     */
    fun updateMetadata(context: Context, uri: Uri, update: MetadataUpdate): Boolean {
        try {
            // Step 1: Find OPF path and read all entries into memory
            var opfPath = ""
            val entries = mutableListOf<Pair<String, ByteArray>>()

            context.contentResolver.openInputStream(uri)?.use { stream ->
                val zip = ZipInputStream(stream)
                var entry: java.util.zip.ZipEntry?
                while (zip.nextEntry.also { entry = it } != null) {
                    val name = entry!!.name
                    val data = zip.readBytes()
                    entries.add(name to data)

                    if (name == "META-INF/container.xml") {
                        val xml = String(data)
                        val match = Regex("full-path=\"([^\"]+)\"").find(xml)
                        opfPath = match?.groupValues?.get(1) ?: ""
                    }
                    if (name.endsWith(".opf") && opfPath.isEmpty()) {
                        opfPath = name
                    }
                }
            }

            if (opfPath.isEmpty()) {
                AppLog.warn("EpubWriter", "No OPF found in EPUB")
                return false
            }

            // Step 2: Modify the OPF content
            val opfIndex = entries.indexOfFirst { it.first == opfPath }
            if (opfIndex < 0) return false

            val originalOpf = String(entries[opfIndex].second)
            val modifiedOpf = modifyOpfMetadata(originalOpf, update)
            entries[opfIndex] = opfPath to modifiedOpf.toByteArray(Charsets.UTF_8)

            // Step 3: Write modified ZIP to temp file
            val tempFile = File(context.cacheDir, "epub_rewrite_${System.currentTimeMillis()}.epub")
            tempFile.outputStream().use { fos ->
                ZipOutputStream(fos).use { zos ->
                    for ((name, data) in entries) {
                        val ze = ZipEntry(name)
                        zos.putNextEntry(ze)
                        zos.write(data)
                        zos.closeEntry()
                    }
                }
            }

            // Step 4: Write temp file back to original URI
            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                tempFile.inputStream().use { it.copyTo(out) }
            }
            tempFile.delete()

            AppLog.info("EpubWriter", "Metadata updated successfully for $uri")
            return true
        } catch (e: Exception) {
            AppLog.error("EpubWriter", "Failed to update metadata: ${e.message}")
            return false
        }
    }

    private fun modifyOpfMetadata(opfXml: String, update: MetadataUpdate): String {
        val doc = Jsoup.parse(opfXml, "", Parser.xmlParser())
        val metadata = doc.selectFirst("metadata") ?: return opfXml

        update.title?.let { newTitle ->
            val el = metadata.selectFirst("dc\\:title") ?: metadata.selectFirst("title")
            if (el != null) {
                el.text(newTitle)
            } else {
                metadata.appendElement("dc:title").text(newTitle)
            }
        }

        update.author?.let { newAuthor ->
            val el = metadata.selectFirst("dc\\:creator") ?: metadata.selectFirst("creator")
            if (el != null) {
                el.text(newAuthor)
            } else {
                metadata.appendElement("dc:creator").text(newAuthor)
            }
        }

        update.description?.let { newDesc ->
            val el = metadata.selectFirst("dc\\:description") ?: metadata.selectFirst("description")
            if (el != null) {
                el.text(newDesc)
            } else {
                metadata.appendElement("dc:description").text(newDesc)
            }
        }

        update.language?.let { newLang ->
            val el = metadata.selectFirst("dc\\:language") ?: metadata.selectFirst("language")
            if (el != null) {
                el.text(newLang)
            } else {
                metadata.appendElement("dc:language").text(newLang)
            }
        }

        update.publisher?.let { newPub ->
            val el = metadata.selectFirst("dc\\:publisher") ?: metadata.selectFirst("publisher")
            if (el != null) {
                el.text(newPub)
            } else {
                metadata.appendElement("dc:publisher").text(newPub)
            }
        }

        return doc.outerHtml()
    }
}
