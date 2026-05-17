package com.morealm.app.ui.reader.renderer.scroll

import android.graphics.Color
import android.graphics.Typeface
import android.text.TextPaint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.reader.scroll.ScrollChapterContent
import com.morealm.app.domain.render.scroll.ScrollLayoutEngine
import com.morealm.app.domain.render.scroll.extractText
import com.morealm.app.domain.render.scroll.findColumnAt
import com.morealm.app.ui.reader.renderer.ReaderInfoBar
import com.morealm.app.ui.reader.renderer.SelectionToolbar
import com.morealm.app.ui.reader.renderer.rememberBatteryStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 滚动 Canvas V2 阅读器宿主 —— ReaderScreen 直接调用，封装：
 * 1. ScrollLayoutEngine 实例化（按外部传入的 paint / 视图参数）
 * 2. 3 章异步加载 + 排版 → [ScrollCanvasReaderState]
 * 3. ScrollCanvasRenderer（三块面板 + 滚动 + 羽化 + 视口剔除 + tap-toggle）
 * 4. ReaderInfoBar 顶 / 底两条状态栏（与旧 SCROLL CanvasRenderer 完全同款 ReaderInfoBar 调用）
 * 5. ScrollSelectionOverlay（M6.4 暂禁，M4-revive 用 awaitEachGesture 修拦截后恢复）
 * 6. ScrollSelectionMenu（同上）
 *
 * Host 不持业务依赖（不引 Hilt / ViewModel），只接：
 *   - 章节状态：currentChapterIndex / chapterCount / loadChapterContent suspend lambda
 *   - 排版参数：viewWidth / viewHeight / paddingLeft / paddingRight / paddingTop / paddingBottom / fontSize
 *   - 回调：onChapterIndexChange / onTapCenter / onSelectionAction
 *   - InfoBar 配置：[ScrollCanvasInfoBarConfig]（null 时不画状态栏，纯净内容模式）
 */
