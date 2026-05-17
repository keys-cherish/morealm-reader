package com.morealm.app.ui.reader.renderer.scroll

import android.graphics.Color
import android.graphics.Typeface
import android.text.TextPaint
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.reader.scroll.ScrollChapterContent
import com.morealm.app.domain.render.scroll.ScrollLayoutEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 滚动 Canvas V2 阅读器宿主 —— ReaderScreen 直接调用，封装：
 * 1. ScrollLayoutEngine 实例化（按外部传入的 paint / 视图参数）
 * 2. 3 章异步加载 + 排版 →  [ScrollCanvasReaderState]
 * 3. ScrollCanvasRenderer（三块面板 + 滚动）
 * 4. ScrollSelectionOverlay（长按 / handle drag）
 * 5. ScrollSelectionMenu（8 项菜单 callback 上抛 ViewModel）
 *
 * Host 不持业务依赖（不引 Hilt / ViewModel），只接：
 *   - currentChapterIndex / chapterCount：当前章索引 + 全书章节数
 *   - loadChapterContent suspend lambda：调用方提供章节内容加载（桥接 ReaderViewModel）
 *   - paint 参数：viewWidth/Height + padding + fontSize
 *   - onChapterIndexChange：cur 切到新章后回调（VM 更新自身 currentIndex 持久化）
 *   - onSelectionAction：选区菜单 8 项动作上抛
 *
 * 设计目标：让 ReaderScreen 加一行 `ScrollCanvasReaderHost(...)` 即可启用 V2 引擎，
 * 无需改 ViewModel 内部状态机。
 *
 * 当前简化（M6 阶段）：
 *   - paint 暂用 hardcode 色（黑文字 / 橙章序号 / 加粗标题）；主题切换 M6.x 接入
 *   - SelectionOverlay 不接 scrollState（自动滚动跳过）；M6.x 完善
 *   - 图片段绘制 / TTS 高亮 / 搜索高亮等 M5 阶段实现
 */
