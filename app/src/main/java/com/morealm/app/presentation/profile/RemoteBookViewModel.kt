package com.morealm.app.presentation.profile

import android.content.Context
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.entity.BookFormat
import com.morealm.app.domain.repository.BookRepository
import com.morealm.app.domain.sync.RemoteBookFile
import com.morealm.app.domain.sync.RemoteBookManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import javax.inject.Inject

/**
 * View model backing the WebDav cloud-bookshelf screen.
 *
 * Three pieces of state:
 *  - [files]:    the listing fetched on screen open / refresh
 *  - [loading]:  show CircularProgressIndicator while loading / downloading
 *  - [status]:   transient one-line feedback ("已导入 三体.epub" /
 *                "下载失败：401 …") consumed by the screen as a Snackbar /
 *                inline text. Cleared automatically on the next action.
 *
 * [downloadAndImport] is the user-facing operation: download the bytes,
 * persist them to `filesDir/remoteBooks/<name>` so the user can re-open
 * the book without WebDav, and insert a [Book] row that points at the
 * local file. The download itself is single-flight per [RemoteBookFile.name]
 * — tapping the same row twice while the first download is in flight
 * silently no-ops.
 */
@HiltViewModel
class RemoteBookViewModel @Inject constructor(
    private val manager: RemoteBookManager,
    private val bookRepo: BookRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _files = MutableStateFlow<List<RemoteBookFile>>(emptyList())
    val files: StateFlow<List<RemoteBookFile>> = _files.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    /** Per-row download state; exposes a Set of in-flight `name`s. */
    private val _downloading = MutableStateFlow<Set<String>>(emptySet())
    val downloading: StateFlow<Set<String>> = _downloading.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _loading.value = true
            _status.value = ""
            try {
                _files.value = manager.listBooks()
                if (_files.value.isEmpty()) {
                    _status.value = "云端 books/ 目录为空 — 把 epub / txt 等放进去就能在这里看到"
                }
            } catch (e: Exception) {
                _status.value = "读取失败：${e.message}"
                AppLog.error("RemoteBook", "Refresh failed", e)
            } finally {
                _loading.value = false
            }
        }
    }

    /**
     * Download a remote file, persist to `filesDir/remoteBooks/<name>`,
     * and insert a Book pointing at it. No-op if already imported (we
     * key on the local path so re-tapping a row doesn't pile duplicates).
     */
    fun downloadAndImport(file: RemoteBookFile) {
        if (file.name in _downloading.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _downloading.value = _downloading.value + file.name
            try {
                val bytes = manager.download(file)
                if (bytes.isEmpty()) {
                    _status.value = "下载到空文件：${file.name}"
                    return@launch
                }
                val target = File(context.filesDir, "remoteBooks").also { it.mkdirs() }
                    .resolve(sanitize(file.name))
                target.writeBytes(bytes)
                val localPath = target.toURI().toString()  // file:// URI
                if (bookRepo.findByLocalPath(localPath) != null) {
                    _status.value = "已存在书架：${file.name}"
                    return@launch
                }
                val format = detectFormat(file.name)
                val parsed = file.name.substringBeforeLast('.', file.name)
                bookRepo.insert(
                    Book(
                        id = UUID.randomUUID().toString(),
                        title = parsed,
                        author = "",
                        localPath = localPath,
                        format = format,
                        addedAt = System.currentTimeMillis(),
                    )
                )
                _status.value = "已导入：${file.name}"
                AppLog.info("RemoteBook", "Imported: ${target.absolutePath} (${bytes.size} bytes, $format)")
            } catch (e: Exception) {
                _status.value = "下载失败：${e.message}"
                AppLog.error("RemoteBook", "Download failed for ${file.name}", e)
            } finally {
                _downloading.value = _downloading.value - file.name
            }
        }
    }

    /** Replace path-hostile chars; keep extension. */
    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_")

    private fun detectFormat(name: String): BookFormat = when (name.substringAfterLast('.', "").lowercase()) {
        "epub" -> BookFormat.EPUB
        "txt" -> BookFormat.TXT
        "umd" -> BookFormat.UMD
        "mobi" -> BookFormat.MOBI
        "azw3" -> BookFormat.AZW3
        "pdf" -> BookFormat.PDF
        "cbz" -> BookFormat.CBZ
        else -> BookFormat.UNKNOWN
    }
}
