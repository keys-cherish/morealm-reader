package com.morealm.app.ui.reader.renderer.scroll

import android.graphics.Color
import android.graphics.Typeface
import android.text.TextPaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.reader.scroll.ScrollChapterContent
import com.morealm.app.domain.render.scroll.ScrollLayoutEngine
import com.morealm.app.domain.render.scroll.extractText
import com.morealm.app.domain.render.scroll.findColumnAt
import com.morealm.app.domain.render.scroll.findColumnByPixel
import com.morealm.app.ui.reader.renderer.ReaderInfoBar
import com.morealm.app.ui.reader.renderer.SelectionToolbar
import com.morealm.app.ui.reader.renderer.drawBgBitmap
import com.morealm.app.ui.reader.renderer.rememberBatteryStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
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
@OptIn(FlowPreview::class)
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
    /**
     * 正文字色（android argb）—— 与 [com.morealm.app.ui.reader.renderer.CanvasRenderer]
     * 同源（来自 ReaderTheme.readerText.toArgb()）。null 走默认黑色，但 ReaderScreen
     * 必传，避免夜间模式黑底黑字。
     */
    textColorArgb: Int? = null,
    /**
     * 用户字体（[android.graphics.Typeface]）。来自 ReaderScreen 的 readerTypeface。
     * null 走默认；与 V1 CanvasRenderer 同源。
     */
    typeface: android.graphics.Typeface? = null,
    /** 是否夜间模式 —— 决定 title / chapterNum 的颜色派生。 */
    isNight: Boolean = false,
    /** letterSpacing（ReaderStyle.letterSpacing）—— 字符间距乘 fontSize。 */
    letterSpacing: Float = 0f,
    /** 字重：0=normal / 1=bold / 2=light（ReaderStyle.textBold）。 */
    textBold: Int = 0,
    /** 阅读区背景图 uri；空串 = 纯色背景。来自 ReaderScreen 的 readerBgImage。 */
    bgImageUri: String = "",
    /** 阅读区纯色背景（android argb）—— 无背景图 / 背景图加载失败时使用。 */
    bgColorArgb: Int = android.graphics.Color.WHITE,
    // ── TTS 自动跟随 ──
    /** TTS 朗读当前段所在的章 idx；< 0 = 未朗读，本组件 noop。 */
    ttsChapterIndex: Int = -1,
    /** TTS 朗读当前段在章内的 chapterPosition；< 0 = 未朗读。 */
    ttsChapterPosition: Int = -1,
    // ── 搜索高亮 ──
    /** 搜索命中段在哪一章。-1 = 无搜索高亮。 */
    searchHighlightChapterIndex: Int = -1,
    /** 搜索命中段在章内的 [startCp, endCp) 范围 */
    searchHighlightCpRange: IntRange = IntRange.EMPTY,
    /** 搜索高亮 argb 颜色 */
    searchHighlightArgb: Int = 0x55FFFF00.toInt(),
    // ── RevealHighlight 跳转后呼吸高亮 ──
    /** 跳转目标段呼吸高亮；null 时不画。 */
    revealHighlight: com.morealm.app.ui.reader.renderer.RevealHighlight? = null,
    // ── 进度上报 ──
    /** 进度 live 回调（每章 0-100 整数）—— sample 150ms，fling 期间持续上报。 */
    onChapterProgressLive: (chapterIndex: Int, progress: Int) -> Unit = { _, _ -> },
    /** 进度 persist 回调 —— debounce 800ms，停手才上报；caller 在此写 DB。 */
    onChapterProgressPersist: (chapterIndex: Int, progress: Int) -> Unit = { _, _ -> },
    /**
     * 全书所有高亮（含 KIND_BACKGROUND / KIND_TEXT_COLOR / KIND_UNDERLINE）。
     * Host 内按 prev/cur/next 章过滤 + 投影为 spec → 透传给 ChapterPaneCanvas 绘制。
     */
    chapterHighlightsRaw: List<com.morealm.app.domain.entity.Highlight> = emptyList(),
    /**
     * tap 命中已存高亮后用户选删除时的回调（V1 LazyScrollSection 等价）。
     * caller 通常转发到 ReaderHighlightController.delete。null 时 tap 高亮不弹菜单。
     */
    onDeleteHighlight: ((id: String) -> Unit)? = null,
    /**
     * tap 命中已存高亮后用户选分享时的回调，传入整条 Highlight（分享卡片需要 content 等字段）。
     */
    onShareHighlight: ((com.morealm.app.domain.entity.Highlight) -> Unit)? = null,
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
    // ── M6.5 paint 派生（与 V1 CanvasRenderer line 393-433 同源算法）──
    // 用 textColorArgb（fresh）+ fontSize + typeface + letterSpacing + bold 派生：
    //   - contentPaint：正文，color = textColorArgb
    //   - titlePaint：章首块大字标题，color = chapterTitleColor（夜模式浅色 / 日模式深色）
    //   - chapterNumPaint：橙色"第 N 章" 章号小字，color = chapterAccentColor
    // 不再 hardcode Color.BLACK → 夜模式自动适配。
    val resolvedTextColor = textColorArgb ?: Color.BLACK
    val chapterTitleColor = if (isNight) 0xFFE0E0E0.toInt() else 0xFF1A1A1A.toInt()
    val chapterAccentColor = if (isNight) 0xFFCFA875.toInt() else 0xFFBFA175.toInt()

    val contentPaint = remember(fontSize, typeface, resolvedTextColor, letterSpacing, textBold) {
        TextPaint().apply {
            color = resolvedTextColor
            textSize = fontSize.toFloat()
            isAntiAlias = true
            this.typeface = when (textBold) {
                1 -> Typeface.create(typeface ?: Typeface.DEFAULT, Typeface.BOLD)
                else -> typeface ?: Typeface.DEFAULT
            }
            this.letterSpacing = letterSpacing
        }
    }
    val titlePaint = remember(fontSize, typeface, chapterTitleColor, letterSpacing) {
        TextPaint().apply {
            color = chapterTitleColor
            textSize = fontSize * 1.45f
            isAntiAlias = true
            isFakeBoldText = true
            this.typeface = typeface ?: Typeface.DEFAULT_BOLD
            this.letterSpacing = letterSpacing + 0.01f
        }
    }
    val chapterNumPaint = remember(fontSize, typeface, chapterAccentColor, letterSpacing) {
        TextPaint().apply {
            color = chapterAccentColor
            textSize = fontSize * 0.85f
            isAntiAlias = true
            isFakeBoldText = true
            this.typeface = Typeface.create(typeface ?: Typeface.DEFAULT, Typeface.BOLD)
            this.letterSpacing = letterSpacing + 0.04f
        }
    }

    val engine = remember(viewWidth, viewHeight, paddingLeft, paddingRight, paddingTop, paddingBottom, contentPaint, titlePaint, chapterNumPaint) {
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

    // engine 引用变化（fontSize / typeface / padding / 任一影响排版的参数变）→
    // 现有所有 layout 失效（用旧 paint 排的，行宽/行高都错），必须强制重排所有 3 章。
    // 用 styleSignature 对比：layout 的 signature 与当前 engine 的 signature 不一致 = 失效。
    LaunchedEffect(engine) {
        val sig = engine.computeStyleSignature()
        if (state.currentChapter?.styleSignature != sig) state.currentChapter = null
        if (state.prevChapter?.styleSignature != sig) state.prevChapter = null
        if (state.nextChapter?.styleSignature != sig) state.nextChapter = null
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

    // ── tap-on-highlight 弹删除/分享菜单（V1 LazyScrollSection L545-575 + L859-885 等价）──
    // tap 命中已存高亮 cp 范围时不切控制栏，弹 HighlightActionToolbar；再 tap 关闭。
    var highlightActionTarget by remember(state.currentChapterIndex) {
        mutableStateOf<com.morealm.app.domain.entity.Highlight?>(null)
    }
    var highlightActionAnchor by remember { mutableStateOf(Offset.Zero) }

    // ── 高亮 spec 投影（V1 LazyScrollSection 按章过滤 + 渲染等价路径）──
    // 当前章 / 上一章 / 下一章 layout 变化 OR highlightRaw 列表变化时重投影。
    // 投影 = 按 cp 范围算 rects，与 ChapterPaneCanvas drawCpRangeRects 算法等价但缓存。
    val prevHighlightSpecs = remember(state.prevChapter, chapterHighlightsRaw) {
        state.prevChapter?.let {
            com.morealm.app.domain.render.scroll.ScrollHighlightProjector.project(it, chapterHighlightsRaw)
        } ?: emptyList()
    }
    val curHighlightSpecs = remember(state.currentChapter, chapterHighlightsRaw) {
        state.currentChapter?.let {
            com.morealm.app.domain.render.scroll.ScrollHighlightProjector.project(it, chapterHighlightsRaw)
        } ?: emptyList()
    }
    val nextHighlightSpecs = remember(state.nextChapter, chapterHighlightsRaw) {
        state.nextChapter?.let {
            com.morealm.app.domain.render.scroll.ScrollHighlightProjector.project(it, chapterHighlightsRaw)
        } ?: emptyList()
    }

    // ── 电池 / 时间维护（与 CanvasRenderer line 380-389 同款）──
    val context = LocalContext.current

    // ── 背景图加载（V1 CanvasRenderer line 1525-1532 同款逻辑）──
    // BgImageManager LRU 缓存（最多 3 张：day/night/blur），同 uri 同尺寸命中。
    val bgEntry = remember(bgImageUri, viewWidth, viewHeight) {
        if (bgImageUri.isNotEmpty() && viewWidth > 0 && viewHeight > 0) {
            com.morealm.app.domain.render.BgImageManager.getBgBitmap(
                context, bgImageUri, viewWidth, viewHeight,
            )
        } else null
    }
    val bgBitmap = bgEntry?.bitmap

    // ── TTS 段自动跟随（V1 LazyScrollRenderer line 348-382 同款）──
    // tts 推进到下一段 → 计算目标 cp 在 currentChapter 的 y 坐标 → 用
    // scrollableState.animateScrollBy 平滑滚到视口中心。目标段已在视口 / 不在当前章
    // 时不滚（不打扰用户主动操作）。
    // 这里用 LaunchedEffect 监听 ttsChapterIndex / ttsChapterPosition 变化即可，
    // scrollableState 由 ScrollCanvasRenderer 内部持有，无法直接动它 —— 改写 pixelOffset
    // 即可达到同等效果（pixelOffset 是 mutableFloatStateOf，写入立即触发 placement-only
    // 重组，下一帧视口就更新）。
    val ttsTargetY by remember(state.currentChapter, ttsChapterIndex, ttsChapterPosition) {
        derivedStateOf {
            val layout = state.currentChapter ?: return@derivedStateOf null
            if (ttsChapterIndex < 0 || ttsChapterIndex != layout.chapterIndex) return@derivedStateOf null
            if (ttsChapterPosition < 0) return@derivedStateOf null
            val hit = layout.findColumnAt(ttsChapterPosition) ?: return@derivedStateOf null
            // 累加 hit.page 之前所有 page.height + 当前 page 内 line.lineTop
            var y = 0f
            for (i in 0 until hit.page.pageIndex) {
                y += layout.pages[i].height
            }
            y += hit.line.lineTop
            y
        }
    }
    LaunchedEffect(ttsTargetY) {
        val targetY = ttsTargetY ?: return@LaunchedEffect
        val viewportH = viewHeight
        if (viewportH <= 0) return@LaunchedEffect
        // 当前视口范围 = [pixelOffset, pixelOffset + viewportH]
        // 目标段已在视口内 → 不滚（不打扰用户）
        val curOffset = state.pixelOffset
        if (targetY in curOffset..(curOffset + viewportH - 200f)) return@LaunchedEffect
        // 否则把目标段滚到视口上 1/3 处（与 V1 LazyScrollRenderer anchorOffset / 2 类似，
        // V2 用上 1/3 让朗读段下方还有"接下来要读的"上下文）
        val desiredOffset = (targetY - viewportH / 3f).coerceAtLeast(0f)
        state.pixelOffset = desiredOffset
        AppLog.debug(
            "ScrollCanvasV2",
            "TTS follow: targetY=$targetY → pixelOffset $curOffset → $desiredOffset (viewportH=$viewportH)",
        )
    }

    // ── 进度上报：live (sample 150ms) + persist (debounce 800ms) ──
    // V1 LazyScrollRenderer line 501-550 同款思路：UI 跟随用 live、DB 写入用 persist。
    // 进度计算 = pixelOffset / (totalHeight - viewportH) 比 / totalHeight 准确（避免章末
    // 进度永远停在 90% 多）。
    LaunchedEffect(state.currentChapter, viewHeight) {
        val layout = state.currentChapter ?: return@LaunchedEffect
        if (layout.totalHeight <= 0f) return@LaunchedEffect
        snapshotFlow { state.pixelOffset }
            .sample(150L)
            .map { offset ->
                val scrollableRange = (layout.totalHeight - viewHeight).coerceAtLeast(1f)
                val progress = (offset / scrollableRange * 100f).toInt().coerceIn(0, 100)
                layout.chapterIndex to progress
            }
            .distinctUntilChanged()
            .collect { (chIdx, prog) -> onChapterProgressLive(chIdx, prog) }
    }
    LaunchedEffect(state.currentChapter, viewHeight) {
        val layout = state.currentChapter ?: return@LaunchedEffect
        if (layout.totalHeight <= 0f) return@LaunchedEffect
        snapshotFlow { state.pixelOffset }
            .debounce(800L)
            .map { offset ->
                val scrollableRange = (layout.totalHeight - viewHeight).coerceAtLeast(1f)
                val progress = (offset / scrollableRange * 100f).toInt().coerceIn(0, 100)
                layout.chapterIndex to progress
            }
            .distinctUntilChanged()
            .collect { (chIdx, prog) -> onChapterProgressPersist(chIdx, prog) }
    }

    val batteryStatus by rememberBatteryStatus(context)
    var currentTime by remember { mutableStateOf(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        }
    }

    Box(modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(bgColorArgb))) {
        // 1. 背景图层（固定不滚动，与 LazyScrollRenderer line 742-751 同款）：
        //    在 Box 内但在 ScrollCanvasRenderer 之前先画 → z-order 在文字下方。
        //    bgBitmap 已经按 viewWidth × viewHeight 缓存，center-crop 填满。
        bgBitmap?.let { bmp ->
            Canvas(Modifier.fillMaxSize()) {
                if (!bmp.isRecycled) {
                    drawIntoCanvas { compose ->
                        drawBgBitmap(compose.nativeCanvas, bmp, size.width, size.height)
                    }
                }
            }
        }

        val currentLayout = state.currentChapter
        if (currentLayout != null) {
            ScrollCanvasRenderer(
                state = state,
                contentPaint = contentPaint,
                titlePaint = titlePaint,
                chapterNumPaint = chapterNumPaint,
                revealHighlight = revealHighlight,
                searchHighlightChapterIndex = searchHighlightChapterIndex,
                searchHighlightCpRange = searchHighlightCpRange,
                searchHighlightArgb = searchHighlightArgb,
                selectionChapterIndex = if (selection.isActive) selection.chapterIndex else -1,
                selectionCpRange = if (selection.isActive) selection.cpRange else IntRange.EMPTY,
                prevHighlightSpecs = prevHighlightSpecs,
                curHighlightSpecs = curHighlightSpecs,
                nextHighlightSpecs = nextHighlightSpecs,
                onChapterShift = { _ ->
                    onChapterIndexChange(state.currentChapterIndex)
                },
                onTapCenter = {
                    // 选区 active 时 tap 取消选区，否则 toggle controls
                    if (selection.isActive) {
                        selection = handleCancelSelection()
                    } else if (highlightActionTarget != null) {
                        highlightActionTarget = null
                    } else {
                        onTapCenter()
                    }
                },
                onTap = { offset ->
                    // tap-on-highlight 命中：tap 落点的 cp 与某条 highlight 的 cp 范围相交 →
                    // 弹删除/分享菜单（HighlightActionToolbar）。consumed=true 拦住 onTapCenter。
                    if (chapterHighlightsRaw.isEmpty()) return@ScrollCanvasRenderer false
                    val yInChapter = offset.y + state.pixelOffset
                    val hit = currentLayout.findColumnByPixel(offset.x - currentLayout.paddingLeft, yInChapter)
                        ?: return@ScrollCanvasRenderer false
                    val cp = hit.column?.chapterPosition ?: hit.line.firstChapterPos
                    val highlight = chapterHighlightsRaw.firstOrNull { h ->
                        h.chapterIndex == currentLayout.chapterIndex &&
                            cp >= h.startChapterPos && cp < h.endChapterPos
                    } ?: return@ScrollCanvasRenderer false
                    highlightActionAnchor = offset
                    highlightActionTarget = highlight
                    true
                },
                onLongPress = { offset ->
                    // longPress 触发选区命中。坐标转换：
                    //   - offset 是 view-local（detectTapGestures 提供）
                    //   - engine column.x 是 chapter-local（[0..visibleWidth]，不含 paddingLeft）
                    //   - 故 x = view-x - paddingLeft；y = view-y + pixelOffset
                    // anchorInBox 仍传 view-local 给 SelectionToolbar 当 Popup anchor（PopupPositionProvider
                    // 内部用 anchorBounds.topLeft + anchorRect 转 window 坐标，应给 view-local）。
                    val xInChapter = offset.x - currentLayout.paddingLeft
                    val yInChapter = offset.y + state.pixelOffset
                    val sel = handleLongPress(
                        layout = currentLayout,
                        chapterIndex = currentLayout.chapterIndex,
                        x = xInChapter,
                        yInChapter = yInChapter,
                        anchorInBox = offset,
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
                    // popup anchor = 长按 tap 点（view-local，与 V1 LazyScrollSection
                    // anchorInBox 等价）。这样 popup / 箭头始终指向用户最初按下位置，
                    // 不会随 handle drag 而飘到选区末。
                    val selText = currentLayout.extractText(cpRange)
                    androidx.compose.runtime.LaunchedEffect(cpRange) {
                        AppLog.info(
                            "ScrollSelection",
                            "SelectionToolbar render anchorInBox=${selection.anchorInBox} " +
                                "cpRange=$cpRange selText='${selText.take(20)}' " +
                                "endCol=${endHit.column?.let { "x=${it.start}..${it.end} char='${it.charData}'" }}",
                        )
                    }
                    SelectionToolbar(
                        offset = selection.anchorInBox,
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

            // tap-on-highlight 弹删除/分享菜单（V1 LazyScrollSection L859-885 等价）
            highlightActionTarget?.let { target ->
                com.morealm.app.ui.reader.renderer.HighlightActionToolbar(
                    offset = highlightActionAnchor,
                    colorArgb = target.colorArgb,
                    onDelete = {
                        onDeleteHighlight?.invoke(target.id)
                        highlightActionTarget = null
                    },
                    onShare = {
                        onShareHighlight?.invoke(target)
                        highlightActionTarget = null
                    },
                    onDismiss = { highlightActionTarget = null },
                )
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
