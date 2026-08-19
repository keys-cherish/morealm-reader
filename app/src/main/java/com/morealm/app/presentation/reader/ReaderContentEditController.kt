package com.morealm.app.presentation.reader

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.morealm.app.domain.entity.BookFormat
import com.morealm.app.domain.parser.LocalBookParser
import com.morealm.app.domain.storage.TxtEditScope
import com.morealm.app.domain.storage.TxtFileEditor
import com.morealm.app.domain.storage.TxtReplaceRequest
import com.morealm.app.domain.storage.TxtUndoSnapshot
import com.morealm.app.core.text.stripHtml
import com.morealm.app.core.log.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class TxtReplaceState(
    val running: Boolean = false,
    val replacedCount: Int = 0,
    val message: String? = null,
    val error: String? = null,
    val canUndo: Boolean = false,
)

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

    private val _txtReplaceState = MutableStateFlow(TxtReplaceState())
    val txtReplaceState: StateFlow<TxtReplaceState> = _txtReplaceState.asStateFlow()
    private var undoSnapshot: TxtUndoSnapshot? = null

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

    /**
     * 搜索面板的真实 TXT 写回入口。target 非空表示只替换该匹配；否则按 scope 全部替换。
     */
    fun replaceTxt(
        editScope: TxtEditScope,
        query: String,
        replacement: String,
        isRegex: Boolean,
        isCaseSensitive: Boolean,
        target: ReaderSearchController.SearchResult? = null,
    ): Boolean {
        if (_txtReplaceState.value.running) return false
        val book = chapter.book.value
        if (book == null || book.format != BookFormat.TXT || book.localPath.isNullOrBlank()) {
            _txtReplaceState.value = TxtReplaceState(error = "仅支持编辑本地 TXT 文件")
            return false
        }
        if (query.isEmpty()) {
            _txtReplaceState.value = TxtReplaceState(error = "搜索内容不能为空")
            return false
        }
        val requestedChapterIndex = target?.chapterIndex ?: chapter.currentChapterIndex.value
        val preferredIndex = requestedChapterIndex
        _txtReplaceState.value = TxtReplaceState(
            running = true,
            message = "正在替换…",
            canUndo = undoSnapshot != null,
        )
        scope.launch(Dispatchers.IO) {
            try {
                val editableUri = resolveEditableTxtUri(book)
                val result = TxtFileEditor.replace(
                    context = context,
                    uri = editableUri,
                    chapters = chapter.chapters.value,
                    scope = editScope,
                    request = TxtReplaceRequest(query, replacement, isRegex, isCaseSensitive),
                    targetChapterIndex = requestedChapterIndex,
                    targetMatchOrdinal = target?.matchOrdinalInChapter,
                )
                if (result.fileChanged) chapter.reparseLocalTxtAfterEdit(preferredIndex)
                result.undoSnapshot?.let { newSnapshot ->
                    undoSnapshot?.discard()
                    undoSnapshot = newSnapshot
                }
                _txtReplaceState.value = TxtReplaceState(
                    replacedCount = result.replacedCount,
                    message = if (result.replacedCount > 0) {
                        "已替换 ${result.replacedCount} 处"
                    } else {
                        "没有可替换的匹配"
                    },
                    canUndo = undoSnapshot != null,
                )
            } catch (e: Exception) {
                AppLog.error("TxtEdit", "TXT replace failed", e)
                _txtReplaceState.value = TxtReplaceState(
                    error = if (e is SecurityException) {
                        "原文件没有写入权限，请在书架重新选择该 TXT 授权后再替换"
                    } else {
                        e.message ?: "替换失败"
                    },
                    canUndo = undoSnapshot != null,
                )
            }
        }
        return true
    }

    /** 撤销最近一次成功的 TXT 替换；恢复后同样等待当前章重新解析并发布。 */
    fun undoLastTxtReplace() {
        if (_txtReplaceState.value.running) return
        val snapshot = undoSnapshot ?: run {
            _txtReplaceState.value = TxtReplaceState(error = "没有可撤销的替换")
            return
        }
        val book = chapter.book.value
        if (book == null || book.format != BookFormat.TXT || book.localPath.isNullOrBlank()) {
            _txtReplaceState.value = TxtReplaceState(error = "当前 TXT 已不可用", canUndo = true)
            return
        }
        val uri = Uri.parse(book.localPath)
        if (snapshot.sourceUri != uri.toString()) {
            _txtReplaceState.value = TxtReplaceState(error = "撤销快照与当前 TXT 不匹配", canUndo = true)
            return
        }
        val preferredIndex = chapter.currentChapterIndex.value
        _txtReplaceState.value = TxtReplaceState(running = true, message = "正在撤销…", canUndo = true)
        scope.launch(Dispatchers.IO) {
            try {
                TxtFileEditor.restore(context, uri, snapshot)
                chapter.reparseLocalTxtAfterEdit(preferredIndex)
                snapshot.discard()
                undoSnapshot = null
                _txtReplaceState.value = TxtReplaceState(message = "已撤销上次替换")
            } catch (e: Exception) {
                AppLog.error("TxtEdit", "TXT undo failed", e)
                _txtReplaceState.value = TxtReplaceState(
                    error = e.message ?: "撤销失败",
                    canUndo = true,
                )
            }
        }
    }

    /**
     * 替换只允许原位写回用户选中的 TXT，不再静默创建应用私有副本。这里只检查 URI
     * 写授权，不能用 openFileDescriptor("rw") 预探测：部分 SAF Provider 支持实际使用的
     * 输出流，却不提供 rw 所要求的可寻址文件描述符，会把可写文件误判成只读。
     */
    private fun resolveEditableTxtUri(book: com.morealm.app.domain.entity.Book): Uri {
        val source = Uri.parse(requireNotNull(book.localPath))
        if (hasWritePermission(source)) return source
        error("原文件没有写入权限，请在书架重新选择该 TXT 授权后再替换")
    }

    private fun hasWritePermission(uri: Uri): Boolean = when (uri.scheme) {
        null, "file" -> uri.path?.let { java.io.File(it) }?.canWrite() == true
        "content" -> context.checkUriPermission(
            uri,
            android.os.Process.myPid(),
            android.os.Process.myUid(),
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        ) == PackageManager.PERMISSION_GRANTED
        else -> false
    }

    fun clearTxtReplaceMessage() {
        if (!_txtReplaceState.value.running) {
            _txtReplaceState.value = TxtReplaceState(canUndo = undoSnapshot != null)
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