@Composable
fun ScrollCanvasReaderHost(
    currentChapterIndex: Int,
    chapterCount: Int,
    loadChapterContent: suspend (Int) -> ScrollChapterContent?,
    viewWidth: Int,
    viewHeight: Int,
    paddingLeft: Int = 80,
    paddingRight: Int = 80,
    paddingTop: Int = 120,
    paddingBottom: Int = 120,
    fontSize: Int = 48,
    onChapterIndexChange: (Int) -> Unit = {},
    onSelectionAction: (ScrollSelectionAction, ScrollSelectionState) -> Unit = { _, _ -> },
    onTapCenter: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val contentPaint = remember(fontSize) {
        TextPaint().apply {
            textSize = fontSize.toFloat()
            color = Color.BLACK
            isAntiAlias = true
        }
    }
    val titlePaint = remember(fontSize) {
        TextPaint().apply {
            textSize = fontSize * 1.5f
            color = Color.BLACK
            typeface = Typeface.DEFAULT_BOLD
            isAntiAlias = true
        }
    }
    val chapterNumPaint = remember(fontSize) {
        TextPaint().apply {
            textSize = fontSize * 0.75f
            color = Color.parseColor("#FF9800")
            isAntiAlias = true
        }
    }

    val engine = remember(viewWidth, viewHeight, paddingLeft, paddingRight, paddingTop, paddingBottom, fontSize) {
        ScrollLayoutEngine(
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            paddingLeft = paddingLeft,
            paddingRight = paddingRight,
            paddingTop = paddingTop,
            paddingBottom = paddingBottom,
            titlePaint = titlePaint,
            contentPaint = contentPaint,
            chapterNumPaint = chapterNumPaint,
        )
    }

    // ScrollCanvasReaderState：持 prev/cur/next + pixelOffset + chapterIndex。
    // 当 currentChapterIndex（外部传入）与 state 不一致时（如目录跳章 / 进入新书），
    // 下方 LaunchedEffect 会同步重建。
    val state = remember { ScrollCanvasReaderState(initialChapterIndex = currentChapterIndex) }

    // 同步外部章索引 → state（目录跳章 / 续读还原触发）
    LaunchedEffect(currentChapterIndex, chapterCount) {
        state.chapterCount = chapterCount
        if (state.currentChapterIndex != currentChapterIndex) {
            state.currentChapterIndex = currentChapterIndex
            state.pixelOffset = 0f
            // 清空三章 → 下面 effect 触发重新加载
            state.prevChapter = null
            state.currentChapter = null
            state.nextChapter = null
        }
    }

    // 异步加载并排版 cur/prev/next 三章；**复用已 ready 章节**避免 swap 后重复网络请求。
    //
    // 复用判定：state.currentChapter?.chapterIndex == curIdx 表示该位置已是正确章 → 跳过 load。
    // 例：swap to next 后 cur swap = old next（已 layout），仅 next 是 null 需 load；prev = old cur 也已 ready。
    LaunchedEffect(state.currentChapterIndex, engine) {
        val curIdx = state.currentChapterIndex
        val needLoadCur = state.currentChapter?.chapterIndex != curIdx
        val needLoadPrev = curIdx > 0 && state.prevChapter?.chapterIndex != curIdx - 1
        val needLoadNext = curIdx < chapterCount - 1 && state.nextChapter?.chapterIndex != curIdx + 1
        AppLog.info(
            "ScrollCanvasV2",
            "HOST sync cur=$curIdx loadCur=$needLoadCur loadPrev=$needLoadPrev loadNext=$needLoadNext " +
                "(chapterCount=$chapterCount viewWidth=$viewWidth viewHeight=$viewHeight)",
        )

        suspend fun loadAndLayout(idx: Int): com.morealm.app.domain.render.scroll.ScrollChapterLayout? {
            return try {
                val content = withContext(Dispatchers.IO) { loadChapterContent(idx) } ?: return null
                AppLog.info("ScrollCanvasV2", "  loaded idx=$idx contentLen=${content.content.length}")
                withContext(Dispatchers.Default) {
                    engine.layoutChapter(content.chapterIndex, content.title, content.content)
                }
            } catch (e: Throwable) {
                AppLog.warn("ScrollCanvasV2", "loadAndLayout FAILED idx=$idx: ${e.message}", e)
                null
            }
        }

        if (needLoadCur) {
            val curLayout = loadAndLayout(curIdx)
            if (curLayout != null) {
                state.currentChapter = curLayout
                AppLog.info("ScrollCanvasV2", "  cur layout READY pages=${curLayout.pages.size} totalH=${curLayout.totalHeight}")
            } else {
                state.currentChapter = null
                AppLog.warn("ScrollCanvasV2", "cur NULL idx=$curIdx — 无内容可滑")
            }
        }
        if (needLoadPrev) {
            val prevLayout = loadAndLayout(curIdx - 1)
            if (state.currentChapterIndex == curIdx) state.prevChapter = prevLayout
        }
        if (needLoadNext) {
            val nextLayout = loadAndLayout(curIdx + 1)
            if (state.currentChapterIndex == curIdx) state.nextChapter = nextLayout
        }
    }

    // 选区状态预留（M6.0 临时禁用：SelectionOverlay 的 detectTapGestures 会消费
    // down event 导致下层 scrollable 拿不到 → 用户「无法滑动」。
    // M6.1 待修：用 awaitEachGesture + awaitFirstDown(pass = Initial, requireUnconsumed = false)
    // 精细控制 pass，不消费 down，让滚动 + 长按选词共存）。
    @Suppress("UNUSED_VARIABLE")
    var selection by remember { mutableStateOf(ScrollSelectionState.Empty) }

    Box(modifier.fillMaxSize()) {
        val currentLayout = state.currentChapter
        if (currentLayout != null) {
            ScrollCanvasRenderer(
                state = state,
                onChapterShift = { _ ->
                    // swap 后 state.currentChapterIndex 已变；通知外部 VM 更新持久化
                    onChapterIndexChange(state.currentChapterIndex)
                },
                onTapCenter = onTapCenter,
            )
            // M6.0 暂禁 SelectionOverlay / SelectionMenu —— pointerInput 拦截
            // down event 导致滚动失效。先确保用户能滚 + 验证跳章 bug 根治。
            // M6.1 用 awaitEachGesture 精细控制 pass 后重新挂 overlay。
        }
    }
}
