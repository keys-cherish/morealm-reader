package com.morealm.app.ui.reader.renderer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.text.TextPaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.morealm.app.domain.entity.looksLikeAutoSplitTitle
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import com.morealm.app.domain.render.ImageCache
import com.morealm.app.domain.render.ImageColumn
import com.morealm.app.domain.render.TextBaseColumn
import com.morealm.app.domain.render.TextColumn
import com.morealm.app.domain.render.TextHtmlColumn
import com.morealm.app.domain.render.TextLine
import com.morealm.app.domain.render.TextPage
import com.morealm.app.domain.render.TextPos
import com.morealm.app.domain.render.canvasrecorder.recordIfNeededThenDraw
import com.morealm.epub.compat.BlockStyle

/** Default highlight colors — fallbacks; prefer passing theme-derived colors at call site */
internal val DEFAULT_SELECTION_COLOR = Color(0x4D2196F3)  // primary @ 30%
internal val DEFAULT_ALOUD_COLOR = Color(0x3300C853)       // green @ 20%
internal val DEFAULT_SEARCH_RESULT_COLOR = Color(0x40FFEB3B) // yellow @ 25%
internal val DEFAULT_BOOKMARK_COLOR = Color(0xFFFF5252)     // error red

/**
 * Per-page representation of a saved user highlight.
 *
 * The renderer doesn't need the full DB row — only the chapter-position
 * range (排版无关定位) + ARGB color, plus the original `id` so a tap can be
 * routed back to delete/share UI without recomputing.
 *
 * Range semantics: `[startChapterPos, endChapterPos)` — inclusive start,
 * exclusive end, matching how `TextLine.chapterPosition + line.charSize`
 * advertises a line's character span. A column is highlighted iff
 * `columnStart < endChapterPos && columnEnd > startChapterPos`.
 */
data class HighlightSpan(
    val id: String,
    val startChapterPos: Int,
    val endChapterPos: Int,
    val colorArgb: Int,
    /**
     * 下划线线型 —— 仅当 span 来自 [com.morealm.app.domain.entity.Highlight.KIND_UNDERLINE]
     * 时被消费。背景 / 字体色两条渲染路径不读这字段，保留默认 0 即可。值对应
     * `Highlight.UNDERLINE_STYLE_SOLID / DASHED / DOTTED / WAVY`。
     */
    val underlineStyle: Int = 0,
)

/** Bookmark triangle size (px) drawn at top-right corner */
private const val BOOKMARK_TRIANGLE_SIZE = 40f

/** Reusable Rect/RectF for background image drawing — avoids allocation per frame. */
private val sharedSrcRect by lazy { Rect() }
private val sharedDstRectF by lazy { RectF() }
private val sharedInfoBgPaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG) }
private val sharedInfoTextPaint by lazy {
    TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
    }
}

data class PageInfoOverlaySpec(
    val chapterTitle: String,
    val pageCount: Int,
    val chapterIndex: Int,
    val chaptersSize: Int,
    val batteryLevel: Int,
    /**
     * 充电状态。CanvasRenderer 把它透传给 BatteryIcon，画一道闪电小象形。
     * 默认 false 保留旧调用方零参数 / 旧 spec 的兼容性（PageContentDrawer 自己
     * 不画电池图标，电池绘制在 Compose 层 BatteryIcon 完成；本字段在 spec 里
     * 仅作为状态承载，不影响 Canvas 路径）。
     */
    val batteryCharging: Boolean = false,
    val currentTime: String,
    val textColorArgb: Int,
    val backgroundColorArgb: Int,
    val paddingHorizontalPx: Float,
    val barHeightPx: Float,
    val verticalPaddingPx: Float,
    val textSizePx: Float,
    /**
     * status bar 高度（px）。top bar 整体向下偏移这么多，避免章节标题紧贴系统状态栏。
     * status bar 隐藏时由调用方传 0f；显示时传 WindowInsets.statusBars 的高度。
     * 默认 0 兼容老调用方。
     */
    val topInsetPx: Float = 0f,
    val showChapterName: Boolean,
    val showTimeBattery: Boolean,
    val headerLeft: String,
    val headerCenter: String,
    val headerRight: String,
    val footerLeft: String,
    val footerCenter: String,
    val footerRight: String,
    val hasBgImage: Boolean = false,
    /** Controls 浮层显示时关掉 Canvas 层的 info bar，避免双重叠加留白。 */
    val controlsVisible: Boolean = false,
)

/**
 * Single-page Canvas drawing composable.
 * Renders text lines, images, selection highlights, TTS highlights,
 * search highlights, and bookmark indicator.
 *
 * # 现代实践（Phase B P1 #4）
 *
 * 旧签名 14 入参（含 8 个 paint/bg/highlight-color 主题字段），改 LocalReaderRenderTheme
 * 注入后签名瘦身到 9 入参，仅保留**真正动态**的渲染状态：page、selection、aloud、
 * bookmark、readAloud 位置、highlights / textColorSpans。
 *
 * 主题字段 (titlePaint / contentPaint / chapterNumPaint / bgBitmap / selectionColor /
 * aloudColor / searchResultColor / bookmarkColor) 统一从 [LocalReaderRenderTheme.current]
 * 取，由 [com.morealm.app.ui.reader.renderer.CanvasRenderer] 主体注入。
 */
