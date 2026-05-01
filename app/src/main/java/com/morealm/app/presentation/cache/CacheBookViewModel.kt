package com.morealm.app.presentation.cache

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.repository.CacheRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CacheBookViewModel @Inject constructor(
    private val cacheRepo: CacheRepository,
) : ViewModel() {

    val webBooks: StateFlow<List<Book>> = cacheRepo.getWebBooks()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _cacheStats = MutableStateFlow<Map<String, CacheStat>>(emptyMap())
    val cacheStats: StateFlow<Map<String, CacheStat>> = _cacheStats.asStateFlow()

    val isDownloading: StateFlow<Boolean> = cacheRepo.isDownloading
    val downloadProgress = cacheRepo.downloadProgress

    /** Per-book TXT-export state. bookId → null (idle) | ExportState (running / done) */
    private val _exportState = MutableStateFlow<Map<String, ExportState>>(emptyMap())
    val exportState: StateFlow<Map<String, ExportState>> = _exportState.asStateFlow()

    /** Held bookId between SAF "create document" launch and result. */
    var pendingExportBookId: String? = null

    data class CacheStat(val totalChapters: Int, val cachedChapters: Int)

    /**
     * UI-visible per-book export progress. [done] increments per-chapter; [written] is
     * the count of chapters that actually had cached content (others are skipped).
     */
    data class ExportState(
        val running: Boolean,
        val done: Int,
        val total: Int,
        val written: Int = 0,
        val message: String = "",
    )

    init { loadCacheStats() }

    fun loadCacheStats() {
        viewModelScope.launch(Dispatchers.IO) {
            val books = webBooks.value
            val stats = mutableMapOf<String, CacheStat>()
            for (book in books) {
                val sourceUrl = book.sourceUrl ?: continue
                val (total, cached) = cacheRepo.getCacheStat(book.id, sourceUrl)
                stats[book.id] = CacheStat(total, cached)
            }
            _cacheStats.value = stats
        }
    }

    fun startDownload(book: Book, startIndex: Int = 0, endIndex: Int = -1) {
        val sourceUrl = book.sourceUrl ?: return
        cacheRepo.startDownload(book.id, sourceUrl, startIndex, endIndex)
    }

    fun stopDownload() {
        cacheRepo.stopDownload()
    }

    fun clearCache(book: Book) {
        viewModelScope.launch(Dispatchers.IO) {
            val sourceUrl = book.sourceUrl ?: return@launch
            cacheRepo.clearCache(sourceUrl)
            loadCacheStats()
        }
    }

    // ── #4 多选模式 + 批量删除 + 清理无效缓存 ──

    private val _multiSelectMode = MutableStateFlow(false)
    val multiSelectMode: StateFlow<Boolean> = _multiSelectMode.asStateFlow()

    /** 选中的书 id 集合。退出多选模式时清空。 */
    private val _selectedBookIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedBookIds: StateFlow<Set<String>> = _selectedBookIds.asStateFlow()

    /** 一次性后台任务的提示文案（"已清理 5 条孤儿缓存"等）— UI 用 Toast 消费。 */
    private val _oneShotToast = MutableStateFlow<String?>(null)
    val oneShotToast: StateFlow<String?> = _oneShotToast.asStateFlow()

    fun consumeToast() { _oneShotToast.value = null }

    fun enterMultiSelect() {
        _multiSelectMode.value = true
        _selectedBookIds.value = emptySet()
    }

    fun exitMultiSelect() {
        _multiSelectMode.value = false
        _selectedBookIds.value = emptySet()
    }

    fun toggleSelected(bookId: String) {
        _selectedBookIds.update { current ->
            if (bookId in current) current - bookId else current + bookId
        }
    }

    fun selectAll() {
        _selectedBookIds.value = webBooks.value.map { it.id }.toSet()
    }

    /**
     * 批量清空选中本书的缓存。结束后退出多选模式 + 弹 toast 报告条数。
     * 注意：这里只清缓存，不删 Book 自身。
     */
    fun clearSelectedCaches() {
        val ids = _selectedBookIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val targets = webBooks.value.filter { it.id in ids }
            val urls = targets.mapNotNull { it.sourceUrl }
            val n = cacheRepo.clearCacheBatch(urls)
            loadCacheStats()
            exitMultiSelect()
            _oneShotToast.value = "已清空 $n 本书的缓存"
        }
    }

    /**
     * 清理无效缓存（孤儿条目）— 不影响任何活跃书的缓存。完成后弹 toast。
     */
    fun clearOrphanedCache() {
        viewModelScope.launch(Dispatchers.IO) {
            val n = cacheRepo.clearOrphanedCache()
            loadCacheStats()
            _oneShotToast.value = if (n > 0) "已清理 $n 条无效缓存" else "未发现无效缓存"
        }
    }

    /**
     * 全部清空 — 危险操作，UI 必须先弹二次确认。
     */
    fun clearAllCaches() {
        viewModelScope.launch(Dispatchers.IO) {
            val all = webBooks.value.mapNotNull { it.sourceUrl }
            val n = cacheRepo.clearCacheBatch(all)
            loadCacheStats()
            _oneShotToast.value = "已清空 $n 本书的缓存"
        }
    }

    /**
     * Export a book's cached chapters to a TXT file at [uri] (the SAF document the user picked).
     * Updates [exportState] with running progress and a final summary message.
     * Caller is responsible for first calling [pendingExportBookId] = book.id and launching
     * the SAF CreateDocument contract — the result URI is then handed back to this method.
     */
    fun exportTxt(book: Book, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            updateExport(book.id) { it.copy(running = true, done = 0, total = 0, message = "") }
            try {
                val written = cacheRepo.exportTxt(book, uri) { current, total ->
                    updateExport(book.id) { it.copy(running = true, done = current, total = total) }
                }
                val total = _exportState.value[book.id]?.total ?: 0
                val msg = when {
                    written == 0 -> "导出失败：没有已缓存的章节"
                    written < total -> "已导出 $written/$total 章（其余未缓存已跳过）"
                    else -> "已导出 $written 章"
                }
                updateExport(book.id) { it.copy(running = false, written = written, message = msg) }
            } catch (e: Exception) {
                updateExport(book.id) {
                    it.copy(running = false, message = "导出失败：${e.message?.take(80) ?: "未知错误"}")
                }
            }
        }
    }

    private fun updateExport(bookId: String, transform: (ExportState) -> ExportState) {
        _exportState.update { map ->
            val current = map[bookId] ?: ExportState(false, 0, 0)
            map + (bookId to transform(current))
        }
    }

    fun dismissExportMessage(bookId: String) {
        _exportState.update { map ->
            val current = map[bookId] ?: return@update map
            map + (bookId to current.copy(message = ""))
        }
    }
}
