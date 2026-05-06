package com.morealm.app.presentation.reader.scroll

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.render.ScrollParagraph
import com.morealm.app.domain.render.ScrollParagraphType
import com.morealm.app.domain.render.TextChapter
import com.morealm.app.domain.render.loadingScrollParagraph
import com.morealm.app.domain.render.toScrollParagraphs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * SCROLL 模式滑动窗口数据源 —— **彻底取代** SCROLL 路径上的 commitChapterShift /
 * loadChapter / coordinator REBUILD / restoreProgress JUMP 这套翻页时代的指挥棒
 * （详见 plan：`C:/Users/test/.claude/plans/glittery-dancing-swing.md`，源码命门
 * 由 `temp/MoRealm_log_*.txt` + `temp/solution.txt` 共同定位）。
 *
 * ── 设计哲学 ──
 *
 * 旧实现的核心病灶是「LazyColumn 瀑布流被强行拉去配合 ViewPager 翻页语义」：
 *   - 用户滑到 cur 章的最后 15 段（near-bottom）→ derivedStateOf 派生 currentChapter →
 *     onNearBottom → ReaderViewModel.nextChapter() → commitChapterShift{Next} → 多数 REJECT
 *     → fallback loadChapter → 清空 _prev/_cur/_nextTextChapter → coordinator REBUILD →
 *     restoreProgress JUMP → LazyScrollSection effect 1 reset → paragraphsState.clear() +
 *     addAll → requestScrollToItem(anchor) —— 用户 fling 被中断，视觉上撞墙 + 瞬移
 *
 * 新实现把 SCROLL 模式当回 **真正的瀑布流**：章与章之间在 LazyColumn 眼里只是相邻 item，
 * 没有「切章」概念。窗口里包含 ±N 章段落，用户向下滑，距离窗口底 < 20 段就**静默拉**
 * 下一章 append 到尾部；向上滑同理 prepend。**永远不**调 scrollToItem 干扰用户滚动。
 *
 * ── 不变量（破坏即崩） ──
 *
 *   1. `paragraphs` mutation 永远不调 `clear()`（除 [resetTo] 显式跳转外）；只
 *      `removeAll { chapterIndex == ch }` + `addAll(0, ...)` / `addAll(...)`，让
 *      LazyListState 自动锚定不变。
 *   2. 添加任意章节段落前 *先* `removeAll { it.chapterIndex == ch }` —— 杜绝同章节段落
 *      跨 layout 周期碰撞产生 `Key was already used` 崩溃。
 *   3. 每次新章节进窗口生成新 `generationId = System.nanoTime()`，参与 `ScrollParagraph.key`
 *      拼接，再保险一层 key 唯一性。
 *   4. 所有 mutation 操作（[appendNext] / [prependPrev] / [resetTo]）由 [windowMutex]
 *      串行化，杜绝并发 race（fast-fling 下可能同时触发 prepend 和 append）。
 *
 * ── 公开 API 一览 ──
 *
 * @property paragraphs 跨章段落滑动窗口 —— LazyColumn 直接消费的 SnapshotStateList。
 * @property loadedChapters 当前窗口包含的章节 idx 升序列表。
 * @property chapterByIdx 章节 idx → [TextChapter] 映射，给 jump / TTS hit-test / 标题查询用。
 * @property pendingJump 命令式跳转请求（书签 / TOC 跳转），LazyScrollSection 转发到
 *           [com.morealm.app.ui.reader.renderer.LazyScrollRenderer.jumpChapterIndex] 等参数。
 *           处理完调 [consumePendingJump] 清掉。
 * @property chapterLayouter 由 Compose 层（CanvasRenderer / LazyScrollSection）通过
 *           [LaunchedEffect] 注入的「排版函数」—— 这个函数依赖 LayoutInputs（屏幕尺寸 /
 *           paint / 字号 / padding），只能在 Compose 上下文里构造。源码层面用 var 让
 *           Compose 端 attach；attach 之前调 [resetTo] / [appendNext] / [prependPrev] 是
 *           安全的（fetch 部分可以先做，layouter 未到位时 loadAndInsertChapter 会 noop
 *           并保留 LOADING 占位，等下一次重试）。
 *
 * ── 与 ReaderChapterController 的协作 ──
 *
 *   - [chapterFetcher] 是注入的「拉一章正文」函数，应该回到
 *     [com.morealm.app.presentation.reader.ReaderChapterController.fetchAndPrepareChapter]
 *     （它已经包含 fetch + replace rules + 繁简转换）。
 *   - [chapterLayouter] 把 String 正文 + 标题 + idx 排版成 [TextChapter]，应回到
 *     [com.morealm.app.domain.render.ChapterProvider.layoutChapter]（同步 API）。
 *   - [onCenterChapterChanged] 视口中心段所属章节漂移到新值时回调，应回到
 *     [com.morealm.app.presentation.reader.ReaderChapterController.setCurrentChapterIndexFromScroll]
 *     —— 让 cur 章节真值流跟上视口（仅 UI 派生量），不触发 loadChapter 副作用。
 *
 * @param scope 协程 scope，应与 ReaderViewModel 同生命周期（viewModelScope）。
 * @param chapterFetcher 拉章节正文 + 准备好（replace rules / 繁简转换）的函数。
 * @param chapterTitleProvider 章节 idx → 标题。
 * @param chapterCountProvider 总章节数，边界检查用。
 * @param omitChapterTitleProvider 控制 [TextChapter.toScrollParagraphs] 是否跳过章首
 *        伪标题段（本地 TXT 自动分章场景）。
 * @param onCenterChapterChanged 视口中心章节漂移回调（debounced 由 LazyScrollSection 处理）。
 * @param maxChaptersInWindow 窗口最多容纳章节数。超出时从远端 trim（见 [trimDistantChapters]）。
 *        默认 5 章 ≈ 万字级。再大内存压力会上来。
 * @param placeholderHeight LOADING 占位段的高度（px）。500-700 比较自然。
 */