@Composable
fun PageCanvas(
    page: TextPage,
    selectionStart: TextPos? = null,
    selectionEnd: TextPos? = null,
    aloudLineIndex: Int = -1,
    hasBookmark: Boolean = false,
    /**
     * TTS 当前段在章节内的字符偏移；仅用作 Compose 重组依赖。
     * 不传时（-1）按非朗读路径走 canvasRecorder 缓存；变化时重组+重算 hasOverlay，
     * 让 page.lines.isReadAloud 的改动能触发画面刷新。
     */
    readAloudChapterPosition: Int = -1,
    /**
     * 用户保存的高亮（若有）— 渲染时画在文字底下、选区/朗读高亮上面。
     * 空列表时走 canvasRecorder 缓存；非空时强制走 hasOverlay 直绘路径，
     * 因为高亮内容会随用户增删而变，缓存版本不再准确。
     */
    highlights: List<HighlightSpan> = emptyList(),
    /**
     * 字体强调色高亮（[com.morealm.app.domain.entity.Highlight.kind] = 1）。
     * 与 [highlights] 同结构 [HighlightSpan]，但不画背景，而是在画字符时把
     * 命中范围内 column 的 paint.color 临时替换为该 span 的 colorArgb。
     */
    textColorSpans: List<HighlightSpan> = emptyList(),
    /**
     * 下划线高亮 ([com.morealm.app.domain.entity.Highlight.kind] = 2)。
     * 与 [highlights] / [textColorSpans] 三独立桶；同结构 [HighlightSpan]，多带
     * [HighlightSpan.underlineStyle] 决定线型。同样会强制走 hasOverlay 直绘路径
     * （canvasRecorder 里不缓存动态线型）。
     */
    underlines: List<HighlightSpan> = emptyList(),
    /**
     * 跳转后的整段褪色高亮状态（已由上层做了"同章"过滤）。
     *
     * **关键设计**：alpha 的 `Animatable.value` 读取**只**发生在下面的
     * `Canvas { ... }` DrawScope 内，让 Compose 把它登记为**绘制阶段**依赖。
     * 这样动画期间每帧只触发本 Canvas 的重画（Phase 3），不触发本 Composable
     * 树的重组（Phase 1）/ 重布局（Phase 2）。
     *
     * 对照旧时代陷阱：如果在外层（@Composable 函数体）`if (rev.alpha.value > 0)`
     * 这样读，整条调用链会随动画每帧重组——经典"重组风暴"。
     */
    revealHighlight: RevealHighlight? = null,
    modifier: Modifier = Modifier,
) {
    // ── 主题注入：8 字段从 LocalReaderRenderTheme.current 取 ────────────────────
    val theme = LocalReaderRenderTheme.current
    val titlePaint = theme.titlePaint
    val contentPaint = theme.contentPaint
    val chapterNumPaint = theme.chapterNumPaint
    val bgBitmap = theme.bgBitmap
    val selColorArgb = theme.selectionColor.toArgb()
    val aloudColorArgb = theme.aloudColor.toArgb()
    val searchColorArgb = theme.searchResultColor.toArgb()
    val bmColorArgb = theme.bookmarkColor.toArgb()

    // 主题 / 字号 / 背景图变化 → 让 canvasRecorder 重录。
    //
    // 为什么放在这里：CanvasRenderer 切日/夜间或换字号时 paint 引用会换新（contentPaint
    // 的 remember key 带 textArgb、titlePaint 带 chapterTitleColor），但没人调
    // [page.canvasRecorder.invalidate]。下一帧 needRecord() 返回 false → 回放的是旧
    // paint 颜色 / 旧 bgBitmap → SLIDE / COVER 模式出现"字色与主题对不上"（白天用上次
    // 夜间的浅色、夜间用上次白天的深色）、"背景图切换不生效"等视觉 bug。SCROLL / 仿真
    // 各自有独立 bitmap 流，不走这个缓存；但 SLIDE / COVER / NONE 都走 PageCanvas。
    //
    // 用 remember 而不是 LaunchedEffect：同步 invalidate 在本次 Canvas {} draw 之前
    // 生效（needRecord() 立即返回 true），避免异步 side-effect 漏掉一帧造成的闪烁。
    // key 用 paint 引用 + bgBitmap 引用 —— 同 theme 下引用复用，remember 保留上次
    // 结果，零额外开销。
    remember(titlePaint, contentPaint, chapterNumPaint, bgBitmap) {
        page.canvasRecorder.invalidate()
    }

    // 必须把 page.lines.any { it.isReadAloud } 也算进来：
    // CanvasRenderer 走的是 LaunchedEffect 直接修改 page.lines 的 isReadAloud 字段，
    // aloudLineIndex 这个 API 入参一直是 -1（默认值），原判断 selectionStart != null
    // || aloudLineIndex >= 0 会让 hasOverlay 永远 false → 走 canvasRecorder 缓存了
    // 第一帧（无高亮），后续 isReadAloud 变化全部被吃掉。再附 readAloudChapterPosition
    // 作为重组依赖，确保 host 推新位置时这里能重新评估 hasOverlay。
    @Suppress("UNUSED_VARIABLE") // 仅作为 Compose snapshot 读取触发
    val readAloudKey = readAloudChapterPosition
    // highlights.size 也作为重组 trigger — 用户增删高亮时强制重算 hasOverlay。
    @Suppress("UNUSED_VARIABLE")
    val highlightsKey = highlights.size
    @Suppress("UNUSED_VARIABLE")
    val textColorSpansKey = textColorSpans.size
    @Suppress("UNUSED_VARIABLE")
    val underlinesKey = underlines.size
    // 字体色 span 也算 overlay：和背景高亮一样，画字符时实时染色无法走 canvasRecorder
    // 缓存（缓存里色已经定死）；用户增删 textColor 高亮时也要走直绘路径。
    //
    // reveal 也算 overlay：alpha 动画期间显然不能复用静态缓存。**注意**：这里只是
    // Composition 层的"是否启用 overlay 路径"决策（presence 触发，非 per-frame），
    // alpha.value 的真正读取仍在下面 Canvas DrawScope 内。
    val hasOverlay = selectionStart != null || aloudLineIndex >= 0 ||
        page.lines.any { it.isReadAloud } || highlights.isNotEmpty() ||
        textColorSpans.isNotEmpty() || underlines.isNotEmpty() || revealHighlight != null
    Canvas(modifier = modifier.fillMaxSize()) {
        val canvas = drawContext.canvas.nativeCanvas
        val w = size.width.toInt()
        val h = size.height.toInt()
        if (w <= 0 || h <= 0) return@Canvas

        if (hasOverlay) {
            // Selection/TTS active — draw directly, skip recorder cache
            if (bgBitmap != null && !bgBitmap.isRecycled) {
                drawBgBitmap(canvas, bgBitmap, size.width, size.height)
            }
            // ── 绘制阶段（Phase 3）合成 reveal HighlightSpan ──
            //
            // 在 DrawScope 内读 [RevealHighlight.alpha.value] —— Compose 把它登记为
            // 绘制阶段依赖；alpha 动画时仅重画本 Canvas，不重组、不重布局。
            // 与已存高亮共用 [drawPageContent] 的 per-line bg-rect 路径，零额外绘制
            // 代码：合成一条临时 [HighlightSpan]，alpha 已通过 [RevealHighlight.currentArgb]
            // 调制进 colorArgb。
            val effectiveHighlights = revealHighlight?.let { rev ->
                val pageStart = page.chapterPosition
                val pageEnd = pageStart + page.charSize
                val overlapsPage = rev.startChapterPos < pageEnd && rev.endChapterPos > pageStart
                // alpha.value 读取 → 仅注册绘制阶段依赖
                val alphaArgb = if (overlapsPage) rev.currentArgb() else 0
                if ((alphaArgb ushr 24) != 0) {
                    highlights + HighlightSpan(
                        id = REVEAL_HIGHLIGHT_ID,
                        startChapterPos = rev.startChapterPos,
                        endChapterPos = rev.endChapterPos,
                        colorArgb = alphaArgb,
                    )
                } else highlights
            } ?: highlights
            drawPageContent(
                canvas = canvas,
                page = page,
                titlePaint = titlePaint,
                contentPaint = contentPaint,
                chapterNumPaint = chapterNumPaint,
                selectionStart = selectionStart,
                selectionEnd = selectionEnd,
                selColorArgb = selColorArgb,
                aloudLineIndex = aloudLineIndex,
                aloudColorArgb = aloudColorArgb,
                searchColorArgb = searchColorArgb,
                hasBookmark = hasBookmark,
                bmColorArgb = bmColorArgb,
                canvasWidth = size.width,
                highlights = effectiveHighlights,
                textColorSpans = textColorSpans,
                underlines = underlines,
            )
        } else {
            // No overlay — use CanvasRecorder: record once, replay on subsequent frames
            page.canvasRecorder.recordIfNeededThenDraw(canvas, w, h) { recCanvas ->
                if (bgBitmap != null && !bgBitmap.isRecycled) {
                    drawBgBitmap(recCanvas, bgBitmap, size.width, size.height)
                }
                drawPageContent(
                    canvas = recCanvas,
                    page = page,
                    titlePaint = titlePaint,
                    contentPaint = contentPaint,
                    chapterNumPaint = chapterNumPaint,
                    searchColorArgb = searchColorArgb,
                    hasBookmark = hasBookmark,
                    bmColorArgb = bmColorArgb,
                    canvasWidth = size.width,
                )
            }
        }
    }
}

