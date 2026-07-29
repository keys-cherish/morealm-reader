package com.morealm.app.ui.reader.renderer.scroll

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.render.ImageCache
import com.morealm.epub.css.EpubBackgroundAttachment
import com.morealm.epub.css.EpubBackgroundGeometry
import com.morealm.epub.css.EpubBackgroundImage
import com.morealm.epub.css.EpubBackgroundLayer
import com.morealm.epub.css.EpubBackgroundOffset
import com.morealm.epub.css.EpubBackgroundSize
import com.morealm.epub.css.Gradient
import com.morealm.epub.css.Length
import com.morealm.epub.render.ScrollPage
import com.morealm.epub.render.ScrollPageSectionRegion
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

private const val EPUB_BACKGROUND_DIAG_TAG = "EpubBgDiag"
private const val EPUB_BACKGROUND_DIAG_LIMIT = 256
private val epubBackgroundDiagKeys = LinkedHashSet<String>()

/** Android Canvas 只负责消费 epub-lib 的背景语义，不在宿主重新解析 CSS。 */
internal fun drawEpubPageBackground(
    canvas: Canvas,
    page: ScrollPage,
    pageWidth: Float,
    pageHeight: Float,
    viewportHeight: Float = pageHeight,
    fontSizePx: Float,
    continuousSectionCoordinates: Boolean = false,
) {
    if (pageWidth <= 0f || pageHeight <= 0f) return
    val regions = if (page.sectionRegions.isNotEmpty()) {
        page.sectionRegions
    } else if (page.background.isVisible) {
        listOf(
            ScrollPageSectionRegion(
                sectionIndex = page.sectionIndex,
                top = 0f,
                bottom = pageHeight,
                sectionOffsetY = 0f,
                sectionHeight = pageHeight,
                background = page.background,
            ),
        )
    } else {
        emptyList()
    }
    val isSingleSectionPage = regions.size == 1
    for (region in regions) {
        if (!region.background.isVisible || region.bottom <= region.top) continue
        drawRegion(
            canvas = canvas,
            region = region,
            width = pageWidth,
            pageHeight = pageHeight,
            viewportHeight = viewportHeight,
            fontSizePx = fontSizePx,
            chapterIndex = page.chapterIndex,
            pageIndex = page.pageIndex,
            continuousSectionCoordinates = continuousSectionCoordinates,
            isSingleSectionPage = isSingleSectionPage,
        )
    }
}

internal data class EpubBackgroundRegionFrame(
    val top: Float,
    val bottom: Float,
    val areaHeight: Float,
    val offsetY: Float,
) {
    fun localY(areaY: Float): Float = top + areaY - offsetY
}

/**
 * 分页阅读器里的 body 背景属于当前页，而不是浏览器式的整章长画布。
 * 单 section 页要使用完整 Canvas 高度，尤其章末页的内容高度通常小于屏幕高度；
 * 多 section 共页时仍按各自 region 隔离，避免相邻 XHTML 的背景互相覆盖。
 */
internal fun resolveEpubBackgroundRegionFrame(
    region: ScrollPageSectionRegion,
    pageHeight: Float,
    continuousSectionCoordinates: Boolean,
    isSingleSectionPage: Boolean,
    viewportHeight: Float = pageHeight,
    hasFixedLayer: Boolean = false,
): EpubBackgroundRegionFrame {
    // `background-attachment: fixed` —— 背景相对阅读视口固定，与 section 的内容高度和
    // 滚动偏移无关。必须先于 continuous 分支判定：卷首整页插画那类「正文只有一个空格、
    // 整页就是一张图」的章节内容高度趋近于零，按内容高度裁剪会把整张图裁没。
    if (hasFixedLayer) {
        return EpubBackgroundRegionFrame(
            top = if (isSingleSectionPage) 0f else region.top,
            bottom = if (isSingleSectionPage) pageHeight else region.bottom,
            areaHeight = viewportHeight,
            offsetY = 0f,
        )
    }
    if (continuousSectionCoordinates) {
        return EpubBackgroundRegionFrame(
            top = region.top,
            bottom = region.bottom,
            areaHeight = region.sectionHeight.coerceAtLeast(region.height),
            offsetY = region.sectionOffsetY,
        )
    }
    val top = if (isSingleSectionPage) 0f else region.top
    val bottom = if (isSingleSectionPage) pageHeight else region.bottom
    return EpubBackgroundRegionFrame(
        top = top,
        bottom = bottom,
        areaHeight = (bottom - top).coerceAtLeast(0f),
        offsetY = 0f,
    )
}

