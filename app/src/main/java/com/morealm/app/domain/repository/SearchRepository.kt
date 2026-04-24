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

    suspend fun searchOnlineSource(source: BookSource, keyword: String): List<SearchBook> =
        WebBook.searchBookAwait(source, keyword)
}