/**
 * **P3-5b Phase 3c**：把 CSS box 装饰（圆角背景 / 边框）画到 line content 的包围盒上。
 *
 * 几何：以 line 的 columns 最左 start / 最右 end 算水平范围，[lineTop]/[lineBottom]
 * 是行的垂直范围。padding 向外扩展矩形；border 占据矩形边缘（向外）。
 *
 * border-style 仅实现关键的 SOLID / DOUBLE：DOUBLE 用「双同心矩形」标准画法（外圈 +
 * 内圈，各画一条 strokeWidth/3 的线，间隙 strokeWidth/3）。DASHED / DOTTED 暂回落 SOLID
 * （视觉差异不大；Phase 3.5+ 用 PathEffect 区分）。
 *
 * EMPTY 时函数早 return 零开销。
 */
private fun drawBlockStyleDecoration(
    canvas: android.graphics.Canvas,
    line: TextLine,
    lineTop: Float,
    lineBottom: Float,
) {
    val bs = line.blockStyle
    if (bs === BlockStyle.EMPTY) return
    if (line.columns.isEmpty()) return

    // 计算内容水平范围：所有 column 的最左 start ~ 最右 end
    var leftX = Float.MAX_VALUE
    var rightX = 0f
    for (col in line.columns) {
        if (col.start < leftX) leftX = col.start
        if (col.end > rightX) rightX = col.end
    }
    if (leftX >= rightX) return

    // 向外扩 padding + 半个 border（让 border 落在矩形边缘）
    val halfBorder = bs.borderWidthPx / 2f
    val rectLeft = leftX - bs.paddingLeftPx - halfBorder
    val rectTop = lineTop - bs.paddingTopPx - halfBorder
    val rectRight = rightX + bs.paddingRightPx + halfBorder
    val rectBottom = lineBottom + bs.paddingBottomPx + halfBorder
    val r = bs.borderRadiusPx
    val paint = sharedBlockStylePaint

    // 1. 背景填充
    bs.backgroundColor?.let { bgArgb ->
        paint.style = Paint.Style.FILL
        paint.color = bgArgb
        paint.strokeWidth = 0f
        paint.pathEffect = null
        canvas.drawRoundRect(rectLeft, rectTop, rectRight, rectBottom, r, r, paint)
    }

    // 2. 边框描边
    val bc = bs.borderColor
    if (bc != null && bs.borderWidthPx > 0f) {
        paint.style = Paint.Style.STROKE
        paint.color = bc
        when (bs.borderStyle) {
            BlockStyle.BorderStyle.DOUBLE -> {
                // CSS double 标准：3 等分 strokeWidth，外圈 + 内圈各画 1/3 实线，中间 1/3 间隙
                val third = bs.borderWidthPx / 3f
                paint.strokeWidth = third
                paint.pathEffect = null
                // 外圈
                canvas.drawRoundRect(rectLeft, rectTop, rectRight, rectBottom, r, r, paint)
                // 内圈：向内缩 2*third 让中间留出 1/3 strokeWidth 的间隙
                val innerOff = 2f * third
                val innerR = (r - innerOff).coerceAtLeast(0f)
                canvas.drawRoundRect(
                    rectLeft + innerOff, rectTop + innerOff,
                    rectRight - innerOff, rectBottom - innerOff,
                    innerR, innerR, paint,
                )
            }
            BlockStyle.BorderStyle.SOLID,
            BlockStyle.BorderStyle.DASHED,    // Phase 3 简化为 SOLID
            BlockStyle.BorderStyle.DOTTED,    // Phase 3 简化为 SOLID
            -> {
                paint.strokeWidth = bs.borderWidthPx
                paint.pathEffect = null
                canvas.drawRoundRect(rectLeft, rectTop, rectRight, rectBottom, r, r, paint)
            }
        }
    }
}

/**
 * 将页面内容绘制到任意 Android Canvas 上。
 * 可用于 Compose Canvas 内部，也可用于离屏 Bitmap 渲染（仿真翻页需要）。
 */
