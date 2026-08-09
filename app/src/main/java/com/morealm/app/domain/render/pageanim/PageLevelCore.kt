package com.morealm.app.domain.render.pageanim

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.reader.scroll.ScrollChapterContent
import com.morealm.epub.render.ScrollChapterLayout
import com.morealm.epub.render.ScrollLayoutEngine
import com.morealm.epub.render.findColumnAt
import com.morealm.app.domain.render.layout.ScrollPageFactory
import com.morealm.app.domain.render.layout.visibleChapterPosition
import com.morealm.app.ui.reader.renderer.scroll.ScrollCanvasReaderState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * page-level 阅读器各 Host 共享 core —— 参照实现 ReadView + TextPageFactory 模型在
 * MoRealm V2 Compose 下的等价实现。
 *
 * ## 设计动机
 *
 * 参照实现 ReadView 是单一 FrameLayout 实例，6 种 PageDelegate (CoverPageDelegate /
 * SlidePageDelegate / SimulationPageDelegate / ScrollPageDelegate / NoAnimPageDelegate)
 * 可热切换。**共享**：state (ReadBook 单例) + pageFactory + 章节加载 + 长按选区 +
 * 手势接收。**独立**：各 Delegate 只 own 动画绘制 + drag 偏移 + fling settle。
 *
 * MoRealm 原 V2 `ScrollCanvasReaderHost` 把上述共享逻辑混在 SCROLL 专用代码里
 * (~1000 行)。COVER/SLIDE/NONE 接入时会被迫共用 SCROLL Host 的全部 setup
 * (选区/TTS/进度/InfoBar)，造成耦合 (2026-05-19 案例验证：曾把 COVER dispatch
 * 塞进 ScrollCanvasReaderHost 文件，名实脱节)。
 *
 * 本 helper 仅抽出**严格 page-level 共享**部分：
 *
 * | 抽到 helper             | 留各 Host                                |
 * | ---                     | ---                                      |
 * | state + factory 创建    | engine + paint 派生（各 Host 自管）       |
 * | chapterCount 同步       | JUMP / restoreProgress（语义各模式不同） |
 * | restoreToken → setExternal | TTS 段跟随 / 进度上报                |
 * | snapshotFlow 通知 VM    | 选区 Overlay / InfoBar / Renderer 调用   |
 * | styleSignature 失效     |                                          |
 * | curChapter 加载         |                                          |
 * | prevNext debounce 预加载 |                                          |
 *
 * 各 Host (ScrollCanvasReaderHost 垂直 / PageLevelReaderHost 横向覆盖/平移) 通过
 * [rememberPageLevelCore] 拿到 state + factory，剩余自管。
 *
 * ## 不抽到 helper 的理由
 *
 * - **JUMP**：SCROLL 算法 = chapter-Y → pageIdx + pageOffsetInPage（垂直语义）；
 *   COVER/SLIDE = page-by-page 直接 moveToPage，pageOffset 含义不同（X 方向）。
 *   各自算法不同，强行抽出会需要 callback 参数化，反而复杂。
 * - **TTS 跟随**：SCROLL 用 chapter-Y 滚到目标段；COVER/SLIDE 用 moveToPage 跳。
 * - **进度上报**：算法依赖 page 累加 Y vs page 数比例，各自不同。
 * - **选区 / InfoBar / Renderer**：完全各模式独立。
 *
 * @see com.morealm.app.ui.reader.renderer.scroll.ScrollCanvasReaderHost SCROLL 调用方
 */
class PageLevelCoreHandle internal constructor(
    val state: ScrollCanvasReaderState,
    val pageFactory: ScrollPageFactory,
)

/**
 * 创建 page-level 共享 core + 启动其 effect。返回 handle 含 state + factory。
 *
 * 调用方负责自己的 engine 实例（含 paint / padding / 排版参数），通过 [engine] 透传给
 * helper 内 章节加载 + styleSignature 失效 effect 使用。
 *
 * @param currentChapterIndex 外部 prop：用户当前章 idx（来自 ViewModel）
 * @param chapterCount 全书章节总数
 * @param restoreToken 外部跳章 token（loadChapter / seekProgressInPlace 换新值，0 = 无跳转）
 * @param onChapterIndexChange page-level swap 后通知 VM 的回调（debounce 150ms）。
 *   caller 应用轻量 API（不 trigger restoreToken / 不重 load chapter），避免反向 propagate race。
 * @param loadChapterContent 章节内容加载 lambda（caller 注入，通常走 ViewModel.fetchAndPrepareChapter）
 * @param engine 排版引擎实例（caller 自己创建并传入）。styleSignature 变化时清 state 的 prev/cur/next chapter 触发重排
 */
