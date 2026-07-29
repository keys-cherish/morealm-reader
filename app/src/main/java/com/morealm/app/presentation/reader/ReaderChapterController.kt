package com.morealm.app.presentation.reader

import android.content.Context
import android.net.Uri
import com.morealm.app.core.text.sortedNaturalBy
import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.entity.BookChapter
import com.morealm.app.domain.entity.BookSource
import com.morealm.app.domain.entity.BookFormat
import com.morealm.app.domain.parser.EpubParser
import com.morealm.app.domain.parser.LocalBookParser
import com.morealm.app.domain.reader.scroll.ScrollChapterContent
import com.morealm.app.domain.render.WireMarkerGuard
import com.morealm.app.domain.repository.BookRepository
import com.morealm.app.domain.repository.ReplaceRuleRepository
import com.morealm.app.domain.repository.SourceRepository
import com.morealm.app.domain.webbook.CacheBook
import com.morealm.app.domain.webbook.ChapterResult
import com.morealm.app.domain.webbook.WebBook
import com.morealm.app.core.log.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.text.StringEscapeUtils
import java.util.Locale

private const val TEXT_BOOK_SOURCE_TYPE = 0
private const val NON_TEXT_WEB_CONTENT_MESSAGE = "（该书源返回的是音频、图片、视频或临时媒体链接，不是可阅读的文本内容）"
private const val READER_ERROR_CHAPTER_URL_PREFIX = "morealm:error:"

/**
 * Friendly placeholder shown when a web chapter ends up with an empty body
 * (server returned 200-empty, parsing rule didn't match, network failed silently…).
 *
 * Without this the reader was rendering literally nothing and the user only saw the
 * floating day/night button — they had no clue the menu was reachable by tapping the
 * screen center. The placeholder explains the failure modes and prompts the menu.
 *
 * NOTE: kept as plain readable Chinese (not encoded) so a future regex maintainer can
 * grep "本章内容为空" easily and bump the message in one place.
 */
internal const val EMPTY_CONTENT_PLACEHOLDER =
    "⚠ 本章内容为空，无法显示\n\n" +
        "可能原因：\n" +
        "• 服务器返回了空响应\n" +
        "• 当前书源的正文规则不适配此章节\n" +
        "• 网络超时或被拦截\n\n" +
        "请尝试：\n" +
        "• 点击屏幕中央 → 顶栏「换源」选择其他书源\n" +
        "• 或退回详情页后重新打开"

/** True when the rendered chapter body is the placeholder above (avoid mistreating it as real content). */
internal fun isEmptyContentPlaceholder(text: String?): Boolean =
    text != null && text.startsWith("⚠ 本章内容为空")

/**
 * Manages chapter loading, preloading, web book support, and replace rules.
 * Extracted from ReaderViewModel.
 */