/** Reusable paint objects — avoids allocation on every frame (60fps = 60 Paint/s otherwise). */
private val sharedHighlightPaint by lazy {
    Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }
}
private val sharedSpacingPaint by lazy { TextPaint() }
private val sharedBookmarkPath by lazy { android.graphics.Path() }

/**
 * **P3-5b Phase 3c**：CSS box 装饰用 paint —— `background-color` 填充用 FILL，
 * `border` 描边用 STROKE。两者共用一个 Paint，绘制前重设 style + color。
 */
private val sharedBlockStylePaint by lazy {
    Paint().apply {
        isAntiAlias = true
    }
}

/**
 * 下划线专用 paint —— STROKE 模式 + ANTI_ALIAS。strokeWidth / color / pathEffect 在每条
 * 下划线绘制前重新设置（共享单例避免 60fps 下每帧 new Paint）。
 *
 * 注意：调用方在用完后必须把 pathEffect 重置为 null，避免被同帧后续的 stroke 调用
 * 意外继承——目前没有其他 stroke 路径走这个 paint，但保险。
 */
private val sharedUnderlinePaint by lazy {
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
}

/**
 * 按下划线 style 选 PathEffect。
 *
 * 复用同名 stroke-width 缩放尺度：虚线/点划线/波浪的视觉密度跟字号联动，
 * 避免大字号下虚线段太短像实线、小字号下太长间隙明显。
 *
 * @param style 0=直线 / 1=虚线 / 2=点划线 / 3=波浪线
 * @param strokeWidth 当前下划线的笔宽，用作 PathEffect 参数的基数
 */
private fun underlinePathEffect(style: Int, strokeWidth: Float): android.graphics.PathEffect? {
    val base = strokeWidth.coerceAtLeast(1.5f)
    return when (style) {
        com.morealm.app.domain.entity.Highlight.UNDERLINE_STYLE_DASHED ->
            android.graphics.DashPathEffect(floatArrayOf(base * 6f, base * 3f), 0f)
        com.morealm.app.domain.entity.Highlight.UNDERLINE_STYLE_DOTTED ->
            android.graphics.DashPathEffect(floatArrayOf(base * 1.2f, base * 2.5f), 0f)
        com.morealm.app.domain.entity.Highlight.UNDERLINE_STYLE_WAVY ->
            android.graphics.DiscretePathEffect(base * 2f, base * 0.9f)
        else -> null  // SOLID
    }
}

