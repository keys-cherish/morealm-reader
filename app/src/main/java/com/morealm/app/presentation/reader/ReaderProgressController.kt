package com.morealm.app.presentation.reader

import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.entity.ReadProgress
import com.morealm.app.domain.entity.ReadStats
import com.morealm.app.domain.repository.BookRepository
import com.morealm.app.domain.repository.ReadStatsRepository
import com.morealm.app.core.log.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manages reading progress persistence and reading stats tracking.
 * Extracted from ReaderViewModel.
 */
class ReaderProgressController(
    private val bookRepo: BookRepository,
    private val readStatsRepo: ReadStatsRepository,
    private val scope: CoroutineScope,
    /** Lazily provide the page turn mode from settings */
    private val pageTurnMode: () -> PageTurnMode,
    /**
     * Hook fired AFTER a successful local progress save. Used by the
     * WebDav per-book progress sync to fire-and-forget upload the new
     * cursor without coupling this controller to network code. Default
     * is a no-op so unit tests / non-sync builds aren't affected.
     */
    private val onProgressSaved: suspend (Book, ReadProgress) -> Unit = { _, _ -> },
) {
    // ── State ──
    val _scrollProgress = MutableStateFlow(0)
    val scrollProgress: StateFlow<Int> = _scrollProgress.asStateFlow()

    val _visiblePage = MutableStateFlow(VisibleReaderPage())
    val visiblePage: StateFlow<VisibleReaderPage> = _visiblePage.asStateFlow()

    var suppressNextProgressSave = false
    private var lastQueuedProgressChapterIndex = -1
    private var lastQueuedScrollProgress = -1
    private var lastQueuedVisibleReadProgress = ""
    private var lastQueuedChapterPosition = -1

    // Reading time tracking
    var readingStartTime: Long = System.currentTimeMillis()
    var lastStatsSaveTime: Long = readingStartTime

    /** Set by chapter controller so progress can reference book/chapter state */
    internal lateinit var chapterController: ReaderChapterController

    // ── Progress Tracking ──

    fun updateScrollProgress(pct: Int) {
        val old = _scrollProgress.value
        val next = pct.coerceIn(0, 100)
        _scrollProgress.value = next
        if (suppressNextProgressSave) {
            suppressNextProgressSave = false
            return
        }
        if (next != old) {
            queueProgressSave()
        }
    }

    fun queueProgressSave(force: Boolean = false) {
        val chapterIndex = chapterController.currentChapterIndex.value
        val scrollProgress = _scrollProgress.value
        val visibleReadProgress = _visiblePage.value.readProgress
        val chapterPosition = _visiblePage.value.chapterPosition
        if (!force &&
            chapterIndex == lastQueuedProgressChapterIndex &&
            scrollProgress == lastQueuedScrollProgress &&
            visibleReadProgress == lastQueuedVisibleReadProgress &&
            chapterPosition == lastQueuedChapterPosition
        ) {
            return
        }
        lastQueuedProgressChapterIndex = chapterIndex
        lastQueuedScrollProgress = scrollProgress
        lastQueuedVisibleReadProgress = visibleReadProgress
        lastQueuedChapterPosition = chapterPosition
        scope.launch(Dispatchers.IO) { saveProgress() }
    }

    suspend fun saveProgress() {
        try {
            val book = chapterController.book.value ?: return
            val chapterCount = chapterController.chapters.value.size
            val visible = _visiblePage.value
            val currentIndex = chapterController.currentChapterIndex.value
            val visibleIndex = visible.chapterIndex
            val chapterIdx = when {
                visibleIndex !in 0 until chapterCount -> currentIndex
                else -> visibleIndex
            }.coerceIn(0, (chapterCount - 1).coerceAtLeast(0))
            val chapterPosition = visible.chapterPosition.coerceAtLeast(0)
            val scrollPct = _scrollProgress.value / 100f
            val totalProgress = if (chapterCount > 0) {
                (chapterIdx.toFloat() + scrollPct) / chapterCount
            } else 0f
            val progress = ReadProgress(
                bookId = book.id,
                chapterIndex = chapterIdx,
                chapterPosition = chapterPosition,
                totalProgress = totalProgress.coerceIn(0f, 1f),
                scrollProgress = _scrollProgress.value,
            )
            AppLog.debug("Progress", buildString {
                append("saveProgress")
                append(" | chapter=$chapterIdx/$chapterCount")
                append(" | position=$chapterPosition")
                append(" | scroll=${_scrollProgress.value}%")
                append(" | total=${String.format("%.4f", totalProgress)}")
            })
            bookRepo.saveProgress(progress)
            bookRepo.update(book.copy(
                lastReadChapter = chapterIdx,
                lastReadPosition = chapterPosition,
                lastReadAt = System.currentTimeMillis(),
                readProgress = progress.totalProgress,
                totalChapters = chapterCount,
            ))
            lastQueuedProgressChapterIndex = chapterIdx
            lastQueuedScrollProgress = progress.scrollProgress
            lastQueuedVisibleReadProgress = _visiblePage.value.readProgress
            lastQueuedChapterPosition = chapterPosition
            flushReadingStats()
            // Fire-and-forget hook for WebDav progress sync. Wrapped in
            // runCatching because a network failure here must NOT bubble
            // up and mask the local save success.
            runCatching { onProgressSaved(book, progress) }
                .onFailure { AppLog.warn("Progress", "onProgressSaved hook threw: ${it.message}") }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.error("Progress", "Failed to save progress", e)
        }
    }

    fun saveProgressNow() {
        scope.launch(Dispatchers.IO) { saveProgress() }
    }

    suspend fun saveProgressNowAndWait() {
        withContext(Dispatchers.IO) { saveProgress() }
    }

    // ── Visible Page Tracking ──

    fun onVisiblePageChanged(index: Int, title: String, readProgress: String, chapterPosition: Int = 0) {
        if (index !in chapterController.chapters.value.indices) return
        val oldVisiblePage = _visiblePage.value
        val visibleChanged = oldVisiblePage.chapterIndex != index ||
            oldVisiblePage.title != title ||
            oldVisiblePage.readProgress != readProgress ||
            oldVisiblePage.chapterPosition != chapterPosition
        _visiblePage.value = VisibleReaderPage(index, title, readProgress, chapterPosition)
        val chapterChanged = index != chapterController.currentChapterIndex.value
        val scrollBoundaryPreview = pageTurnMode() == PageTurnMode.SCROLL && chapterChanged
        if (!scrollBoundaryPreview && !chapterChanged && visibleChanged) {
            queueProgressSave()
        }
    }

    fun onVisibleChapterChanged(index: Int) {
        val chapter = chapterController.chapters.value.getOrNull(index) ?: return
        onVisiblePageChanged(index, chapter.title, _visiblePage.value.readProgress, _visiblePage.value.chapterPosition)
    }

    // ── Reading Stats ──

    suspend fun saveReadingStats(durationMs: Long) {
        try {
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val existing = readStatsRepo.getByDate(today)
            val stats = (existing ?: ReadStats(date = today)).copy(
                readDurationMs = (existing?.readDurationMs ?: 0L) + durationMs,
                pagesRead = (existing?.pagesRead ?: 0) + 1,
            )
            readStatsRepo.save(stats)
        } catch (_: Exception) {}
    }

    fun flushReadingStats() {
        val now = System.currentTimeMillis()
        val elapsed = now - readingStartTime
        if (elapsed > 30_000 && now - lastStatsSaveTime > 60_000) {
            lastStatsSaveTime = now
            readingStartTime = now
            scope.launch(Dispatchers.IO) {
                saveReadingStats(elapsed)
            }
        }
    }
}