class ReaderChapterController(
    private val bookId: String,
    private val bookRepo: BookRepository,
    private val sourceRepo: SourceRepository,
    private val replaceRuleRepo: ReplaceRuleRepository,
    private val prefs: com.morealm.app.domain.preference.AppPreferences,
    private val context: Context,
    private val scope: CoroutineScope,
    /** Lazily provide the chinese convert mode from settings */
    private val chineseConvertMode: () -> Int,
    /**
     * 首行缩进字符数（0..2），来自 activeStyle.paragraphIndent。TXT 正文按此值预埋
     * 段首全角空格（[normalizeTxtParagraphIndent]）。默认 2（CJK 标准两字缩进），
     * 单测 / 旧调用方零迁移。
     */
    private val firstLineIndentChars: () -> Int = { 2 },
    /** Lazily provide the page turn mode from settings */
    private val pageTurnMode: () -> PageTurnMode,
    /** Reset TTS paragraph index on chapter load */
    private val resetTtsParagraphIndex: () -> Unit,
    private val fontRepo: com.morealm.app.domain.font.FontRepository,
    /** Save progress after chapter loads */
    private val onChapterLoaded: () -> Unit,
    /** Notify progress controller to suppress next save */
    private val setSuppressNextProgressSave: (Boolean) -> Unit,
    /**
     * 首次章节加载完成（RenderedReaderChapter + visiblePage 全部刷新完毕）时触发。
     * ReaderViewModel 用它把 ReaderProgressController.initialLoadComplete 置 true，
     * 解除「启动时 combine collector 初始 emit 把 (0,0,0) 刷进 DB」的闸门。
     *
     * 幂等：loadChapter 每次成功都会调，但 progress controller 那边只看第一次。
     * 默认 no-op 保证单测 / 旧调用方零迁移。
     */
    private val onInitialChapterLoaded: () -> Unit = {},
    /** 跨控制器共享状态（visiblePage/scrollProgress/navigateDirection/linkedBooks 真值）。 */
    private val shared: ReaderSharedState = ReaderSharedState(),
) {
    // ── Core State ──
    private val _book = MutableStateFlow<Book?>(null)
    val book: StateFlow<Book?> = _book.asStateFlow()

    private val _chapters = MutableStateFlow<List<BookChapter>>(emptyList())
    val chapters: StateFlow<List<BookChapter>> = _chapters.asStateFlow()

    private val _currentChapterIndex = MutableStateFlow(0)
    val currentChapterIndex: StateFlow<Int> = _currentChapterIndex.asStateFlow()

    /**
     * **settled chapter index**（仿 Compose PagerState `settledPage` 模型）—— fling /
     * 跨章 swap 期间保持稳定，待 300ms 静止后才更新。
     *
     * 用途：低频副作用应该订阅这个而不是 [currentChapterIndex]
     *   - `saveProgress` 持久化进度 → 用 settled（避免 fling 跨多章时反复写 DB）
     *   - TTS `handleSeek` 跨章 → 用 settled（避免 fling 中 TTS 章节判断抖动）
     *   - 章节元数据加载（如目录高亮当前章）→ 用 settled
     *
     * 高频 UI（InfoBar 章号 / page i/n 显示）仍订阅 [currentChapterIndex] (live)
     * 以保证用户视觉即时响应。
     *
     * 设计参考：2026-05-19 四方 agent 验证 V2 page-level 失败根因后引入。详
     * memory `feedback_high_freq_state_imperative.md` + `project_v2_architecture_failure_mode.md`。
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    val settledChapterIndex: StateFlow<Int> = _currentChapterIndex
        .debounce(300L)
        .stateIn(
            scope,
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            _currentChapterIndex.value,
        )

    private val _chapterContent = MutableStateFlow("")
    val chapterContent: StateFlow<String> = _chapterContent.asStateFlow()

    private val _renderedChapter = MutableStateFlow(RenderedReaderChapter())
    val renderedChapter: StateFlow<RenderedReaderChapter> = _renderedChapter.asStateFlow()

    private val _nextPreloadedChapter = MutableStateFlow<PreloadedReaderChapter?>(null)
    val nextPreloadedChapter: StateFlow<PreloadedReaderChapter?> = _nextPreloadedChapter.asStateFlow()

    private val _prevPreloadedChapter = MutableStateFlow<PreloadedReaderChapter?>(null)
    val prevPreloadedChapter: StateFlow<PreloadedReaderChapter?> = _prevPreloadedChapter.asStateFlow()

    // ── Three-chapter cache (Legado MD3 ReadBook 模型对齐) ──
    //
    // 对齐参考: 成熟开源阅读器的三章缓存模型
    //   var prevTextChapter: TextChapter? = null
    //   var curTextChapter: TextChapter? = null
    //   var nextTextChapter: TextChapter? = null
    //   fun moveToNextChapter(upContent: Boolean): Boolean {
    //     prevTextChapter = curTextChapter
    //     curTextChapter = nextTextChapter
    //     nextTextChapter = null
    //     ...
    //   }
    //
    // MoRealm 采用 StateFlow 而非 var：StateFlow.value = newValue 在主线程同步生效，
    // 与 Legado 的同步赋值语义等价；Compose collectAsState 会在下一帧触发重组。
    //
    // 三个 flow 的赋值路径：
    //   - publishCurTextChapter / Prev / Next：CanvasRenderer 在 layoutChapterAsync
    //     完成时回调（onTextChapterReady prop）→ 把已排版的 TextChapter 推回这里。
    //     idx 校验保证错章节的排版结果不会污染 cur（用户快速跨章时的迟到回调）。
    //   - commitChapterShiftNext / Prev：ScrollRenderer onChapterCommit 触发，
    //     在调用栈内同步腾挪三个 flow，**这是无缝跨章的核心**。
    private val _prevTextChapter = MutableStateFlow<com.morealm.app.domain.render.TextChapter?>(null)
    val prevTextChapter: StateFlow<com.morealm.app.domain.render.TextChapter?> = _prevTextChapter.asStateFlow()

    private val _curTextChapter = MutableStateFlow<com.morealm.app.domain.render.TextChapter?>(null)
    val curTextChapter: StateFlow<com.morealm.app.domain.render.TextChapter?> = _curTextChapter.asStateFlow()

    private val _nextTextChapter = MutableStateFlow<com.morealm.app.domain.render.TextChapter?>(null)
    val nextTextChapter: StateFlow<com.morealm.app.domain.render.TextChapter?> = _nextTextChapter.asStateFlow()

    /**
     * 由 CanvasRenderer 在 layoutChapterAsync.onCompleted (或 onPageReady index=0)
     * 回调时调用。idx 必须等于当前 currentChapterIndex 才覆写，否则丢弃——
     * 防止用户快速跨章后旧章节的迟到 layout 结果污染新 cur。
     *
     * 对齐 Legado: ReadBook.contentLoadFinish 中 `curTextChapter = textChapter`。
     */
    fun publishCurTextChapter(idx: Int, ch: com.morealm.app.domain.render.TextChapter) {
        if (idx == _currentChapterIndex.value) {
            _curTextChapter.value = ch
            AppLog.debug("ReadBook", "publishCurTextChapter idx=$idx pages=${ch.pageSize} completed=${ch.isCompleted}")
        } else {
            AppLog.debug(
                "ReadBook",
                "publishCurTextChapter REJECT stale: requested idx=$idx but cur=${_currentChapterIndex.value}",
            )
        }
    }

    /**
     * prev 章节预排版完成后由 CanvasRenderer 调用。idx 校验为 cur-1。
     * 对齐 Legado: ReadBook.contentLoadFinish 中 prevTextChapter 赋值。
     */
    fun publishPrevTextChapter(idx: Int, ch: com.morealm.app.domain.render.TextChapter) {
        if (idx == _currentChapterIndex.value - 1) {
            _prevTextChapter.value = ch
            AppLog.debug("ReadBook", "publishPrevTextChapter idx=$idx pages=${ch.pageSize}")
        }
    }

    /**
     * next 章节预排版完成后由 CanvasRenderer 调用。idx 校验为 cur+1。
     * 对齐 Legado: ReadBook.contentLoadFinish 中 nextTextChapter 赋值。
     */
    fun publishNextTextChapter(idx: Int, ch: com.morealm.app.domain.render.TextChapter) {
        if (idx == _currentChapterIndex.value + 1) {
            _nextTextChapter.value = ch
            AppLog.debug("ReadBook", "publishNextTextChapter idx=$idx pages=${ch.pageSize}")
        }
    }

    /**
     * 同步指针腾挪 NEXT 路径：prev = cur; cur = next; next = null。
     *
     * 对齐参考实现的跨章 NEXT 指针腾挪精神——在调用栈
     * 内完成所有相关 StateFlow 的赋值，下一帧 Compose 重组立即看到新章节，
     * **不存在异步窗口**。
     *
     * **原子同步腾挪的 StateFlow 集合**（缺一不可）：
     *   1. `_prevTextChapter` ← 旧 `_curTextChapter`（旧 cur 沉为 prev，
     *      ScrollRenderer 下一帧 viewport 顶部用旧 cur 的 last page 填充）
     *   2. `_curTextChapter` ← 旧 `_nextTextChapter`（已排好版的预下章瞬间转为 cur）
     *   3. `_nextTextChapter` ← null（异步重填新 next）
     *   4. `_currentChapterIndex` ← curIdx + 1
     *   5. `_chapterContent` ← 缓存的 nextContent（**关键**：CanvasRenderer 接 content
     *      prop 同源驱动 layoutChapterAsync；若 _chapterContent 不同步，CanvasRenderer
     *      重组时会发现 content/chapterIndex 不匹配，触发不必要的重排，丢弃已就绪的 next 排版）
     *   6. `_renderedChapter` ← 新章节 metadata（携带 restoreToken=nanoTime 供 CanvasRenderer
     *      progress 恢复路径感知）
     *   7. `_prevPreloadedChapter` ← (curIdx, oldCurTitle, oldCurContent)：让
     *      CanvasRenderer.prevChapterTitle/Content 派生的 prelayoutCache cacheKey 命中
     *      已有 prev TextChapter
     *   8. `_nextPreloadedChapter` ← null：旧 next 已转 cur，等异步预加载新 next
     *
     * **前置条件**：`_nextTextChapter` 已就绪（prelayout 完成）+ next 章节 content
     * 已缓存（nextChapterCache 或 _nextPreloadedChapter）。任一缺失则返回 false，
     * 调用方回退到老 [loadChapter] 异步路径。
     *
     * **调用线程**：必须在主线程调用，所有 StateFlow.value = ... 同帧生效。
     *
     * @return true 腾挪成功；false 表示前置条件不满足，调用方回退老路径。
     */
    fun commitChapterShiftNext(): Boolean {
        val curIdx = _currentChapterIndex.value
        val nextIdx = curIdx + 1
        val chapterList = _chapters.value
        if (nextIdx >= chapterList.size) {
            AppLog.debug("ReadBook", "commitChapterShiftNext REJECT at last chapter $curIdx/${chapterList.size}")
            return false
        }
        // next content 必须可取到——否则 _chapterContent 同步无源。
        // **单一可信来源** _nextPreloadedChapter (StateFlow<PreloadedReaderChapter?>)。
        // index 不匹配（被快速 PREV/NEXT 切换覆盖到别的章）就 REJECT，绝不走无 index
        // 的裸 String 路径——这是 22:41 那次 PREV 后内容/序号错位的根因。
        val nextPreloaded = _nextPreloadedChapter.value
        val nextContent: String = nextPreloaded
            ?.takeIf { it.index == nextIdx }
            ?.content
            ?: run {
                AppLog.warn(
                    "ReadBook",
                    "commitChapterShiftNext REJECT next content not cached (cur=$curIdx)" +
                        " | _nextTextChapter.idx=${_nextTextChapter.value?.chapterIndex}" +
                        " nextPreloaded.idx=${nextPreloaded?.index} wantNextIdx=$nextIdx",
                )
                return false
            }
        // 仿 legado ReadBook.moveToNextChapter：_nextTextChapter 未就绪时不 REJECT，
        // 改为推进 index + 置 _curTextChapter = null，由 CanvasRenderer 主路径重排。
        // 只要 nextContent 已缓存（上面那段 when 保证），视觉上就是「章头标题→加载态
        // →内容就位」一帧过渡，不再触发 coordinator REBUILD 的整屏闪。
        val nextCh = _nextTextChapter.value
        val nextChReady = nextCh != null
        if (!nextChReady) {
            AppLog.info(
                "ReadBook",
                "commitChapterShiftNext NEXT-NOT-LAID-OUT (cur=$curIdx→$nextIdx) | " +
                    "fall through to sync shift + async layout (legado-style)" +
                    " prevCh=${_prevTextChapter.value?.chapterIndex}" +
                    " curCh=${_curTextChapter.value?.chapterIndex}",
            )
        }
        // 保存旧 cur 信息——同步赋值会覆盖 _chapterContent，必须先快照。
        val oldCurContent = _chapterContent.value
        val oldCurTitle = chapterList[curIdx].title

        AppLog.info(
            "ReadBook",
            "commitChapterShiftNext $curIdx → $nextIdx | sync moveToNextChapter" +
                (if (nextChReady) "" else " (lazy-layout)"),
        )

        // ── 原子同步腾挪（主线程当帧）——以下 8 个赋值视为「单帧不可分」 ──
        _prevTextChapter.value = _curTextChapter.value
        _curTextChapter.value = nextCh  // nextCh 可能为 null；CanvasRenderer 主路径会排版回填
        _nextTextChapter.value = null
        _currentChapterIndex.value = nextIdx
        _chapterContent.value = nextContent
        _renderedChapter.value = RenderedReaderChapter(
            index = nextIdx,
            title = chapterList[nextIdx].title,
            content = nextContent,
            contentVersion = System.nanoTime(),
            initialProgress = 0,
            initialChapterPosition = 0,
            restoreToken = System.nanoTime(),
        )
        _prevPreloadedChapter.value = PreloadedReaderChapter(curIdx, oldCurTitle, oldCurContent)
        _nextPreloadedChapter.value = null
        // visible state 同步——避免 progress controller 看到 stale chapterIndex 导致进度错配
        scrollProgressState.value = 0
        run {
            visiblePageState.value = visiblePageState.value.copy(
                chapterIndex = nextIdx,
                title = chapterList[nextIdx].title,
                chapterPosition = 0,
            )
        }
        shared.commitNavigateDirection(1, "commitChapterShiftNext")
        clearHitTracking()

        // 异步预加载新 next（curIdx+2），不阻塞返回
        scope.launch(Dispatchers.IO) {
            preloadNextChapter(nextIdx + 1)
        }
        // 跨章瞬间不立即 saveProgress——visiblePage.chapterPosition 同步设为 0 + scrollProgress=0
        // 是「过渡占位」，等 reportProgress 把 chapterPosition 写到真实首页字符位置后，
        // collector debounce 才能攒出自洽快照。立即 saveProgress 会写出跟 scroll 矛盾的
        // (chapter=新, position=0, scroll=0) 快照，闪退恢复时定位错误。
        // 同时 suppress 下一次 collector emit，因为同步改的 (chIdx, scroll, position)
        // 也是过渡值——等 reportProgress 改 _visiblePage 才允许写。
        setSuppressNextProgressSave(true)
        return true
    }

    /**
     * 同步指针腾挪 PREV 路径：next = cur; cur = prev; prev = null。
     * 对齐参考实现的跨章 PREV 指针腾挪。
     *
     * 同步腾挪集合与 [commitChapterShiftNext] 对称：
     *   - `_nextTextChapter` ← 旧 `_curTextChapter`
     *   - `_curTextChapter` ← 旧 `_prevTextChapter`
     *   - `_prevTextChapter` ← null（异步重填）
     *   - `_currentChapterIndex` ← curIdx - 1
     *   - `_chapterContent` ← prevContent（来自 _prevPreloadedChapter / prevChapterCache）
     *   - `_renderedChapter` ← 新章 metadata，**initialChapterPosition = 末尾**让
     *     CanvasRenderer 启动到末页（PREV 跨章对齐 Legado moveToPrevChapter 行为）
     *   - `_nextPreloadedChapter` ← (curIdx, oldCurTitle, oldCurContent)
     *   - `_prevPreloadedChapter` ← null
     */
    /**
     * 同步提交跨章 PREV（参考成熟开源阅读器实现的跨章 PREV 指针腾挪）。
     *
     * @param toLast `true`（**默认**）= 跳上一章**末页**（手势 PREV 连续阅读语义，常见路径）；
     *               `false` = 跳上一章**章头**（按钮 PREV，显式覆盖默认）。
     *
     * 设计对齐 Legado：default 设为手势场景（更常见），按钮调用方显式传 `false`。
     * 详见 MEMORY.md「阅读器导航语义」段。
     */
    fun commitChapterShiftPrev(toLast: Boolean = true): Boolean {
        val toLastPage = toLast  // 内部沿用 toLastPage 命名，方便阅读现有 if (toLastPage) 分支
        val curIdx = _currentChapterIndex.value
        val prevIdx = curIdx - 1
        val chapterList = _chapters.value
        if (prevIdx < 0) {
            AppLog.debug("ReadBook", "commitChapterShiftPrev REJECT at first chapter")
            return false
        }
        // 与 commitChapterShiftNext 对称——单一可信来源 _prevPreloadedChapter，
        // index 不匹配就 REJECT 回退老路径，绝不走裸 String fallback。
        val prevPreloaded = _prevPreloadedChapter.value
        val prevContent: String = prevPreloaded
            ?.takeIf { it.index == prevIdx }
            ?.content
            ?: run {
                AppLog.warn(
                    "ReadBook",
                    "commitChapterShiftPrev REJECT prev content not cached (cur=$curIdx)" +
                        " | _prevTextChapter.idx=${_prevTextChapter.value?.chapterIndex}" +
                        " prevPreloaded.idx=${prevPreloaded?.index} wantPrevIdx=$prevIdx",
                )
                return false
            }
        // 仿 legado ReadBook.moveToPrevChapter：_prevTextChapter 未就绪时不 REJECT，
        // 改为推进 index + 置 _curTextChapter = null，由 CanvasRenderer 主路径重排。
        // 连点 PREV 不再落回 loadChapter，避免 coordinator REBUILD 整屏闪。
        val prevCh = _prevTextChapter.value
        val prevChReady = prevCh != null
        if (!prevChReady) {
            AppLog.info(
                "ReadBook",
                "commitChapterShiftPrev PREV-NOT-LAID-OUT (cur=$curIdx→$prevIdx) | " +
                    "fall through to sync shift + async layout (legado-style)" +
                    " curCh=${_curTextChapter.value?.chapterIndex}" +
                    " nextCh=${_nextTextChapter.value?.chapterIndex}",
            )
        }
        val oldCurContent = _chapterContent.value
        val oldCurTitle = chapterList[curIdx].title

        AppLog.info(
            "ReadBook",
            "commitChapterShiftPrev $curIdx → $prevIdx | sync moveToPrevChapter" +
                (if (prevChReady) "" else " (lazy-layout)"),
        )

        _nextTextChapter.value = _curTextChapter.value
        _curTextChapter.value = prevCh  // 可能为 null；CanvasRenderer 主路径排版回填
        _prevTextChapter.value = null
        _currentChapterIndex.value = prevIdx
        _chapterContent.value = prevContent
        _renderedChapter.value = RenderedReaderChapter(
            index = prevIdx,
            title = chapterList[prevIdx].title,
            content = prevContent,
            contentVersion = System.nanoTime(),
            // Bug 3 修复：按钮 PREV 跳章头（与"下一章"按钮对称）。
            // **Bug 3 后续修复**（手势 PREV bug）：按 MEMORY.md 「阅读器导航语义」铁则，
            // 手势 PREV（仿真翻页 / 滑动 / 覆盖 / 竖排手势）应跳**上一章末页**——连续阅读
            // 体感。startFromLastPage=true 让 CanvasRenderer initialPage 算成 pageCount-1。
            // 按钮路径 toLastPage=false（默认）→ startFromLastPage=false → 章头不变。
            initialProgress = 0,
            initialChapterPosition = 0,
            startFromLastPage = toLastPage,
            restoreToken = System.nanoTime(),
        )
        _nextPreloadedChapter.value = PreloadedReaderChapter(curIdx, oldCurTitle, oldCurContent)
        _prevPreloadedChapter.value = null
        // 与 initialProgress=0 / initialChapterPosition=0 保持一致：上一章按钮跳章头（Bug 3）
        scrollProgressState.value = 0
        run {
            visiblePageState.value = visiblePageState.value.copy(
                chapterIndex = prevIdx,
                title = chapterList[prevIdx].title,
                chapterPosition = 0,
            )
        }
        shared.commitNavigateDirection(-1, "commitChapterShiftPrev")
        clearHitTracking()

        scope.launch(Dispatchers.IO) {
            preloadPrevChapter(prevIdx - 1)
        }
        // PREV 跨章同 commitChapterShiftNext：scroll=100 + position=0 是过渡占位，
        // 等 reportProgress 把 chapterPosition 写到末页真实字符位置（如 1583）才自洽。
        // 立即 saveProgress 会写出 (chapter=新, position=0, scroll=100, total=新章首) 这种
        // scroll 跟 position 互相矛盾的快照——闪退恢复时按 lastReadPosition=0 落到章首，
        // 而不是用户离开时的末页。suppress 下一次 collector emit 给 reportProgress 留窗口。
        setSuppressNextProgressSave(true)
        return true
    }

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /**
     * 登录提示总线：章节 / 目录加载失败且异常像 401/403/"需要登录"时 emit 出对应源。
     *
     * ReaderScreen 订阅 → 弹 Snackbar "去登录"，点后走 [com.morealm.app.presentation.source.SourceLoginViewModel.showLoginDialog]。
     * 这里不直接启 Dialog，是为了保持 controller 的无 UI 纯逻辑层。
     *
     * extraBufferCapacity = 2 允许用户连翻两章都失败时两次提示都能到达 UI；replay = 0
     * 避免进屏时吃到旧事件。**只在源真的配置了 loginUrl 时才 emit**，无登录入口的源
     * 提示"去登录"没意义只会误导。
     */
    private val _loginPrompt = MutableSharedFlow<BookSource>(
        extraBufferCapacity = 2,
    )
    val loginPrompt: SharedFlow<BookSource> =
        _loginPrompt.asSharedFlow()

    // 旧的 `nextChapterCache` / `prevChapterCache`（@Volatile var String?）已废除——
    // 裸 String 不带 index 语义，commitChapterShift{Next,Prev} 的 fallback 路径
    // (`xxxCache != null -> xxxCache!!`) 无法验证「这是不是我想要的那一章」，
    // 快速来回切章时被异步 preload 覆盖到别的章 → 一旦 commit 走 fallback 就把
    // 错章节内容当成对的章用，导致 _currentChapterIndex 跟 _chapterContent 错位
    // （日志 224128 22:41:01：commitChapterShiftPrev 18→17，但 chapter content
    //  sample='清风明月枝头动' 实为 idx=16 卷首页内容；用户看到 UI 错位）。
    //
    // 根治：单一可信来源 `_prev/nextPreloadedChapter: StateFlow<PreloadedReaderChapter?>`，
    // 类型上携带 (index, title, content) 元组，commit 路径强制校验 .index == 期望章号，
    // 不匹配 → REJECT → 回退老 loadChapter 异步路径（重读 + 重排）。
    var chapterLoadJob: kotlinx.coroutines.Job? = null
    var chapterLoadToken: Int = 0
    var lastPreCacheCenter: Int = -1

    // ── Replace rules cache ──
    var cachedReplaceRules: List<com.morealm.app.domain.entity.ReplaceRule> = emptyList()
    private val regexCache = HashMap<String, Regex>(16)

    private fun getCachedRegex(pattern: String): Regex {
        return regexCache.getOrPut(pattern) { Regex(pattern) }
    }

    // ── 共享状态别名（真值在 ReaderSharedState，保留旧属性名减小 diff）──
    private val visiblePageState get() = shared._visiblePage
    private val scrollProgressState get() = shared._scrollProgress
    private val navigateDirectionState get() = shared._navigateDirection
    private val linkedBooksState get() = shared._linkedBooks

    fun isWebBook(book: Book): Boolean {
        return book.format == com.morealm.app.domain.entity.BookFormat.WEB ||
            (book.localPath == null && book.sourceUrl != null)
    }

    suspend fun initReplaceRules() {
        cachedReplaceRules = replaceRuleRepo.getRulesForBook(bookId)
    }

    // ── Book Loading ──

    private fun publishReaderError(title: String, detail: String) {
        val content = readerErrorContent(title, detail)
        val errorChapter = BookChapter(
            id = "${bookId}_reader_error",
            bookId = bookId,
            index = 0,
            title = title,
            url = READER_ERROR_CHAPTER_URL_PREFIX,
            variable = content,
        )
        chapterLoadJob?.cancel()
        chapterLoadToken++
        _nextPreloadedChapter.value = null
        _prevPreloadedChapter.value = null
        _chapters.value = listOf(errorChapter)
        _currentChapterIndex.value = 0
        _chapterContent.value = content
        _renderedChapter.value = RenderedReaderChapter(
            index = 0,
            title = title,
            content = content,
            contentVersion = System.nanoTime(),
            initialProgress = 0,
            restoreToken = System.nanoTime(),
        )
        visiblePageState.value = VisibleReaderPage(0, title, "0.0%", 0)
        scrollProgressState.value = 0
        shared.commitNavigateDirection(0, "loadBook reset")
        _loading.value = false
    }

    suspend fun loadBook() {
        _loading.value = true
        try {
            val book = bookRepo.getById(bookId)
            if (book == null) {
                AppLog.error("Chapter", "Book not found: $bookId")
                _loading.value = false
                return
            }
            _book.value = book
            AppLog.info("Chapter", "Opened: ${book.title} (${book.format})")

            val isWebBook = isWebBook(book)

            // For web books, try to load cached chapters from DB first for instant display
            if (isWebBook) {
                val cachedChapters = withContext(Dispatchers.IO) {
                    bookRepo.getChaptersList(bookId)
                }
                if (cachedChapters.isNotEmpty()) {
                    _chapters.value = cachedChapters
                    AppLog.info("Chapter", "Loaded ${cachedChapters.size} cached chapters from DB")

                    // Show chapters immediately, load first chapter
                    val progress = bookRepo.getProgress(bookId)
                    val resumeCursor = resolveReaderResumeCursor(book, progress, cachedChapters.size)
                    val startIndex = resumeCursor.chapterIndex
                    lastPreCacheCenter = startIndex
                    val savedScrollProgress = resumeCursor.chapterProgress
                    val savedChapterPosition = resumeCursor.chapterPosition
                    AppLog.info(
                        "BookmarkDebug",
                        "loadBook ENTRY (web) bookId=$bookId startIndex=$startIndex" +
                            " savedScrollProgress=$savedScrollProgress savedChapterPosition=$savedChapterPosition" +
                            " cursorSource=${resumeCursor.source}" +
                            " |" +
                            " bookLastReadChapter=${book.lastReadChapter}" +
                            " bookLastReadPosition=${book.lastReadPosition}" +
                            " dbProgress.chapterIndex=${progress?.chapterIndex}" +
                            " dbProgress.chapterPosition=${progress?.chapterPosition}",
                    )
                    scrollProgressState.value = savedScrollProgress
                    loadChapter(startIndex, restoreProgress = savedScrollProgress, restoreChapterPosition = savedChapterPosition)

                    // Refresh chapters in background (non-blocking)
                    scope.launch(Dispatchers.IO) {
                        try {
                            val freshChapters = loadWebBookChapters(book)
                            if (freshChapters.isNotEmpty() && freshChapters.size != cachedChapters.size) {
                                _chapters.value = freshChapters
                                bookRepo.saveChapters(bookId, freshChapters)
                                if (book.totalChapters != freshChapters.size) {
                                    bookRepo.update(book.copy(totalChapters = freshChapters.size))
                                }
                                AppLog.info("Chapter", "Refreshed chapters: ${freshChapters.size}")
                            }
                        } catch (e: Exception) {
                            AppLog.warn("Chapter", "Background chapter refresh failed: ${e.message}")
                        }
                    }

                    if (book.folderId != null) {
                        val folderBooks = bookRepo.getBooksByFolderId(book.folderId!!)
                            .sortedNaturalBy { it.title }
                        shared.commitLinkedBooks(folderBooks.filter { it.id != bookId }, "loadBook folder scan")
                    }
                    return
                }
            }

            // 本地书章节 DB 缓存：命中 = 跳过全文件解析（1GB TXT 二次打开秒开）。
            // usedChapterCache 时下方 saveChapters 也跳过（章节本来就来自 DB，万章书免重写）；
            // localFingerprint 非 null 时在 totalChapters 写回处一并回填（合并成一次 update，
            // 防止两次独立 update 用旧 book 对象互相覆盖）。
            var usedChapterCache = false
            var localFingerprint: com.morealm.app.domain.storage.LocalFileFingerprint.Fingerprint? = null

            var chapters: List<BookChapter> = if (isWebBook) {
                try {
                    loadWebBookChapters(book)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.error("Chapter", "Failed to load web chapters", e)
                    shouldPromptLogin(e)?.let { _loginPrompt.tryEmit(it) }
                    publishReaderError(
                        title = "书源加载失败",
                        detail = webReaderErrorDetail(
                            book,
                            e.readerErrorMessage("目录解析失败"),
                        ),
                    )
                    return
                }
            } else {
                val localPath = book.localPath ?: run {
                    AppLog.warn("Chapter", "No local path for book ${book.id}")
                    _loading.value = false
                    return
                }
                val uri = Uri.parse(localPath)

                // ── 文件指纹：真打不开 → 明确兜底提示（原位引用导入后文件可能被用户移动/删除）──
                // 注意指纹取不到 ≠ 文件丢失：奇葩 provider 可能 stat 不了但能读——那种情况
                // 跳过缓存直接解析（localFingerprint 留 null，不回填、不误拦）。
                val fingerprint = withContext(Dispatchers.IO) {
                    com.morealm.app.domain.storage.LocalFileFingerprint.of(context, uri)
                }
                if (fingerprint == null) {
                    val readable = withContext(Dispatchers.IO) {
                        runCatching {
                            context.contentResolver.openInputStream(uri)?.use { } != null
                        }.getOrDefault(false)
                    }
                    if (!readable) {
                        AppLog.warn("Chapter", "Local file inaccessible: $localPath")
                        publishReaderError(
                            title = "文件已移动或删除",
                            detail = "找不到这本书的源文件，或访问授权已失效。\n\n" +
                                "如果文件还在设备上，请重新导入一次即可继续阅读（阅读进度会保留）。\n\n" +
                                "原文件位置：\n$localPath",
                        )
                        return
                    }
                    AppLog.warn("Chapter", "Fingerprint unavailable but file readable, chapter cache disabled: $localPath")
                }
                localFingerprint = fingerprint

                // ── 章节 DB 缓存：指纹一致直接用，跳过全文件解析 ──
                val cachedChapters = withContext(Dispatchers.IO) {
                    bookRepo.getChaptersList(bookId)
                }
                val mapped: List<BookChapter>
                if (fingerprint != null && cachedChapters.isNotEmpty() &&
                    com.morealm.app.domain.storage.LocalFileFingerprint.matches(book.fileSize, book.fileMtime, fingerprint)
                ) {
                    usedChapterCache = true
                    mapped = cachedChapters
                    AppLog.info(
                        "Chapter",
                        "Local chapter cache hit: ${cachedChapters.size} chapters " +
                            "(size=${fingerprint.size} mtime=${fingerprint.mtime})",
                    )
                } else {
                    val customTxtRegex = prefs.customTxtChapterRegex.first()
                    val rawChapters = LocalBookParser.parseChapters(context, uri, book.format, customTxtRegex)
                    mapped = rawChapters.map { ch ->
                        if (ch.bookId != bookId) ch.copy(id = "${bookId}_${ch.index}", bookId = bookId) else ch
                    }
                }

                if (book.format == com.morealm.app.domain.entity.BookFormat.EPUB) {
                    // **D1.b 已知**：reader 未 mount → cbw 未知 → preCacheChapters 内部 skip。
                    // host fetchAndPrepareChapter 真值 cbw 走 on-demand 解析。TODO(D2)：把
                    // ScrollLayoutEngine.visibleWidth 通过 viewModel 写回 controller，让 ReaderScreen
                    // mount 后触发 preCacheChapters(cbw=visibleWidth)，恢复预热语义。
                    scope.launch(Dispatchers.IO) {
                        try {
                            com.morealm.app.domain.parser.EpubParser.preCacheChapters(context, uri, mapped)
                            AppLog.info("Chapter", "EPUB pre-cache skipped until reader width is known (D1.b)")
                        } catch (e: Exception) {
                            AppLog.warn("Chapter", "EPUB pre-cache failed", e)
                        }
                    }
                    // 2026-05-25 EPUB 自带字体：确保 book 在 EpubCoreBridge cache 中，
                    // 然后 build font registry 并 setActive 让 renderer 端 resolveActive 拿到
                    com.morealm.app.domain.parser.EpubCoreBridge.withCoreBook(context, uri) { }
                    val registry = com.morealm.app.domain.parser.EpubCoreBridge.fontRegistryOf(uri, fontRepo)
                    com.morealm.app.domain.font.EpubFontRegistry.setActive(registry)
                    AppLog.info("Chapter", "EPUB font registry activated: ${registry.size} families")
                }
                if (book.format == com.morealm.app.domain.entity.BookFormat.CBZ) {
                    scope.launch(Dispatchers.IO) {
                        try {
                            com.morealm.app.domain.parser.CbzParser.preCacheImages(context, uri, mapped)
                            AppLog.info("Chapter", "CBZ images pre-cached")
                        } catch (e: Exception) {
                            AppLog.warn("Chapter", "CBZ pre-cache failed", e)
                        }
                    }
                }
                mapped
            }

            // isFallback 标记：fallback 1-chapter placeholder 不应 save 到 DB（防 wipe 已有 cached）。
            // 用户日志 2026-05-17 12:48 bug：fallback 被 save → 下次启动 cached=1 章 → 看到「章节没了」。
            var isFallback = false
            if (chapters.isEmpty()) {
                AppLog.warn("Chapter", "No chapters found for book ${book.id}")
                if (isWebBook) {
                    // Fallback: create a single chapter from the book URL so content can still be fetched
                    val fallbackUrl = book.tocUrl?.takeIf { it.isNotBlank() } ?: book.bookUrl
                    if (fallbackUrl.isNotBlank()) {
                        AppLog.info("Chapter", "No TOC, creating fallback chapter from bookUrl (in-memory only, NOT persisted)")
                        chapters = listOf(
                            BookChapter(
                                id = "${bookId}_0",
                                bookId = bookId,
                                index = 0,
                                title = book.title,
                                url = fallbackUrl,
                            )
                        )
                        isFallback = true
                    } else {
                        publishReaderError(
                            title = "书源无章节",
                            detail = webReaderErrorDetail(book, "该书源没有解析到章节目录"),
                        )
                        return
                    }
                } else {
                    _loading.value = false
                    return
                }
            }

            _chapters.value = chapters
            // 仅持久化真实 toc；fallback placeholder 仅用 in-memory 让 reader 能加载内容
            // 用户下次重新打开时尝试 web fetch 重新拉真实 toc（不会被 fallback wipe 锁死）。
            // 章节缓存命中时同样跳过——章节本来就来自 DB，万章书重写一遍纯浪费。
            if (!isFallback && !usedChapterCache) {
                bookRepo.saveChapters(bookId, chapters)
            }
            AppLog.info(
                "Chapter",
                "Parsed ${chapters.size} chapters" +
                    if (isFallback) " (fallback, not persisted)"
                    else if (usedChapterCache) " (from DB cache)"
                    else "",
            )

            // totalChapters 变化 / 本地书指纹回填合并成**一次** update ——
            // 两次独立 update 会用各自的旧 book 快照互相覆盖对方刚写的列。
            // fallback placeholder（web 1 章占位）历史上也会写 totalChapters，保持不变。
            val updatedBook = book.copy(
                totalChapters = chapters.size,
                fileSize = localFingerprint?.size ?: book.fileSize,
                fileMtime = localFingerprint?.mtime ?: book.fileMtime,
            )
            if (updatedBook != book) {
                bookRepo.update(updatedBook)
                // 必须同步刷新 _book.value：ReaderProgressController.saveProgressLocked 用
                // chapterController.book.value 做**整行** update(book.copy(lastRead...))——
                // 若这里不刷新，首次翻页存进度就会拿旧快照把刚回填的 fileSize/fileMtime
                // 清回 0，章节缓存永远不会命中（静默失效，极难排查）。
                _book.value = updatedBook
            }

            val progress = bookRepo.getProgress(bookId)
            val resumeCursor = resolveReaderResumeCursor(_book.value ?: book, progress, chapters.size)
            val startIndex = resumeCursor.chapterIndex
            lastPreCacheCenter = startIndex

            val savedScrollProgress = resumeCursor.chapterProgress
            val savedChapterPosition = resumeCursor.chapterPosition
            AppLog.info(
                "BookmarkDebug",
                "loadBook ENTRY (local) bookId=$bookId startIndex=$startIndex" +
                    " savedScrollProgress=$savedScrollProgress savedChapterPosition=$savedChapterPosition" +
                    " cursorSource=${resumeCursor.source}" +
                    " |" +
                    " bookLastReadChapter=${book.lastReadChapter}" +
                    " bookLastReadPosition=${book.lastReadPosition}" +
                    " dbProgress.chapterIndex=${progress?.chapterIndex}" +
                    " dbProgress.chapterPosition=${progress?.chapterPosition}",
            )
            scrollProgressState.value = savedScrollProgress
            loadChapter(startIndex, restoreProgress = savedScrollProgress, restoreChapterPosition = savedChapterPosition)

            if (book.folderId != null) {
                val folderBooks = bookRepo.getBooksByFolderId(book.folderId!!)
                    .sortedNaturalBy { it.title }
                shared.commitLinkedBooks(folderBooks.filter { it.id != bookId }, "reloadLinkedBooks")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.error("Chapter", "Failed to load book", e)
            shouldPromptLogin(e)?.let { _loginPrompt.tryEmit(it) }
            _book.value?.takeIf { isWebBook(it) }?.let { book ->
                publishReaderError(
                    title = "书源加载失败",
                    detail = webReaderErrorDetail(
                        book,
                        e.readerErrorMessage("书籍加载失败"),
                    ),
                )
            }
        } finally {
            _loading.value = false
        }
    }

    fun loadChapter(index: Int, restoreProgress: Int = 0, restoreChapterPosition: Int = 0) {
        val chapterList = _chapters.value
        if (index < 0 || index >= chapterList.size) return

        val prevIndex = _currentChapterIndex.value
        // [DIAGNOSTIC 2026-05-14] 排查「点目录第一卷跳到第二章」：在入口打出
        // 「上层传进来的 index + 实际取到的章节 (title/url) + 上一章 index」一行，
        // 与 ReaderScreen onChapterClick 的 ChapterIdxDebug 行配对验证目标章是否对得上。
        val targetCh = chapterList[index]
        com.morealm.app.core.log.AppLog.info(
            "ChapterIdxDebug",
            "ChapterController.loadChapter ENTRY index=$index prevIndex=$prevIndex" +
                " target.title=\"${targetCh.title}\" target.url=${targetCh.url}" +
                " restoreProg=$restoreProgress restorePos=$restoreChapterPosition",
        )
        chapterLoadJob?.cancel()
        val loadToken = ++chapterLoadToken
        _loading.value = true
        // Phase 2 一致性防线：loadChapter 是「跳跃式」加载（任意 index，可能与同步
        // 腾挪状态不连贯）。重置三个真值流，避免后续 commitChapterShift 看到错章节
        // 的残留 _prev/_nextTextChapter 误判为已就绪，污染 cur 渲染。
        // _curTextChapter 会被本次 layoutChapterAsync 完成后 publishCurTextChapter
        // 重新填充；_prev/_nextTextChapter 等 prelayoutCache 完成后 publishPrev/Next 重填。
        _prevTextChapter.value = null
        _curTextChapter.value = null
        _nextTextChapter.value = null
        // EffectiveReplacesDialog: hit tracking is per-chapter, reset before this chapter starts processing.
        if (prevIndex != index) clearHitTracking()
        val targetProgress = restoreProgress.coerceIn(0, 100)
        val targetChapterPosition = restoreChapterPosition.coerceAtLeast(0)
        resetTtsParagraphIndex()
        val chapter = chapterList[index]
        val book = _book.value ?: run {
            _loading.value = false
            return
        }
        val isWebBook = isWebBook(book)

        chapterLoadJob = scope.launch(Dispatchers.IO) {
            try {
                // 章节内容获取走单一可信源 _next/prevPreloadedChapter（带 index 校验）。
                // 旧裸 String cache 字段已废除，避免「快速 PREV/NEXT 切换时 cache 被
                // 覆盖到别的章但 commit 当对的章用」的错位 bug。
                val nextPreloaded = _nextPreloadedChapter.value?.takeIf { it.index == index && index == prevIndex + 1 }
                val prevPreloaded = _prevPreloadedChapter.value?.takeIf { it.index == index && index == prevIndex - 1 }
                // Track which cache path was used so we can defer clearing preloaded
                // chapter state until AFTER _renderedChapter is published — avoids a
                // frame where the UI sees null preloaded data but hasn't received the
                // new chapter content yet, which causes a visible page-0 flash.
                var usedNextCache = false
                var usedPrevCache = false
                val content = when {
                    nextPreloaded != null -> {
                        usedNextCache = true
                        nextPreloaded.content
                    }
                    prevPreloaded != null -> {
                        usedPrevCache = true
                        prevPreloaded.content
                    }
                    else -> {
                        _nextPreloadedChapter.value = null
                        _prevPreloadedChapter.value = null
                        val raw = if (isWebBook) {
                            loadWebChapterContent(book, chapter, index)
                        } else {
                            val localPath = book.localPath ?: ""
                            LocalBookParser.readChapter(context, Uri.parse(localPath), book.format, chapter)
                        }
                        val replaced = applyReplaceRules(raw)
                        // [DIAGNOSTIC 2026-05-10] 临时排查繁简反复切换后仍是繁体的问题。
                        val capturedMode = chineseConvertMode()
                        AppLog.info(
                            "ChineseDebug",
                            "loadChapter ELSE idx=$index modeRead=$capturedMode replacedLen=${replaced.length}" +
                                " replacedSample='${replaced.take(20).replace("\n", "\\n")}'",
                        )
                        com.morealm.app.core.text.ChineseConverter.convert(replaced, capturedMode)
                    }
                }

                if (loadToken != chapterLoadToken) {
                    AppLog.info(
                        "ChineseDebug",
                        "loadChapter STALE-DROP idx=$index token=$loadToken cur=$chapterLoadToken",
                    )
                    return@launch
                }
                // [DIAGNOSTIC 2026-05-10] 验证最终 publish 的内容采样
                AppLog.info(
                    "ChineseDebug",
                    "loadChapter PUBLISH idx=$index len=${content.length}" +
                        " sample='${content.take(20).replace("\n", "\\n")}'",
                )

                // Publish new chapter content FIRST, before clearing old preloaded data.
                // This ensures the UI always has valid content to display during the
                // transition, preventing the page-0 flash on backward navigation.
                _chapterContent.value = content
                _renderedChapter.value = RenderedReaderChapter(
                    index = index,
                    title = chapter.title,
                    content = content,
                    contentVersion = System.nanoTime(),
                    initialProgress = targetProgress,
                    initialChapterPosition = targetChapterPosition,
                    restoreToken = System.nanoTime(),
                )
                _currentChapterIndex.value = index

                // NOW safe to clear old preloaded chapter data — the new chapter is
                // already published so the UI won't see a gap.
                if (usedNextCache) _nextPreloadedChapter.value = null
                if (usedPrevCache) _prevPreloadedChapter.value = null

                scrollProgressState.value = targetProgress
                visiblePageState.value = visiblePageState.value.copy(
                    chapterIndex = index,
                    title = chapter.title,
                    chapterPosition = targetChapterPosition,
                )
                setSuppressNextProgressSave(targetProgress > 0 || targetChapterPosition > 0)

                AppLog.info("Chapter", "loadChapter #$index/${chapterList.size} \"${chapter.title.take(20)}\" prog=$targetProgress pos=$targetChapterPosition ${if (isWebBook) "web" else "local"}")
                AppLog.info("ChapterIdxDebug", "loadChapter idx=$index title=\"${chapter.title}\" url=${chapter.url}")
                // BookmarkDebug: 同步打到书签调试 tag 方便抓链路（addBookmark →
                // jumpToBookmark → loadChapter → RenderedReaderChapter.initialChapterPosition
                // → CanvasRenderer.restoreProgress）。
                AppLog.info(
                    "BookmarkDebug",
                    "loadChapter #$index prog=$targetProgress pos=$targetChapterPosition" +
                        " renderedInitialChapPos=$targetChapterPosition",
                )
                // Don't reset navigateDirection here — let CanvasRenderer consume it
                // for startFromLastPage before resetting after progress restoration.
                if (targetProgress == 0 && targetChapterPosition == 0) onChapterLoaded()
                onInitialChapterLoaded()
                preloadNextChapter(index + 1)
                preloadPrevChapter(index - 1)
                maybeRetriggerPreCache(index)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (loadToken != chapterLoadToken) return@launch
                AppLog.error("Chapter", "Failed to load chapter $index", e)
                shouldPromptLogin(e)?.let { _loginPrompt.tryEmit(it) }
                val title = if (isWebBook) "正文加载失败" else "加载失败"
                val detail = if (isWebBook) {
                    webReaderErrorDetail(
                        book,
                        e.readerErrorMessage("正文解析失败"),
                    )
                } else {
                    e.readerErrorMessage("章节读取失败")
                }
                val errorContent = readerErrorContent(title, detail)
                _chapterContent.value = errorContent
                _renderedChapter.value = RenderedReaderChapter(
                    index = index,
                    title = chapter.title.ifBlank { title },
                    content = errorContent,
                    contentVersion = System.nanoTime(),
                    initialProgress = 0,
                    initialChapterPosition = 0,
                    restoreToken = System.nanoTime(),
                )
                _currentChapterIndex.value = index
                visiblePageState.value = VisibleReaderPage(index, chapter.title.ifBlank { title }, "0.0%", 0)
                scrollProgressState.value = 0
                shared.commitNavigateDirection(0, "loadChapter reset")
            } finally {
                if (loadToken == chapterLoadToken) {
                    _loading.value = false
                }
            }
        }
    }

    private suspend fun preloadNextChapter(nextIndex: Int) {
        val chapterList = _chapters.value
        if (nextIndex >= chapterList.size) return
        val book = _book.value ?: return
        try {
            withContext(Dispatchers.IO) {
                val raw = if (isWebBook(book)) {
                    loadWebChapterContent(book, chapterList[nextIndex], nextIndex)
                } else {
                    val localPath = book.localPath ?: return@withContext
                    LocalBookParser.readChapter(context, Uri.parse(localPath), book.format, chapterList[nextIndex])
                }
                val replaced = applyReplaceRules(raw)
                val converted = com.morealm.app.core.text.ChineseConverter.convert(replaced, chineseConvertMode())
                _nextPreloadedChapter.value = PreloadedReaderChapter(nextIndex, chapterList[nextIndex].title, converted)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 正常的：用户翻页时上一次 preload 协程会被 cancel，不是错误。
            // 不记 log，但必须重抛 — CancellationException 一旦被吞，结构化并发的
            // 取消传递就断了，上层 launch 会看到这个协程"成功完成"。
            throw e
        } catch (e: Exception) {
            AppLog.warn("Chapter", "Preload next chapter $nextIndex failed", e)
        }
    }

    private suspend fun preloadPrevChapter(prevIndex: Int) {
        if (prevIndex < 0) return
        val chapterList = _chapters.value
        val book = _book.value ?: return
        try {
            withContext(Dispatchers.IO) {
                val raw = if (isWebBook(book)) {
                    loadWebChapterContent(book, chapterList[prevIndex], prevIndex)
                } else {
                    val localPath = book.localPath ?: return@withContext
                    LocalBookParser.readChapter(context, Uri.parse(localPath), book.format, chapterList[prevIndex])
                }
                val replaced = applyReplaceRules(raw)
                val converted = com.morealm.app.core.text.ChineseConverter.convert(replaced, chineseConvertMode())
                _prevPreloadedChapter.value = PreloadedReaderChapter(prevIndex, chapterList[prevIndex].title, converted)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 同 preloadNextChapter — 翻页时上一轮 preload 被 cancel 是正常的。
            throw e
        } catch (e: Exception) {
            AppLog.warn("Chapter", "Preload prev chapter $prevIndex failed", e)
        }
    }

    /**
     * Re-trigger windowed pre-cache when user navigates far from the last pre-cache center.
     */
    fun maybeRetriggerPreCache(currentIndex: Int) {
        val book = _book.value ?: return
        val distance = kotlin.math.abs(currentIndex - lastPreCacheCenter)
        if (distance < 10) return
        lastPreCacheCenter = currentIndex

        val isWebBook = isWebBook(book)
        if (isWebBook) {
            val sourceUrl = book.sourceUrl ?: return
            scope.launch(Dispatchers.IO) {
                try {
                    val source = sourceRepo.getByUrl(sourceUrl) ?: return@launch
                    val webChapters = _chapters.value.map { ch ->
                        ChapterResult(title = ch.title, url = ch.url)
                    }
                    CacheBook.preload(source, webChapters, currentIndex, preloadCount = 5)
                    AppLog.debug("Chapter", "Web book pre-cache around chapter $currentIndex")
                } catch (e: Exception) {
                    AppLog.warn("Chapter", "Web pre-cache failed", e)
                }
            }
            return
        }

        val localPath = book.localPath ?: return
        val format = book.format
        if (format != com.morealm.app.domain.entity.BookFormat.EPUB
            && format != com.morealm.app.domain.entity.BookFormat.CBZ) return

        val chapters = _chapters.value
        val uri = Uri.parse(localPath)

        // **D1.b 已知**：EPUB 路径 cbw 不通到这里 → preCacheChapters 内部 skip（默认 cbw=0）。
        // CBZ 不依赖 cbw 正常工作。TODO(D2)：通过 viewModel 把 reader visibleWidth 写回
        // controller 字段，传给 EpubParser.preCacheChapters(..., cbw=visibleWidth) 恢复预热。
        scope.launch(Dispatchers.IO) {
            try {
                when (format) {
                    com.morealm.app.domain.entity.BookFormat.EPUB ->
                        com.morealm.app.domain.parser.EpubParser.preCacheChapters(context, uri, chapters, currentIndex)
                    com.morealm.app.domain.entity.BookFormat.CBZ ->
                        com.morealm.app.domain.parser.CbzParser.preCacheImages(context, uri, chapters, currentIndex)
                    else -> {}
                }
                if (format == com.morealm.app.domain.entity.BookFormat.EPUB) {
                    AppLog.debug("Chapter", "EPUB pre-cache around $currentIndex skipped (cbw unknown, D1.b TODO)")
                } else {
                    AppLog.debug("Chapter", "Re-triggered pre-cache around chapter $currentIndex")
                }
            } catch (e: Exception) {
                AppLog.warn("Chapter", "Pre-cache re-trigger failed", e)
            }
        }
    }

    // ── Web Book Support ──

    suspend fun loadWebBookChapters(book: Book): List<BookChapter> {
        val sourceUrl = book.sourceUrl ?: return emptyList()
        val source = withContext(Dispatchers.IO) {
            sourceRepo.getByUrl(sourceUrl)
        } ?: return emptyList()
        if (source.bookSourceType != TEXT_BOOK_SOURCE_TYPE) {
            AppLog.warn("Chapter", "Blocked non-text source chapters: ${source.bookSourceName} type=${source.bookSourceType}")
            return listOf(
                BookChapter(
                    id = "${book.id}_0",
                    bookId = book.id,
                    index = 0,
                    title = "非文本书源",
                    url = book.bookUrl,
                )
            )
        }
        if (book.bookUrl.isBlank()) return emptyList()

        var tocUrl = book.tocUrl?.takeIf { it.isNotBlank() } ?: book.bookUrl
        if (tocUrl == book.bookUrl && !book.hasDetail) {
            try {
                val searchBook = com.morealm.app.domain.entity.SearchBook(
                    bookUrl = book.bookUrl,
                    origin = sourceUrl,
                    originName = book.originName,
                    name = book.title,
                    author = book.author,
                    tocUrl = book.tocUrl ?: "",
                )
                val detailed = WebBook.getBookInfoAwait(source, searchBook)
                if (detailed.tocUrl.isNotBlank()) {
                    tocUrl = detailed.tocUrl
                }
                val updated = book.copy(
                    tocUrl = detailed.tocUrl.ifBlank { null },
                    description = detailed.intro?.ifBlank { book.description } ?: book.description,
                    coverUrl = detailed.coverUrl ?: book.coverUrl,
                    hasDetail = true,
                )
                bookRepo.update(updated)
                _book.value = updated
                AppLog.info("Chapter", "Fetched book info, tocUrl=${detailed.tocUrl}")
            } catch (e: Exception) {
                AppLog.warn("Chapter", "Failed to fetch book info: ${e.message}")
            }
        }

        val webChapters = WebBook.getChapterListAwait(source, book.bookUrl, tocUrl)
        return webChapters.mapIndexed { i, ch ->
            BookChapter(
                id = "${bookId}_$i", bookId = bookId,
                index = i, title = ch.title, url = ch.url,
            )
        }
    }

    suspend fun loadWebChapterContent(book: Book, chapter: BookChapter, index: Int): String {
        AppLog.info("ChapterIdxDebug", "loadWebChapterContent ENTRY idx=$index title=\"${chapter.title}\" url=${chapter.url}")
        if (chapter.url.startsWith(READER_ERROR_CHAPTER_URL_PREFIX)) {
            return chapter.variable ?: readerErrorContent(chapter.title, "当前书源没有返回可阅读内容。")
        }
        val sourceUrl = book.sourceUrl ?: return "（无书源）"
        val source = withContext(Dispatchers.IO) {
            sourceRepo.getByUrl(sourceUrl)
        } ?: run {
            val cached = CacheBook.getContent(sourceUrl, chapter.url)
            return cached?.let(::sanitizeWebChapterContent) ?: "（书源未找到）"
        }
        if (source.bookSourceType != TEXT_BOOK_SOURCE_TYPE) {
            AppLog.warn("Chapter", "Blocked non-text source content: ${source.bookSourceName} type=${source.bookSourceType}")
            return NON_TEXT_WEB_CONTENT_MESSAGE
        }

        val cached = CacheBook.getContent(sourceUrl, chapter.url)
        if (cached != null) return sanitizeWebChapterContent(cached)

        val nextUrl = _chapters.value.getOrNull(index + 1)?.url
        val content = WebBook.getContentAwait(source, chapter.url, nextUrl)
        AppLog.info(
            "ChapterIdxDebug",
            "loadWebChapterContent FETCHED idx=$index title=\"${chapter.title}\"" +
                " bodyLen=${content.length} bodyHead=\"${content.take(80).replace('\n', ' ')}\"",
        )
        val sanitized = sanitizeWebChapterContent(content)
        // Empty body / parse-failure → return a readable placeholder instead of "" so
        // the reader has something to render and the user is told how to recover.
        // Don't cache the placeholder — next attempt may succeed.
        if (sanitized.isBlank()) {
            AppLog.warn("Chapter", "empty content for ${book?.title}@${chapter.title} url=${chapter.url}")
            return EMPTY_CONTENT_PLACEHOLDER
        }
        if (content.isNotBlank() && sanitized == content) {
            CacheBook.putContent(sourceUrl, chapter.url, content)
        }
        return sanitized
    }

    fun sanitizeWebChapterContent(content: String): String {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return content
        val lower = trimmed.lowercase(Locale.ROOT)
        val nonBlankLines = trimmed.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(4)
            .toList()
        val looksLikeOnlyUrls = nonBlankLines.isNotEmpty() &&
            nonBlankLines.size <= 3 &&
            nonBlankLines.all { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
        val looksLikeMediaToken = lower.startsWith("#extm3u") ||
            lower.contains(".m3u8") ||
            lower.contains(".mp3") ||
            lower.contains(".m4a") ||
            lower.contains(".mp4") ||
            lower.contains("sound_id=") ||
            lower.contains("expire_time=") ||
            lower.contains("token=")
        return if (looksLikeOnlyUrls && looksLikeMediaToken) {
            AppLog.warn("Chapter", "Blocked media/token URL from WEB content")
            NON_TEXT_WEB_CONTENT_MESSAGE
        } else {
            content
        }
    }

    // ── Replace Rules ──

    /**
     * 当前章「真命中」规则集合 — 在 [applyReplaceRules] / [applyLoadedReplaceRulesSync] 内
     * 当 result != input 时记录该 rule。EffectiveReplacesDialog 通过 [hitContentRules] /
     * [hitTitleRules] 暴露给 UI。
     *
     * 「真命中」语义：rule.replace 真的改变了内容才算（含正则全局替换零次匹配 → 不算命中）。
     * 这与 Legado curTextChapter.effectiveReplaceRules 等价。
     *
     * 切章时：在 setChapterIndex / loadCurrentChapter 头部调用 [clearHitTracking] 重置。
     */
    private val hitContentRulesSet = java.util.Collections.synchronizedSet(linkedSetOf<com.morealm.app.domain.entity.ReplaceRule>())
    private val hitTitleRulesSet = java.util.Collections.synchronizedSet(linkedSetOf<com.morealm.app.domain.entity.ReplaceRule>())

    private val _hitContentRules = MutableStateFlow<List<com.morealm.app.domain.entity.ReplaceRule>>(emptyList())
    val hitContentRules: StateFlow<List<com.morealm.app.domain.entity.ReplaceRule>> = _hitContentRules.asStateFlow()

    private val _hitTitleRules = MutableStateFlow<List<com.morealm.app.domain.entity.ReplaceRule>>(emptyList())
    val hitTitleRules: StateFlow<List<com.morealm.app.domain.entity.ReplaceRule>> = _hitTitleRules.asStateFlow()

    /** Reset hit-tracking sets — must be called when current chapter changes. */
    fun clearHitTracking() {
        hitContentRulesSet.clear()
        hitTitleRulesSet.clear()
        _hitContentRules.value = emptyList()
        _hitTitleRules.value = emptyList()
    }

    /**
     * 清空 next/prev 章节的所有缓存（@Volatile 字段 + StateFlow）。
     *
     * 设计目的：当影响"章节内容呈现"的全局开关切换时（如繁简转换 mode、替换规则启用/禁用），
     * 已缓存的内容是用旧规则转换出来的，必须丢弃，否则会出现：切繁简模式后翻到下一章看到的
     * 还是用旧 mode 转过的字（"反效果"现象）；同步翻页路径 commitChapterShiftNext/Prev
     * 也会直接消费旧 PreloadedReaderChapter。
     *
     * 不清 _chapterContent（当前章），调用方负责后续 loadChapter 重排版。
     */
    fun clearPreloadedChapters() {
        _nextPreloadedChapter.value = null
        _prevPreloadedChapter.value = null
    }

    /** Re-pull rules from db (called after EffectiveReplacesDialog disables/edits a rule). */
    suspend fun refreshReplaceRules() {
        cachedReplaceRules = replaceRuleRepo.getRulesForBook(bookId)
        // 不在此处 clear hits — 重渲染时会自然刷新
    }

    /**
     * 登录脚本 `java.refreshBookToc()` 的落点：强制从书源重拉目录并持久化。
     * 无 web book / book 未加载时静默 no-op。不会重置当前章索引 / 进度。
     */
    fun refreshTocFromSource() {
        val book = _book.value ?: return
        if (!isWebBook(book)) return
        scope.launch(Dispatchers.IO) {
            try {
                val fresh = loadWebBookChapters(book)
                if (fresh.isNotEmpty()) {
                    _chapters.value = fresh
                    bookRepo.saveChapters(bookId, fresh)
                    if (book.totalChapters != fresh.size) {
                        bookRepo.update(book.copy(totalChapters = fresh.size))
                    }
                    AppLog.info("Chapter", "refreshTocFromSource: ${fresh.size} chapters")
                }
            } catch (e: Exception) {
                AppLog.warn("Chapter", "refreshTocFromSource failed: ${e.message?.take(60)}")
            }
        }
    }

    /**
     * 登录脚本 `java.refreshContent()` 的落点：重新加载当前章正文（Web book 会走网络）。
     * 通过走同款 [loadChapter]，命中 loading 状态 + 重排版 + 重 preload。
     */
    fun reloadCurrentChapter() {
        val idx = _currentChapterIndex.value
        scope.launch(Dispatchers.Main) { loadChapter(idx) }
    }

    /**
     * TXT 原文件写回后强制重建章节字节偏移，并同步刷新 DB 与当前阅读器状态。
     * 旧章节表绝不能继续使用：任意长度变化都会让后续 start/endPosition 全部失效。
     */
    suspend fun reparseLocalTxtAfterEdit(preferredChapterIndex: Int) {
        val book = _book.value ?: error("书籍尚未加载")
        require(book.format == BookFormat.TXT && !book.localPath.isNullOrBlank()) {
            "仅支持编辑本地 TXT"
        }
        val uri = Uri.parse(book.localPath)
        LocalBookParser.clearCharsetCache(uri)
        LocalBookParser.releaseTxtBuffer()
        val customRegex = prefs.customTxtChapterRegex.first()
        val fresh = LocalBookParser.parseChapters(context, uri, BookFormat.TXT, customRegex)
            .map { item ->
                if (item.bookId == bookId) item
                else item.copy(id = "${bookId}_${item.index}", bookId = bookId)
            }
        check(fresh.isNotEmpty()) { "替换后未能重新解析章节" }
        bookRepo.saveChapters(bookId, fresh)
        val fingerprint = com.morealm.app.domain.storage.LocalFileFingerprint.of(context, uri)
        val updated = book.copy(
            totalChapters = fresh.size,
            fileSize = fingerprint?.size ?: 0L,
            fileMtime = fingerprint?.mtime ?: 0L,
        )
        bookRepo.update(updated)
        _book.value = updated
        _chapters.value = fresh
        clearPreloadedChapters()
        val targetIndex = preferredChapterIndex.coerceIn(0, fresh.lastIndex)
        val previousRenderToken = _renderedChapter.value.restoreToken
        loadChapter(targetIndex)
        // loadChapter 内部启动异步读取。必须等新正文真正发布后再向 UI 返回“替换成功”，
        // 否则用户立刻关闭搜索面板时仍会看到旧页，只能退出重进才误以为生效。
        kotlinx.coroutines.withTimeout(10_000L) {
            _renderedChapter.first { rendered ->
                rendered.index == targetIndex && rendered.restoreToken != previousRenderToken
            }
        }
    }

    private fun publishHits() {
        _hitContentRules.value = hitContentRulesSet.toList()
        _hitTitleRules.value = hitTitleRulesSet.toList()
    }

    /**
     * 解析当前章内 `<a href>` 目标为纯文本（脚注气泡数据源）。
     *
     * 仅本地 EPUB 有内链语义；WEB/TXT/MOBI → null。href 相对基准 = 当前章 `chapter.url`。
     * IO 内联在协程里做（打开 zip + 流式扫一个 spine 文件，几十 ms 量级，气泡场景可接受）。
     */
    suspend fun resolveEpubLinkText(href: String): String? = withContext(Dispatchers.IO) {
        val book = _book.value ?: return@withContext null
        if (book.format != BookFormat.EPUB) return@withContext null
        val localPath = book.localPath ?: return@withContext null
        val chapter = _chapters.value.getOrNull(_currentChapterIndex.value) ?: return@withContext null
        runCatching {
            EpubParser.readLinkTargetText(context, Uri.parse(localPath), chapter.url, href)
        }.onFailure {
            AppLog.warn("Chapter", "resolveEpubLinkText failed href='$href': ${it.message}")
        }.getOrNull()
    }

    /**
     * 书内 `<a href>` → 章节跳转目标（目录页超链接）。纯路径匹配，不开书。
     *
     * 匹配优先级：
     *  1. 完整 `file#frag` 命中 navPoint（同 xhtml 多 navPoint 书，chapter.url 含 fragment）
     *  2. 无 fragment：按文件匹配整章（多 navPoint 共享文件时取第一个 = 章头）
     *  3. 有 fragment 但不是 navPoint → null，交给脚注提取；提取失败后调用方
     *     用 [fallbackToFile]=true 重查，退回整章跳转（锚点在章内某处的粗定位）
     *
     * @return 章节 index（喂 loadChapter，章头语义）；无法对应任何章节 → null
     */
    fun resolveEpubLinkChapterIndex(
        href: String,
        fallbackToFile: Boolean = false,
        sourceChapterIndex: Int = _currentChapterIndex.value,
    ): Int? {
        val book = _book.value ?: return null
        if (book.format != BookFormat.EPUB) return null
        val chapters = _chapters.value
        val current = chapters.getOrNull(sourceChapterIndex) ?: return null
        val filePart = href.substringBefore('#')
        if (filePart.isEmpty()) return null // 同文件锚 = 脚注/章内锚，不做章跳转
        val fragment = href.substringAfter('#', "")
        val target = resolveRelativeHref(current.url.substringBeforeLast("#"), filePart)
        if (fragment.isNotEmpty()) {
            val exact = chapters.indexOfFirst { it.url == "$target#$fragment" }
            if (exact >= 0) return exact
            if (!fallbackToFile) return null
        }
        return chapters.indexOfFirst { it.url.substringBeforeLast("#") == target }
            .takeIf { it >= 0 }
    }

    /**
     * 批量判定某个已排版章节里的链接目标是否真实存在。
     *
     * [sourceChapterIndex] 必须来自产出这些 LinkRange 的 layout，不能偷用当前章：相邻章
     * 已预排版时，两个章节的相对 `notes.xhtml#x` 基准目录可能不同。纯章节跳转先用目录
     * 表判定；剩余 fragment 共用一次 EPUB 打开提取文本；最后按目标文件存在做粗跳转兜底。
     */
    suspend fun resolveEpubLinkRanges(
        sourceChapterIndex: Int,
        links: List<com.morealm.epub.render.LinkRange>,
    ): List<com.morealm.epub.render.LinkRange> = withContext(Dispatchers.IO) {
        if (links.isEmpty()) return@withContext emptyList()
        val book = _book.value ?: return@withContext emptyList()
        if (book.format != BookFormat.EPUB) return@withContext emptyList()
        val localPath = book.localPath ?: return@withContext emptyList()
        val chapter = _chapters.value.getOrNull(sourceChapterIndex) ?: return@withContext emptyList()

        val direct = HashSet<String>()
        val textCandidates = LinkedHashSet<String>()
        for (href in links.map { it.href }.distinct()) {
            when {
                href.startsWith("http://", ignoreCase = true) ||
                    href.startsWith("https://", ignoreCase = true) -> direct.add(href)
                resolveEpubLinkChapterIndex(
                    href = href,
                    sourceChapterIndex = sourceChapterIndex,
                ) != null -> direct.add(href)
                else -> textCandidates.add(href)
            }
        }

        val textResolvable = runCatching {
            EpubParser.findResolvableLinkHrefs(
                context = context,
                uri = Uri.parse(localPath),
                baseChapterUrl = chapter.url,
                hrefs = textCandidates,
            )
        }.onFailure {
            AppLog.warn(
                "Chapter",
                "resolveEpubLinkRanges failed chapter=$sourceChapterIndex: ${it.message}",
            )
        }.getOrDefault(emptySet())

        links.filter { link ->
            link.href in direct || link.href in textResolvable ||
                resolveEpubLinkChapterIndex(
                    href = link.href,
                    fallbackToFile = true,
                    sourceChapterIndex = sourceChapterIndex,
                ) != null
        }
    }

    /**
     * 相对 href 解析，行为对齐 epub-lib ChapterReader.resolveRelative（internal 不可见，
     * 同款 URI resolve + 只解 %xx 不动 `+`）—— 保证章跳转与脚注提取算出同一目标路径。
     */
    private fun resolveRelativeHref(baseHref: String, src: String): String {
        if (src.isEmpty()) return src
        return try {
            val normalized = java.net.URI(baseHref).resolve(src).normalize().toString()
            java.net.URLDecoder.decode(normalized.replace("+", "%2B"), "UTF-8")
        } catch (_: Exception) {
            src
        }
    }

    /**
     * EPUB 精排内容是带排版 marker 的 wire 串，任何全文 regex 都可能打碎协议
     * （2026-07-26：一条断行合并规则把行首 BlockStyle marker 并进行中，翻页层只剥行首
     * marker → marker 字面量画进正文）。含 marker 时整章跳过替换规则。
     *
     * 标题不含 marker，这里只是让两个入口共用同一判断。
     */
    private fun skipReplaceForWireContent(content: String, isTitle: Boolean): Boolean {
        if (!WireMarkerGuard.containsWireMarkers(content)) return false
        AppLog.info(
            "Chapter",
            "replace rules skipped: content carries layout wire markers " +
                "(isTitle=$isTitle len=${content.length})",
        )
        return true
    }

    suspend fun applyReplaceRules(content: String, isTitle: Boolean = false): String {
        if (cachedReplaceRules.isEmpty()) return content
        if (skipReplaceForWireContent(content, isTitle)) return content
        var result = content
        var anyHit = false
        for (rule in cachedReplaceRules) {
            if (!rule.enabled || !rule.isValid()) continue
            if (isTitle && !rule.scopeTitle) continue
            if (!isTitle && !rule.scopeContent) continue
            val before = result
            try {
                result = if (rule.isRegex) {
                    try {
                        kotlinx.coroutines.withTimeout(rule.timeoutMs.toLong()) {
                            withContext(Dispatchers.Default) {
                                result.replace(getCachedRegex(rule.pattern), rule.replacement)
                            }
                        }
                    } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                        result
                    }
                } else {
                    result.replace(rule.pattern, rule.replacement)
                }
            } catch (_: Exception) {}
            if (result != before) {
                if (isTitle) hitTitleRulesSet.add(rule) else hitContentRulesSet.add(rule)
                anyHit = true
            }
        }
        if (anyHit) publishHits()
        return result
    }

    fun applyLoadedReplaceRulesSync(content: String, isTitle: Boolean = false): String {
        if (cachedReplaceRules.isEmpty()) return content
        if (skipReplaceForWireContent(content, isTitle)) return content
        var result = content
        var anyHit = false
        for (rule in cachedReplaceRules) {
            if (!rule.enabled || !rule.isValid()) continue
            if (isTitle && !rule.scopeTitle) continue
            if (!isTitle && !rule.scopeContent) continue
            val before = result
            try {
                result = if (rule.isRegex) {
                    result.replace(getCachedRegex(rule.pattern), rule.replacement)
                } else {
                    result.replace(rule.pattern, rule.replacement)
                }
            } catch (_: Exception) {
            }
            if (result != before) {
                if (isTitle) hitTitleRulesSet.add(rule) else hitContentRulesSet.add(rule)
                anyHit = true
            }
        }
        if (anyHit) publishHits()
        return result
    }

    // ── Error Content Helpers ──

    fun readerErrorContent(title: String, detail: String): String {
        val readableDetail = wrapLongErrorText(
            applyLoadedReplaceRulesSync(StringEscapeUtils.unescapeHtml4(detail.ifBlank { "当前书源没有返回可阅读内容。" })),
        )
        return buildString {
            append(title)
            append("\n\n")
            append(readableDetail)
            append("\n\n")
            append("可以返回搜索页换一个书源，或稍后重试。")
        }
    }

    private fun Throwable.readerErrorMessage(fallback: String): String {
        return localizedMessage
            ?.takeIf { it.isNotBlank() }
            ?.take(240)
            ?: fallback
    }

    /**
     * 粗启发式判断异常是否"像登录要做"。精准判定需要拿到 HTTP 状态码，但现有
     * WebBook 抛出的异常大多只有 message 字符串（包 HTTP 状态 / 业务原因 / "请登录"），
     * 所以采用文本匹配：
     *  - 401/403 这些明确状态码
     *  - "login/登录/unauth/forbidden/需要.*会员" 这类业务词
     *
     * 宁可漏报不可误报：只有强信号才 emit，否则用户每遇网络抖动都看到"去登录"是噪声。
     * book 非 web / 源没配 loginUrl 时一律 false。
     */
    private suspend fun shouldPromptLogin(e: Throwable): BookSource? {
        val book = _book.value ?: return null
        if (!isWebBook(book)) return null
        val url = book.sourceUrl ?: return null
        val source = sourceRepo.getByUrl(url) ?: return null
        if (source.loginUrl.isNullOrBlank()) return null
        val msg = (e.message ?: "") + " " + (e.cause?.message ?: "")
        val lower = msg.lowercase()
        val hit = "401" in msg || "403" in msg ||
            "unauth" in lower || "forbidden" in lower ||
            "login" in lower ||
            "登录" in msg ||          // "登录"
            "未授权" in msg ||    // "未授权"
            "会员" in msg ||          // "会员"
            "登入" in msg             // "登入"
        return if (hit) source else null
    }

    fun webReaderErrorDetail(book: Book, reason: String): String {
        val sourceName = StringEscapeUtils.unescapeHtml4(book.originName.ifBlank { book.sourceUrl ?: "未知书源" })
        val title = StringEscapeUtils.unescapeHtml4(book.title)
        return "书名：$title\n来源：$sourceName\n原因：$reason"
    }

    fun wrapLongErrorText(text: String, segmentLength: Int = 48): String {
        return text.lineSequence().joinToString("\n") { line ->
            line.split(' ').joinToString(" ") { token ->
                if (token.length <= segmentLength) token else token.chunked(segmentLength).joinToString("\n")
            }
        }
    }

    fun onScrollNearBottom() {
        val nextIdx = _currentChapterIndex.value + 1
        if (nextIdx < _chapters.value.size && _nextPreloadedChapter.value?.index != nextIdx) {
            scope.launch(Dispatchers.IO) {
                preloadNextChapter(nextIdx)
            }
        }
    }

    // ── SCROLL 模式专用接口（supplied for ChapterWindowSource） ──
    //
    // 这两个函数是 SCROLL 重架的「桥」：让独立 [ChapterWindowSource] 能复用
    // ReaderChapterController 已有的 fetch + replace rule + 繁简转换管线，又
    // 不会触发 [loadChapter] 的副作用风暴（清空三 flow / coordinator REBUILD /
    // restoreProgress JUMP）。
    //
    // 见 docs（temp/solution.txt）的「废除运行时强行 JUMP」与「LazyColumn 直接 addAll」
    // 现代化原则：SCROLL 模式滑动窗口仅扩展段落集合，不切换 cur 章。

    /**
     * 仅取章节正文文本：用 web book 走 [loadWebChapterContent]，本地书走
     * [LocalBookParser.readChapter]，再过 [applyReplaceRules] + 繁简转换。
     *
     * 与 [loadChapter] 的关键区别：
     * - **不**写 [_chapterContent] / [_renderedChapter] / [_currentChapterIndex] 任何 state
     * - **不**清空 prev/cur/next flow，**不**触发 preload neighbors
     * - **不**与 [chapterLoadJob] / [chapterLoadToken] 冲突（独立 IO 协程）
     *
     * 返回 null 表示加载失败（例如越界、book 为空、IO 异常）；调用方应自行处理
     * 占位 / 重试。同步异常会被吞 + 走 [AppLog.warn]。
     *
     * @param index 目标章节索引
     */
    suspend fun fetchAndPrepareChapter(
        index: Int,
        // **D1.b**：EPUB % margin 解析参考宽（host UI 计算 visibleWidth 后传入）。
        // 0 = 旧入口（默认值兼容）/ 非 EPUB / 不接 % margin。详 LocalBookParser.readChapter。
        epubContainingBlockWidthPx: Int = 0,
    ): String? {
        val chapterList = _chapters.value
        if (index !in chapterList.indices) return null
        val book = _book.value ?: return null
        val chapter = chapterList[index]
        return try {
            withContext(Dispatchers.IO) {
                val raw = if (isWebBook(book)) {
                    loadWebChapterContent(book, chapter, index)
                } else {
                    val localPath = book.localPath ?: return@withContext null
                    LocalBookParser.readChapter(
                        context, Uri.parse(localPath), book.format, chapter,
                        epubContainingBlockWidthPx = epubContainingBlockWidthPx,
                    )
                }
                val replaced = applyReplaceRules(raw)
                val converted = com.morealm.app.core.text.ChineseConverter.convert(replaced, chineseConvertMode())
                // 本地 TXT 首行缩进归一化（详见 [normalizeTxtParagraphIndent]）：纯文本路径
                // 没有 ContentProcessor 预埋的段首 "　　"，统一在此补齐，让 TXT 在滚动 /
                // 翻页两种模式下都有与网络书一致的首行缩进。
                if (book.format == com.morealm.app.domain.entity.BookFormat.TXT) {
                    normalizeTxtParagraphIndent(converted)
                } else {
                    converted
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 结构化并发的取消传递必须重抛，不能吞，否则上层 launch 会以为该协程「成功完成」
            throw e
        } catch (e: Exception) {
            AppLog.warn("Chapter", "fetchAndPrepareChapter($index) failed: ${e.message}", e)
            null
        }
    }

    /**
     * page-level 阅读器专用入口。EPUB 保留 StructuredChapterContent 的 section/CSS/背景
     * 作用域；其他格式以及实际命中内容替换的 EPUB 继续使用稳定的字符串路径。
     */
    suspend fun fetchAndPrepareScrollChapter(
        index: Int,
        epubContainingBlockWidthPx: Int = 0,
    ): ScrollChapterContent? {
        val chapterList = _chapters.value
        if (index !in chapterList.indices) return null
        val book = _book.value ?: return null
        val chapter = chapterList[index]

        if (isWebBook(book) || book.format != BookFormat.EPUB) {
            val text = fetchAndPrepareChapter(index, epubContainingBlockWidthPx) ?: return null
            return ScrollChapterContent(index, chapter.title, text)
        }

        val localPath = book.localPath ?: return null
        return try {
            val structured = withContext(Dispatchers.IO) {
                EpubParser.readChapterStructured(
                    context = context,
                    uri = Uri.parse(localPath),
                    chapter = chapter,
                    containingBlockWidthPx = epubContainingBlockWidthPx,
                )
            }
            if (structured.isEmpty()) {
                val fallback = fetchAndPrepareChapter(index, epubContainingBlockWidthPx) ?: return null
                return ScrollChapterContent(index, chapter.title, fallback)
            }

            val flattened = structured.flattenToString()
            val replaced = applyReplaceRules(flattened)
            val converted = com.morealm.app.core.text.ChineseConverter.convert(replaced, chineseConvertMode())
            val structureStillMatches = converted == flattened
            if (!structureStillMatches) {
                AppLog.warn(
                    "Chapter",
                    "EPUB structured layout fallback idx=$index: replace/conversion changed flattened content",
                )
            }
            ScrollChapterContent(
                chapterIndex = index,
                title = chapter.title,
                content = converted,
                structuredContent = structured.takeIf { structureStillMatches },
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.warn("Chapter", "fetchAndPrepareScrollChapter($index) failed: ${e.message}", e)
            val fallback = fetchAndPrepareChapter(index, epubContainingBlockWidthPx) ?: return null
            ScrollChapterContent(index, chapter.title, fallback)
        }
    }

    /**
     * 本地 TXT 段落首行缩进归一化。
     *
     * 背景：滚动 / 翻页统一走 [com.morealm.app.domain.render.layout.ScrollLayoutEngine]，
     * 引擎默认 `paragraphIndent=""`，首行缩进靠**文本里预埋的段首全角空格**渲染。
     * 网络书由 [com.morealm.app.domain.webbook.ContentProcessor] 预埋；本地 TXT 走
     * [com.morealm.app.domain.parser.LocalBookParser.readChapter] 不带缩进 → 两种模式
     * 都顶格。此处补齐：
     *
     *  1. 逐段（按 `\n`，与引擎 `split('\n')` 同口径）trim 掉行首脏缩进（全角空格 / NBSP /
     *     Tab / 普通空格 —— 覆盖整个 Unicode 空白类），抹平源文件五花八门的缩进；
     *  2. 非空段统一加 [firstLineIndentChars] 个全角空格（用户「首行缩进」设置，0=顶格）。
     *
     * 即源文件本来用 4 空格 / Tab / 全角空格缩进也归一成统一缩进，绝不双重缩进。
     * 标题行即便被加上缩进，引擎 `normalizeTitleForCompare` 比对前会剥掉全角空格，
     * 重复标题去重 / 自画标题块不受影响。
     *
     * 用户改「首行缩进」设置后，缩进字符数变化 → 正文 cp 坐标随之改变，必须**重新取章
     * 重排**才生效（ReaderViewModel 监听 firstLineIndent 触发，同简繁 reload 模式）。
     *
     * 仅对 [com.morealm.app.domain.entity.BookFormat.TXT] 调用 —— EPUB / MOBI 等是
     * HTML / 结构化内容，缩进靠自带 CSS `text-indent`（引擎 config fallback），不能逐行加
     * 空格，否则破坏标签结构。
     */
    private fun normalizeTxtParagraphIndent(content: String): String {
        val indent = "　".repeat(firstLineIndentChars().coerceIn(0, 4))
        return content.split('\n').joinToString("\n") { line ->
            val trimmed = line.trim { it.isWhitespace() || Character.isSpaceChar(it) }
            if (trimmed.isEmpty()) "" else indent + trimmed
        }
    }

    /**
     * SCROLL 模式下视口中心段所属章节漂移到新值时，由 [ChapterWindowSource]（debounced 300ms）
     * 调用，仅同步 [_currentChapterIndex]，让进度系统 / TTS / TOC 高亮等下游 collect 到正确
     * 章索引。
     *
     * **关键**：不调 [loadChapter]、不清空 prev/cur/next flow、不触发 preload。
     * 视口里的段落 (paragraphs) 由 [ChapterWindowSource] 自己管，cur 章的"切换"对
     * SCROLL 模式来说只是 UI 派生量。
     *
     * 同 idx 重复调用时短路（避免 StateFlow 触发不必要的下游重组）。
     */
    fun setCurrentChapterIndexFromScroll(index: Int) {
        if (index < 0 || index >= _chapters.value.size) return
        if (_currentChapterIndex.value == index) return
        _currentChapterIndex.value = index

        // 同步 visiblePageState.chapterIndex —— 防 saveProgress 用 stale chapterIndex
        // 写错章（V2 page-level 4 方验证发现的第 4 个独立真值）。
        // 参考 commitChapterShiftNext line 311-316 同款同步模式。
        run {
            val cur = visiblePageState.value
            if (cur.chapterIndex != index) {
                val title = _chapters.value.getOrNull(index)?.title.orEmpty()
                visiblePageState.value = cur.copy(
                    chapterIndex = index,
                    title = title,
                    chapterPosition = 0,
                )
            }
        }
    }

    /**
     * 「拖动 Slider 所见所得」轻量 seek 入口（2026-05-18）。
     *
     * 同 [loadChapter] 的对比：
     *   - 跨章 (index != _currentChapterIndex.value) → 直接 fallback [loadChapter]，
     *     走完整 cancel/restart + chapterLoadJob + readChapter IO + 重排版路径。
     *   - 同章 (index == _currentChapterIndex.value) → 不重 load 章节内容，
     *     仅重写 [_renderedChapter] 的 (initialProgress, restoreToken) → 下游
     *     [com.morealm.app.ui.reader.renderer.scroll.ScrollCanvasReaderHost] 的
     *     LaunchedEffect(restoreToken) 监听到新 token 后按 progress / 100 算 pixelOffset
     *     直接 placement-only 写入，**60fps 顺，无 Recompose / Measure**。
     *
     * 调用方：ReaderControlBar 拖动 conflate worker（高频）+ 松手 onValueChangeFinished
     * （低频）共用。同 (idx, progress) 重复调用时 _renderedChapter.copy 仍写一遍——
     * restoreToken 必然 nanoTime 不同，下游 LaunchedEffect 会重新执行（cancel-restart
     * 是 Compose 协程 cheap 操作；写 pixelOffset 是 mutableFloatStateOf 也 cheap）。
     *
     * 注意：此方法**不**主动调 saveProgressNow——拖动期间用户位置不稳定，等
     * onValueChangeFinished 通过 [ReaderProgressController] snapshot 收集器节流持久化。
     */
    fun seekProgressInPlace(index: Int, progress: Int) {
        val curIdx = _currentChapterIndex.value
        if (index != curIdx) {
            // 跨章：走完整 loadChapter 路径（与现有 onSeekFullBook 同等价格）
            com.morealm.app.core.log.AppLog.info(
                "ProgressSeek",
                "seekProgressInPlace CROSS-CH idx=$index progress=$progress curIdx=$curIdx → loadChapter",
            )
            loadChapter(index, restoreProgress = progress)
            return
        }
        val clamped = progress.coerceIn(0, 100)
        val rendered = _renderedChapter.value
        if (rendered.index != index) {
            // 罕见竞态：rendered 还没切到 curIdx → fallback loadChapter 强同步
            com.morealm.app.core.log.AppLog.info(
                "ProgressSeek",
                "seekProgressInPlace RENDER-MISMATCH idx=$index renderedIdx=${rendered.index} curIdx=$curIdx → fallback loadChapter",
            )
            loadChapter(index, restoreProgress = clamped)
            return
        }
        val oldToken = rendered.restoreToken
        val newToken = System.nanoTime()
        _renderedChapter.value = rendered.copy(
            initialProgress = clamped,
            initialChapterPosition = 0,
            restoreToken = newToken,
        )
        scrollProgressState.value = clamped
        com.morealm.app.core.log.AppLog.info(
            "ProgressSeek",
            "seekProgressInPlace SAME-CH idx=$index progress=$clamped token=$oldToken→$newToken",
        )
    }

    /** 给 ChapterWindowSource 用的章节标题查询 —— 本身就是 [chapters] flow 的薄包装。 */
    fun chapterTitleAt(index: Int): String =
        _chapters.value.getOrNull(index)?.title.orEmpty()

    /** 章节总数 —— 边界检查用。 */
    fun chaptersSize(): Int = _chapters.value.size
}