fun drawPageContent(
    canvas: android.graphics.Canvas,
    page: TextPage,
    titlePaint: TextPaint,
    contentPaint: TextPaint,
    chapterNumPaint: TextPaint? = null,
    selectionStart: TextPos? = null,
    selectionEnd: TextPos? = null,
    selColorArgb: Int = DEFAULT_SELECTION_COLOR.toArgb(),
    aloudLineIndex: Int = -1,
    aloudColorArgb: Int = DEFAULT_ALOUD_COLOR.toArgb(),
    searchColorArgb: Int = DEFAULT_SEARCH_RESULT_COLOR.toArgb(),
    hasBookmark: Boolean = false,
    bmColorArgb: Int = DEFAULT_BOOKMARK_COLOR.toArgb(),
    canvasWidth: Float = 0f,
    /**
     * 用户保存的高亮 — 在 search-result 之后、selection-highlight 之前画底色矩形。
     * 顺序选择理由：search 是临时的 navigation 视觉，应该最底；user 高亮是持久数据
     * 视觉，盖在 search 之上但被实时 selection 盖住，避免选择时看不到当前选区边界。
     * TTS 朗读高亮再画在最上面，朗读期间用户能清楚看到当前段。
     */
    highlights: List<HighlightSpan> = emptyList(),
    /**
     * 字体强调色高亮（kind=1）。在画字符时按 chapterPosition 命中替换 paint.color。
     * 与 [highlights] 互不影响，可同时存在（同一段文字可同时是背景高亮 + 字色强调）。
     */
    textColorSpans: List<HighlightSpan> = emptyList(),
    /**
     * 下划线高亮（kind=2）。在文字基线下方画线，按 [HighlightSpan.underlineStyle]
     * 切换线型（0 直线 / 1 虚线 / 2 点划线 / 3 波浪线）。与 [highlights] / [textColorSpans]
     * 完全独立 —— 同一段文字可以同时是「背景 + 字色 + 下划线」三层叠加。
     */
    underlines: List<HighlightSpan> = emptyList(),
) {
    val highlightPaint = sharedHighlightPaint
    val spacingPaint = sharedSpacingPaint
    val paddingTop = page.paddingTop

    for ((lineIndex, line) in page.lines.withIndex()) {
        val paint = when {
            line.isChapterNum && chapterNumPaint != null -> chapterNumPaint
            line.isTitle -> titlePaint
            else -> contentPaint
        }
        val lineTop = line.lineTop + paddingTop
        val lineBottom = line.lineBottom + paddingTop

        // 0. **P3-5b Phase 3c**：CSS box 装饰（圆角背景 / 边框）。
        // line.blockStyle 由 ChapterProvider 从 paragraph 透传；EMPTY 时跳过。
        // 多行 paragraph 的情况下每条 line 独立画自己的盒子 —— Phase 3 视觉简化，
        // Phase 3.5+ 可改按整段统一画一个大盒。
        // 必须最先画（在文字、高亮、下划线之前），否则会覆盖到上面。
        drawBlockStyleDecoration(canvas, line, lineTop, lineBottom)

        // 1. Search result highlights
        for (col in line.columns) {
            if (col is TextBaseColumn && col.isSearchResult) {
                highlightPaint.color = searchColorArgb
                canvas.drawRect(col.start, lineTop, col.end, lineBottom, highlightPaint)
            }
        }

        // 1b. User-saved highlights (Highlight entity).
        // For each highlight overlapping this line's chapter-position range,
        // walk the columns in order to find the leftmost column that starts
        // before highlightEnd and rightmost column that ends after
        // highlightStart, then draw one rect from leftmost.start to
        // rightmost.end. Multi-line highlights paint per-line — a single
        // highlight produces one rect per line it touches.
        if (highlights.isNotEmpty()) {
            val lineStart = line.chapterPosition
            val lineCharCount = line.charSize
            // line.chapterPosition advances by `+1` past paragraph end (see
            // ChapterProvider.kt:538 — accounts for the implicit '\n' of a
            // paragraph break). For overlap detection we use the visible
            // character span only.
            val lineEnd = lineStart + lineCharCount
            for (h in highlights) {
                if (h.startChapterPos >= lineEnd || h.endChapterPos <= lineStart) continue
                // Overlap exists. Walk columns to find x range.
                var charPos = lineStart
                var leftX: Float? = null
                var rightX: Float? = null
                for (col in line.columns) {
                    if (col is TextBaseColumn) {
                        val colStart = charPos
                        val colEnd = charPos + col.charData.length
                        if (colEnd > h.startChapterPos && colStart < h.endChapterPos) {
                            if (leftX == null) leftX = col.start
                            rightX = col.end
                        }
                        charPos = colEnd
                    }
                }
                if (leftX != null && rightX != null) {
                    highlightPaint.color = h.colorArgb
                    canvas.drawRect(leftX, lineTop, rightX, lineBottom, highlightPaint)
                }
            }
        }

        // 1c. Underline highlights (kind=2)
        //
        // 与 1b 用同一套 column-overlap 算法找到 leftX/rightX，但画的不是 FILL 矩形
        // 而是 STROKE 直线，Y 落在 line.lineBottom 附近（文字基线略下）。线型由
        // [HighlightSpan.underlineStyle] 控制 PathEffect：
        //   - 0 SOLID  → null（实线）
        //   - 1 DASHED → DashPathEffect 长划
        //   - 2 DOTTED → DashPathEffect 点划
        //   - 3 WAVY   → DiscretePathEffect 近似波浪（segment=4 deviation=2，
        //                视觉上像手绘下划线，比真正 quadTo() 波形画起来开销小一半）
        //
        // strokeWidth 跟字号成正比（0.07×textSize），既避免大字号时显得纤弱，又避免
        // 小字号时压住文字下沿。
        if (underlines.isNotEmpty()) {
            val lineStart = line.chapterPosition
            val lineCharCount = line.charSize
            val lineEnd = lineStart + lineCharCount
            // 下划线 Y：从 lineTop 下移整个字符高度 (-ascent + descent)，刚好到字底沿。
            // 不能用 lineBottom — 它含 lineSpacingExtra，会把线推到行距底部，远离文字。
            val fm = paint.fontMetrics
            val strokeWidth = (paint.textSize * 0.1f).coerceAtLeast(2.5f)
            val underlineY = lineTop + (-fm.ascent + fm.descent) + strokeWidth * 0.5f
            for (u in underlines) {
                if (u.startChapterPos >= lineEnd || u.endChapterPos <= lineStart) continue
                var charPos = lineStart
                var leftX: Float? = null
                var rightX: Float? = null
                for (col in line.columns) {
                    if (col is TextBaseColumn) {
                        val colStart = charPos
                        val colEnd = charPos + col.charData.length
                        if (colEnd > u.startChapterPos && colStart < u.endChapterPos) {
                            if (leftX == null) leftX = col.start
                            rightX = col.end
                        }
                        charPos = colEnd
                    }
                }
                if (leftX != null && rightX != null) {
                    val p = sharedUnderlinePaint
                    p.color = u.colorArgb
                    p.strokeWidth = strokeWidth
                    p.pathEffect = underlinePathEffect(u.underlineStyle, strokeWidth)
                    canvas.drawLine(leftX, underlineY, rightX, underlineY, p)
                }
            }
            // 重置共享 paint 的 pathEffect，避免后续 Paint 使用者误继承（drawLine 后
            // 紧接着 drawText 不会用 sharedUnderlinePaint，但保险起见显式 null）。
            sharedUnderlinePaint.pathEffect = null
        }

        // 2. Selection highlights
        if (selectionStart != null && selectionEnd != null) {
            val startLine = selectionStart.lineIndex
            val endLine = selectionEnd.lineIndex
            if (lineIndex in startLine..endLine) {
                val colStart = if (lineIndex == startLine) selectionStart.columnIndex else 0
                val colEnd = if (lineIndex == endLine) selectionEnd.columnIndex else line.columns.lastIndex
                if (colStart <= colEnd && colStart < line.columns.size) {
                    val left = line.columns[colStart.coerceIn(0, line.columns.lastIndex)].start
                    val right = line.columns[colEnd.coerceIn(0, line.columns.lastIndex)].end
                    highlightPaint.color = selColorArgb
                    canvas.drawRect(left, lineTop, right, lineBottom, highlightPaint)
                }
            }
        }

        // 3. TTS read-aloud highlight
        if (line.isReadAloud || lineIndex == aloudLineIndex) {
            val left = line.columns.firstOrNull()?.start ?: 0f
            val right = line.columns.lastOrNull()?.end ?: 0f
            highlightPaint.color = aloudColorArgb
            canvas.drawRect(left, lineTop, right, lineBottom, highlightPaint)
        }

        // 4. Decorative accent bar after title end (4.htm style)
        if (line.isTitleEnd && chapterNumPaint != null) {
            val densityScale = (contentPaint.textSize / 18f).coerceIn(1f, 3f)
            val barWidth = 32f * densityScale
            val barHeight = 2f
            val barY = lineBottom + contentPaint.textSize * 0.35f
            val barX = line.columns.firstOrNull()?.start ?: 0f
            highlightPaint.color = chapterNumPaint.color
            canvas.drawRect(barX, barY, barX + barWidth, barY + barHeight, highlightPaint)
        }

        // 5. Draw text / images
        if (line.isImage) {
            for (col in line.columns) {
                if (col is ImageColumn) {
                    drawImageColumn(canvas, col, line)
                }
            }
        } else {
            // Reuse a single TextPaint for lines with extra letter spacing
            val drawPaint = if (line.extraLetterSpacing != 0f) {
                spacingPaint.set(paint)
                spacingPaint.letterSpacing = paint.letterSpacing + line.extraLetterSpacing
                spacingPaint
            } else paint

            val lineBase = line.lineBase + paddingTop
            // 字体色覆盖：跟随 column 推进 charPos，对每个 column 用 mid-char 位置
            // 命中 textColorSpans。命中则临时换 paint.color，画完恢复。
            // 命中检测用 mid-char 而不是 col-start，避免边界 column 被错误染色
            // （比如 span [10,15)，column 第 10 个字往前的 column 不该染）。
            //
            // 警告：drawPaint 可能是共享 paint（contentPaint/titlePaint），不能
            // 永久写入；我们只在画该 column 时改色，画完恢复。
            var charPos = line.chapterPosition
            for (col in line.columns) {
                if (col is TextBaseColumn) {
                    if (col is TextHtmlColumn) {
                        spacingPaint.set(drawPaint)
                        spacingPaint.textSize = col.textSize
                        col.textColor?.let { spacingPaint.color = it }
                    }
                    val actualPaint = if (col is TextHtmlColumn) spacingPaint else drawPaint
                    // 字色 override —— 用 charData.length 算 mid 位置；
                    // span 半开区间 [start, end) 命中即覆盖。
                    val charLen = col.charData.length.coerceAtLeast(1)
                    val midPos = charPos + charLen / 2
                    val overrideArgb = if (textColorSpans.isNotEmpty()) {
                        textColorSpans.firstOrNull {
                            midPos >= it.startChapterPos && midPos < it.endChapterPos
                        }?.colorArgb
                    } else null
                    val originalColor = actualPaint.color
                    if (overrideArgb != null) actualPaint.color = overrideArgb
                    canvas.drawText(
                        col.charData,
                        col.start + line.extraLetterSpacingOffsetX,
                        lineBase,
                        actualPaint,
                    )
                    if (overrideArgb != null) actualPaint.color = originalColor
                    charPos += charLen
                }
            }
        }
    }

    // 5. Bookmark indicator
    if (hasBookmark) {
        highlightPaint.color = bmColorArgb
        sharedBookmarkPath.apply {
            rewind()
            moveTo(canvasWidth - BOOKMARK_TRIANGLE_SIZE, 0f)
            lineTo(canvasWidth, 0f)
            lineTo(canvasWidth, BOOKMARK_TRIANGLE_SIZE)
            close()
        }
        canvas.drawPath(sharedBookmarkPath, highlightPaint)
    }
}

