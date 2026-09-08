package com.morealm.app.domain.render.pageanim

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.reader.runtime.ArtifactLoader
import com.morealm.app.domain.reader.runtime.ContentAnchor
import com.morealm.app.domain.reader.runtime.ReaderRuntimeAdapter
import com.morealm.app.domain.reader.runtime.ReaderSession
import com.morealm.app.domain.reader.runtime.ReaderWindowStore
import com.morealm.app.domain.reader.runtime.WindowEntry
import com.morealm.app.domain.reader.scroll.ScrollChapterContent
import com.morealm.epub.render.ScrollChapterLayout
import com.morealm.epub.render.ScrollLayoutEngine
import com.morealm.epub.render.findColumnAt
import com.morealm.app.domain.render.layout.ScrollPageFactory
import com.morealm.app.domain.render.layout.visibleChapterPosition
import com.morealm.app.ui.reader.renderer.scroll.ScrollCanvasReaderState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
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
 * ## 真源结构（Phase 1 runtime 真源化后）
 *
 * 章节窗口/加载/去重的真源在 `domain/reader/runtime/`：
 * - [ReaderWindowStore]：三窗口条目 + `Loading(requestId)` 幂等门控（并发去重靠它，
 *   不再有 inflight ConcurrentHashMap 防御层）。
 * - [ArtifactLoader]：fetch → 排版 → 背景专页展开 的唯一加载路径。
 * - [WindowLoadCoordinator]：把 ensure/complete/fail 编排成协程加载，并把结果投影进
 *   [ScrollCanvasReaderState]（投影层，Compose 订阅面）。
 * - [ReaderSession]：导航事务（Host 的跨章 commit 走 prepare/commit）。
 *
 * | 抽到 core                 | 留各 Host                                |
 * | ---                      | ---                                      |
 * | store/session/加载编排    | engine + paint 派生（各 Host 自管）       |
 * | chapterCount 同步         | JUMP / restoreProgress（语义各模式不同） |
 * | restoreToken → setExternal | TTS 段跟随 / 进度上报                |
 * | snapshotFlow 通知 VM      | 选区 Overlay / InfoBar / Renderer 调用   |
 * | styleSignature/版本失效   |                                          |
 * | cur 加载 + prev/next 预载 |                                          |
 *
 * @see com.morealm.app.ui.reader.renderer.scroll.ScrollCanvasReaderHost SCROLL 调用方
 */
class PageLevelCoreHandle internal constructor(
    val state: ScrollCanvasReaderState,
    val pageFactory: ScrollPageFactory,
    val windowStore: ReaderWindowStore,
    val session: ReaderSession,
)

