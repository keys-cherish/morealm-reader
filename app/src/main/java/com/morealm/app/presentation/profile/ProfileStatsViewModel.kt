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

    val totalBooks: StateFlow<Int> = flow { emit(bookRepo.countLogicalBooks()) }
        .stateIn(viewModelScope, SharingStarted.Lazily, 0)

    private val recentStats = readStatsRepo.getRecent(30)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val totalReadMs: StateFlow<Long> = recentStats
        .map { stats -> stats.sumOf { it.readDurationMs } }
        .stateIn(viewModelScope, SharingStarted.Lazily, 0L)

    val todayReadMs: StateFlow<Long> = recentStats.map { stats ->
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        stats.find { it.date == today }?.readDurationMs ?: 0L
    }.stateIn(viewModelScope, SharingStarted.Lazily, 0L)

    val recentDays: StateFlow<Int> = recentStats.map { stats ->
        if (stats.isEmpty()) return@map 0
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val dates = stats.map { it.date }.toSet()
        var count = 0
        val cal = Calendar.getInstance()
        while (dates.contains(fmt.format(cal.time))) {
            count++
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        count
    }.stateIn(viewModelScope, SharingStarted.Lazily, 0)

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
                val peakHour = "22:00"

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
                    totalBooks = if (booksFinished > 0) booksFinished else allBooks.size,
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
