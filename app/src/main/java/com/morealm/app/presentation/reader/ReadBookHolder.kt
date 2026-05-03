package com.morealm.app.presentation.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.render.TextChapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 章节状态单一真值 holder，仿照 Legado 的 ReadBook 模型。
 *
 * 设计目标：消除滚动模式跨章「commit → loadChapter(async) → StateFlow → 多
 * LaunchedEffect」三跳异步链路造成的章末闪现/留白/进度振荡。
 *
 * 与 [ReaderChapterController] 的职责分工：
 *   - ReaderChapterController：章节内容的 IO 加载 / replace 规则 / 网书源 / 错误处理
 *   - ReadBookHolder：(prev/cur/next)TextChapter 三章节指针 + curChapterIndex
 *     + curPageIndex 的状态机，以及 [moveToNextChapter] / [moveToPrevChapter] 的
 *     **同步指针腾挪**
 *
 * ## 关键设计
 *
 * ### 1. 同步腾挪
 * [moveToNextChapter] 在调用栈内完成 `prev = cur; cur = next; next = null`，
 * 下一帧重组 Compose 看到的就是新章节，**不存在异步窗口**。被腾出去的 next
 * 指针由 [chapterLoader] 在后台协程异步重填，但同步链路在此之前已完成。
 *
 * 对应 Legado [`ContentTextView.scroll`] 中
 * ```
 * pageFactory.moveToNext(true) → ReadBook.moveToNextChapter(...) →
 * curTextChapter = nextTextChapter（同步赋值）
 * ```
 * 的精神。
 *
 * ### 2. 不直接做 IO
 * Holder 不知道章节怎么加载——调用方注入 [chapterLoader] 函数 `(Int) -> TextChapter?`。
 * 这样 Holder 不依赖 Repository / Context / Hilt 容器，可以**纯 JVM 单测**。
 *
 * ### 3. Compose 状态而非 StateFlow
 * 用 [mutableStateOf] 而不是 [kotlinx.coroutines.flow.MutableStateFlow]，因为：
 *   - 同步赋值后下一帧重组立即可见（StateFlow + collectAsState 引入 Channel 帧延迟）
 *   - Legado 的 ReadBook 是同步 var，对齐这个语义
 *   - 字段 read 在 Compose 重组里自动建立依赖
 *
 * ### 4. 生命周期
 * Holder 与 [com.morealm.app.presentation.reader.ReaderViewModel] 同 [scope]，
 * viewModelScope 退出时自动取消所有预加载协程。
 *
 * ## 不做的事
 *
 * - **不持有正文文本**：那是 [ReaderChapterController._chapterContent] 的活
 * - **不做 layout 排版**：Holder 接收已排好版的 [TextChapter]
 * - **不管 navigation direction**：UI 层用 curChapterIndex 变化推断
 * - **不参与跨章动画**：那是 [com.morealm.app.ui.reader.renderer.ScrollRenderer]
 *   的 pageOffset 修正逻辑
 *
 * ## 与现有死代码 `_curTextChapter` 的关系
 *
 * [ReaderChapterController] 第 100-108 行已声明 `_prevTextChapter / _curTextChapter
 * / _nextTextChapter` 三个 MutableStateFlow，但从未被赋值（注释标 "Legado-style"
 * 但接了一半）。Phase 2 接入完成后那段死代码会被删除。
 */
