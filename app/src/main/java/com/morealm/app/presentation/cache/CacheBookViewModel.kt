package com.morealm.app.presentation.cache

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

    data class CacheStat(val totalChapters: Int, val cachedChapters: Int)

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
}
