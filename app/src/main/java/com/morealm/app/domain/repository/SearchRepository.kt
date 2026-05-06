package com.morealm.app.domain.repository

import com.morealm.app.domain.db.BookDao
import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.entity.SearchBook
import com.morealm.app.domain.entity.BookSource
import com.morealm.app.domain.webbook.WebBook
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SearchRepository @Inject constructor(
    private val bookDao: BookDao,
    private val sourceRepo: SourceRepository,
) {
    suspend fun searchLocalBooks(keyword: String): List<Book> =
        bookDao.searchBooks("%$keyword%")

    suspend fun getEnabledSources(): List<BookSource> =
        sourceRepo.getEnabledSourcesList()

    /**
     * Lite projection used by [SearchViewModel] to schedule per-source workers without
     * loading all nested rule JSON. Pair with [loadSourceByUrl] to fetch the full entity on
     * demand right before a worker actually calls into [WebBook].
     */
    suspend fun getEnabledSourcesLite() = sourceRepo.getEnabledSourcesLite()

    /** O(1) count of enabled sources. */
    suspend fun getEnabledSourceCount(): Int = sourceRepo.getEnabledSourceCount()

    /** Single-source materialization for workers. Returns null if the source was deleted/disabled mid-search. */
    suspend fun loadSourceByUrl(url: String): BookSource? = sourceRepo.getByUrl(url)

    suspend fun searchOnlineSource(source: BookSource, keyword: String): List<SearchBook> =
        WebBook.searchBookAwait(source, keyword)
}