/**
 * 竖排（VERTICAL_RL）页面绘制——layout 阶段已经把每列的几何放进
 * [TextLine.columnLeftX] / [TextLine.columnRightX]（X 范围）和每个 [TextColumn]
 * 的 `start`/`end`（在该列内的 Y 范围）。本函数只负责"按 layout 已经决定的位置画字符"。
 *
 * # 字形选用：OpenType `vert` 特性优先，旋转矩阵作 fallback
 *
 * 现代中文/日文字体内部都内置两套标点字形（`「」` 横排版 vs `﹁﹂` 竖排版、
 * 句号和顿号在角上 vs 居中等）。通过给 paint 设置 `fontFeatureSettings = "'vert' on, 'vrt2' on"`，
 * Android 的 HarfBuzz text shaper 会在 GSUB 阶段自动把横排字形替换成竖排字形——
 * 没有额外的 Canvas 仿射变换开销，基线对齐由字体自己保证，视觉效果远优于
 * 用 `canvas.rotate(90)` 物理旋转横排字形。
 *
 * 物理旋转仅作为 fallback：layout 阶段如果显式标记了 [TextColumn.rotate90]
 * （连续 ≥3 个 ASCII 数字字母 tate-chu-yoko 长串、或老旧字体没有 vert 特性的
 * 全角破折号），drawer 会 save/translate/rotate(90)/restore 把那个字符侧着画。
 * Phase 2 的 `layoutInternalVertical` 暂未填充 rotate90（Task #5 接入）。
 *
 * # 不支持的视觉路径（Phase 2 限制）
 *
 * 横排 [drawPageContent] 内的 search-result / selection / user-highlight / TTS-aloud
 * highlight / 章号末行装饰条 / 字色 override / 图片栏，竖排都**不**复用——它们都按
 * 横排几何（line.lineTop/Bottom + col.start/end 都是 X）写，强行复用会画错位置。
 * 这些视觉在 Phase 3 / Task #5 引入竖排专用绘制路径。
 */
internal fun drawPageContentVertical(
    canvas: android.graphics.Canvas,
    page: TextPage,
    titlePaint: TextPaint,
    contentPaint: TextPaint,
    chapterNumPaint: TextPaint? = null,
    hasBookmark: Boolean = false,
    bmColorArgb: Int = DEFAULT_BOOKMARK_COLOR.toArgb(),
    @Suppress("UNUSED_PARAMETER") canvasWidth: Float = 0f,
) {
    val highlightPaint = sharedHighlightPaint
    val paddingTop = page.paddingTop

    // 共享 paint 实例 —— 给三个 paint 都临时打开 vert 特性，画完恢复，避免影响
    // 其他横排路径或后续帧。
    val originalContentFeatures = contentPaint.fontFeatureSettings
    val originalTitleFeatures = titlePaint.fontFeatureSettings
    val originalChapterNumFeatures = chapterNumPaint?.fontFeatureSettings
    contentPaint.fontFeatureSettings = "'vert' on, 'vrt2' on"
    titlePaint.fontFeatureSettings = "'vert' on, 'vrt2' on"
    chapterNumPaint?.let { it.fontFeatureSettings = "'vert' on, 'vrt2' on" }

    try {
        for (line in page.lines) {
            // line 在竖排里就是「一列」。columnLeftX/RightX 给出 X 范围，
            // col.start/end 给出 Y 范围。
            val paint = when {
                line.isChapterNum && chapterNumPaint != null -> chapterNumPaint
                line.isTitle -> titlePaint
                else -> contentPaint
            }
            val centerX = (line.columnLeftX + line.columnRightX) / 2f
            for (col in line.columns) {
                if (col is TextBaseColumn) {
                    // 字符画在列中心 X。Y 用 col.start + paint.textSize（baseline 偏移近似）。
                    // paint.textSize 对应 ascent 区域，把字符 baseline 落在 col.start + textSize
                    // 大致让字符上半在 col.start 之上、下半在之下，符合视觉重心。
                    val charWidth = paint.measureText(col.charData)
                    val charX = centerX - charWidth / 2f
                    val charY = paddingTop + col.start + paint.textSize
                    if (col is TextColumn && col.rotate90) {
                        // Fallback：layout 显式要求物理旋转（tate-chu-yoko 长串等）。
                        canvas.save()
                        canvas.translate(centerX, charY - paint.textSize / 2f)
                        canvas.rotate(90f)
                        canvas.drawText(col.charData, -charWidth / 2f, paint.textSize / 2f, paint)
                        canvas.restore()
                    } else {
                        canvas.drawText(col.charData, charX, charY, paint)
                    }
                }
            }
        }

        // 书签三角——竖排里阅读起点在右上角，把书签三角放到左上角对应「翻到下一页方向」的视觉锚点。
        // 横排里它在右上（页右上 = 翻页方向起点），竖排里页面左上 = 翻页方向起点（左滑翻页 / 列从右到左）。
        if (hasBookmark) {
            highlightPaint.color = bmColorArgb
            sharedBookmarkPath.apply {
                rewind()
                moveTo(0f, 0f)
                lineTo(BOOKMARK_TRIANGLE_SIZE, 0f)
                lineTo(0f, BOOKMARK_TRIANGLE_SIZE)
                close()
            }
            canvas.drawPath(sharedBookmarkPath, highlightPaint)
        }
    } finally {
        contentPaint.fontFeatureSettings = originalContentFeatures
        titlePaint.fontFeatureSettings = originalTitleFeatures
        chapterNumPaint?.let { it.fontFeatureSettings = originalChapterNumFeatures }
    }
}

