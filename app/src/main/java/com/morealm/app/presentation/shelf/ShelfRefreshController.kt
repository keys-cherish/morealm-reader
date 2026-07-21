package com.morealm.app.presentation.shelf

import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.db.BookDao
import com.morealm.app.domain.db.ChapterDao
import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.entity.BookChapter
import com.morealm.app.domain.entity.BookFormat
import com.morealm.app.domain.entity.SearchBook
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
 * Background batch refresher for shelf web-book TOCs (参考成熟开源阅读器实现的
 * 书架后台批量更新目录逻辑：入队 / 去重 / 后台任务调度）。
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
            refreshBook(book)
        } catch (e: Exception) {
            _errorCount.value += 1
            AppLog.warn(TAG, "refresh failed for ${book.title}: ${e.message?.take(160)}")
        } finally {
            bumpProgress()
        }
    }

    /** 详情页首次预览使用的同步入口；返回成功时章节和详情元数据都已经落库。 */
    suspend fun refreshNow(book: Book): Result<Int> = withContext(Dispatchers.IO) {
        runCatching { refreshBook(book) }.onFailure { error ->
            AppLog.warn(TAG, "prepare ${book.title} failed: ${error.message?.take(160)}")
        }
    }

    private suspend fun refreshBook(initialBook: Book): Int {
        val source = initialBook.sourceUrl?.let { sourceRepo.getByUrl(it) }
            ?: sourceRepo.getByUrl(initialBook.origin)
            ?: error("书源已被移除或停用")
        var book = bookDao.getById(initialBook.id) ?: initialBook
        var tocUrl = book.tocUrl?.takeIf { it.isNotBlank() } ?: book.bookUrl

        // 发现结果通常只有列表字段。先解析详情，目录依赖 tocUrl 时才有真实入口。
        if (tocUrl == book.bookUrl && !book.hasDetail) {
            val detailed = WebBook.getBookInfoAwait(
                source,
                SearchBook(
                    bookUrl = book.bookUrl,
                    origin = book.origin,
                    originName = book.originName,
                    name = book.title,
                    author = book.author,
                    coverUrl = book.coverUrl,
                    intro = book.description,
                    wordCount = book.wordCount,
                    tocUrl = book.tocUrl.orEmpty(),
                    variable = book.variable,
                ),
            )
            book = book.copy(
                title = detailed.name.ifBlank { book.title },
                author = detailed.author.ifBlank { book.author },
                coverUrl = detailed.coverUrl?.takeIf { it.isNotBlank() } ?: book.coverUrl,
                description = detailed.intro?.takeIf { it.isNotBlank() } ?: book.description,
                wordCount = detailed.wordCount?.takeIf { it.isNotBlank() } ?: book.wordCount,
                kind = detailed.kind?.takeIf { it.isNotBlank() } ?: book.kind,
                tocUrl = detailed.tocUrl.takeIf { it.isNotBlank() },
                variable = detailed.variable ?: book.variable,
                hasDetail = true,
            )
            bookDao.update(book)
            tocUrl = book.tocUrl?.takeIf { it.isNotBlank() } ?: book.bookUrl
        }

        val toc = WebBook.getChapterListAwait(
            bookSource = source,
            bookUrl = book.bookUrl,
            tocUrl = tocUrl,
        ).mapIndexed { index, chapter ->
            BookChapter(
                id = "${book.id}_$index",
                bookId = book.id,
                index = index,
                title = chapter.title,
                url = chapter.url,
                isVolume = chapter.isVolume,
            )
        }
        if (toc.isEmpty()) error("书源没有解析到章节目录")

        // 只有完整拉到新目录后才替换，网络错误或规则失效时不能清空旧章节。
        chapterDao.deleteByBookId(book.id)
        chapterDao.insertAll(toc)

        val current = bookDao.getById(book.id) ?: book
        val oldTotal = current.totalChapters
        val newTotal = toc.size
        val isInitialFetch = oldTotal == 0
        val newCount = if (isInitialFetch) 0 else (newTotal - oldTotal).coerceAtLeast(0)
        bookDao.updateLastCheck(
            id = book.id,
            total = newTotal,
            newCount = if (newCount > 0) newCount else current.lastCheckCount,
            time = System.currentTimeMillis(),
        )
        AppLog.debug(
            TAG,
            "refreshed ${book.title}: $oldTotal → $newTotal " +
                when {
                    isInitialFetch -> "(initial fetch)"
                    newCount > 0 -> "(+$newCount new)"
                    else -> ""
                },
        )
        return newTotal
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
