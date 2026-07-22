package com.morealm.app.ui.reader.renderer.scroll

import android.graphics.Canvas
import com.morealm.epub.render.ScrollLine

/**
 * 按同一段落的统一包围盒应用 CSS rotate。这样多行及不同样式片段共享旋转中心，
 * 不会退化成每个文字片段分别倾斜。
 */
internal fun saveScrollLineRotation(
    canvas: Canvas,
    pageLines: List<ScrollLine>,
    line: ScrollLine,
    pageOffsetY: Float = 0f,
): Int? {
    val degrees = line.blockStyle.rotationDegrees
    if (degrees == 0f) return null

    var left = Float.POSITIVE_INFINITY
    var right = Float.NEGATIVE_INFINITY
    var top = Float.POSITIVE_INFINITY
    var bottom = Float.NEGATIVE_INFINITY
    for (peer in pageLines) {
        if (peer.paragraphNum != line.paragraphNum ||
            peer.sectionIndex != line.sectionIndex ||
            peer.blockStyle.rotationDegrees != degrees
        ) continue
        top = minOf(top, peer.lineTop)
        bottom = maxOf(bottom, peer.lineBottom)
        val cells = peer.cells
        if (!cells.isNullOrEmpty()) {
            for (cell in cells) {
                left = minOf(left, cell.contentLeft)
                right = maxOf(right, cell.contentLeft + cell.boxWidth)
            }
        } else {
            for (column in peer.columns) {
                left = minOf(left, column.start)
                right = maxOf(right, column.end)
            }
        }
    }
    if (!left.isFinite() || !right.isFinite() || !top.isFinite() || !bottom.isFinite()) return null

    val saveCount = canvas.save()
    canvas.rotate(degrees, (left + right) / 2f, pageOffsetY + (top + bottom) / 2f)
    return saveCount
}
