package com.morealm.app.ui.reader.renderer.scroll

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import com.morealm.app.domain.render.ImageCache
import com.morealm.epub.css.EpubBackgroundGeometry
import com.morealm.epub.css.EpubBackgroundImage
import com.morealm.epub.css.Gradient
import com.morealm.epub.render.ScrollPage
import com.morealm.epub.render.ScrollPageSectionRegion
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/** Android Canvas 只负责消费 epub-lib 的背景语义，不在宿主重新解析 CSS。 */
internal fun drawEpubPageBackground(
    canvas: Canvas,
    page: ScrollPage,
    pageWidth: Float,
    pageHeight: Float,
    fontSizePx: Float,
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
    for (region in regions) {
        if (!region.background.isVisible || region.bottom <= region.top) continue
        drawRegion(canvas, region, pageWidth, fontSizePx)
    }
}

private fun drawRegion(
    canvas: Canvas,
    region: ScrollPageSectionRegion,
    width: Float,
    fontSizePx: Float,
) {
    val clipTop = region.top
    val clipBottom = region.bottom
    val sectionHeight = region.sectionHeight.coerceAtLeast(region.height)
    val save = canvas.save()
    canvas.clipRect(0f, clipTop, width, clipBottom)

    region.background.colorArgb?.let { color ->
        canvas.drawRect(0f, clipTop, width, clipBottom, Paint().apply {
            style = Paint.Style.FILL
            this.color = color
        })
    }

    // CSS 第一层位于最上方，Canvas 因此从列表末尾向前画。
    for (layer in region.background.layers.asReversed()) {
        when (val image = layer.image) {
            is EpubBackgroundImage.Url -> drawUrlLayer(
                canvas = canvas,
                uri = image.uri,
                region = region,
                areaWidth = width,
                areaHeight = sectionHeight,
                fontSizePx = fontSizePx,
                layer = layer,
            )
            is EpubBackgroundImage.LinearGradient -> drawLinearLayer(
                canvas, image.value, region, width, sectionHeight, fontSizePx, layer,
            )
            is EpubBackgroundImage.RadialGradient -> drawRadialLayer(
                canvas, image.value, region, width, sectionHeight, fontSizePx, layer,
            )
            is EpubBackgroundImage.CssFunction -> Unit
        }
    }
    canvas.restoreToCount(save)
}

private fun drawUrlLayer(
    canvas: Canvas,
    uri: String,
    region: ScrollPageSectionRegion,
    areaWidth: Float,
    areaHeight: Float,
    fontSizePx: Float,
    layer: com.morealm.epub.css.EpubBackgroundLayer,
) {
    val bounds = ImageCache.getBounds(uri) ?: return
    val plan = EpubBackgroundGeometry.plan(
        layer = layer,
        areaWidth = areaWidth,
        areaHeight = areaHeight,
        intrinsicWidth = bounds.first.toFloat(),
        intrinsicHeight = bounds.second.toFloat(),
        fontSizePx = fontSizePx,
    ) ?: return
    val bitmap = ImageCache.get(uri, ceil(plan.tileWidth).toInt().coerceAtLeast(1)) ?: return
    if (bitmap.isRecycled) return
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    val src = Rect(0, 0, bitmap.width, bitmap.height)
    plan.forEachTile { left, top, right, bottom ->
        val localTop = region.top + top - region.sectionOffsetY
        canvas.drawBitmap(
            bitmap,
            src,
            RectF(left, localTop, right, region.top + bottom - region.sectionOffsetY),
            paint,
        )
    }
}

private fun drawLinearLayer(
    canvas: Canvas,
    gradient: Gradient.Linear,
    region: ScrollPageSectionRegion,
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
        val localTop = region.top + top - region.sectionOffsetY
        val localBottom = region.top + bottom - region.sectionOffsetY
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
    region: ScrollPageSectionRegion,
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
        val localTop = region.top + top - region.sectionOffsetY
        val localBottom = region.top + bottom - region.sectionOffsetY
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