private fun drawRegion(
    canvas: Canvas,
    region: ScrollPageSectionRegion,
    width: Float,
    pageHeight: Float,
    viewportHeight: Float,
    fontSizePx: Float,
    chapterIndex: Int,
    pageIndex: Int,
    continuousSectionCoordinates: Boolean,
    isSingleSectionPage: Boolean,
) {
    val frame = resolveEpubBackgroundRegionFrame(
        region = region,
        pageHeight = pageHeight,
        continuousSectionCoordinates = continuousSectionCoordinates,
        isSingleSectionPage = isSingleSectionPage,
        viewportHeight = viewportHeight,
        hasFixedLayer = region.background.layers.any {
            it.attachment == EpubBackgroundAttachment.FIXED
        },
    )
    val clipTop = frame.top
    val clipBottom = frame.bottom
    if (frame.areaHeight <= 0f || clipBottom <= clipTop) return
    val save = canvas.save()
    canvas.clipRect(0f, clipTop, width, clipBottom)

    region.background.colorArgb?.let { color ->
        canvas.drawRect(0f, clipTop, width, clipBottom, Paint().apply {
            style = Paint.Style.FILL
            this.color = color
        })
    }

    // CSS 第一层位于最上方，Canvas 因此从列表末尾向前画。
    for ((reverseLayerIndex, rawLayer) in region.background.layers.asReversed().withIndex()) {
        val layerIndex = region.background.layers.lastIndex - reverseLayerIndex
        val layer = resolveViewportBackgroundSize(
            layer = rawLayer,
            viewportWidth = width,
            viewportHeight = viewportHeight,
            fontSizePx = fontSizePx,
        )
        when (val image = layer.image) {
            is EpubBackgroundImage.Url -> drawUrlLayer(
                canvas = canvas,
                uri = image.uri,
                region = region,
                frame = frame,
                areaWidth = width,
                areaHeight = frame.areaHeight,
                fontSizePx = fontSizePx,
                layer = layer,
                diagnosticContext = EpubBackgroundDiagnosticContext(
                    chapterIndex = chapterIndex,
                    pageIndex = pageIndex,
                    sectionIndex = region.sectionIndex,
                    layerIndex = layerIndex,
                    pageWidth = width,
                    viewportHeight = viewportHeight,
                    rawLayer = rawLayer,
                ),
            )
            is EpubBackgroundImage.LinearGradient -> drawLinearLayer(
                canvas, image.value, frame, width, frame.areaHeight, fontSizePx, layer,
            )
            is EpubBackgroundImage.RadialGradient -> drawRadialLayer(
                canvas, image.value, frame, width, frame.areaHeight, fontSizePx, layer,
            )
            is EpubBackgroundImage.CssFunction -> Unit
        }
    }
    canvas.restoreToCount(save)
}

/**
 * EPUB body 背景的百分比尺寸以阅读视口为基准，而不是整章滚动高度。
 *
 * 例如 `background-size:auto 40%` 在 6000px 长章中仍应取 2048px 屏幕的 40%，
 * 否则同一张人物图会被放大到数千像素，头部和身体分散到页面上下两端。
 */
internal fun resolveViewportBackgroundSize(
    layer: EpubBackgroundLayer,
    viewportWidth: Float,
    viewportHeight: Float,
    fontSizePx: Float,
): EpubBackgroundLayer {
    val explicit = layer.size as? EpubBackgroundSize.Explicit ?: return layer
    if (viewportWidth <= 0f || viewportHeight <= 0f) return layer

    fun resolve(offset: EpubBackgroundOffset?, percentBase: Float): EpubBackgroundOffset? {
        offset ?: return null
        val px = percentBase * offset.percent / 100f +
            (offset.length?.toPx(fontSizePx, viewportWidth, viewportHeight) ?: 0f)
        return EpubBackgroundOffset(length = Length(px, Length.Unit.Px))
    }

    return layer.copy(
        size = EpubBackgroundSize.Explicit(
            width = resolve(explicit.width, viewportWidth),
            height = resolve(explicit.height, viewportHeight),
        ),
    )
}

