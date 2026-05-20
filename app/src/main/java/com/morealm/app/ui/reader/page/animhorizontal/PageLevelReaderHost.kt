package com.morealm.app.ui.reader.page.animhorizontal

import android.graphics.Color
import android.graphics.Typeface
import android.text.TextPaint
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.morealm.app.domain.reader.scroll.ScrollChapterContent
import com.morealm.app.domain.render.pageanim.rememberPageLevelCore
import com.morealm.app.domain.render.scroll.ScrollLayoutEngine
import com.morealm.app.ui.reader.page.animation.PageAnimType
import com.morealm.app.ui.reader.renderer.ReaderInfoBar
import com.morealm.app.ui.reader.renderer.rememberBatteryStatus
import com.morealm.app.ui.reader.renderer.scroll.ScrollCanvasInfoBarConfig
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import androidx.compose.runtime.snapshotFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * page-level 翻页阅读器宿主 —— 服务 COVER / SLIDE / NONE 三种横向翻页动画。
 *
 * 设计参考 Legado `ReadView` (FrameLayout 单实例) + `PageDelegate` (各动画独立) 模型：
 *
 * - **共享** (本 Host)：page-level state/factory (走 [rememberPageLevelCore]) + engine
 *   + paint 派生 + 长按选区 + 手势接收 + InfoBar + 进度上报 + TTS 跟随
 * - **独立** (各 Renderer)：动画绘制 + drag 偏移 + fling settle
 *
 * 与 [com.morealm.app.ui.reader.renderer.scroll.ScrollCanvasReaderHost] 的关系：
 * 后者专属 SCROLL（垂直滚动），本 Host 服务横向翻页。两个 Host **不互相调用**，
 * 共享只通过 [rememberPageLevelCore] helper。SCROLL 文件名实统一不被混用。
 *
 * P2 阶段（2026-05-20）：骨架版本，只接 NONE Renderer；COVER / SLIDE 后续阶段补。
 * 选区 / TTS / InfoBar / 进度上报 / restoreToken JUMP P3 接入 ReaderScreen 时
 * 再依次补全（按"独立 Host 自管"原则）。
 */
