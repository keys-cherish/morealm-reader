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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    val showChapterName: Boolean,
    val showTimeBattery: Boolean,
    val headerLeft: String,
    val headerCenter: String,
    val headerRight: String,
    val footerLeft: String,
    val footerCenter: String,
    val footerRight: String,
    val hasBgImage: Boolean = false,
)

/**
 * Single-page Canvas drawing composable.
 * Renders text lines, images, selection highlights, TTS highlights,
 * search result highlights, and bookmark indicators.
 *
 * Drawing order (back to front):
 * 1. Search result highlight rectangles
 * 2. Selection highlight rectangles
 * 3. TTS read-aloud highlight rectangles
 * 4. Text characters
 * 5. Images
 * 6. Bookmark indicator
 */
@Composable
fun PageCanvas(
    page: TextPage,
    titlePaint: TextPaint,
    contentPaint: TextPaint,
    chapterNumPaint: TextPaint? = null,
    bgBitmap: Bitmap? = null,
    selectionStart: TextPos? = null,
    selectionEnd: TextPos? = null,
    selectionColor: Color = DEFAULT_SELECTION_COLOR,
    aloudLineIndex: Int = -1,
    aloudColor: Color = DEFAULT_ALOUD_COLOR,
    searchResultColor: Color = DEFAULT_SEARCH_RESULT_COLOR,
    hasBookmark: Boolean = false,
    bookmarkColor: Color = DEFAULT_BOOKMARK_COLOR,
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
    modifier: Modifier = Modifier,
) {
    val selColorArgb = selectionColor.toArgb()
    val aloudColorArgb = aloudColor.toArgb()
    val searchColorArgb = searchResultColor.toArgb()
    val bmColorArgb = bookmarkColor.toArgb()

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
    // 字体色 span 也算 overlay：和背景高亮一样，画字符时实时染色无法走 canvasRecorder
    // 缓存（缓存里色已经定死）；用户增删 textColor 高亮时也要走直绘路径。
    val hasOverlay = selectionStart != null || aloudLineIndex >= 0 ||
        page.lines.any { it.isReadAloud } || highlights.isNotEmpty() ||
        textColorSpans.isNotEmpty()
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
                highlights = highlights,
                textColorSpans = textColorSpans,
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
                val path = col.src.removePrefix("file://")
                val targetWidth = (col.end - col.start).toInt().coerceAtLeast(1)
                ImageCache.get(path, targetWidth)
            }
        }
    }
}

/**
 * 将一页内容渲染到 Bitmap。仿真翻页的贝塞尔算法需要 Bitmap 作为输入。
 * @param bgColor 背景色 ARGB
 * @param bgBitmap 背景图片（已缩放到屏幕尺寸），绘制在背景色之上、文字之下
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
    drawInfoBar(
        canvas = canvas,
        width = width,
        top = 0f,
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
    val actualChapterTitle = page.title.takeIf { it.isNotBlank() } ?: spec.chapterTitle
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

private fun drawImageColumn(
    canvas: android.graphics.Canvas,
    col: ImageColumn,
    line: TextLine,
) {
    val path = col.src.removePrefix("file://")
    val targetWidth = (col.end - col.start).toInt().coerceAtLeast(1)
    val bitmap = ImageCache.get(path, targetWidth) ?: return
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