private fun drawUrlLayer(
    canvas: Canvas,
    uri: String,
    region: ScrollPageSectionRegion,
    frame: EpubBackgroundRegionFrame,
    areaWidth: Float,
    areaHeight: Float,
    fontSizePx: Float,
    layer: com.morealm.epub.css.EpubBackgroundLayer,
    diagnosticContext: EpubBackgroundDiagnosticContext,
) {
    val eventPrefix = diagnosticContext.eventPrefix(region)
    val bounds = ImageCache.getBounds(uri)
    if (bounds == null) {
        logEpubBackgroundDiagnostic(
            key = "$eventPrefix|bounds-miss|$uri",
            message = "$eventPrefix result=bounds-miss uri='$uri' raw=${diagnosticContext.rawLayer}",
        )
        return
    }
    val plan = EpubBackgroundGeometry.plan(
        layer = layer,
        areaWidth = areaWidth,
        areaHeight = areaHeight,
        intrinsicWidth = bounds.first.toFloat(),
        intrinsicHeight = bounds.second.toFloat(),
        fontSizePx = fontSizePx,
    )
    if (plan == null) {
        logEpubBackgroundDiagnostic(
            key = "$eventPrefix|plan-null|$uri",
            message = "$eventPrefix result=plan-null uri='$uri' bounds=${bounds.first}x${bounds.second} " +
                "resolved=$layer raw=${diagnosticContext.rawLayer}",
        )
        return
    }

    val firstLocalLeft = plan.originX + plan.firstColumn * plan.stepX
    val firstSectionTop = plan.originY + plan.firstRow * plan.stepY
    val firstLocalTop = frame.localY(firstSectionTop)
    val lastLocalLeft = plan.originX + plan.lastColumn * plan.stepX
    val lastSectionTop = plan.originY + plan.lastRow * plan.stepY
    val lastLocalTop = frame.localY(lastSectionTop)
    logEpubBackgroundDiagnostic(
        key = "$eventPrefix|plan|$uri|$layer",
        message = "$eventPrefix result=plan uri='$uri' bounds=${bounds.first}x${bounds.second} " +
            "rawSize=${diagnosticContext.rawLayer.size} resolvedSize=${layer.size} " +
            "position=${layer.position} repeat=${layer.repeat} " +
            "tile=${plan.tileWidth}x${plan.tileHeight} origin=${plan.originX},${plan.originY} " +
            "grid=${plan.firstColumn}..${plan.lastColumn},${plan.firstRow}..${plan.lastRow} " +
            "canvasFirst=$firstLocalLeft,$firstLocalTop canvasLast=$lastLocalLeft,$lastLocalTop",
    )

    val bitmap = ImageCache.get(uri, ceil(plan.tileWidth).toInt().coerceAtLeast(1))
    if (bitmap == null) {
        logEpubBackgroundDiagnostic(
            key = "$eventPrefix|bitmap-miss|$uri|${plan.tileWidth}",
            message = "$eventPrefix result=bitmap-miss uri='$uri' targetWidth=${ceil(plan.tileWidth).toInt()}",
        )
        return
    }
    if (bitmap.isRecycled) {
        logEpubBackgroundDiagnostic(
            key = "$eventPrefix|bitmap-recycled|$uri",
            message = "$eventPrefix result=bitmap-recycled uri='$uri' bitmap=${bitmap.width}x${bitmap.height}",
        )
        return
    }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    val src = Rect(0, 0, bitmap.width, bitmap.height)
    plan.forEachTile { left, top, right, bottom ->
        val localTop = frame.localY(top)
        canvas.drawBitmap(
            bitmap,
            src,
            RectF(left, localTop, right, frame.localY(bottom)),
            paint,
        )
    }
}

private data class EpubBackgroundDiagnosticContext(
    val chapterIndex: Int,
    val pageIndex: Int,
    val sectionIndex: Int,
    val layerIndex: Int,
    val pageWidth: Float,
    val viewportHeight: Float,
    val rawLayer: EpubBackgroundLayer,
) {
    fun eventPrefix(region: ScrollPageSectionRegion): String =
        "chapter=$chapterIndex page=$pageIndex section=$sectionIndex layer=$layerIndex " +
            "pageWidth=$pageWidth viewportHeight=$viewportHeight " +
            "region=${region.top}..${region.bottom} offset=${region.sectionOffsetY} " +
            "sectionHeight=${region.sectionHeight}"
}

/**
 * Canvas 会高频重绘，同一组背景参数只记录一次；保留上限用于约束长时间阅读时的诊断开销。
 */
private fun logEpubBackgroundDiagnostic(key: String, message: String) {
    val shouldLog = synchronized(epubBackgroundDiagKeys) {
        if (!epubBackgroundDiagKeys.add(key)) {
            false
        } else {
            while (epubBackgroundDiagKeys.size > EPUB_BACKGROUND_DIAG_LIMIT) {
                val iterator = epubBackgroundDiagKeys.iterator()
                if (iterator.hasNext()) {
                    iterator.next()
                    iterator.remove()
                }
            }
            true
        }
    }
    if (shouldLog) AppLog.info(EPUB_BACKGROUND_DIAG_TAG, message)
}