fun drawRecordedPageContent(
    canvas: android.graphics.Canvas,
    page: TextPage,
    titlePaint: TextPaint,
    contentPaint: TextPaint,
    chapterNumPaint: TextPaint? = null,
    width: Int,
    height: Int,
    searchColorArgb: Int = DEFAULT_SEARCH_RESULT_COLOR.toArgb(),
    canvasWidth: Float = 0f,
) {
    page.canvasRecorder.recordIfNeededThenDraw(canvas, width, height) { recCanvas ->
        drawPageContent(
            canvas = recCanvas,
            page = page,
            titlePaint = titlePaint,
            contentPaint = contentPaint,
            chapterNumPaint = chapterNumPaint,
            searchColorArgb = searchColorArgb,
            canvasWidth = canvasWidth,
        )
    }
}

fun predecodePageImages(page: TextPage) {
    for (line in page.lines) {
        if (!line.isImage) continue
        for (col in line.columns) {
            if (col is ImageColumn) {
                val targetWidth = (col.end - col.start).toInt().coerceAtLeast(1)
                ImageCache.get(col.src, targetWidth)
            }
        }
    }
}

/**
 * 将一页内容渲染到 Bitmap。仿真翻页的贝塞尔算法需要 Bitmap 作为输入。
 * @param bgColor 背景色 ARGB
 * @param bgBitmap 背景图片（已缩放到屏幕尺寸），绘制在背景色之上、文字之下
 * @param highlights 命中本页的用户高亮（kind=0，背景矩形）。caller 已过滤过页范围。
 * @param textColorSpans 命中本页的字体强调色 spans（kind=1，替换 paint.color）。
 * @param selectionStart 实时选区起点 [TextPos]。null = 无选区。
 * @param selectionEnd 实时选区终点 [TextPos]。null = 无选区。
 * @param selColorArgb 实时选区背景色 ARGB（默认 [DEFAULT_SELECTION_COLOR]）。
 */
fun renderPageToBitmap(
    width: Int,
    height: Int,
    bgColor: Int,
    page: TextPage,
    titlePaint: TextPaint,
    contentPaint: TextPaint,
    chapterNumPaint: TextPaint? = null,
    reuseBitmap: Bitmap? = null,
    bgBitmap: Bitmap? = null,
    pageInfoOverlay: PageInfoOverlaySpec? = null,
    highlights: List<HighlightSpan> = emptyList(),
    textColorSpans: List<HighlightSpan> = emptyList(),
    underlines: List<HighlightSpan> = emptyList(),
    selectionStart: TextPos? = null,
    selectionEnd: TextPos? = null,
    selColorArgb: Int = DEFAULT_SELECTION_COLOR.toArgb(),
): Bitmap {
    val bmp = if (reuseBitmap != null && reuseBitmap.width == width && reuseBitmap.height == height && !reuseBitmap.isRecycled) {
        reuseBitmap.eraseColor(bgColor)
        reuseBitmap
    } else {
        reuseBitmap?.recycle()
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.eraseColor(bgColor)
        }
    }
    val canvas = android.graphics.Canvas(bmp)
    // Draw background image on top of solid color, below text
    if (bgBitmap != null && !bgBitmap.isRecycled) {
        drawBgBitmap(canvas, bgBitmap, width.toFloat(), height.toFloat())
    }
    drawPageContent(
        canvas = canvas,
        page = page,
        titlePaint = titlePaint,
        contentPaint = contentPaint,
        chapterNumPaint = chapterNumPaint,
        selectionStart = selectionStart,
        selectionEnd = selectionEnd,
        selColorArgb = selColorArgb,
        highlights = highlights,
        textColorSpans = textColorSpans,
        underlines = underlines,
        canvasWidth = width.toFloat(),
    )
    pageInfoOverlay?.let { overlay ->
        drawPageInfoOverlay(canvas, width, height, page, overlay)
    }
    return bmp
}

private fun drawPageInfoOverlay(
    canvas: android.graphics.Canvas,
    width: Int,
    height: Int,
    page: TextPage,
    spec: PageInfoOverlaySpec,
) {
    // Controls 浮层显示时关掉 Canvas info bar，避免双重叠加。
    if (spec.controlsVisible) return
    drawInfoBar(
        canvas = canvas,
        width = width,
        top = spec.topInsetPx,
        isTop = true,
        slots = listOf(
            if (spec.showTimeBattery) spec.headerLeft else "none",
            if (spec.showChapterName) spec.headerCenter else "none",
            if (spec.showTimeBattery) spec.headerRight else "none",
        ),
        page = page,
        spec = spec,
    )
    drawInfoBar(
        canvas = canvas,
        width = width,
        top = height - spec.barHeightPx,
        isTop = false,
        slots = listOf(
            if (spec.showChapterName) spec.footerLeft else "none",
            spec.footerCenter,
            spec.footerRight,
        ),
        page = page,
        spec = spec,
    )
}

