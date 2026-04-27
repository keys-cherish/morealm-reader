package com.morealm.app.presentation.reader

import android.net.Uri
import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.entity.BookChapter
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

    // ── Search Functions ──

    fun searchFullText(query: String) {
        if (query.isBlank()) { _searchResults.value = emptyList(); return }
        _searching.value = true
        scope.launch(Dispatchers.IO) {
            try {
                val book = chapter.book.value ?: return@launch
                val isWebBook = chapter.isWebBook(book)
                val chapterList = chapter.chapters.value
                val results = mutableListOf<SearchResult>()
                val lowerQuery = query.lowercase()

                for (ch in chapterList) {
                    val content = if (isWebBook) {
                        chapter.loadWebChapterContent(book, ch, ch.index)
                    } else {
                        val localPath = book.localPath ?: break
                        LocalBookParser.readChapter(context, Uri.parse(localPath), book.format, ch)
                    }
                    val plainText = content.stripHtml()
                    val idx = plainText.lowercase().indexOf(lowerQuery)
                    if (idx >= 0) {
                        val start = (idx - 20).coerceAtLeast(0)
                        val end = (idx + query.length + 30).coerceAtMost(plainText.length)
                        val snippet = (if (start > 0) "..." else "") +
                            plainText.substring(start, end).trim() +
                            (if (end < plainText.length) "..." else "")
                        results.add(
                            SearchResult(
                                chapterIndex = ch.index,
                                chapterTitle = ch.title,
                                snippet = snippet,
                                query = query,
                                queryIndexInChapter = idx,
                                queryLength = query.length,
                            ),
                        )
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

    fun clearSearchResults() { _searchResults.value = emptyList() }

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
