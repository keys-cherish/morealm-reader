package com.morealm.app.ui.reader.renderer.scroll

import androidx.compose.ui.geometry.Rect
import com.morealm.app.domain.render.ImageCache
import com.morealm.epub.render.ImageRect
import com.morealm.epub.render.ScrollChapterLayout
import com.morealm.epub.render.findImageAt

data class EpubImageHit(val src: String, val isFullPage: Boolean, val drawnRect: Rect? = null)

/** Adapts common page geometry to chapter coordinates and Compose selection rectangles. */
object EpubImageHitTester {
    fun findImageAt(
        layout: ScrollChapterLayout,
        x: Float,
        yInChapter: Float,
        tolerancePx: Float,
        contentWidthPx: Float = Float.NaN,
        pageIndex: Int? = null,
    ): EpubImageHit? {
        var pageTop = 0f
        for (page in layout.pages) {
            val top = pageTop
            pageTop += page.height
            if (pageIndex != null && page.pageIndex != pageIndex) continue
            val y = yInChapter - top
            if (y < 0f || y >= page.height) continue
            val contentWidth = contentWidthPx.takeIf { it.isFinite() && it > 0f }
                ?: (layout.viewWidth - 2 * layout.paddingLeft).toFloat()
            val hit = page.findImageAt(
                x, y, contentWidth,
                ImageRect(-layout.paddingLeft.toFloat(), 0f,
                    (layout.viewWidth - layout.paddingLeft).toFloat(), page.height),
                tolerancePx,
                dimensions = { ImageCache.getBounds(it) },
            ) ?: continue
            val rect = hit.drawnRect
            return EpubImageHit(hit.src, hit.fullPage,
                Rect(rect.left, rect.top + top, rect.right, rect.bottom + top))
        }
        return null
    }
}
