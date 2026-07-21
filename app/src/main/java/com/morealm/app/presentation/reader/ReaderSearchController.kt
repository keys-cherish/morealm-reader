package com.morealm.app.presentation.reader

import android.net.Uri
import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.entity.BookChapter
import com.morealm.app.domain.entity.BookFormat
import com.morealm.app.domain.parser.LocalBookParser
import com.morealm.app.core.text.stripHtml
import com.morealm.app.core.log.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.regex.Pattern

/**
 * Manages full-text search across all chapters.
 * Extracted from ReaderViewModel.
 */
class ReaderSearchController(
    private val scope: CoroutineScope,
    private val chapter: ReaderChapterController,
    private val context: android.content.Context,
) {
    // ── Data classes (moved from ViewModel) ──
    data class SearchResult(
        val chapterIndex: Int,
        val chapterTitle: String,
        val snippet: String,
        val query: String = "",
        val queryIndexInChapter: Int = -1,
        val queryLength: Int = 0,
        val matchOrdinalInChapter: Int = 0,
    )

    data class SearchSelection(
        val chapterIndex: Int,
        val queryIndexInChapter: Int,
        val queryLength: Int,
    )

    // ── State ──
    private val _searchResults = MutableStateFlow<List<SearchResult>>(emptyList())
    val searchResults: StateFlow<List<SearchResult>> = _searchResults.asStateFlow()

    private val _pendingSearchSelection = MutableStateFlow<SearchSelection?>(null)
    val pendingSearchSelection: StateFlow<SearchSelection?> = _pendingSearchSelection.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private val _searchError = MutableStateFlow<String?>(null)
    val searchError: StateFlow<String?> = _searchError.asStateFlow()

    // ── Search Functions ──

    /**
     * 全书搜索。按 [mode] 过滤掉与当前阅读位置 ([currentChapterIndex] / [currentChapterPosition])
     * 方向不符的命中：
     *  - "forward" 只保留 chapterIndex < cur 或 (chapterIndex == cur 且 queryIndexInChapter < curPos) 的命中
     *  - "backward" 反之
     *  - 其他（默认 "all"）不过滤
     *
     * 过滤在每章命中后即时决策，让 50 个上限只算入"实际会展示给用户"的结果，避免
     * 前向模式遇到长尾全章节都不在范围内时早早撞上限丢真正想看的命中。
     */
    fun searchFullText(
        query: String,
        mode: String = "all",
        currentChapterIndex: Int = -1,
        currentChapterPosition: Int = 0,
        isRegex: Boolean = false,
        isCaseSensitive: Boolean = false,
    ) {
        if (query.isBlank()) { _searchResults.value = emptyList(); return }
        val pattern = runCatching { compilePattern(query, isRegex, isCaseSensitive) }
            .getOrElse {
                _searchError.value = "正则表达式无效：${it.message?.take(100)}"
                _searchResults.value = emptyList()
                return
            }
        _searchError.value = null
        _searching.value = true
        scope.launch(Dispatchers.IO) {
            try {
                val book = chapter.book.value ?: return@launch
                val isWebBook = chapter.isWebBook(book)
                val chapterList = chapter.chapters.value
                val results = mutableListOf<SearchResult>()
                for (ch in chapterList) {
                    val content = if (isWebBook) {
                        chapter.loadWebChapterContent(book, ch, ch.index)
                    } else {
                        val localPath = book.localPath ?: break
                        if (book.format == BookFormat.TXT) {
                            // TXT 编辑必须以原始章节文本计匹配序号；readChapter 会压缩空行，
                            // 对跨行正则会让“第 N 个匹配”与原文件错位。
                            LocalBookParser.readTxtChapter(context, Uri.parse(localPath), ch)
                        } else {
                            LocalBookParser.readChapter(context, Uri.parse(localPath), book.format, ch)
                        }
                    }
                    val plainText = if (book.format == BookFormat.TXT) content else content.stripHtml()
                    val matcher = pattern.matcher(plainText)
                    var ordinal = 0
                    while (matcher.find()) {
                        val idx = matcher.start()
                        val length = matcher.end() - matcher.start()
                        if (passDirectionFilter(mode, ch.index, idx, currentChapterIndex, currentChapterPosition)) {
                            val start = (idx - 20).coerceAtLeast(0)
                            val end = (matcher.end() + 30).coerceAtMost(plainText.length)
                            val snippet = (if (start > 0) "..." else "") +
                                plainText.substring(start, end).replace('\n', ' ').trim() +
                                (if (end < plainText.length) "..." else "")
                            results.add(
                                SearchResult(
                                    chapterIndex = ch.index,
                                    chapterTitle = ch.title,
                                    snippet = snippet,
                                    query = query,
                                    queryIndexInChapter = idx,
                                    queryLength = length,
                                    matchOrdinalInChapter = ordinal,
                                ),
                            )
                        }
                        ordinal++
                        if (results.size >= 50) break
                    }
                    if (results.size >= 50) break
                }
                _searchResults.value = results
            } catch (e: Exception) {
                AppLog.error("Search", "Full text search failed", e)
            } finally {
                _searching.value = false
            }
        }
    }

    /**
     * 方向过滤判定。当 [mode] 为 "all" 或 [currentChapterIndex] < 0 时一律放行（后者
     * 表示调用方未提供当前位置信息，退化为全文模式 — 避免误把所有命中过滤掉）。
     */
    private fun passDirectionFilter(
        mode: String,
        hitChapterIndex: Int,
        hitQueryIndex: Int,
        currentChapterIndex: Int,
        currentChapterPosition: Int,
    ): Boolean {
        if (currentChapterIndex < 0) return true
        return when (mode) {
            "forward" -> hitChapterIndex < currentChapterIndex ||
                (hitChapterIndex == currentChapterIndex && hitQueryIndex < currentChapterPosition)
            "backward" -> hitChapterIndex > currentChapterIndex ||
                (hitChapterIndex == currentChapterIndex && hitQueryIndex > currentChapterPosition)
            else -> true
        }
    }

    fun clearSearchResults() {
        _searchResults.value = emptyList()
        _searchError.value = null
    }

    /**
     * 仅搜索当前已加载章节，返回章节内**所有**命中位置（不止首个）。
     *
     * 触发场景：用户在阅读器底部搜索面板把 Tab 切到「当前章」时调用。区别于
     * [searchFullText] 的 "全书首匹配 + 50 个结果上限"，章内搜索：
     *   - 不再请求其他章节内容（无网络/IO 开销，瞬时返回）
     *   - 命中无上限（同一章内 200+ 命中也全列出，让用户像 Ctrl+F 一样翻阅）
     *   - 每个命中独占一个 [SearchResult]，[queryIndexInChapter] 各不相同，
     *     点击就能精确跳到对应字符位置（复用现有 [openSearchResult] 链路）。
     *
     * 不直接读 ChapterController 的 _chapterContent —— 那是 stripHtml 之前的原始
     * 文本，命中位置算出来的 char index 跟阅读器排版后的 chapterPosition 不一致，
     * 跳转会偏移。所以参数从外部传当前章节文本（caller 用同一份 stripHtml 后的内容）。
     */
    fun searchCurrentChapter(
        query: String,
        plainText: String,
        chapterIndex: Int,
        chapterTitle: String,
        mode: String = "all",
        currentChapterPosition: Int = 0,
        isRegex: Boolean = false,
        isCaseSensitive: Boolean = false,
    ) {
        if (query.isBlank()) { _searchResults.value = emptyList(); return }
        val pattern = runCatching { compilePattern(query, isRegex, isCaseSensitive) }
            .getOrElse {
                _searchError.value = "正则表达式无效：${it.message?.take(100)}"
                _searchResults.value = emptyList()
                return
            }
        _searchError.value = null
        val results = mutableListOf<SearchResult>()
        val matcher = pattern.matcher(plainText)
        var matchNo = 1
        var ordinal = 0
        while (matcher.find()) {
            val idx = matcher.start()
            // 章内场景固定 chapterIndex；passDirectionFilter 只比 hitQueryIndex 与 curPos。
            if (passDirectionFilter(mode, chapterIndex, idx, chapterIndex, currentChapterPosition)) {
                val start = (idx - 16).coerceAtLeast(0)
                val end = (matcher.end() + 24).coerceAtMost(plainText.length)
                val snippet = (if (start > 0) "..." else "") +
                    plainText.substring(start, end).replace('\n', ' ').trim() +
                    (if (end < plainText.length) "..." else "")
                results.add(
                    SearchResult(
                        chapterIndex = chapterIndex,
                        chapterTitle = "[第 $matchNo 处] $chapterTitle",
                        snippet = snippet,
                        query = query,
                        queryIndexInChapter = idx,
                        queryLength = matcher.end() - matcher.start(),
                        matchOrdinalInChapter = ordinal,
                    ),
                )
                matchNo++
            }
            ordinal++
        }
        _searchResults.value = results
    }

    private fun compilePattern(query: String, isRegex: Boolean, isCaseSensitive: Boolean): Pattern {
        val source = if (isRegex) query else Pattern.quote(query)
        var flags = Pattern.MULTILINE
        if (!isCaseSensitive) flags = flags or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        return Pattern.compile(source, flags)
    }

    fun openSearchResult(result: SearchResult) {
        _pendingSearchSelection.value = SearchSelection(
            chapterIndex = result.chapterIndex,
            queryIndexInChapter = result.queryIndexInChapter,
            queryLength = result.queryLength,
        )
        chapter.loadChapter(result.chapterIndex, restoreChapterPosition = result.queryIndexInChapter)
    }

    fun consumeSearchSelection() {
        _pendingSearchSelection.value = null
    }
}