@Composable
fun ScrollCanvasReaderHost(
    currentChapterIndex: Int,
    chapterCount: Int,
    loadChapterContent: suspend (Int) -> ScrollChapterContent?,
    paddingLeft: Int = 80,
    paddingRight: Int = 80,
    paddingTop: Int = 120,
    paddingBottom: Int = 120,
    fontSize: Int = 48,
    onChapterIndexChange: (Int) -> Unit = {},
    onTapCenter: () -> Unit = {},
    // ── M4-revive 选区菜单 callbacks（直接复用 SelectionToolbar）──
    onCopyText: (String) -> Unit = {},
    onSpeakFromHere: (chapterPosition: Int) -> Unit = {},
    onTranslateText: (String) -> Unit = {},
    onLookupWord: (String) -> Unit = {},
    onShareQuote: (String) -> Unit = {},
    /** 选区 → 高亮（KIND_BG）。null 时菜单按钮自动摘掉。 */
    onAddHighlight: ((startCp: Int, endCp: Int, content: String, argb: Int) -> Unit)? = null,
    /** 选区 → 字体强调色（KIND_TEXT_COLOR）。null 时按钮自动摘掉。 */
    onAddTextColor: ((startCp: Int, endCp: Int, content: String, argb: Int) -> Unit)? = null,
    /** 选区 → 下划线（KIND_UNDERLINE，含 style 0..3）。null 时按钮自动摘掉。 */
    onAddUnderline: ((startCp: Int, endCp: Int, content: String, argb: Int, style: Int) -> Unit)? = null,
    /** 选区交集橡皮删除。null 时按钮自动摘掉。 */
    onEraseHighlight: ((startCp: Int, endCp: Int) -> Unit)? = null,
    selectionMenuConfig: com.morealm.app.domain.entity.SelectionMenuConfig =
        com.morealm.app.domain.entity.SelectionMenuConfig.DEFAULT,
    infoBar: ScrollCanvasInfoBarConfig? = null,
    modifier: Modifier = Modifier,
) {
    // 用 BoxWithConstraints 拿真实容器宽 / 高（reader 实际可用区域，扣除 TopAppBar /
    // BottomBar / Notch 等占用后）。修复用户反馈"右边字被吃掉"：之前用
    // screenWidthDp 算 viewWidth，但 reader 容器宽往往 < screen width，layout 按
    // screen 排版导致行宽溢出可见区被屏幕截掉。
    BoxWithConstraints(modifier.fillMaxSize()) {
    val viewWidth = constraints.maxWidth
    val viewHeight = constraints.maxHeight
    // M6.4 paint 暂用 hardcode（M6.5 阶段接 ReaderRenderTheme 替换为主题派生）。
    // 颜色用 android.graphics.Color 是因为 TextPaint 用 Android Paint API（不是 Compose Color）。
    val contentPaint = remember(fontSize, infoBar?.textColor) {
        TextPaint().apply {
            textSize = fontSize.toFloat()
            color = infoBar?.textColor?.toArgb() ?: Color.BLACK
            isAntiAlias = true
        }
    }
    val titlePaint = remember(fontSize, infoBar?.textColor) {
        TextPaint().apply {
            textSize = fontSize * 1.5f
            color = infoBar?.textColor?.toArgb() ?: Color.BLACK
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

    val engine = remember(viewWidth, viewHeight, paddingLeft, paddingRight, paddingTop, paddingBottom, fontSize, infoBar?.textColor) {
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

    val state = remember { ScrollCanvasReaderState(initialChapterIndex = currentChapterIndex) }

    LaunchedEffect(currentChapterIndex, chapterCount) {
        state.chapterCount = chapterCount
        if (state.currentChapterIndex != currentChapterIndex) {
            state.currentChapterIndex = currentChapterIndex
            state.pixelOffset = 0f
            state.prevChapter = null
            state.currentChapter = null
            state.nextChapter = null
        }
    }

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

    // 选区状态（M4-revive 已修：SelectionOverlay fullscreen pointerInput 已删除，长按检测
    // 改由 ScrollCanvasRenderer 内 detectTapGestures.onLongPress 触发；handle drag 仍在
    // SelectionOverlay 内部 HandleDot 子 Composable 24dp 触发区内处理，不影响整屏 scroll）。
    var selection by remember { mutableStateOf(ScrollSelectionState.Empty) }

    // ── 电池 / 时间维护（与 CanvasRenderer line 380-389 同款）──
    val context = LocalContext.current
    val batteryStatus by rememberBatteryStatus(context)
    var currentTime by remember { mutableStateOf(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        }
    }

    Box(Modifier.fillMaxSize()) {
        val currentLayout = state.currentChapter
        if (currentLayout != null) {
            ScrollCanvasRenderer(
                state = state,
                onChapterShift = { _ ->
                    onChapterIndexChange(state.currentChapterIndex)
                },
                onTapCenter = {
                    // 选区 active 时 tap 取消选区，否则 toggle controls
                    if (selection.isActive) {
                        selection = handleCancelSelection()
                    } else {
                        onTapCenter()
                    }
                },
                onLongPress = { offset ->
                    // longPress 触发选区命中（view-local y → chapter y）
                    val yInChapter = offset.y + state.pixelOffset
                    val sel = handleLongPress(
                        layout = currentLayout,
                        chapterIndex = currentLayout.chapterIndex,
                        x = offset.x,
                        yInChapter = yInChapter,
                    )
                    if (sel.isActive) selection = sel
                },
            )
            // 选区 overlay（画 handle + handle drag）
            ScrollSelectionOverlay(
                selection = selection,
                onSelectionChange = { selection = it },
                layout = currentLayout,
                pixelOffsetProvider = { state.pixelOffset },
                scrollableState = null,  // 自动滚动联动 M6.x 完善
                viewportHeightProvider = { viewHeight },
            )

            // 选区菜单 —— 直接复用旧 LazyScrollSection 同款 SelectionToolbar，
            // 完整功能（8 项按钮 + 调色板 + 线型面板 + above/below 自适应定位）。
            if (selection.isActive && selection.chapterIndex == currentLayout.chapterIndex) {
                val cpRange = selection.cpRange
                val endHit = currentLayout.findColumnAt(cpRange.last)
                if (endHit != null) {
                    val endLineBottom = computeLineBottomYInChapterPublic(currentLayout, cpRange.last)
                    val anchorY = endLineBottom - state.pixelOffset
                    val anchorX = (endHit.column?.end ?: 0f) + currentLayout.paddingLeft
                    val selText = currentLayout.extractText(cpRange)
                    SelectionToolbar(
                        offset = Offset(anchorX, anchorY),
                        onCopy = {
                            onCopyText(selText)
                            selection = ScrollSelectionState.Empty
                        },
                        onSpeak = {
                            onSpeakFromHere(cpRange.first)
                            selection = ScrollSelectionState.Empty
                        },
                        onTranslate = {
                            onTranslateText(selText)
                            selection = ScrollSelectionState.Empty
                        },
                        onShare = {
                            onShareQuote(selText)
                            selection = ScrollSelectionState.Empty
                        },
                        onLookup = {
                            onLookupWord(selText)
                            selection = ScrollSelectionState.Empty
                        },
                        onHighlight = onAddHighlight?.let { cb ->
                            { argb ->
                                cb(cpRange.first, cpRange.last, selText, argb)
                                selection = ScrollSelectionState.Empty
                            }
                        },
                        onEraseHighlight = onEraseHighlight?.let { cb ->
                            {
                                cb(cpRange.first, cpRange.last)
                                selection = ScrollSelectionState.Empty
                            }
                        },
                        onTextColor = onAddTextColor?.let { cb ->
                            { argb ->
                                cb(cpRange.first, cpRange.last, selText, argb)
                                selection = ScrollSelectionState.Empty
                            }
                        },
                        onUnderline = onAddUnderline?.let { cb ->
                            { argb, style ->
                                cb(cpRange.first, cpRange.last, selText, argb, style)
                                selection = ScrollSelectionState.Empty
                            }
                        },
                        onDismiss = { selection = ScrollSelectionState.Empty },
                        config = selectionMenuConfig,
                    )
                }
            }
        }

        // ── InfoBar 顶 / 底两条（与 CanvasRenderer line 2078-2147 同款 ReaderInfoBar 调用）──
        if (infoBar != null && currentLayout != null) {
            val chapterTitle = currentLayout.title

            // V2 没"页"概念（pixelOffset 像素滚动），page / progress / page_progress slot
            // fallback 到 chapter_progress 保持显示。与旧 CanvasRenderer mapSlotForScroll 一致。
            fun mapSlot(s: String): String = when (s) {
                "page", "progress", "page_progress" -> "chapter_progress"
                else -> s
            }

            ReaderInfoBar(
                slotLeft = if (infoBar.showTimeBattery) mapSlot(infoBar.headerLeft) else "none",
                slotCenter = if (infoBar.showChapterName) mapSlot(infoBar.headerCenter) else "none",
                slotRight = if (infoBar.showTimeBattery) mapSlot(infoBar.headerRight) else "none",
                chapterTitle = chapterTitle,
                pageIndex = 0,
                pageCount = 0,
                currentPage = null,
                chapterIndex = state.currentChapterIndex,
                chaptersSize = infoBar.chaptersSize,
                batteryLevel = batteryStatus.level,
                batteryCharging = batteryStatus.charging,
                currentTime = currentTime,
                textColor = infoBar.textColor,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .height(64.dp)
                    .then(
                        if (infoBar.hasBgImage) Modifier
                        else Modifier.background(
                            Brush.verticalGradient(
                                0f to infoBar.backgroundColor,
                                0.72f to infoBar.backgroundColor,
                                1f to infoBar.backgroundColor.copy(alpha = 0f),
                            )
                        )
                    )
                    .padding(horizontal = infoBar.paddingHorizontal.dp, vertical = 8.dp),
            )
            ReaderInfoBar(
                slotLeft = if (infoBar.showChapterName) mapSlot(infoBar.footerLeft) else "none",
                slotCenter = if (infoBar.showTimeBattery) mapSlot(infoBar.footerCenter) else "none",
                slotRight = if (infoBar.showTimeBattery) mapSlot(infoBar.footerRight) else "none",
                chapterTitle = chapterTitle,
                pageIndex = 0,
                pageCount = 0,
                currentPage = null,
                chapterIndex = state.currentChapterIndex,
                chaptersSize = infoBar.chaptersSize,
                batteryLevel = batteryStatus.level,
                batteryCharging = batteryStatus.charging,
                currentTime = currentTime,
                textColor = infoBar.textColor,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(64.dp)
                    .then(
                        if (infoBar.hasBgImage) Modifier
                        else Modifier.background(
                            Brush.verticalGradient(
                                0f to infoBar.backgroundColor.copy(alpha = 0f),
                                0.28f to infoBar.backgroundColor,
                                1f to infoBar.backgroundColor,
                            )
                        )
                    )
                    .padding(horizontal = infoBar.paddingHorizontal.dp, vertical = 8.dp),
            )
        }
    }
    }
}
