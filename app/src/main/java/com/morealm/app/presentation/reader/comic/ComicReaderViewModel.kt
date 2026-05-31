package com.morealm.app.presentation.reader.comic

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.entity.BookFormat
import com.morealm.app.domain.parser.ComicResourceLoader
import com.morealm.app.domain.parser.EpubComicResourceLoader
import com.morealm.app.domain.parser.MobiComicResourceLoader
import com.morealm.app.domain.preference.AppPreferences
import com.morealm.app.domain.repository.BookRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 漫画阅读 ViewModel —— 与小说 [com.morealm.app.presentation.reader.ReaderViewModel]
 * 完全独立。两条管线零代码共享，便于各自演进。
 *
 * 职责：
 *   - 取 Book + 解析 MOBI/AZW3 资源索引（仅 offset/length，不读图片字节）
 *   - 暴露图片总数 + 当前页索引给 UI（用于进度展示与滚动定位）
 *   - 持久化阅读进度（落到 [Book.lastReadPosition] —— 用图片 index 作为位置）
 */
@HiltViewModel
class ComicReaderViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bookRepo: BookRepository,
    private val prefs: AppPreferences,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val bookId: String = checkNotNull(savedStateHandle["bookId"]) { "bookId required" }

    private val _state = MutableStateFlow(ComicReaderState())
    val state: StateFlow<ComicReaderState> = _state.asStateFlow()

    /** 音量键翻页偏好 —— 与小说阅读器共用同一份 [AppPreferences] 设置（阅读设置里改即两端生效）。 */
    val volumeKeyPage: StateFlow<Boolean> = prefs.volumeKeyPage
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val volumeKeyReverse: StateFlow<Boolean> = prefs.volumeKeyReverse
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        loadBookAndImages()
    }

    private fun loadBookAndImages() {
        viewModelScope.launch(Dispatchers.IO) {
            val book = bookRepo.getById(bookId)
            if (book == null) {
                _state.value = _state.value.copy(error = "未找到该书", loading = false)
                return@launch
            }
            val uri = book.localPath?.let { Uri.parse(it) }
            if (uri == null) {
                _state.value = _state.value.copy(error = "未找到本地文件路径", book = book, loading = false)
                return@launch
            }

            // 按 format 派发 loader。EPUB 用 epublib spine 顺序解析图片；MOBI/AZW3 用 PDB
            // record offset 直读。两条管线对 ComicReaderScreen 透明 —— 都通过
            // ComicResourceRegistry 按 hash 反查读字节。
            val loader: ComicResourceLoader? = when (book.format) {
                BookFormat.MOBI, BookFormat.AZW3 -> MobiComicResourceLoader
                BookFormat.EPUB -> EpubComicResourceLoader
                else -> null
            }
            if (loader == null) {
                _state.value = _state.value.copy(
                    error = "暂不支持的漫画格式：${book.format}",
                    book = book,
                    loading = false,
                )
                return@launch
            }
            val index = try {
                loader.activate(context, uri)
            } catch (e: Exception) {
                AppLog.warn(TAG, "activate failed (${book.format}): ${e.message}")
                null
            }
            // loading=false 必须设 —— 老路径漏设导致 error 路径仍卡在 CircularProgressIndicator
            // （UI 黑底 + 中央小转圈，用户感知为「黑屏」）。
            if (index == null || index.totalImages == 0) {
                _state.value = _state.value.copy(
                    error = "未在该文件中解析到图片",
                    book = book,
                    loading = false,
                )
                return@launch
            }
            val startIndex = book.lastReadPosition.coerceIn(0, index.totalImages - 1)
            _state.value = _state.value.copy(
                book = book,
                bookUri = uri,
                hash = index.hash,
                totalImages = index.totalImages,
                startIndex = startIndex,
                currentIndex = startIndex,
                loading = false,
                error = null,
            )
            AppLog.info(TAG, "loaded: ${index.totalImages} images (${book.format}) startIndex=$startIndex")
        }
    }

    /** 用户滚动 / 翻图后更新当前位置 —— 防抖由 UI 层处理。 */
    fun updateCurrentIndex(index: Int) {
        val cur = _state.value
        if (index == cur.currentIndex || cur.totalImages == 0) return
        val clamped = index.coerceIn(0, cur.totalImages - 1)
        _state.value = cur.copy(currentIndex = clamped)
    }

    /** 保存当前阅读位置到 DB。退出阅读器时由 UI 调用一次即可。 */
    fun saveProgress() {
        val cur = _state.value
        val book = cur.book ?: return
        val newPos = cur.currentIndex
        val newProgress = if (cur.totalImages <= 1) 0f else newPos.toFloat() / (cur.totalImages - 1)
        viewModelScope.launch(Dispatchers.IO) {
            bookRepo.update(
                book.copy(
                    lastReadPosition = newPos,
                    readProgress = newProgress,
                    lastReadAt = System.currentTimeMillis(),
                )
            )
        }
    }

    override fun onCleared() {
        saveProgress()
        super.onCleared()
    }

    companion object {
        private const val TAG = "ComicReaderVM"
    }
}

data class ComicReaderState(
    val book: Book? = null,
    val bookUri: Uri? = null,
    val hash: String = "",
    val totalImages: Int = 0,
    val startIndex: Int = 0,
    val currentIndex: Int = 0,
    val loading: Boolean = true,
    val error: String? = null,
)