class ChapterWindowSource(
    private val scope: CoroutineScope,
    private val chapterFetcher: suspend (chapterIdx: Int) -> String?,
    private val chapterTitleProvider: (chapterIdx: Int) -> String,
    private val chapterCountProvider: () -> Int,
    private val omitChapterTitleProvider: () -> Boolean,
    private val onCenterChapterChanged: (chapterIdx: Int) -> Unit,
    private val maxChaptersInWindow: Int = 5,
    private val placeholderHeight: Float = 600f,
) {
    /**
     * 排版函数（由 Compose 层 attach）—— 见类级文档「ChapterLayouter 注入」。
     *
     * 在 attach 之前调度的 fetch 任务会等到 layouter 到位后才能完成插入；为简化实现，
     * 当 layouter == null 时 [loadAndInsertChapter] 直接保留 LOADING 占位 + 重试一次。
     */
    var chapterLayouter: (suspend (title: String, content: String, chapterIdx: Int, chaptersSize: Int, omitTitle: Boolean) -> TextChapter?)? = null
    /**
     * 跨章段落滑动窗口 —— LazyColumn 直接消费。
     *
     * 状态用 [mutableStateListOf]（即 SnapshotStateList）：mutation 自动驱动 Compose
     * 重组，且引用稳定（mutate 后还是同一对象，不会让上层 LazyScrollRenderer 的
     * `LaunchedEffect(listState)` / `snapshotFlow` 误判要 reset）。
     */
    val paragraphs: SnapshotStateList<ScrollParagraph> = mutableStateListOf()

    /**
     * 当前窗口包含的章节 idx **升序** 列表 —— [appendNext] / [prependPrev] / trim
     * 操作的边界判定来源。维护手段：所有 mutation 在 [windowMutex] 内同步发生，
     * 改完调用 [Collection.sorted] 保持升序（用 SnapshotStateList 不能 in-place sort
     * 所以 reset 后再 addAll）。
     */
    val loadedChapters: SnapshotStateList<Int> = mutableStateListOf()

    /**
     * 章节 idx → 已排版 [TextChapter] 映射。给以下场景用：
     *   - LazyScrollSection 的 jump 路径需要 chapter 对象做 `chapterPositionToParagraphPos`
     *   - 顶栏标题切换（按 viewport center 段查标题）
     *   - TTS 自动跟随（按 chapterIndex 找对应章节段范围）
     *
     * 用 [mutableStateMapOf] 让上层 collect 到 chapter 入窗即可触发依赖更新。
     */
    val chapterByIdx: SnapshotStateMap<Int, TextChapter> = mutableStateMapOf()

    /**
     * 各章字符总数 —— 与 [chapterByIdx] 独立维护。
     *
     * [chapterByIdx] 在 trim 时会清 entry（释放 TextChapter 内存），但本 map 在 trim 时
     * **不删除**。进度计算依赖这个口径：章节被 trim 后滚动窗口仍在它附近时，
     * `chapterCharSize(chapterIdx)` 仍能返回正确的字符数，进度不跳变。
     *
     * 用 [mutableStateMapOf] 让上层 `ChapterWindowSource.chapterCharSize()` 读取时
     * 自动建立 Compose snapshot 依赖（trim 后 recompose 读到这个 map 新值）。
     */
    val chapterCharSizes: SnapshotStateMap<Int, Int> = mutableStateMapOf()

    /**
     * 命令式跳转请求（书签 / TOC 跳转）—— 上层 LazyScrollSection 监听此字段、
     * 转发到 LazyScrollRenderer 的 jumpChapterIndex / Position / Token。
     *
     * 处理完调 [consumePendingJump] 清掉，避免重复触发。
     *
     * **关键**：必须是 Compose 反应式 state（[mutableStateOf]）。如果是普通 var，
     * Compose 不会登记 snapshot 依赖，[resetTo] step 3 的赋值在「paragraphs mutation
     * 已经在 step 2 结束」时发生，若没有后续 mutation 触发 LazyScrollSection 重组，
     * pendingJump 新值永远不会被消费 → 用户点了书签 / 续读却没滚到目标位置。
     *
     * 目前能跑是因为 step 4 的 prefetch 会稍后再 mutate paragraphs 顺带触发重组，
     * 但这条依赖是时序巧合，fling / 窗口 mutate 节奏稍错就丢。改成 state 后
     * 上层读取 pendingJump 会自动登记依赖，赋值即触发重组，再无脆性。
     */
    private var _pendingJump by mutableStateOf<PendingJump?>(null)
    val pendingJump: PendingJump? get() = _pendingJump

    /**
     * 视口中心段所属章节 —— 由 LazyScrollSection 在 onVisibleParagraphChanged 里更新，
     * trim 决策与 [onCenterChapterChanged] 触发都看它。
     */
    private var centerChapterIdx: Int = -1

    /** 串行化所有 paragraphs / loadedChapters / chapterByIdx mutation —— 杜绝并发 race。 */
    private val windowMutex = Mutex()

    /** 每次 mutation 涉及的「正在加载」章节 idx 集合 —— 防止重复 enqueue。 */
    private val inFlightChapters = mutableSetOf<Int>()

    /** 当前 append/prepend job，方便 reset 时取消。 */
    private var inFlightJobs: MutableList<Job> = mutableListOf()

    // ══════════════════════════════════════════════════════════════
    // Public API
    // ══════════════════════════════════════════════════════════════

    /**
     * 重置窗口到指定章节 + 章内位置 —— 首次进入书 / 用户 TOC 或书签跳转用。
     *
     * 流程：
     *   1. 取消所有 in-flight job、清空 paragraphs / loadedChapters / chapterByIdx
     *   2. 异步 fetch + layout 中心章节（出 LOADING 占位先撑场子）
     *   3. 中心章节就绪后写 [pendingJump]（含 chapterIndex + chapterPos + token），
     *      上层 LazyScrollSection 会消费它驱动 LazyScrollRenderer 滚到目标段
     *   4. 后台并行 prefetch 上一章 / 下一章扩窗口
     *
     * @param chapterIdx 目标章节
     * @param chapterPos 章内字符偏移（0 = 章首）
     */
    fun resetTo(chapterIdx: Int, chapterPos: Int) {
        scope.launch {
            AppLog.debug(
                "ChapterWindow",
                "resetTo start: ch=$chapterIdx pos=$chapterPos window=${loadedChapters.toList()} " +
                    "paras=${paragraphs.size} center=$centerChapterIdx inFlight=$inFlightChapters",
            )
            // step 1: cancel + clear
            inFlightJobs.forEach { it.cancel() }
            inFlightJobs.clear()
            inFlightChapters.clear()
            windowMutex.withLock {
                paragraphs.clear()
                loadedChapters.clear()
                chapterByIdx.clear()
                _pendingJump = null
                // 立刻塞个 LOADING 占位，避免 LazyColumn 看到空 list 退到 ReaderLoadingCover
                paragraphs.add(loadingScrollParagraph(chapterIdx, placeholderHeight, System.nanoTime()))
            }
            centerChapterIdx = chapterIdx

            // step 2: load center synchronously（占据主路径）
            loadAndInsertChapter(chapterIdx, isAppend = true)

            // step 3: emit jump
            windowMutex.withLock {
                _pendingJump = PendingJump(
                    chapterIdx = chapterIdx,
                    chapterPos = chapterPos,
                    token = System.nanoTime(),
                )
            }

            // step 4: parallel prefetch neighbors
            inFlightJobs.add(scope.launch { tryPrependPrev() })
            inFlightJobs.add(scope.launch { tryAppendNext() })
        }
    }

    /**
     * 静默 append 下一章 —— LazyScrollRenderer onNearBottom 触发。
     *
     * 「静默」体现在：
     *   - 不调任何 scrollToItem（用户的 fling 不被打断）
     *   - 不切换 cur 章节真值（cur 由 viewport center 派生 + debounce 决定，见 [updateViewportCenter]）
     *   - 重复请求自动去重（inFlightChapters）
     */
    fun appendNext() {
        AppLog.debug(
            "ChapterWindow",
            "appendNext requested: window=${loadedChapters.toList()} paras=${paragraphs.size} " +
                "center=$centerChapterIdx inFlight=$inFlightChapters",
        )
        scope.launch { tryAppendNext() }
    }

    /** 静默 prepend 上一章 —— LazyScrollRenderer onNearTop 触发。 */
    fun prependPrev() {
        AppLog.debug(
            "ChapterWindow",
            "prependPrev requested: window=${loadedChapters.toList()} paras=${paragraphs.size} " +
                "center=$centerChapterIdx inFlight=$inFlightChapters",
        )
        scope.launch { tryPrependPrev() }
    }

    /**
     * 视口中心段所属章节漂移时由 LazyScrollSection 调用 —— 触发 [onCenterChapterChanged]
     * + 决定 trim 方向。
     */
    fun updateViewportCenter(chapterIdx: Int) {
        if (chapterIdx < 0) return
        if (centerChapterIdx == chapterIdx) return
        val oldCenter = centerChapterIdx
        centerChapterIdx = chapterIdx
        AppLog.debug(
            "ChapterWindow",
            "viewport center changed: $oldCenter -> $chapterIdx window=${loadedChapters.toList()} paras=${paragraphs.size}",
        )
        onCenterChapterChanged(chapterIdx)
        scope.launch { trimDistantChapters() }
    }

    /** 上层消费完 [pendingJump] 后调用清空，避免重复触发。 */
    fun consumePendingJump() {
        _pendingJump = null
    }

    /** 章节查询 —— 给 LazyScrollSection 显示标题用 */
    fun chapterTitleAt(chapterIdx: Int): String = chapterTitleProvider(chapterIdx)

    /** 给 LazyScrollRenderer 的 chapterCharSizeProvider 用 */
    fun chapterCharSize(chapterIdx: Int): Int =
        chapterCharSizes[chapterIdx] ?: chapterByIdx[chapterIdx]?.getContent()?.length?.coerceAtLeast(1) ?: 1

    // ══════════════════════════════════════════════════════════════
    // Internal — window mutation primitives
    // ══════════════════════════════════════════════════════════════

    private suspend fun tryAppendNext() {
        val targetIdx: Int
        windowMutex.withLock {
            val last = loadedChapters.lastOrNull()
            if (last == null) {
                AppLog.debug("ChapterWindow", "appendNext skipped: window empty")
                return
            }
            targetIdx = last + 1
            if (targetIdx >= chapterCountProvider()) {
                AppLog.debug("ChapterWindow", "appendNext skipped: at last chapter target=$targetIdx count=${chapterCountProvider()}")
                return
            }
            if (targetIdx in loadedChapters) {
                AppLog.debug("ChapterWindow", "appendNext skipped: target already loaded target=$targetIdx window=${loadedChapters.toList()}")
                return
            }
            if (targetIdx in inFlightChapters) {
                AppLog.debug("ChapterWindow", "appendNext skipped: target in-flight target=$targetIdx inFlight=$inFlightChapters")
                return
            }
            if (loadedChapters.size > maxChaptersInWindow) {
                // 窗口已满 —— trim 远离中心的一端（trim 也在 windowMutex 内做）
                AppLog.debug("ChapterWindow", "appendNext before trim: target=$targetIdx window=${loadedChapters.toList()} center=$centerChapterIdx")
                trimDistantChaptersLocked()
                if (loadedChapters.size > maxChaptersInWindow) {
                    AppLog.debug("ChapterWindow", "appendNext skipped: window still full after trim target=$targetIdx window=${loadedChapters.toList()}")
                    return
                }
            }
            inFlightChapters.add(targetIdx)
            // 立刻 append LOADING 占位，给用户视觉反馈 + 让 LazyColumn 测量稳定
            paragraphs.add(loadingScrollParagraph(targetIdx, placeholderHeight, System.nanoTime()))
            AppLog.debug(
                "ChapterWindow",
                "appendNext placeholder: target=$targetIdx window=${loadedChapters.toList()} paras=${paragraphs.size} inFlight=$inFlightChapters",
            )
        }
        try {
            loadAndInsertChapter(targetIdx, isAppend = true)
        } finally {
            windowMutex.withLock { inFlightChapters.remove(targetIdx) }
        }
    }

    private suspend fun tryPrependPrev() {
        val targetIdx: Int
        windowMutex.withLock {
            val first = loadedChapters.firstOrNull()
            if (first == null) {
                AppLog.debug("ChapterWindow", "prependPrev skipped: window empty")
                return
            }
            targetIdx = first - 1
            if (targetIdx < 0) {
                AppLog.debug("ChapterWindow", "prependPrev skipped: at first chapter target=$targetIdx window=${loadedChapters.toList()}")
                return
            }
            if (targetIdx in loadedChapters) {
                AppLog.debug("ChapterWindow", "prependPrev skipped: target already loaded target=$targetIdx window=${loadedChapters.toList()}")
                return
            }
            if (targetIdx in inFlightChapters) {
                AppLog.debug("ChapterWindow", "prependPrev skipped: target in-flight target=$targetIdx inFlight=$inFlightChapters")
                return
            }
            if (loadedChapters.size > maxChaptersInWindow) {
                AppLog.debug("ChapterWindow", "prependPrev before trim: target=$targetIdx window=${loadedChapters.toList()} center=$centerChapterIdx")
                trimDistantChaptersLocked()
                if (loadedChapters.size > maxChaptersInWindow) {
                    AppLog.debug("ChapterWindow", "prependPrev skipped: window still full after trim target=$targetIdx window=${loadedChapters.toList()}")
                    return
                }
            }
            inFlightChapters.add(targetIdx)
            // prepend LOADING 占位 —— 同样的视觉护栏
            paragraphs.add(0, loadingScrollParagraph(targetIdx, placeholderHeight, System.nanoTime()))
            AppLog.debug(
                "ChapterWindow",
                "prependPrev placeholder: target=$targetIdx window=${loadedChapters.toList()} paras=${paragraphs.size} inFlight=$inFlightChapters",
            )
        }
        try {
            loadAndInsertChapter(targetIdx, isAppend = false)
        } finally {
            windowMutex.withLock { inFlightChapters.remove(targetIdx) }
        }
    }

    /**
     * fetch + layout 一章，然后在 windowMutex 内：
     *   1. 移除该 chapterIdx 现存所有段落（含 LOADING 占位）
     *   2. 把新段落 [addAll] / [addAll(0, ...)] 插入对应位置
     *   3. 更新 loadedChapters / chapterByIdx
     *
     * @param isAppend true = 尾部插入；false = 头部插入。
     *        如果新章节正好在现有 loaded 之间（极罕见，可能源自重置），会按 chapterIdx
     *        升序找到合适位置插入。
     */
    private suspend fun loadAndInsertChapter(chapterIdx: Int, isAppend: Boolean) {
        // step a: fetch + layout（IO + Default，不持锁）
        val raw = chapterFetcher(chapterIdx)
        if (raw == null) {
            AppLog.warn("ChapterWindow", "fetch chapter $chapterIdx returned null; remove placeholder")
            windowMutex.withLock {
                paragraphs.removeAll { it.chapterIndex == chapterIdx && it.contentType == ScrollParagraphType.LOADING }
            }
            return
        }
        val title = chapterTitleProvider(chapterIdx)
        val layouter = chapterLayouter
        if (layouter == null) {
            // Compose 端尚未 attach；保留 LOADING 占位，让上层在 attach 后 retry。
            // 这种情形短暂存在（首次进入书 / 模式切换瞬间），用户视觉上仍有占位反馈。
            AppLog.debug("ChapterWindow", "chapter $chapterIdx fetched but layouter not attached yet; deferring")
            return
        }
        val tc = withContext(Dispatchers.Default) {
            layouter(title, raw, chapterIdx, chapterCountProvider(), omitChapterTitleProvider())
        }
        if (tc == null) {
            AppLog.warn("ChapterWindow", "layout chapter $chapterIdx returned null; remove placeholder")
            windowMutex.withLock {
                paragraphs.removeAll { it.chapterIndex == chapterIdx && it.contentType == ScrollParagraphType.LOADING }
            }
            return
        }

        val gen = System.nanoTime()
        val omit = omitChapterTitleProvider()
        val newParas = tc.toScrollParagraphs(omit, gen)
        if (newParas.isEmpty()) {
            AppLog.warn("ChapterWindow", "chapter $chapterIdx layout produced 0 paragraphs; remove placeholder")
            windowMutex.withLock {
                paragraphs.removeAll { it.chapterIndex == chapterIdx && it.contentType == ScrollParagraphType.LOADING }
            }
            return
        }

        // step b: 插入（持锁）
        windowMutex.withLock {
            val beforeParas = paragraphs.size
            val beforeWindow = loadedChapters.toList()
            // 不变量：插入新段落前 *先* 移除该 chapterIdx 现存所有段落
            // （含 LOADING 占位 + 旧 generation 的实际段）—— 杜绝 key 重复 + 章节段落重叠
            paragraphs.removeAll { it.chapterIndex == chapterIdx }

            // 决定插入位置：
            //   - 如果 chapterIdx 比 loadedChapters 全部都大：append 到尾
            //   - 如果比全部都小：prepend 到首
            //   - 否则按升序插入到对应位置（罕见，通常发生在 reset 流程中）
            val insertParaIdx = computeInsertParagraphIndex(chapterIdx)
            paragraphs.addAll(insertParaIdx, newParas)

            // 更新 loadedChapters / chapterByIdx / chapterCharSizes
            chapterByIdx[chapterIdx] = tc
            chapterCharSizes[chapterIdx] = tc.getContent().length.coerceAtLeast(1)
            if (chapterIdx !in loadedChapters) {
                // SnapshotStateList 不支持 in-place sort，重建
                val merged = (loadedChapters.toList() + chapterIdx).sorted()
                loadedChapters.clear()
                loadedChapters.addAll(merged)
            }
            AppLog.debug(
                "ChapterWindow",
                "inserted chapter $chapterIdx (${newParas.size} paras, gen=$gen) at paraIdx=$insertParaIdx isAppend=$isAppend; " +
                    "beforeWindow=$beforeWindow beforeParas=$beforeParas window=${loadedChapters.toList()} totalParas=${paragraphs.size}",
            )
        }
    }

    /**
     * 算新章节段落应插入 paragraphs 的位置：
     *   - 已 loaded 章节升序排列；新 chapterIdx 在 loadedChapters[i] 之后、loadedChapters[i+1] 之前
     *   - paragraphs 里章节段落按 chapterIndex 升序连续排列（不变量）
     *   - 所以插入位置 = paragraphs 中第一个 `it.chapterIndex > chapterIdx` 的 idx
     *   - 找不到（新 chapterIdx 是最大的）→ 插到末尾
     *
     * 必须在 windowMutex 内调用。
     */
    private fun computeInsertParagraphIndex(chapterIdx: Int): Int {
        val firstAfter = paragraphs.indexOfFirst { it.chapterIndex > chapterIdx }
        return if (firstAfter < 0) paragraphs.size else firstAfter
    }

    /**
     * Trim 远离 [centerChapterIdx] 的一端章节段落，让 loadedChapters.size 收敛到
     * <= [maxChaptersInWindow]。
     *
     * Trim 策略（修复版）：
     *   - 找到 loadedChapters 里距 centerChapterIdx 最远的章节
     *   - **关键修复**：当有多个章节距离相同时，优先 trim 远离滚动方向的一端：
     *     * center 在窗口后半部分 → 用户向后滚动 → trim 前面的章节（min）
     *     * center 在窗口前半部分 → 用户向前滚动 → trim 后面的章节（max）
     *   - 移除其所有段落，从 chapterByIdx + loadedChapters 也删掉
     *
     * **不**调 scrollToItem —— LazyListState 自动按 key 锚定剩余可见 item，
     * trim 远端段落时视觉上无感知。
     */
    private suspend fun trimDistantChapters() {
        windowMutex.withLock { trimDistantChaptersLocked() }
    }

    private fun trimDistantChaptersLocked() {
        while (loadedChapters.size > maxChaptersInWindow) {
            if (loadedChapters.isEmpty()) return
            val center = if (centerChapterIdx >= 0) centerChapterIdx else loadedChapters.first()

            // 计算每个章节到 center 的距离
            val distances = loadedChapters.map { it to kotlin.math.abs(it - center) }
            val maxDistance = distances.maxOfOrNull { it.second } ?: return

            // 找到所有距离最远的章节
            val farthestCandidates = distances.filter { it.second == maxDistance }.map { it.first }
            if (farthestCandidates.isEmpty()) return

            // 如果只有一个候选，直接 trim
            val farthest = if (farthestCandidates.size == 1) {
                farthestCandidates.first()
            } else {
                // 多个候选时，根据 center 位置决定 trim 哪一端
                // center 在窗口后半部分 → trim 前面的（min）
                // center 在窗口前半部分 → trim 后面的（max）
                val windowMid = (loadedChapters.first() + loadedChapters.last()) / 2.0
                if (center >= windowMid) {
                    // center 偏后 → 用户向后滚动 → trim 前面的
                    farthestCandidates.minOrNull() ?: farthestCandidates.first()
                } else {
                    // center 偏前 → 用户向前滚动 → trim 后面的
                    farthestCandidates.maxOrNull() ?: farthestCandidates.last()
                }
            }

            // 防御：不要 trim center 本身
            if (farthest == center) return

            paragraphs.removeAll { it.chapterIndex == farthest }
            chapterByIdx.remove(farthest)
            loadedChapters.remove(farthest)
            AppLog.debug(
                "ChapterWindow",
                "trim chapter $farthest (center=$center, candidates=$farthestCandidates); window=${loadedChapters.toList()} totalParas=${paragraphs.size}",
            )
        }
    }
}

/**
 * 命令式跳转请求 —— [ChapterWindowSource.resetTo] 触发，由 LazyScrollSection 转发到
 * LazyScrollRenderer 驱动 scrollToItem。
 *
 * @property token 幂等 key（[System.nanoTime]）。LazyScrollRenderer LaunchedEffect 内监听
 *           此值变化触发滚动；同 token 不重复触发。
 */
data class PendingJump(
    val chapterIdx: Int,
    val chapterPos: Int,
    val token: Long,
)