private fun drawInfoBar(
    canvas: android.graphics.Canvas,
    width: Int,
    top: Float,
    isTop: Boolean,
    slots: List<String>,
    page: TextPage,
    spec: PageInfoOverlaySpec,
) {
    if (slots.all { it == "none" }) return
    val bottom = top + spec.barHeightPx
    if (!spec.hasBgImage) {
        val bgPaint = sharedInfoBgPaint
        val fullBg = spec.backgroundColorArgb
        val clearBg = withAlpha(fullBg, 0)
        bgPaint.shader = if (isTop) {
            LinearGradient(
                0f,
                top,
                0f,
                bottom,
                intArrayOf(fullBg, fullBg, clearBg),
                floatArrayOf(0f, 0.72f, 1f),
                Shader.TileMode.CLAMP,
            )
        } else {
            LinearGradient(
                0f,
                top,
                0f,
                bottom,
                intArrayOf(clearBg, fullBg, fullBg),
                floatArrayOf(0f, 0.28f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, top, width.toFloat(), bottom, bgPaint)
        bgPaint.shader = null
    }

    val textPaint = sharedInfoTextPaint
    textPaint.color = withAlpha(spec.textColorArgb, ((spec.textColorArgb ushr 24) * 0.4f).toInt().coerceIn(0, 255))
    textPaint.textSize = spec.textSizePx
    textPaint.isFakeBoldText = false
    val contentTop = top + spec.verticalPaddingPx
    val contentBottom = bottom - spec.verticalPaddingPx
    val baseline = (contentTop + contentBottom - textPaint.descent() - textPaint.ascent()) / 2f
    val leftText = infoSlotText(slots[0], page, spec)
    val centerText = infoSlotText(slots[1], page, spec)
    val rightText = infoSlotText(slots[2], page, spec)

    leftText?.let {
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(ellipsizeForWidth(it, textPaint, width / 2f), spec.paddingHorizontalPx, baseline, textPaint)
    }
    centerText?.let {
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText(ellipsizeForWidth(it, textPaint, width / 2f), width / 2f, baseline, textPaint)
    }
    rightText?.let {
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(ellipsizeForWidth(it, textPaint, width / 2f), width - spec.paddingHorizontalPx, baseline, textPaint)
    }
}

private fun infoSlotText(slot: String, page: TextPage, spec: PageInfoOverlaySpec): String? {
    if (slot == "none") return null
    val actualPageIndex = page.index
    val actualPageCount = page.pageSize.takeIf { it > 0 } ?: spec.pageCount
    // 自动分节伪标题（"第N节" / "正文"）退回 spec.chapterTitle —— 后者由 ReaderScreen
    // 经 [BookChapter.displayTitle] 已替换为书名。
    val actualChapterTitle = page.title
        .takeIf { it.isNotBlank() && !it.looksLikeAutoSplitTitle() }
        ?: spec.chapterTitle
    val actualChapterIndex = page.chapterIndex
    val readProgress = page.readProgress.takeIf { it.isNotBlank() } ?: run {
        if (actualPageCount > 1) {
            "%.1f%%".format(actualPageIndex.toFloat() / (actualPageCount - 1) * 100)
        } else {
            "100.0%"
        }
    }
    return when (slot) {
        "chapter" -> actualChapterTitle
        "time" -> spec.currentTime
        "battery", "battery_pct" -> "${spec.batteryLevel}%"
        "page" -> "${actualPageIndex + 1}/$actualPageCount"
        "progress" -> readProgress
        "page_progress" -> "${actualPageIndex + 1}/$actualPageCount  $readProgress"
        "book_name" -> "MoRealm"
        "time_battery" -> "${spec.currentTime}  ${spec.batteryLevel}%"
        "battery_time" -> "${spec.batteryLevel}%  ${spec.currentTime}"
        "time_battery_pct" -> "${spec.currentTime}  ${spec.batteryLevel}%"
        "chapter_progress" -> if (spec.chaptersSize > 0) "${actualChapterIndex + 1}/${spec.chaptersSize}" else null
        else -> null
    }
}

private fun ellipsizeForWidth(text: String, paint: TextPaint, maxWidth: Float): String {
    if (paint.measureText(text) <= maxWidth) return text
    val ellipsis = "..."
    val ellipsisWidth = paint.measureText(ellipsis)
    if (ellipsisWidth >= maxWidth) return ellipsis
    var end = text.length
    while (end > 0 && paint.measureText(text, 0, end) + ellipsisWidth > maxWidth) {
        end--
    }
    return text.take(end) + ellipsis
}

private fun withAlpha(color: Int, alpha: Int): Int {
    return (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
}

/**
 * Draw a pre-decoded background bitmap onto the canvas, center-crop to fill.
 * Reuses shared Rect/RectF to avoid per-frame allocation.
 */
internal fun drawBgBitmap(
    canvas: android.graphics.Canvas,
    bgBitmap: Bitmap,
    canvasWidth: Float,
    canvasHeight: Float,
) {
    val bw = bgBitmap.width.toFloat()
    val bh = bgBitmap.height.toFloat()
    val cw = canvasWidth
    val ch = canvasHeight
    // Center-crop: scale to fill, then center
    val scale = maxOf(cw / bw, ch / bh)
    val srcW = (cw / scale).toInt()
    val srcH = (ch / scale).toInt()
    val srcX = ((bw - srcW) / 2f).toInt().coerceAtLeast(0)
    val srcY = ((bh - srcH) / 2f).toInt().coerceAtLeast(0)
    sharedSrcRect.set(srcX, srcY, srcX + srcW, srcY + srcH)
    sharedDstRectF.set(0f, 0f, cw, ch)
    canvas.drawBitmap(bgBitmap, sharedSrcRect, sharedDstRectF, null)
}

internal fun drawImageColumn(
    canvas: android.graphics.Canvas,
    col: ImageColumn,
    line: TextLine,
) {
    val targetWidth = (col.end - col.start).toInt().coerceAtLeast(1)
    val bitmap = ImageCache.get(col.src, targetWidth) ?: return
    // Maintain aspect ratio within the allocated slot (ported from Legado)
    val slotW = col.end - col.start
    val slotH = line.lineBottom - line.lineTop
    val bmpW = bitmap.width.toFloat()
    val bmpH = bitmap.height.toFloat()
    val scale = minOf(slotW / bmpW, slotH / bmpH)
    val drawW = bmpW * scale
    val drawH = bmpH * scale
    val offsetX = col.start + (slotW - drawW) / 2f
    val offsetY = line.lineTop + (slotH - drawH) / 2f
    canvas.drawBitmap(
        bitmap,
        null,
        android.graphics.RectF(offsetX, offsetY, offsetX + drawW, offsetY + drawH),
        null,
    )
}