@OptIn(FlowPreview::class)
@Composable
fun PageLevelReaderHost(
    currentChapterIndex: Int,
    chapterCount: Int,
    loadChapterContent: suspend (Int) -> ScrollChapterContent?,
    /** 翻页动画类型 —— NONE / COVER / SLIDE，决定内部 dispatch 到哪个 Renderer。 */
    animType: PageAnimType,
    viewWidth: Int,
    viewHeight: Int,
    paddingLeft: Int = 80,
    paddingRight: Int = 80,
    paddingTop: Int = 120,
    paddingBottom: Int = 120,
    fontSize: Int = 48,
    textColorArgb: Int? = null,
    typeface: Typeface? = null,
    isNight: Boolean = false,
    letterSpacing: Float = 0f,
    textBold: Int = 0,
    lineSpacingExtra: Float = 1.2f,
    paragraphSpacing: Int = 8,
    paragraphIndent: String = "",
    titleMode: Int = 0,
    titleAlign: Int = 0,
    textFullJustify: Boolean = true,
    bgColorArgb: Int = Color.WHITE,
    restoreToken: Long = 0L,
    /** InfoBar 顶/底状态栏配置；null = 不画状态栏（纯净内容模式）。 */
    infoBar: ScrollCanvasInfoBarConfig? = null,
    /** 进度上报 live 回调（每章 0-100 整数）—— sample 150ms，UI 跟随用。 */
    onChapterProgressLive: (chapterIndex: Int, progress: Int) -> Unit = { _, _ -> },
    /** 进度上报 persist 回调 —— debounce 800ms，停止翻页后才上报；caller 在此写 DB。 */
    onChapterProgressPersist: (chapterIndex: Int, progress: Int) -> Unit = { _, _ -> },
    onChapterIndexChange: (Int) -> Unit = {},
    onTapCenter: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // ── paint 派生（与 ScrollCanvasReaderHost L225-260 同算法）──
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

    // safe area + InfoBar 占位（infoBar != null 时正文 padding 给 InfoBar 让位避免遮挡）
    val density = androidx.compose.ui.platform.LocalDensity.current
    val infoBarHeightPx = with(density) { 64.dp.toPx() }.toInt()
    val statusBarPx = with(density) {
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding().toPx()
    }.toInt()
    val navBarPx = with(density) {
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding().toPx()
    }.toInt()
    val cutoutTopPx = with(density) {
        WindowInsets.displayCutout.asPaddingValues().calculateTopPadding().toPx()
    }.toInt()
    val cutoutBottomPx = with(density) {
        WindowInsets.displayCutout.asPaddingValues().calculateBottomPadding().toPx()
    }.toInt()
    val topInsetPx = maxOf(statusBarPx, cutoutTopPx)
    val bottomInsetPx = maxOf(navBarPx, cutoutBottomPx)
    val effectivePadTop = if (infoBar != null) paddingTop + topInsetPx + infoBarHeightPx else paddingTop + topInsetPx
    val effectivePadBottom = if (infoBar != null) paddingBottom + bottomInsetPx + infoBarHeightPx else paddingBottom + bottomInsetPx

    val engine = remember(
        viewWidth, viewHeight, paddingLeft, paddingRight, effectivePadTop, effectivePadBottom,
        contentPaint, titlePaint, chapterNumPaint, lineSpacingExtra, paragraphSpacing,
        paragraphIndent, titleMode, titleAlign, textFullJustify,
    ) {
        ScrollLayoutEngine(
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            paddingLeft = paddingLeft,
            paddingRight = paddingRight,
            paddingTop = effectivePadTop,
            paddingBottom = effectivePadBottom,
            titlePaint = titlePaint,
            contentPaint = contentPaint,
            chapterNumPaint = chapterNumPaint,
            lineSpacingExtra = lineSpacingExtra,
            paragraphSpacing = paragraphSpacing,
            paragraphIndent = paragraphIndent,
            titleMode = titleMode,
            titleAlign = titleAlign,
            textFullJustify = textFullJustify,
        )
    }

    val core = rememberPageLevelCore(
        currentChapterIndex = currentChapterIndex,
        chapterCount = chapterCount,
        restoreToken = restoreToken,
        onChapterIndexChange = onChapterIndexChange,
        loadChapterContent = loadChapterContent,
        engine = engine,
    )

    // ── 进度上报 live (sample 150ms) + persist (debounce 800ms) ──
    // page-level 横向语义：progress = (curPage idx + 1) / pageCount * 100。
    // 与 SCROLL 的 chapter-Y / scrollableRange 算法不同（横向无连续滚动概念）。
    LaunchedEffect(core.state.currentChapter) {
        val layout = core.state.currentChapter ?: return@LaunchedEffect
        snapshotFlow { core.pageFactory.pageIndex }
            .sample(150L)
            .map { pageIdx ->
                val total = layout.pages.size.coerceAtLeast(1)
                val progress = ((pageIdx + 1).toFloat() / total * 100f).toInt().coerceIn(0, 100)
                layout.chapterIndex to progress
            }
            .distinctUntilChanged()
            .collect { (chIdx, prog) -> onChapterProgressLive(chIdx, prog) }
    }
    LaunchedEffect(core.state.currentChapter) {
        val layout = core.state.currentChapter ?: return@LaunchedEffect
        snapshotFlow { core.pageFactory.pageIndex }
            .debounce(800L)
            .map { pageIdx ->
                val total = layout.pages.size.coerceAtLeast(1)
                val progress = ((pageIdx + 1).toFloat() / total * 100f).toInt().coerceIn(0, 100)
                layout.chapterIndex to progress
            }
            .distinctUntilChanged()
            .collect { (chIdx, prog) -> onChapterProgressPersist(chIdx, prog) }
    }

    // 电池 / 时间维护（InfoBar 用）
    val context = LocalContext.current
    val batteryStatus by rememberBatteryStatus(context)
    var currentTime by remember { mutableStateOf(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000L)
            currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        }
    }

    Box(modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(bgColorArgb))) {
        val currentLayout = core.state.currentChapter
        if (currentLayout != null) {
            // dispatch 到具体 Renderer（独立 own 自己的动画 + drag）
            when (animType) {
                PageAnimType.NONE -> {
                    NonePageRenderer(
                        state = core.state,
                        pageFactory = core.pageFactory,
                        backgroundColor = androidx.compose.ui.graphics.Color(bgColorArgb),
                        contentPaint = contentPaint,
                        titlePaint = titlePaint,
                        chapterNumPaint = chapterNumPaint,
                        modifier = Modifier.fillMaxSize().pointerInput(animType) {
                            // NONE 无动画手势：zone tap 直接 moveToPrev/Next（共享在 Host 层
                            // 不下放到 Renderer，符合"长按/选区/手势共享" Legado 模型）。
                            detectTapGestures { offset ->
                                val w = size.width.toFloat()
                                when {
                                    offset.x < w * 0.33f -> core.pageFactory.moveToPrev()
                                    offset.x > w * 0.67f -> core.pageFactory.moveToNext()
                                    else -> onTapCenter()
                                }
                            }
                        },
                    )
                }
                // TODO P4.next: PageAnimType.SLIDE -> SlidePageRenderer(...)
                // TODO P5: PageAnimType.COVER -> CoverPageRenderer(...)
                else -> {
                    // 未实现的动画 fallback NONE 渲染（safety net，避免显示空白）
                    NonePageRenderer(
                        state = core.state,
                        pageFactory = core.pageFactory,
                        backgroundColor = androidx.compose.ui.graphics.Color(bgColorArgb),
                        contentPaint = contentPaint,
                        titlePaint = titlePaint,
                        chapterNumPaint = chapterNumPaint,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            // ── InfoBar 顶/底（P4.1 接入，与 SCROLL Host 同款 + 横向 page-level slot 语义）──
            if (infoBar != null) {
                // 横向 page-level 模式有"页"概念，slot "page"/"progress"/"page_progress" 保留原义
                // （不像 SCROLL 把 "page" 降级到 "chapter_progress"）。
                fun mapSlot(s: String): String = s

                // page-level 进度 = (curPage 在章内 1-based idx) / 章 page 总数 * 100
                val scrollPercent by remember(currentLayout) {
                    derivedStateOf {
                        val total = currentLayout.pages.size.coerceAtLeast(1)
                        val curIdx = core.pageFactory.pageIndex.coerceIn(0, total - 1)
                        ((curIdx + 1).toFloat() / total * 100f).coerceIn(0f, 100f)
                    }
                }

                val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                val cutoutTop = WindowInsets.displayCutout.asPaddingValues().calculateTopPadding()
                val cutoutBottom = WindowInsets.displayCutout.asPaddingValues().calculateBottomPadding()
                val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                val topInsetDp = if (statusBarTop.value >= cutoutTop.value) statusBarTop else cutoutTop
                val bottomInsetDp = if (navBarBottom.value >= cutoutBottom.value) navBarBottom else cutoutBottom

                val chapterTitle = currentLayout.title

                ReaderInfoBar(
                    slotLeft = if (infoBar.showTimeBattery) mapSlot(infoBar.headerLeft) else "none",
                    slotCenter = if (infoBar.showChapterName) mapSlot(infoBar.headerCenter) else "none",
                    slotRight = if (infoBar.showTimeBattery) mapSlot(infoBar.headerRight) else "none",
                    chapterTitle = chapterTitle,
                    pageIndex = core.pageFactory.pageIndex,
                    pageCount = currentLayout.pages.size,
                    currentPage = null,
                    chapterIndex = core.state.currentChapterIndex,
                    chaptersSize = infoBar.chaptersSize,
                    batteryLevel = batteryStatus.level,
                    batteryCharging = batteryStatus.charging,
                    currentTime = currentTime,
                    textColor = infoBar.textColor,
                    scrollPercentOverride = scrollPercent,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .height(64.dp + topInsetDp)
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
                        .padding(top = topInsetDp, start = infoBar.paddingHorizontal.dp,
                            end = infoBar.paddingHorizontal.dp, bottom = 8.dp),
                )
                ReaderInfoBar(
                    slotLeft = if (infoBar.showChapterName) mapSlot(infoBar.footerLeft) else "none",
                    slotCenter = if (infoBar.showTimeBattery) mapSlot(infoBar.footerCenter) else "none",
                    slotRight = if (infoBar.showTimeBattery) mapSlot(infoBar.footerRight) else "none",
                    chapterTitle = chapterTitle,
                    pageIndex = core.pageFactory.pageIndex,
                    pageCount = currentLayout.pages.size,
                    currentPage = null,
                    chapterIndex = core.state.currentChapterIndex,
                    chaptersSize = infoBar.chaptersSize,
                    batteryLevel = batteryStatus.level,
                    batteryCharging = batteryStatus.charging,
                    currentTime = currentTime,
                    textColor = infoBar.textColor,
                    scrollPercentOverride = scrollPercent,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(64.dp + bottomInsetDp)
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
                        .padding(top = 8.dp, start = infoBar.paddingHorizontal.dp,
                            end = infoBar.paddingHorizontal.dp, bottom = bottomInsetDp),
                )
            }
        }
    }
}
