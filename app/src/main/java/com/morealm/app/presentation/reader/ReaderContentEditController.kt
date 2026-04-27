package com.morealm.app.presentation.reader

import android.content.Context
import android.net.Uri
import com.morealm.app.domain.parser.LocalBookParser
import com.morealm.app.core.text.stripHtml
import com.morealm.app.core.log.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Manages content editing and TXT export.
 * Extracted from ReaderViewModel.
 */
class ReaderContentEditController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val chapter: ReaderChapterController,
) {
    // ── State ──
    private val _editingContent = MutableStateFlow(false)
    val editingContent: StateFlow<Boolean> = _editingContent.asStateFlow()

    // ── Content Editing ──

    fun startEditContent() { _editingContent.value = true }
    fun cancelEditContent() { _editingContent.value = false }

    fun saveEditedContent(newContent: String) {
        chapter.chapterContent // access to update via chapter controller
        _editingContent.value = false
        val book = chapter.book.value ?: return
        val chapterObj = chapter.chapters.value.getOrNull(chapter.currentChapterIndex.value) ?: return
        val localPath = book.localPath ?: return
        if (book.format == com.morealm.app.domain.entity.BookFormat.EPUB) {
            scope.launch(Dispatchers.IO) {
                val uri = Uri.parse(localPath)
                val cacheDir = java.io.File(context.cacheDir, "epub_chapters/${uri.hashCode()}")
                cacheDir.mkdirs()
                val href = chapterObj.url.substringBeforeLast("#")
                val cacheFile = java.io.File(cacheDir, href.replace('/', '_') + ".html")
                cacheFile.writeText(newContent)
                AppLog.info("Edit", "Saved edited content for chapter ${chapterObj.index}")
            }
        }
    }

    // ── Export ──

    fun exportAsTxt(outputUri: Uri) {
        val book = chapter.book.value ?: return
        val chapterList = chapter.chapters.value
        if (chapterList.isEmpty()) return
        val isWebBook = chapter.isWebBook(book)

        scope.launch(Dispatchers.IO) {
            try {
                context.contentResolver.openOutputStream(outputUri)?.use { out ->
                    val writer = out.bufferedWriter(Charsets.UTF_8)
                    writer.appendLine(book.title)
                    if (book.author.isNotBlank()) writer.appendLine("\u4f5c\u8005\uff1a${book.author}")
                    writer.appendLine()

                    for (ch in chapterList) {
                        writer.appendLine(ch.title)
                        writer.appendLine()
                        val content = if (isWebBook) {
                            chapter.loadWebChapterContent(book, ch, ch.index)
                        } else {
                            val localPath = book.localPath ?: break
                            LocalBookParser.readChapter(context, Uri.parse(localPath), book.format, ch)
                        }
                        writer.appendLine(content.stripHtml().trim())
                        writer.appendLine()
                    }
                    writer.flush()
                }
                AppLog.info("Edit", "Exported ${chapterList.size} chapters to TXT")
            } catch (e: Exception) {
                AppLog.error("Edit", "Export failed", e)
            }
        }
    }
}
