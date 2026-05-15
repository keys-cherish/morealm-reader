package com.morealm.app.presentation.reader

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.entity.BookFormat
import com.morealm.app.domain.parser.ComicBookDetector
import com.morealm.app.domain.parser.EpubParser
import com.morealm.app.domain.repository.BookRepository
import com.morealm.app.domain.storage.BookFileHealthChecker
import kotlinx.coroutines.withTimeoutOrNull
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
        AppLog.info(TAG, "init bookId=$bookId")
        viewModelScope.launch(Dispatchers.IO) {
            val book = bookRepo.getById(bookId)
            if (book == null) {
                AppLog.warn(TAG, "book not found bookId=$bookId")
                _result.value = Result.NotFound
                return@launch
            }
            AppLog.info(TAG, "book loaded title=${book.title} format=${book.format} isComic=${book.isComic}")

            // ── 本地文件健康检查 ──
            //
            // 历史问题：用户拿到 0 字节占位 EPUB / 损坏文件入了库（导入前的 magic 校验
            // 早期版本没有），下次打开 ReaderViewModel / EpubParser 走不下去，常见结果：
            // OOM、黑屏卡死、章节列表空但 reader 仍试图 layout 触发崩溃。
            //
            // 在 probe 这一站统一拦住坏文件，给 UI 报清晰错误（Result.BadFile）而不是
            // 进 reader 后乱崩。只校验有 localPath 的本地书；web book 没本地文件跳过。
            val localPath = book.localPath
            if (!localPath.isNullOrBlank()) {
                val uri = runCatching { Uri.parse(localPath) }.getOrNull()
                if (uri != null) {
                    val health = BookFileHealthChecker.check(context, uri, book.format)
                    if (health is BookFileHealthChecker.Health.Invalid) {
                        AppLog.warn(TAG, "book unhealthy title=${book.title} reason=${health.reason}")
                        _result.value = Result.BadFile(
                            bookTitle = book.title,
                            reason = health.reason,
                        )
                        return@launch
                    }
                }
            }
            // 已标记的直接出结果，但 EPUB 的 isComic=true 可能来自旧 buggy 算法
            // （Level 1.5 反向 Novel 规则上线前的误判，如「魔女の旅々 学園物語」轻小说被判 Comic）。
            // 这里带 3s 超时重新评估一次：超时 / detect 仍 true → 走 Comic 路由；
            // detect 翻 false → 静默纠正 DB 并走 Novel 路由。
            // 超时阈值给 3s 是因为 50MB EPUB 打开 ZIP + 读 OPF + 算字节统计在中端机
            // 通常 500ms 内完成，3s 余量足够 + 不会让用户感受卡顿。
            if (book.isComic) {
                if (book.format == BookFormat.EPUB) {
                    val uri = book.localPath?.let { Uri.parse(it) }
                    if (uri != null) {
                        val rechecked = withTimeoutOrNull(3_000L) {
                            runCatching { EpubParser.detectIsComic(context, uri) }.getOrNull()
                        }
                        if (rechecked == false) {
                            AppLog.info(TAG, "isComic=true overridden by recheck → Novel")
                            bookRepo.update(book.copy(isComic = false))
                            _result.value = Result.Novel
                            return@launch
                        }
                        // rechecked == true 或 null（超时）→ 信任 DB 原值
                    }
                }
                AppLog.info(TAG, "result=Comic (pre-marked)")
                _result.value = Result.Comic
                return@launch
            }
            // 未标记但格式是 MOBI/AZW3：兜底 detect 一次（只读文件头 64KB，< 5ms）。
            //
            // EPUB **不在此处兜底** —— EpubParser.detectIsComic 必须打开 ZIP + 解析 opf，
            // 大型 EPUB 数秒到数十秒；又是 object 单例 @Synchronized，会与同时打开的其他
            // EPUB 串行死锁，导致路由黑屏（用户 220ms 内连点两本 EPUB 时已复现）。
            //
            // EPUB 漫画检测已在 [ShelfImportController.enrichBookMetadata] (Phase 2) 跑过，
            // 结果落 book.isComic。Phase 2 未完用户就点开 → 走文字阅读器（不黑屏），
            // 下次点开 isComic 已写回，路由自动到漫画阅读器。
            if (book.format in setOf(BookFormat.MOBI, BookFormat.AZW3)) {
                val uri = book.localPath?.let { Uri.parse(it) }
                AppLog.info(TAG, "fallback detect start format=${book.format}")
                val detected = uri != null && try {
                    ComicBookDetector.detect(context, uri, book.format)
                } catch (e: Exception) {
                    AppLog.warn(TAG, "fallback detect threw: ${e.message}")
                    false
                }
                AppLog.info(TAG, "fallback detect done detected=$detected")
                if (detected) {
                    // 写回 DB，下次打开直接走漫画路由
                    bookRepo.update(book.copy(isComic = true))
                    _result.value = Result.Comic
                    return@launch
                }
            }
            AppLog.info(TAG, "result=Novel")
            _result.value = Result.Novel
        }
    }

    private companion object {
        const val TAG = "BookFmtProbe"
    }

    sealed interface Result {
        data object Novel : Result
        data object Comic : Result
        data object NotFound : Result

        /**
         * 本地文件存在问题（0 字节 / 头部错 / IO 不通）。路由层应该显示错误页让
         * 用户删除/重新导入，**不应该**继续进 reader——之前进了 reader 会触发
         * OOM 或黑屏卡死。
         */
        data class BadFile(val bookTitle: String, val reason: String) : Result
    }
}
