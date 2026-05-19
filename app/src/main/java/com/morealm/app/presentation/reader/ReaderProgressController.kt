package com.morealm.app.presentation.reader

import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.entity.ReadProgress
import com.morealm.app.domain.entity.ReadStats
import com.morealm.app.domain.repository.BookRepository
import com.morealm.app.domain.repository.ReadStatsRepository
import com.morealm.app.core.log.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /**
     * 当 restoreProgress 即将刷出 (chapterIdx, position) 时被置 true，
     * collector 在下一次 emit 时把它消费掉，避免恢复进度时被误写回 DB。
     * 调用方仍按"set true 后立即更新 _visiblePage / _scrollProgress"模式。
     */
    var suppressNextProgressSave = false

    /**
     * 「首次章节加载完成」闸门。ViewModel init 启动 progress collector 的同一时刻
     * 还在 IO 上跑 loadBook → parseChapters → loadChapter；parse 约需 100-300ms，
     * 恰好撞上 combine 的初始 emit + debounce(300) 的到点时刻 —— 如果不设闸门，
     * 会在 loadChapter 写进 visiblePage 之前先用初始 (0,0,0) 把 DB 刷成 0，导致
     * 下次打开书退回章首（log 11:39:37.431 现象命门）。
     *
     * ChapterController.loadChapter 成功后（_renderedChapter + visiblePage 全都刷新
     * 完毕）调 [ReaderChapterController.markInitialLoadComplete] → 这里置 true，
     * 之后 saveProgress 才开始真的写库。
     *
     * 注意：这是「单向闸门」—— 置 true 后永远保持，后续章节跳转 / TTS 续播
     * 不会再影响它。
     */
    @Volatile
    var initialLoadComplete: Boolean = false

    /** Snapshot collector job — 由 [start] 启动，[stop] 取消。 */
    private var collectorJob: Job? = null

    /**
     * 仅参与 dedup 的最小集；用 distinctUntilChanged 比较即可，
     * **故意不含 readProgress 文本** —— 该字段在分页 rebuild 时会随 pageCount
     * 变化而抖动，曾经导致 dedup 失效（同一 chapter+position+scroll 在 1.5s
     * 内被 saveProgress 写 4 次，详见 log 19:31:27.004→27.508→27.569→28.549）。
     */
    private data class ProgressSnapshot(
        val chapterIndex: Int,
        val chapterPosition: Int,
        val scrollProgress: Int,
    )

    /**
     * Serializes saveProgress() execution.
     *
     * Without this lock, two coroutines launched from queueProgressSave / saveProgressNow
     * could enter saveProgress() concurrently. They each snapshot _visiblePage.value at a
     * different instant — one reads chapterPosition=191 while another reads 0 — and both
     * call bookRepo.saveProgress(...). The DB ends up with whichever finished last, and
     * which one finishes last is non-deterministic (Dispatchers.IO scheduling).
     *
     * Real-world symptom from log.txt: at the same millisecond two D/Progress lines were
     * emitted with different positions (lines 247/248, 431/432, 449/450, 473/474), causing
     * the persisted "lastReadPosition" to drift on app restart.
     *
     * With the lock the second coroutine waits for the first to finish, then re-reads the
     * latest _visiblePage.value, so both end up writing the same final state.
     */
    private val saveMutex = Mutex()

    // Reading time tracking
    var readingStartTime: Long = System.currentTimeMillis()
    var lastStatsSaveTime: Long = readingStartTime

    /** Set by chapter controller so progress can reference book/chapter state */
    internal lateinit var chapterController: ReaderChapterController

    /**
     * 启动进度快照收集器：把 (chapterIndex, chapterPosition, scrollProgress)
     * 三元组合并成单一冷 Flow，经 distinctUntilChanged + debounce(300ms) +
     * conflate 收敛后调一次 [saveProgress]。
     *
     * 解决两类病理：
     *  - L1：同一稳定状态被重复写（原 lastQueued* dedup 因 readProgress 文本
     *    抖动失效）。distinctUntilChanged 用纯快照比较，彻底去重。
     *  - L2：仿真翻页 commit 后第二条回调用旧 page.index 把 scroll% 拉回 0%。
     *    debounce 300ms 把 0%→2%→0% 反弹合并为最终值，落地的是稳定态。
     *
     * 由 [ReaderViewModel] 在 `progress.chapterController = chapter` 之后立即调用。
     * 必须在 chapterController 注入后才能读 `currentChapterIndex` flow。
     */
    @OptIn(FlowPreview::class)
    fun start() {
        if (collectorJob != null) return
        collectorJob = scope.launch(Dispatchers.IO) {
            combine(
                _scrollProgress,
                _visiblePage,
                chapterController.currentChapterIndex,
            ) { scroll, visible, currentIdx ->
                val cnt = chapterController.chapters.value.size
                val rawIdx = if (visible.chapterIndex in 0 until cnt) {
                    visible.chapterIndex
                } else {
                    currentIdx
                }
                ProgressSnapshot(
                    chapterIndex = rawIdx.coerceIn(0, (cnt - 1).coerceAtLeast(0)),
                    chapterPosition = visible.chapterPosition.coerceAtLeast(0),
                    scrollProgress = scroll,
                )
            }
                .distinctUntilChanged()
                .filter { snap ->
                    // 与原 onVisiblePageChanged 中 scrollBoundaryPreview 判定保持等价：
                    // SCROLL 模式下 visibleChapter ≠ currentChapter 是跨章 preview，
                    // 不持久化（等 currentChapterIndex 真正推进后再 emit 一次）。
                    val current = chapterController.currentChapterIndex.value
                    !(pageTurnMode() == PageTurnMode.SCROLL && snap.chapterIndex != current)
                }
                .debounce(300)
                .conflate()
                .collect {
                    if (suppressNextProgressSave) {
                        suppressNextProgressSave = false
                        return@collect
                    }
                    saveProgress()
                }
        }
    }

    fun stop() {
        collectorJob?.cancel()
        collectorJob = null
    }

    // ── Progress Tracking ──

    // Tracks the previous direction of scroll% movement so we can detect direction
    // flips (e.g. 0→7→0→7…). +1 = increasing, -1 = decreasing, 0 = unknown.
    // Used only by the diagnostic log inside updateScrollProgress.
    private var lastScrollProgressDirection: Int = 0

    fun updateScrollProgress(pct: Int) {
        val old = _scrollProgress.value
        val next = pct.coerceIn(0, 100)
        if (next == old) return
        _scrollProgress.value = next
        // 诊断日志保留——大跳/方向反转时打一行，定位翻页反弹之类异常。
        // 实际持久化由快照收集器 (start) 统一处理，这里不再 launch save。
        val delta = next - old
        val direction = when {
            delta > 0 -> 1
            delta < 0 -> -1
            else -> 0
        }
        val flipped = lastScrollProgressDirection != 0 &&
            direction != 0 &&
            direction != lastScrollProgressDirection
        if (kotlin.math.abs(delta) >= 5 || flipped) {
            AppLog.debug(
                "ProgressTrace",
                "updateScrollProgress jump | $old%→$next% (Δ=$delta)" +
                    " | flip=$flipped" +
                    " | chapterPosition=${_visiblePage.value.chapterPosition}" +
                    " | chapterIndex=${_visiblePage.value.chapterIndex}",
            )
        }
        lastScrollProgressDirection = direction
    }

    /**
     * 强制立即保存（绕过 debounce）。
     * 用于退出阅读器、TTS 切章、手动 navigation 等需要确定写入的场景。
     */
    fun queueProgressSave(@Suppress("UNUSED_PARAMETER") force: Boolean = false) {
        scope.launch(Dispatchers.IO) { saveProgress() }
    }

    suspend fun saveProgress() {
        // saveMutex serializes concurrent callers — see field doc on `saveMutex`.
        saveMutex.withLock {
            saveProgressLocked()
        }
    }

    private suspend fun saveProgressLocked() {
        try {
            val book = chapterController.book.value ?: return
            // 首次章节加载未完成前拒绝写库 —— 防止 ViewModel init 阶段的
            // 初始 combine emit + debounce(300ms) 把 (0,0,0) 刷进 DB 盖掉
            // 上次阅读进度（详见 [initialLoadComplete] KDoc）。
            if (!initialLoadComplete) {
                AppLog.debug("Progress", "saveProgress gated: initial load not complete yet")
                return
            }
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
        // ── chapterPosition=0 feedback loop 守卫 ──
        //
        // 背景：CanvasRenderer 走 LazyScrollRenderer 时，「段首回调」(line 1644) 会带
        // p.firstChapterPosition 上报；该值在用户停在段首行时常常 = 0 或较小值，
        // 紧跟其后的「页/段切换回调」(line 1683 / 1683 等价 path) 又会带回精确的
        // chapterPosition (如 191)。两条回调在同一帧错峰发射 → _visiblePage 被
        // 0↔191 lockstep toggle，下次 saveProgress 写出的 lastReadPosition 就漂回
        // 章首（详见 log.txt 19:05:13~20 段诊断）。
        //
        // 修法：同章 + 上报值 = 0 + 旧值 > 0 → 视作"渲染层段首回调的影子上报"，
        // 保留旧值。跨章（index 变化）时 0 是合法的新章首，正常应用。
        val sameChapter = index == oldVisiblePage.chapterIndex
        val keptPosition = if (
            sameChapter &&
            chapterPosition == 0 &&
            oldVisiblePage.chapterPosition > 0
        ) {
            // 调试日志保留：诊断假阳性（万一某些路径合法 reset 到 0）。
            AppLog.debug(
                "Progress",
                "ignored zero chapterPosition shadow update | idx=$index keep=${oldVisiblePage.chapterPosition}",
            )
            oldVisiblePage.chapterPosition
        } else {
            chapterPosition
        }
        _visiblePage.value = VisibleReaderPage(index, title, readProgress, keptPosition)
        // 持久化由 [start] 启动的快照收集器接管，这里只更新内存 state。
        // - SCROLL 模式跨章 preview 由 collector 内 filter 拦截
        // - 同章页内变化经 distinctUntilChanged + debounce 自然合并
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