/**
 * 创建 page-level 共享 core + 启动其 effect。返回 handle 含 state + factory + runtime。
 *
 * 调用方负责自己的 engine 实例（含 paint / padding / 排版参数），通过 [engine] 透传给
 * core 内 章节加载 + styleSignature 失效 effect 使用。
 *
 * @param bookId runtime LayoutKey 用的书 id
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
    bookId: String,
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
    /**
     * false = Host 的排版输入还没定稿（首帧组合时 onSizeChanged 实测尺寸 / 系统栏 inset
     * 尚未到位，engine 是注定被换代的临时实例）。此时不派发加载 —— 否则临时 engine 的
     * 排版白跑一整遍（1s+ 量级）才被换代作废重排，是进阅读器 / 进程死亡恢复白屏时长
     * 翻倍的根因。true 后 load effect 正常派发；后续 engine 再换代由
     * [WindowLoadCoordinator.updateLoader] 的在飞取消兜底。
     */
    layoutInputsSettled: Boolean = true,
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
    // contentVersion 上次被记账时对应的章 idx —— 区分「同章正文重发布」（TTX 缩进烘录 /
    // 替换规则 / 简繁转换，必须整窗失效重排）与「普通跨章导航」（loadChapter 每次都换
    // nanoTime，但正文没变，失效会把预载好的三章窗口全炸掉 → 按钮「下一章」黑/白屏闪）。
    var appliedContentChapter by remember { mutableIntStateOf(-1) }
    // 已消费的外部跳章 token（每个 token 至多触发一次 setExternalChapterIndex，防振荡）。
    var appliedExternalJumpToken by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    val pageFactory = remember(state) {
        ScrollPageFactory(
            dataSource = state,
            chapterShiftCallback = { delta ->
                if (delta == +1) state.swapToNext() else state.swapToPrev()
            },
        )
    }

    val windowStore = remember(bookId) {
        ReaderWindowStore(ReaderRuntimeAdapter.unitIdForChapter(currentChapterIndex))
    }
    val session = remember(windowStore) {
        ReaderSession(
            windowStore = windowStore,
            firstAnchorFor = { unitId -> ContentAnchor(unitId, 0) },
            lastAnchorFor = { unitId ->
                val window = windowStore.snapshot
                val entry = when (unitId) {
                    window.previous -> window.previousEntry
                    window.current -> window.currentEntry
                    window.next -> window.nextEntry
                    else -> WindowEntry.Empty
                }
                val lastOffset = (entry as? WindowEntry.Ready)
                    ?.artifact?.anchorIndex?.characterOffsets?.lastOrNull() ?: 0
                ContentAnchor(unitId, lastOffset)
            },
        )
    }
    val coroScope = rememberCoroutineScope()
    val coordinator = remember(windowStore, state) {
        WindowLoadCoordinator(windowStore, state, coroScope, bookId)
    }
    val loadChapterContentLatest by rememberUpdatedState(loadChapterContent)
    val loader = remember(engine) {
        ArtifactLoader({ idx -> loadChapterContentLatest(idx) }, engine)
    }

    // cur 章排版就绪：写投影 + reflow 锚点恢复 / 跨章 reset。
    fun applyCurrentLayout(layout: ScrollChapterLayout) {
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
    }

    SideEffect {
        coordinator.updateLoader(loader)
        coordinator.contentVersion = contentVersion
        coordinator.onCurrentReady = ::applyCurrentLayout
    }

    // chapterCount 同步（独立 effect，不参与 setExternal 决策）
    LaunchedEffect(chapterCount) {
        state.chapterCount = chapterCount
    }

    // 外部 jump 触发器。key 含 currentChapterIndex 是修「恢复竞态丢进度」：进程死亡
    // 恢复的首帧组合里 renderedChapter（带新 token）可能先于 currentChapterIndex 流
    // 到位（两条 StateFlow 跨帧竞态，prop 仍是 0）——只 key restoreToken 时该帧条件
    // `state.idx != prop` 不成立，之后 prop 变真值 effect 也不再醒来 → 窗口自顾自
    // 加载第 0 章并把可见页上报出去，进度被覆写成第 0 章（「间歇性丢进度」成因）。
    //
    // 防振荡（2026-05-19 根因案例）依旧成立：appliedExternalJumpToken 保证每个 token
    // 至多触发一次 setExternal —— page-level swap 反向通知 VM 后 prop 迟到追平时，
    // 旧 token 已消费，不会再被拿来把窗口拽回去。
    LaunchedEffect(restoreToken, currentChapterIndex) {
        if (
            restoreToken != 0L &&
            restoreToken != appliedExternalJumpToken &&
            state.currentChapterIndex != currentChapterIndex
        ) {
            appliedExternalJumpToken = restoreToken
            AppLog.info(
                "PageLevelCore",
                "[external jump] idx ${state.currentChapterIndex} → $currentChapterIndex (token=$restoreToken)",
            )
            val reusedLayout = state.setExternalChapterIndex(currentChapterIndex)
            if (reusedLayout) {
                // 邻章复用：layout 已就绪但 pageIndex 还是旧章的，立刻归零防越界
                // （EMPTY_PAGE 卡死，P0 2026-05-24）。Host 的 JUMP 随后按 cp/progress
                // 精确定位（按钮/目录跳转本就是章头语义，0 即终值）。
                pageFactory.moveToPage(0)
            }
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

    // 边界补载自愈：翻到章末页 / 章首页时确保邻章在载。正常路径 ensureNeighbors 已经
    // 预载过，这里 requestLoad 撞上 Loading/Ready 都是 store 门控下的 no-op；真正的
    // 服务对象是 **Failed 条目**（网络书瞬时失败后没人重试，用户翻到章末就卡死 ——
    // 「页有时候加载不出来」）：ensure 对 Failed 返回 Started，翻到边界即重试一次。
    LaunchedEffect(state, pageFactory) {
        snapshotFlow { Triple(pageFactory.pageIndex, state.currentChapter, state.currentChapterIndex) }
            .collect { (pageIdx, cur, curIdx) ->
                if (cur == null || cur.chapterIndex != curIdx) return@collect
                if (pageIdx >= cur.pages.size - 1 && state.nextChapter == null && curIdx + 1 < state.chapterCount) {
                    coordinator.requestLoad(curIdx + 1)
                }
                if (pageIdx == 0 && state.prevChapter == null && curIdx > 0) {
                    coordinator.requestLoad(curIdx - 1)
                }
            }
    }

    // engine 引用变化（字号 / 字体 / padding 变）→ 已有 layout 失效，清掉触发重排。
    // store 失效与投影清理同门控：仅当存在「非空且 sig 失配」的槽位才 invalidateAll ——
    // 调色板类 engine 重建（颜色不进 signature）两边都不清，行为等价旧链路。
    LaunchedEffect(engine) {
        val sig = engine.computeStyleSignature()
        // 同章重排恢复：清 currentChapter 前先记下当前视口顶 cp。加载完成回调重排完
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
        val anyStale = listOf(state.currentChapter, state.prevChapter, state.nextChapter)
            .any { it != null && it.styleSignature != sig }
        if (anyStale) coordinator.invalidateAll()
        if (state.currentChapter?.styleSignature != sig) state.currentChapter = null
        if (state.prevChapter?.styleSignature != sig) state.prevChapter = null
        if (state.nextChapter?.styleSignature != sig) state.nextChapter = null
    }

    // 加载编排：cur 缺失即 ensure（Started 才真加载，Loading 表示已在飞 —— 完成回调负责
    // 投影与预载）；cur 已就绪则直接对齐邻窗 + 预载 prev/next。
    LaunchedEffect(state.currentChapterIndex, currentChapterIndex, engine, contentVersion, layoutInputsSettled) {
        // 输入未定稿（首帧临时 engine）不派发 —— settled 翻 true 时本 effect 重跑补派。
        if (!layoutInputsSettled) return@LaunchedEffect
        val curIdx = state.currentChapterIndex
        // 同章 TXT 写回时 external chapter index 不变，旧逻辑不会走 setExternalChapterIndex，
        // state.currentChapter 因而一直保留旧分页。正文版本变化后在这里显式失效三章布局；
        // 同章 Slider/搜索定位只改 restoreToken，不会误触发昂贵重排。
        if (
            contentVersion != 0L &&
            contentVersion != appliedContentVersion &&
            curIdx == currentChapterIndex
        ) {
            // 仅「同一章的正文重发布」才整窗失效；跨章导航只记账不失效 ——
            // loadChapter 的 contentVersion 每次都是新 nanoTime，正文并没有变，
            // 失效会把预载好的 prev/next 全部作废，按钮跳章从秒切退化成整屏重排（屏闪）。
            val sameChapterRepublish = appliedContentChapter == curIdx
            if (sameChapterRepublish) {
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
                coordinator.invalidateAll()
                AppLog.info("PageLevelCore", "content version changed -> invalidate layouts idx=$curIdx")
            }
            appliedContentVersion = contentVersion
            appliedContentChapter = curIdx
        }
        coordinator.alignCurrent(curIdx)
        if (state.currentChapter?.chapterIndex != curIdx) {
            AppLog.info("PageLevelCore", "HOST loadCur idx=$curIdx")
            coordinator.requestLoad(curIdx)
        } else {
            // A visible projection alone is insufficient: navigation also needs a Ready artifact.
            coordinator.requestLoad(curIdx)
        }
    }

    return remember(state, pageFactory, windowStore, session) {
        PageLevelCoreHandle(state, pageFactory, windowStore, session)
    }
}
