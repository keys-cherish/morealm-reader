package com.morealm.app.domain.render.pageanim

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.reader.scroll.ScrollChapterContent
import com.morealm.app.domain.render.layout.ScrollChapterLayout
import com.morealm.app.domain.render.layout.ScrollLayoutEngine
import com.morealm.app.domain.render.layout.ScrollPageFactory
import com.morealm.app.ui.reader.renderer.scroll.ScrollCanvasReaderState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

/**
 * page-level 阅读器各 Host 共享 core —— Legado ReadView + TextPageFactory 模型在
 * MoRealm V2 Compose 下的等价实现。
 *
 * ## 设计动机
 *
 * Legado ReadView 是单一 FrameLayout 实例，6 种 PageDelegate (CoverPageDelegate /
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
    restoreToken: Long,
    onChapterIndexChange: (Int) -> Unit,
    loadChapterContent: suspend (Int) -> ScrollChapterContent?,
    engine: ScrollLayoutEngine,
): PageLevelCoreHandle {
    val state = remember { ScrollCanvasReaderState(initialChapterIndex = currentChapterIndex) }
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

    // engine 引用变化（字号 / 字体 / padding 变）→ 已有 layout 失效，清掉触发重排
    LaunchedEffect(engine) {
        val sig = engine.computeStyleSignature()
        if (state.currentChapter?.styleSignature != sig) state.currentChapter = null
        if (state.prevChapter?.styleSignature != sig) state.prevChapter = null
        if (state.nextChapter?.styleSignature != sig) state.nextChapter = null
    }

    // 章节加载 helper
    suspend fun loadAndLayout(idx: Int): ScrollChapterLayout? {
        return try {
            val content = withContext(Dispatchers.IO) { loadChapterContent(idx) } ?: return null
            AppLog.info("PageLevelCore", "  loaded idx=$idx contentLen=${content.content.length}")
            withContext(Dispatchers.Default) {
                engine.layoutChapter(content.chapterIndex, content.title, content.content)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            AppLog.warn("PageLevelCore", "loadAndLayout FAILED idx=$idx: ${e.message}", e)
            null
        }
    }

    // curChapter 即时加载（首次进 reader / 外部跳章后必须立即有内容可显示）
    LaunchedEffect(state.currentChapterIndex, engine) {
        val curIdx = state.currentChapterIndex
        if (state.currentChapter?.chapterIndex != curIdx) {
            AppLog.info("PageLevelCore", "HOST loadCur idx=$curIdx")
            val layout = loadAndLayout(curIdx)
            if (layout != null) {
                state.currentChapter = layout
                AppLog.info("PageLevelCore", "  cur READY pages=${layout.pages.size} totalH=${layout.totalHeight}")
            } else {
                state.currentChapter = null
                AppLog.warn("PageLevelCore", "cur NULL idx=$curIdx")
            }
        }
    }

    // prevNext 延迟预加载（debounce 300ms，章边界反复拖不触发）
    LaunchedEffect(engine) {
        snapshotFlow { state.currentChapterIndex }
            .debounce(300L)
            .collect { curIdx ->
                if (curIdx > 0 && state.prevChapter?.chapterIndex != curIdx - 1) {
                    val layout = loadAndLayout(curIdx - 1)
                    if (state.currentChapterIndex == curIdx) state.prevChapter = layout
                }
                if (curIdx < state.chapterCount - 1 && state.nextChapter?.chapterIndex != curIdx + 1) {
                    val layout = loadAndLayout(curIdx + 1)
                    if (state.currentChapterIndex == curIdx) state.nextChapter = layout
                }
            }
    }

    return remember(state, pageFactory) { PageLevelCoreHandle(state, pageFactory) }
}
