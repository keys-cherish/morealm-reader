package com.morealm.app.presentation.reader

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morealm.app.domain.entity.BookFormat
import com.morealm.app.domain.parser.ComicBookDetector
import com.morealm.app.domain.repository.BookRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 路由分流用的轻量 ViewModel —— 只为了判断 `reader/{bookId}` 应该进
 * 小说阅读器还是漫画阅读器。
 *
 * 不能让 `navigateToReader(...)` 调用方手动查 DB 后再决定跳哪条路由 —— 那要改
 * 散落 5+ 处入口（书架 / 搜索 / Listen / 详情 / 通知点击 / 系统分享）。
 * 集中在路由内部分流，调用方零改动。
 *
 * 兜底：对漫画检测引入前已导入的旧 MOBI/AZW3 书 isComic=false，但实际可能是漫画。
 * 这里对这种"未检测过"的 case 主动调 [ComicBookDetector]（只读文件头 64KB，
 * < 5ms），命中后把结果写回 DB，下次打开就走对应路由。
 */
@HiltViewModel
class BookFormatProbeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bookRepo: BookRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val bookId: String = checkNotNull(savedStateHandle["bookId"]) { "bookId required" }

    private val _result = MutableStateFlow<Result?>(null)
    val result: StateFlow<Result?> = _result.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val book = bookRepo.getById(bookId)
            if (book == null) {
                _result.value = Result.NotFound
                return@launch
            }
            // 已标记的直接出结果
            if (book.isComic) {
                _result.value = Result.Comic
                return@launch
            }
            // 未标记但格式是 MOBI/AZW3：兜底 detect 一次（漫画检测引入前导入的旧书）
            if (book.format in setOf(BookFormat.MOBI, BookFormat.AZW3)) {
                val uri = book.localPath?.let { Uri.parse(it) }
                val detected = uri != null && try {
                    ComicBookDetector.detect(context, uri, book.format)
                } catch (_: Exception) { false }
                if (detected) {
                    // 写回 DB，下次打开直接走漫画路由
                    bookRepo.update(book.copy(isComic = true))
                    _result.value = Result.Comic
                    return@launch
                }
            }
            _result.value = Result.Novel
        }
    }

    sealed interface Result {
        data object Novel : Result
        data object Comic : Result
        data object NotFound : Result
    }
}
