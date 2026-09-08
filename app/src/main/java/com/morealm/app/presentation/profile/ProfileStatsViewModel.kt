package com.morealm.app.presentation.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morealm.app.domain.repository.BookRepository
import com.morealm.app.domain.repository.ReadStatsRepository
import com.morealm.app.core.log.AppLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class AnnualReport(
    val year: Int,
    val totalBooks: Int,
    val totalWordsWan: Int,
    val totalDurationHours: Int,
    val activeDays: Int,
    val longestSessionMin: Int,
    val peakHour: String,
    val favoriteBook: String,
    val tags: List<String>,
)

@HiltViewModel
class ProfileStatsViewModel @Inject constructor(
    private val bookRepo: BookRepository,
    private val readStatsRepo: ReadStatsRepository,
) : ViewModel() {

    val readBooks = bookRepo.getReadingHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val completedBooks = readBooks.map { books -> books.filter { it.readProgress.isFinite() && it.readProgress >= 1f } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val totalBooks: StateFlow<Int> = readBooks.map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val dailyStats = readStatsRepo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val today = flow {
        while (true) {
            emit(java.time.LocalDate.now())
            kotlinx.coroutines.delay(60_000)
        }
    }.distinctUntilChanged()

    val totalReadMs: StateFlow<Long> = dailyStats
        .map { stats -> stats.sumOf { it.readDurationMs.coerceAtLeast(0L) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val todayReadMs: StateFlow<Long> = combine(dailyStats, today) { stats, date ->
        stats.firstOrNull { it.date == date.toString() }?.readDurationMs?.coerceAtLeast(0L) ?: 0L
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    val recentDays: StateFlow<Int> = combine(dailyStats, today) { stats, date ->
        readingStreak(stats.filter { it.readDurationMs > 0 }.map { it.date }.toSet(), date)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _annualReport = MutableStateFlow<AnnualReport?>(null)
    val annualReport: StateFlow<AnnualReport?> = _annualReport.asStateFlow()

    fun loadAnnualReport(year: Int = Calendar.getInstance().get(Calendar.YEAR)) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val prefix = "$year"
                val yearStats = readStatsRepo.getByYear(prefix)
                val totalMs = yearStats.sumOf { it.readDurationMs }
                val totalWords = yearStats.sumOf { it.wordsRead }
                val booksFinished = yearStats.sumOf { it.booksFinished }
                val activeDays = yearStats.count { it.readDurationMs > 0 }
                val longestMs = yearStats.maxOfOrNull { it.readDurationMs } ?: 0L
                val peakHour = "暂无分时数据"

                val allBooks = bookRepo.getAllBooksSync()
                val favoriteBook = allBooks
                    .filter { it.lastReadAt > 0 && it.readProgress > 0f }
                    .maxByOrNull { it.readProgress }
                    ?.title ?: allBooks.firstOrNull()?.title ?: ""

                val tags = allBooks
                    .flatMap { listOfNotNull(it.category, it.kind) }
                    .flatMap { it.split(",", "\u3001", "/", ";") }
                    .map { it.trim() }
                    .filter { it.isNotBlank() && it.length <= 6 }
                    .groupingBy { it }
                    .eachCount()
                    .entries
                    .sortedByDescending { it.value }
                    .take(5)
                    .map { it.key }

                _annualReport.value = AnnualReport(
                    year = year,
                    totalBooks = booksFinished,
                    totalWordsWan = (totalWords / 10000).toInt(),
                    totalDurationHours = (totalMs / 3600000).toInt(),
                    activeDays = activeDays,
                    longestSessionMin = (longestMs / 60000).toInt(),
                    peakHour = peakHour,
                    favoriteBook = favoriteBook,
                    tags = tags,
                )
            } catch (e: Exception) {
                AppLog.error("Profile", "Failed to load annual report", e)
            }
        }
    }
}