@OptIn(FlowPreview::class)
@Composable
fun rememberPageLevelCore(
    currentChapterIndex: Int,
    chapterCount: Int,
    /**
     * 当前正文发布版本。它与 restoreToken 分离：restoreToken 只负责定位；本值变化才重排。
     */
    contentVersion: Long,
    restoreToken: Long,
    onChapterIndexChange: (Int) -> Unit,
    loadChapterContent: suspend (Int) -> ScrollChapterContent?,
    engine: ScrollLayoutEngine,
    /**
     * true = 横向翻页（COVER/SLIDE/NONE，整页瞬切，pageOffset 恒 0）；false = 垂直滚动
     * （pageOffset 是页内纵向偏移）。仅影响同章 reflow 恢复时 pageOffset 的设法：横向
     * 恢复到含锚点的整页（offset=0），垂直恢复到锚点行的页内 Y。详见 reflow restore 处。
     */
    horizontalPaged: Boolean = false,
): PageLevelCoreHandle {
    val state = remember { ScrollCanvasReaderState(initialChapterIndex = currentChapterIndex) }
    // 同章重排（改字号/字体/行距 → engine 重建 → styleSignature 变）锚点：重排前记当前视口顶 cp，
    // 重排完据此恢复阅读位置，避免被跨章 reset 拉回章首。-1 = 无锚点（真跨章 / 首次加载走 reset 0）。
    var reflowAnchorCp by remember { mutableIntStateOf(-1) }
    // reflow 恢复落点 pageIndex —— 区分「reflow 自身的 moveToPage」与「用户主动翻页」：
    // 下方 snapshotFlow 监听到 pageIndex 偏离落点即视为用户翻页 → 让锚点失效（否则翻页后
    // 再切字体会按旧锚点跳回翻页前位置）。-1 = 无落点。
    var reflowSettlePageIdx by remember { mutableIntStateOf(-1) }
    // cp 命中失败时的兜底锚：重排前记下的章内页比例（0-1）。cp 主锚 + 比例兜底两层定位，
    // cp 越界 / 边缘命中失败时按比例就近落页，而非粗暴回章首（边缘漂移到章首根因）。
    var reflowAnchorFraction by remember { mutableFloatStateOf(0f) }
    var appliedContentVersion by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    val pageFactory = remember(state) {
        ScrollPageFactory(
            dataSource = state,
            chapterShiftCallback = { delta ->
                if (delta == +1) state.swapToNext() else state.swapToPrev()
            },
        )
    }

    // chapterCount 同步（独立 effect，不参与 setExternal 决策）
    LaunchedEffect(chapterCount) {
        state.chapterCount = chapterCount
    }

    // 外部 jump 触发器：仅看 restoreToken 变化。
    // page-level swap 反向通知 VM 时不动 restoreToken（契约），故此 effect 不触发
    // = 避免反向 setExternal 振荡（2026-05-19 MoRealm_log_20260519_195536 根因案例）。
    LaunchedEffect(restoreToken) {
        if (restoreToken != 0L && state.currentChapterIndex != currentChapterIndex) {
            AppLog.info(
                "PageLevelCore",
                "[external jump] idx ${state.currentChapterIndex} → $currentChapterIndex (token=$restoreToken)",
            )
            state.setExternalChapterIndex(currentChapterIndex)
        }
    }

    // page-level swap → 节流通知 VM。fling 期快速跨多章只触发最后一次。
    val onChapterIndexChangeUpdated by rememberUpdatedState(onChapterIndexChange)
    LaunchedEffect(state) {
        snapshotFlow { state.currentChapterIndex }
            .distinctUntilChanged()
            .debounce(150L)
            .collect { onChapterIndexChangeUpdated(it) }
    }

    // 用户主动翻页（pageIndex 偏离 reflow 落点）→ 锚点失效。否则同章翻页后再切字体会按旧
    // 锚点跳回翻页前位置。reflow 自身的 moveToPage 落点 == reflowSettlePageIdx，不误清。
    LaunchedEffect(state, pageFactory) {
        snapshotFlow { pageFactory.pageIndex }
            .collect { idx ->
                if (reflowAnchorCp >= 0 && idx != reflowSettlePageIdx) {
                    AppLog.info("PageLevelCore", "  user paged (pageIdx=$idx != settle=$reflowSettlePageIdx) -> clear reflow anchor")
                    reflowAnchorCp = -1
                }
            }
    }

    // engine 引用变化（字号 / 字体 / padding 变）→ 已有 layout 失效，清掉触发重排
    LaunchedEffect(engine) {
        val sig = engine.computeStyleSignature()
        // 同章重排恢复：清 currentChapter 前先记下当前视口顶 cp。下方 loadCur effect 重排完
        // （chapterIndex 不变）按此 cp 定位，避免 cross-ch reset 把视野拉回章首（改字号跳章首根因）。
        val cur = state.currentChapter
        // 仅在「无锚点」时记录：连续快速切字体时锚点冻结在用户原始视口 cp，避免每次 reflow
        // 用「整页对齐后的新页首 cp」重记 → 页首单调递减、视口逐次漂移（用户报「切换字体每页
        // 都不同」根因：cp 4124→4028→3947→…）。翻页后由下方 snapshotFlow 清锚点再重记。
        if (cur != null && cur.styleSignature != sig && reflowAnchorCp < 0) {
            reflowAnchorCp = visibleChapterPosition(
                layout = cur,
                pageIndex = pageFactory.pageIndex,
                pageOffset = state.pageOffset,
            ) ?: -1
            // 同时记章内页比例，作 cp 命中失败时的兜底锚（见 reflowAnchorFraction）。
            reflowAnchorFraction = if (cur.pages.isNotEmpty()) {
                pageFactory.pageIndex.toFloat() / cur.pages.size
            } else 0f
        }
        if (state.currentChapter?.styleSignature != sig) state.currentChapter = null
        if (state.prevChapter?.styleSignature != sig) state.prevChapter = null
        if (state.nextChapter?.styleSignature != sig) state.nextChapter = null
    }

    // **Commit X idempotent guard** —— loadAndLayout 并发去重：同 chapter idx 多 caller
    // 同时请求时，第一个跑 + 其余 join 已 inflight 的 Deferred。修复 chapter idx 重复
    // layoutChapter 3 次阻塞 worker → next chapter 排队 → SHIFT-NEXT-FAIL 卡死。
    //
    // 防御性修复（不查 root cause）；真正根因是 LaunchedEffect / Compose recomposition
    // 触发 cur/prev/next 重复加载请求。TODO(A5)：A5 measure/layout 重构时把
    // recomposition root cause 一并修（LaunchedEffect key 设计 / derivedStateOf / coroutine
    // cancellation 任一），跟 next 预加载真根因 + ScrollLine.alignment 重构同窗口。
    val inflightLayout = remember { ConcurrentHashMap<Int, Deferred<ScrollChapterLayout?>>() }
    val coroScope = rememberCoroutineScope()

    // 章节加载 helper
    suspend fun loadAndLayout(idx: Int): ScrollChapterLayout? {
        inflightLayout[idx]?.let { existing ->
            AppLog.info("PageLevelCore", "[IDEMPOTENT] chapter=$idx join existing inflight Deferred")
            return try {
                existing.await()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                null
            }
        }
        // **关键 bugfix**：deferred 用 coroScope（Composable lifecycle）独立于 caller 的 launch
        // —— caller 协程 cancel（LaunchedEffect 重启）时 deferred 仍跑，不应该 remove inflight。
        // 改用 invokeOnCompletion 在 deferred 真完成时 remove；caller cancel 不动 map。
        // 这样新 caller 来时仍能看见正在跑的 deferred + join 它（拿已跑结果或等结果）。
        val deferred = coroScope.async(Dispatchers.IO) {
            try {
                val content = loadChapterContent(idx) ?: return@async null
                AppLog.info(
                    "PageLevelCore",
                    // 不读 content.content：那是 lazy flatten，走结构化排版时本不必产生。
                    "  loaded idx=$idx blocks=${content.structuredContent?.blocks?.size ?: -1} " +
                        "plainLen=${content.plainContent?.length ?: -1}",
                )
                withContext(Dispatchers.Default) {
                    content.structuredContent?.let { structured ->
                        val layout = engine.layoutStructuredChapter(
                            content.chapterIndex,
                            content.title,
                            structured,
                            // EPUB 的标题属于 XHTML 正文。目录标题只用于导航和页眉，不能
                            // 再生成一个视觉标题，否则含 h1 的页面会出现重复标题。
                            omitChapterTitleBlock = true,
                        )
                        // 背景专页属于 EPUB 内容语义，不属于某一种翻页动画。所有模式都
                        // 走同一展开逻辑，避免滚动能显示、平移/覆盖/仿真只剩空白页。
                        expandBackgroundOnlyScrollPage(
                            layout = layout,
                            content = structured,
                            chapterTitle = content.title,
                            pageWidth = engine.viewWidth,
                            resolveImageDimensions = engine.imageDimensionsResolver::resolve,
                        )
                    } ?: engine.layoutChapter(content.chapterIndex, content.title, content.content)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AppLog.warn("PageLevelCore", "loadAndLayout FAILED idx=$idx: ${e.message}", e)
                null
            }
        }
        inflightLayout[idx] = deferred
        deferred.invokeOnCompletion {
            // race-safe remove：deferred 完成/失败/被取消都 remove（仅当 map 中仍是同一 deferred）
            inflightLayout.remove(idx, deferred)
        }
        return try {
            deferred.await()
        } catch (e: CancellationException) {
            // caller cancelled but deferred (coroScope-bound) may still complete and serve future joiners
            throw e
        }
    }

    // curChapter 即时加载（首次进 reader / 外部跳章后必须立即有内容可显示）+
    // **待办 2 根因修**：cur layout ready 瞬间立即并行 launch prev/next preload，
    // 替代旧的 LaunchedEffect debounce(300L) 预加载链路。
    //
    // 旧链路问题：cur idx 变化 → debounce 300ms → load prev/next，某 EPUB SCROLL 短章
    // (totalHeight=868 < view=1848) user 滚一下就到 cur 末，next 还没 ready 就 SHIFT-NEXT-FAIL
    // 卡 buffer（实测 11:23 / 11:57 / 12:10 日志现场）。
    //
    // 新链路：cur layout ready 瞬间立即触发 prev/next，节省 300ms debounce + cur layout
    // 跟 prev/next 加载在时间上重叠（cur ready 时 user 通常还在看第一屏）。
    //
    // 并行 launch：prev/next 加载相互独立，无依赖关系 → 并行启动比 sequential 快一倍。
    // LaunchedEffect 在 state.currentChapterIndex 或 engine 变化时自动 cancel 旧的子
    // launch（user fling 跨多章时不堆积，新章节立即重 trigger）。
    LaunchedEffect(state.currentChapterIndex, currentChapterIndex, engine, contentVersion) {
        val curIdx = state.currentChapterIndex
        // 同章 TXT 写回时 external chapter index 不变，旧逻辑不会走 setExternalChapterIndex，
        // state.currentChapter 因而一直保留旧分页。正文版本变化后在这里显式失效三章布局；
        // 同章 Slider/搜索定位只改 restoreToken，不会误触发昂贵重排。
        if (
            contentVersion != 0L &&
            contentVersion != appliedContentVersion &&
            curIdx == currentChapterIndex
        ) {
            val oldLayout = state.currentChapter
            if (oldLayout != null && reflowAnchorCp < 0) {
                reflowAnchorCp = visibleChapterPosition(
                    layout = oldLayout,
                    pageIndex = pageFactory.pageIndex,
                    pageOffset = state.pageOffset,
                ) ?: -1
                reflowAnchorFraction = if (oldLayout.pages.isNotEmpty()) {
                    pageFactory.pageIndex.toFloat() / oldLayout.pages.size
                } else {
                    0f
                }
            }
            state.prevChapter = null
            state.currentChapter = null
            state.nextChapter = null
            appliedContentVersion = contentVersion
            AppLog.info("PageLevelCore", "content version changed -> invalidate layouts idx=$curIdx")
        }
        if (state.currentChapter?.chapterIndex != curIdx) {
            AppLog.info("PageLevelCore", "HOST loadCur idx=$curIdx")
            val layout = loadAndLayout(curIdx)
            if (layout != null) {
                state.currentChapter = layout
                // 跨章 load 完毕：reset pageFactory.pageIndex + state.pageOffset 到章首 0。
                // V2 swap 路径 (ScrollPageFactory.moveToNext/Prev) 自身已 reset pageIndex；
                // 本路径专门处理 loadChapter / setExternalChapterIndex 跨章后 factory
                // 没人 reset 的 case —— 旧 pageIndex 若越界新 layout（如章 8 pageIndex=38
                // 切到只有 1 page 的章 0）→ curPage = EMPTY_PAGE → curPageH = 0 →
                // applyPageScrollDelta consume + 不动 = 卡死无法滑动 (P0 fix 2026-05-24).
                // Slider 拖动 in-place seek 的 restoreToken JUMP 路径会在 Host 层下一帧
                // 覆盖 pageIndex / pageOffset 到目标 progress（cp>0 或 prog>0 时），无副作用。
                val oldPageIdx = pageFactory.pageIndex
                val anchorCp = reflowAnchorCp
                if (anchorCp >= 0) {
                    // 同章重排：按重排前记下的 cp 在新 layout 定位，保持阅读位置不跳章首。
                    val hit = layout.findColumnAt(anchorCp)
                    if (hit != null) {
                        // 先记落点再 moveToPage：下方 snapshotFlow 据 settle 区分 reflow 自身翻页与
                        // 用户翻页，settle 必须在触发 pageIndex 变化前就位，否则被误判为用户翻页清锚点。
                        reflowSettlePageIdx = hit.page.pageIndex
                        pageFactory.moveToPage(hit.page.pageIndex)
                        // 横向翻页整页瞬切、pageOffset 恒 0：定位到含锚点的整页即可。若设成行内
                        // lineTop，页面会纵向偏移半页（用户报「改字体/边距后卡半页、跳页」根因）。
                        // 垂直滚动才需要把页内滚到锚点行的 Y。
                        state.pageOffset = if (horizontalPaged) 0f else hit.line.lineTop
                        // 不重置 reflowAnchorCp：连续切字体冻结锚点，避免逐次漂移（见记录处注释）。
                        AppLog.info("PageLevelCore", "  reflow restore cp=$anchorCp → page=${hit.page.pageIndex} off=${state.pageOffset} horizontalPaged=$horizontalPaged")
                    } else {
                        // cp 在新 layout 命中失败（cp 越界 / 边缘）→ 用章内页比例兜底，定位到
                        // 对应比例的页，不再粗暴回章首（边缘漂移到章首根因）。保持 anchorCp
                        // 不清，连续切字体仍稳定。
                        val lastPage = (layout.pages.size - 1).coerceAtLeast(0)
                        val targetPage = (reflowAnchorFraction.coerceIn(0f, 1f) * lastPage)
                            .roundToInt().coerceIn(0, lastPage)
                        reflowSettlePageIdx = targetPage
                        pageFactory.moveToPage(targetPage)
                        state.pageOffset = 0f
                        AppLog.info("PageLevelCore", "  reflow fraction-fallback cp=$anchorCp miss -> frac=$reflowAnchorFraction page=$targetPage")
                    }
                } else if (oldPageIdx != 0 || state.pageOffset != 0f) {
                    pageFactory.moveToPage(0)
                    state.pageOffset = 0f
                    AppLog.info("PageLevelCore", "  cross-ch reset pageIndex $oldPageIdx → 0, pageOffset → 0")
                }
                AppLog.info("PageLevelCore", "  cur READY pages=${layout.pages.size} totalH=${layout.totalHeight}")
            } else {
                state.currentChapter = null
                AppLog.warn("PageLevelCore", "cur NULL idx=$curIdx")
            }
        }
        // cur layout 已经 ready（无论是本 effect 刚加载完，还是之前已 cache），立即
        // 触发 prev/next 预加载。冗余 check `chapterIndex != curIdx + 1` 避免重复加载。
        launch {
            if (curIdx > 0 && state.prevChapter?.chapterIndex != curIdx - 1) {
                val prev = loadAndLayout(curIdx - 1)
                if (state.currentChapterIndex == curIdx) state.prevChapter = prev
            }
        }
        launch {
            if (curIdx < state.chapterCount - 1 && state.nextChapter?.chapterIndex != curIdx + 1) {
                val next = loadAndLayout(curIdx + 1)
                if (state.currentChapterIndex == curIdx) state.nextChapter = next
            }
        }
    }

    return remember(state, pageFactory) { PageLevelCoreHandle(state, pageFactory) }
}