private fun drawLinearLayer(
    canvas: Canvas,
    gradient: Gradient.Linear,
    frame: EpubBackgroundRegionFrame,
    areaWidth: Float,
    areaHeight: Float,
    fontSizePx: Float,
    layer: com.morealm.epub.css.EpubBackgroundLayer,
) {
    val plan = EpubBackgroundGeometry.plan(
        layer, areaWidth, areaHeight, areaWidth, areaHeight, fontSizePx,
    ) ?: return
    val (colors, positions) = gradientStops(gradient.stops)
    if (colors.size < 2) return
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    plan.forEachTile { left, top, right, bottom ->
        val localTop = frame.localY(top)
        val localBottom = frame.localY(bottom)
        val points = linearPoints(gradient.direction, left, localTop, right, localBottom)
        paint.shader = LinearGradient(
            points[0], points[1], points[2], points[3], colors, positions, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(left, localTop, right, localBottom, paint)
    }
    paint.shader = null
}

private fun drawRadialLayer(
    canvas: Canvas,
    gradient: Gradient.Radial,
    frame: EpubBackgroundRegionFrame,
    areaWidth: Float,
    areaHeight: Float,
    fontSizePx: Float,
    layer: com.morealm.epub.css.EpubBackgroundLayer,
) {
    val plan = EpubBackgroundGeometry.plan(
        layer, areaWidth, areaHeight, areaWidth, areaHeight, fontSizePx,
    ) ?: return
    val (colors, positions) = gradientStops(gradient.stops)
    if (colors.size < 2) return
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    plan.forEachTile { left, top, right, bottom ->
        val localTop = frame.localY(top)
        val localBottom = frame.localY(bottom)
        val center = radialCenter(gradient.shape, left, localTop, right, localBottom)
        val radius = max(
            hypot(center.first - left, center.second - localTop),
            hypot(right - center.first, localBottom - center.second),
        ).coerceAtLeast(1f)
        paint.shader = RadialGradient(
            center.first, center.second, radius, colors, positions, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(left, localTop, right, localBottom, paint)
    }
    paint.shader = null
}

private fun gradientStops(stops: List<Gradient.ColorStop>): Pair<IntArray, FloatArray> {
    val normalized = if (stops.size == 1) listOf(stops[0], stops[0]) else stops
    val colors = IntArray(normalized.size) { normalized[it].color }
    val positions = FloatArray(normalized.size) { index ->
        val explicit = normalized[index].position
            ?.trim()
            ?.takeIf { it.endsWith('%') }
            ?.dropLast(1)
            ?.toFloatOrNull()
            ?.div(100f)
        (explicit ?: index.toFloat() / (normalized.size - 1).coerceAtLeast(1)).coerceIn(0f, 1f)
    }
    for (i in 1 until positions.size) positions[i] = max(positions[i], positions[i - 1])
    return colors to positions
}

private fun linearPoints(
    rawDirection: String,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
): FloatArray {
    val cx = (left + right) / 2f
    val cy = (top + bottom) / 2f
    val direction = rawDirection.trim().lowercase()
    val (dx, dy) = when {
        direction == "to top" -> 0f to -1f
        direction == "to right" -> 1f to 0f
        direction == "to left" -> -1f to 0f
        direction == "to top right" || direction == "to right top" -> 1f to -1f
        direction == "to top left" || direction == "to left top" -> -1f to -1f
        direction == "to bottom right" || direction == "to right bottom" -> 1f to 1f
        direction == "to bottom left" || direction == "to left bottom" -> -1f to 1f
        direction.endsWith("deg") -> {
            val degrees = direction.dropLast(3).toFloatOrNull() ?: 180f
            val radians = degrees / 180f * PI
            sin(radians).toFloat() to -cos(radians).toFloat()
        }
        else -> 0f to 1f
    }
    val half = (abs((right - left) * dx) + abs((bottom - top) * dy)) / 2f
    return floatArrayOf(cx - dx * half, cy - dy * half, cx + dx * half, cy + dy * half)
}

private fun radialCenter(
    shape: String,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
): Pair<Float, Float> {
    val position = shape.substringAfter(" at ", "center").lowercase()
    val x = when {
        "left" in position -> left
        "right" in position -> right
        else -> (left + right) / 2f
    }
    val y = when {
        "top" in position -> top
        "bottom" in position -> bottom
        else -> (top + bottom) / 2f
    }
    return x to y
}
