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

    // 异步加载并排版 cur/prev/next 三章
    LaunchedEffect(state.currentChapterIndex, engine) {
        val curIdx = state.currentChapterIndex
        // cur 先加载（用户立即可见）
        val curContent = withContext(Dispatchers.IO) { loadChapterContent(curIdx) }
        if (curContent != null) {
            val curLayout = withContext(Dispatchers.Default) {
                engine.layoutChapter(curContent.chapterIndex, curContent.title, curContent.content)
            }
            state.currentChapter = curLayout
        }
        // prev / next 并行后台加载
        if (curIdx > 0) {
            val prevContent = withContext(Dispatchers.IO) { loadChapterContent(curIdx - 1) }
            if (prevContent != null) {
                val prevLayout = withContext(Dispatchers.Default) {
                    engine.layoutChapter(prevContent.chapterIndex, prevContent.title, prevContent.content)
                }
                if (state.currentChapterIndex == curIdx) state.prevChapter = prevLayout
            }
        }
        if (curIdx < chapterCount - 1) {
            val nextContent = withContext(Dispatchers.IO) { loadChapterContent(curIdx + 1) }
            if (nextContent != null) {
                val nextLayout = withContext(Dispatchers.Default) {
                    engine.layoutChapter(nextContent.chapterIndex, nextContent.title, nextContent.content)
                }
                if (state.currentChapterIndex == curIdx) state.nextChapter = nextLayout
            }
        }
    }

    // 选区状态（Host 持，UI 层 SelectionOverlay 操作）
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
            )
            ScrollSelectionOverlay(
                selection = selection,
                onSelectionChange = { selection = it },
                layout = currentLayout,
                pixelOffsetProvider = { state.pixelOffset },
                // 自动滚动暂不联动选区（M6.x 完善）
                scrollableState = null,
                viewportHeightProvider = { viewHeight },
            )
            ScrollSelectionMenu(
                selection = selection,
                layout = currentLayout,
                pixelOffsetProvider = { state.pixelOffset },
                onAction = { action ->
                    onSelectionAction(action, selection)
                    selection = ScrollSelectionState.Empty
                },
            )
        }
    }
}
