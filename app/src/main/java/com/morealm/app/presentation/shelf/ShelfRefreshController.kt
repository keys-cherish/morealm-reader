package com.morealm.app.presentation.shelf

import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.db.BookDao
import com.morealm.app.domain.db.ChapterDao
import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.entity.BookChapter
import com.morealm.app.domain.entity.BookFormat
import com.morealm.app.domain.repository.SourceRepository
import com.morealm.app.domain.webbook.WebBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Background batch refresher for shelf web-book TOCs (Legado parity — see
 * io.legado.app.ui.main.MainViewModel.upToc / addToWaitUp / startUpTocJob).
 *
 * Behavior contract
 * -----------------
 *  - Pull TOC for every refreshable web book (format = WEB && canUpdate)
 *  - Compare new total-chapter count against the stored value; if it grew,
 *    set Book.lastCheckCount = (new − old). The shelf renders this as a
 *    "N 新" badge until the user opens the book
 *  - Bounded parallelism (default 4) — overshooting can get a single source
 *    rate-limited, which we observed in the wild
 *  - Refresh requests for books already in flight are coalesced (no double work)
 *  - Cancellation is graceful: setting [cancel] stops the queue draining but
 *    in-flight TOC fetches finish normally so we don't half-update a book
 *
 * State exposed for UI
 * --------------------
 *  - [isRefreshing]  : true while the queue is non-empty or a worker is running
 *  - [progress]      : (done, total) — drives the linear indicator on the shelf
 *  - [errorCount]    : per-session count of refresh failures (UI may surface)
 */
@Singleton
class ShelfRefreshController @Inject constructor(
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val sourceRepo: SourceRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val inFlight: MutableSet<String> = mutableSetOf()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /** Pair(done, total) — total is set when refresh starts, done increments per-book. */
    private val _progress = MutableStateFlow(0 to 0)
    val progress: StateFlow<Pair<Int, Int>> = _progress.asStateFlow()

    private val _errorCount = MutableStateFlow(0)
    val errorCount: StateFlow<Int> = _errorCount.asStateFlow()

    private var currentJob: Job? = null

    /**
     * Kick off (or queue into) a refresh of [books].
     *
     * If a refresh is already running, only the books not currently in flight
     * are appended — no second worker is spawned.
     *
     * @param books the candidate set; non-WEB and canUpdate=false books are filtered here
     * @param parallelism max concurrent TOC fetches; clamped to [1, 8]
     */
    fun refresh(books: List<Book>, parallelism: Int = 4) {
        val targets = books.filter {
            it.format == BookFormat.WEB && it.canUpdate && !it.bookUrl.isBlank()
        }
        if (targets.isEmpty()) return

        scope.launch {
            val toEnqueue = mutex.withLock {
                val fresh = targets.filter { it.id !in inFlight }
                inFlight.addAll(fresh.map { it.id })
                fresh
            }
            if (toEnqueue.isEmpty()) return@launch

            // Update progress.total atomically. We add to the running total if a
            // refresh was already mid-flight; this lets the UI keep one continuous
            // bar instead of resetting whenever new books are queued.
            _progress.value = _progress.value.copy(second = _progress.value.second + toEnqueue.size)
            _isRefreshing.value = true

            // Bound the parallelism — Legado defaults to AppConfig.threadCount (4-8).
            val pool = parallelism.coerceIn(1, 8)
            val chunks = toEnqueue.chunked(pool)
            for (chunk in chunks) {
                val deferred = chunk.map { book ->
                    async { refreshOne(book) }
                }
                deferred.awaitAll()
            }

            // Drain in-flight set; if nothing else is queued, mark idle.
            mutex.withLock {
                inFlight.removeAll(toEnqueue.map { it.id }.toSet())
                if (inFlight.isEmpty()) {
                    _isRefreshing.value = false
                    _progress.value = 0 to 0  // reset for next session
                }
            }
        }.also { currentJob = it }
    }

    /**
     * Refresh a single book's TOC. Failures are swallowed (logged + counted)
     * — one bad source must not abort the whole batch.
     */
    private suspend fun refreshOne(book: Book) = withContext(Dispatchers.IO) {
        try {
            val source = book.sourceUrl?.let { sourceRepo.getByUrl(it) }
                ?: sourceRepo.getByUrl(book.origin)
                ?: run {
                    AppLog.warn(TAG, "no source for ${book.title} (${book.origin})")
                    bumpProgress()
                    return@withContext
                }
            val toc: List<BookChapter> = WebBook.getChapterListAwait(
                bookSource = source,
                bookUrl = book.bookUrl,
                tocUrl = book.tocUrl?.takeIf { it.isNotBlank() } ?: book.bookUrl,
            ).mapIndexed { i, ch ->
                BookChapter(
                    id = "${book.id}_$i",
                    bookId = book.id,
                    index = i,
                    title = ch.title,
                    url = ch.url,
                    isVolume = ch.isVolume,
                )
            }

            // Replace chapter list. We replace rather than diff because chapter URLs
            // can change on some sources between refreshes (cache-buster query params).
            // The user's read-progress key is `lastReadChapter` (index, not URL), so
            // a full replace doesn't lose their place.
            chapterDao.deleteByBookId(book.id)
            if (toc.isNotEmpty()) {
                chapterDao.insertAll(toc)
            }

            // Diff against stored count to compute lastCheckCount. The previous value
            // is read from DB, not from the [book] arg, because another writer may
            // have touched the row between when we built the list and now.
            val current = bookDao.getById(book.id)
            val oldTotal = current?.totalChapters ?: book.totalChapters
            val newTotal = toc.size
            val newCount = (newTotal - oldTotal).coerceAtLeast(0)
            bookDao.updateLastCheck(
                id = book.id,
                total = newTotal,
                newCount = if (newCount > 0) newCount else (current?.lastCheckCount ?: 0),
                time = System.currentTimeMillis(),
            )
            AppLog.debug(
                TAG,
                "refreshed ${book.title}: $oldTotal → $newTotal ${if (newCount > 0) "(+$newCount new)" else ""}"
            )
        } catch (e: Exception) {
            _errorCount.value += 1
            AppLog.warn(TAG, "refresh failed for ${book.title}: ${e.message?.take(160)}")
        } finally {
            bumpProgress()
        }
    }

    private fun bumpProgress() {
        _progress.value = _progress.value.copy(first = _progress.value.first + 1)
    }

    /** Cancel the queue. In-flight fetches finish naturally to avoid half-state. */
    fun cancel() {
        currentJob?.cancel()
        scope.launch {
            mutex.withLock {
                inFlight.clear()
                _isRefreshing.value = false
                _progress.value = 0 to 0
                _errorCount.value = 0
            }
        }
    }

    companion object {
        private const val TAG = "ShelfRefresh"
    }
}
