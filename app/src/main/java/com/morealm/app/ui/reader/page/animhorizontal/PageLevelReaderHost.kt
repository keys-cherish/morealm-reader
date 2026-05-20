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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.morealm.app.domain.reader.scroll.ScrollChapterContent
import com.morealm.app.domain.render.pageanim.rememberPageLevelCore
import com.morealm.app.domain.render.scroll.ScrollLayoutEngine
import com.morealm.app.ui.reader.page.animation.PageAnimType

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

    // safe area + InfoBar 占位（P2 阶段 InfoBar 暂未接入，effectivePadTop/Bottom 不含 InfoBar 高度）
    val density = androidx.compose.ui.platform.LocalDensity.current
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
    val effectivePadTop = paddingTop + maxOf(statusBarPx, cutoutTopPx)
    val effectivePadBottom = paddingBottom + maxOf(navBarPx, cutoutBottomPx)

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
                // TODO P4: PageAnimType.SLIDE -> SlidePageRenderer(...)
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
        }
    }
}