class ReadBookHolder(
    private val scope: CoroutineScope,
    /**
     * Holder 不负责 IO；调用方注入加载函数。返回 null 表示加载失败或越界。
     * 实现方应在协程内执行（IO Dispatcher），但 Holder 自己不强制——单测可以
     * 注入同步实现。
     */
    private val chapterLoader: suspend (chapterIndex: Int) -> TextChapter?,
) {
    /**
     * 当前章节正文（已排版）。Compose 重组时直接读这个字段建立依赖。
     * 同步赋值——[moveToNextChapter] 调用返回时该字段已是新章节。
     */
    var curTextChapter by mutableStateOf<TextChapter?>(null)
        private set

    /**
     * 上一章节正文。用于滚动模式 ScrollRenderer 在 viewport 顶部预览渲染。
     * 跨章 NEXT 后由 cur 沉降而来；跨章 PREV 后由后台 chapterLoader 重填。
     */
    var prevTextChapter by mutableStateOf<TextChapter?>(null)
        private set

    /**
     * 下一章节正文。用于滚动模式 ScrollRenderer 在 viewport 底部预览渲染。
     * 跨章 PREV 后由 cur 沉降而来；跨章 NEXT 后由后台 chapterLoader 重填。
     */
    var nextTextChapter by mutableStateOf<TextChapter?>(null)
        private set

    /** 当前章节索引（0-based）。同步与 curTextChapter 一同赋值。 */
    var curChapterIndex by mutableIntStateOf(0)
        private set

    /**
     * 章节内当前页索引（0-based）。
     * - 跨章 NEXT 后置为 0（新章节首页）
     * - 跨章 PREV 后置为 (新章节 pageSize - 1)（新章节末页，跟 Legado 一致）
     * - 用户翻页 / 滚动期间由调用方直接赋值
     *
     * 公开 setter（不走 helper 函数）：避免与 Compose mutableIntStateOf 自动生成的
     * setXxx JVM 签名冲突，调用方写 `holder.curPageIndex = N` 即可。
     */
    var curPageIndex by mutableIntStateOf(0)

    /**
     * 总章节数。用于边界检测，加载新书时由 ReaderViewModel 直接赋值。
     * 必须在 [loadChapter] / [moveToNextChapter] 之前设置，否则越界检测错误。
     */
    var totalChapters by mutableIntStateOf(0)

    private var preloadNextJob: Job? = null
    private var preloadPrevJob: Job? = null

    /**
     * 同步指针腾挪：用 next 替换 cur，cur 沉到 prev，next 异步重填。
     *
     * @return true 表示腾挪成功（next 已预加载且没越界）；false 表示
     *   next 还没就绪或当前已是末章。调用方失败时应回退到老路径
     *   （ReaderChapterController.loadChapter 异步加载）。
     */
    fun moveToNextChapter(): Boolean {
        if (curChapterIndex + 1 >= totalChapters) {
            AppLog.debug("ReadBookHolder", "moveToNextChapter REJECT: at last chapter $curChapterIndex/$totalChapters")
            return false
        }
        val nextCh = nextTextChapter
        if (nextCh == null) {
            AppLog.warn("ReadBookHolder", "moveToNextChapter REJECT: nextTextChapter not preloaded yet (idx=$curChapterIndex)")
            return false
        }

        AppLog.info("ReadBookHolder", "moveToNextChapter $curChapterIndex → ${curChapterIndex + 1}")
        prevTextChapter = curTextChapter
        curTextChapter = nextCh
        nextTextChapter = null
        curChapterIndex++
        curPageIndex = 0  // 跨章 NEXT 落新章首页

        preloadNextJob?.cancel()
        preloadNextJob = scope.launch(Dispatchers.IO) {
            val target = curChapterIndex + 1
            nextTextChapter = if (target < totalChapters) chapterLoader(target) else null
        }
        return true
    }

    /**
     * 同步指针腾挪 PREV 路径：cur 沉到 next，prev 升为 cur。
     *
     * curPageIndex 落新章节末页（与 Legado moveToPrev 一致）。注意此时新
     * curTextChapter.pageSize 可能还在异步排版中（layoutChapterAsync 流式增页），
     * 调用方读 curPageIndex 后应做一次越界 clamp（因为 pageSize 可能未到位）。
     */
    fun moveToPrevChapter(): Boolean {
        if (curChapterIndex <= 0) {
            AppLog.debug("ReadBookHolder", "moveToPrevChapter REJECT: at first chapter")
            return false
        }
        val prevCh = prevTextChapter
        if (prevCh == null) {
            AppLog.warn("ReadBookHolder", "moveToPrevChapter REJECT: prevTextChapter not preloaded yet (idx=$curChapterIndex)")
            return false
        }

        AppLog.info("ReadBookHolder", "moveToPrevChapter $curChapterIndex → ${curChapterIndex - 1}")
        nextTextChapter = curTextChapter
        curTextChapter = prevCh
        prevTextChapter = null
        curChapterIndex--
        // 落末页：用新 cur 的 pageSize - 1。如果 pageSize 还没排完版，
        // 用 0 兜底——后续 layoutCompleted 后调用方自行 clamp。
        curPageIndex = (prevCh.pages.size - 1).coerceAtLeast(0)

        preloadPrevJob?.cancel()
        preloadPrevJob = scope.launch(Dispatchers.IO) {
            val target = curChapterIndex - 1
            prevTextChapter = if (target >= 0) chapterLoader(target) else null
        }
        return true
    }

    /**
     * 显式跳转加载（书签 / 打开 app / 菜单 PREV / 跨度跳转）。
     * 不走同步腾挪——会重置 prev/next 三章节缓存重新预加载。
     *
     * 与 [moveToNextChapter] / [moveToPrevChapter] 的区别：
     *   - 跳转目标可能是任意 chapter，不是相邻章节
     *   - 即使相邻章节，prev/next 缓存也可能与目标不匹配（如用户从章 3 跳到章 7）
     *   - 因此必须重新拉
     */
    fun loadChapter(index: Int, pageIndex: Int = 0) {
        if (index < 0 || index >= totalChapters) {
            AppLog.warn("ReadBookHolder", "loadChapter REJECT: index $index out of [0, $totalChapters)")
            return
        }
        scope.launch(Dispatchers.IO) {
            val cur = chapterLoader(index)
            // 同步覆盖 cur + index + pageIndex（最小不一致窗口）。
            // 三个赋值在协程当前 dispatcher 上是按序执行的，Compose 下一次重组
            // 看到的是一致的 (index, cur, pageIndex) 三元组。
            curTextChapter = cur
            curChapterIndex = index
            curPageIndex = pageIndex.coerceAtLeast(0)
            // 异步刷新 prev/next（这两个不要求与 cur 严格同步，可以晚一帧）
            preloadNextJob?.cancel()
            preloadPrevJob?.cancel()
            preloadPrevJob = scope.launch(Dispatchers.IO) {
                prevTextChapter = if (index - 1 >= 0) chapterLoader(index - 1) else null
            }
            preloadNextJob = scope.launch(Dispatchers.IO) {
                nextTextChapter = if (index + 1 < totalChapters) chapterLoader(index + 1) else null
            }
        }
    }

    /**
     * 切书 / 关闭阅读器时调用，清空所有状态并取消预加载协程。
     */
    fun reset() {
        preloadNextJob?.cancel()
        preloadPrevJob?.cancel()
        preloadNextJob = null
        preloadPrevJob = null
        curTextChapter = null
        prevTextChapter = null
        nextTextChapter = null
        curChapterIndex = 0
        curPageIndex = 0
        totalChapters = 0
    }
}
