package com.morealm.app.domain.render

import android.graphics.Paint
import android.text.TextPaint
import java.util.LinkedList

/**
 * Object pool for Paint / TextPaint to avoid per-frame allocation.
 * Ported from Legado's PaintPool + BaseSafeObjectPool pattern.
 *
 * Usage:
 *   val paint = PaintPool.obtainPaint()
 *   // ... use paint ...
 *   PaintPool.recyclePaint(paint)
 */
object PaintPool {

    private const val MAX_POOL_SIZE = 8

    private val paintPool = LinkedList<Paint>()
    private val textPaintPool = LinkedList<TextPaint>()
    private val emptyPaint = Paint()
    private val emptyTextPaint = TextPaint()

    @Synchronized
    fun obtainPaint(): Paint {
        return paintPool.pollFirst() ?: Paint()
    }

    @Synchronized
    fun recyclePaint(paint: Paint) {
        paint.set(emptyPaint)
        if (paintPool.size < MAX_POOL_SIZE) {
            paintPool.add(paint)
        }
    }

    @Synchronized
    fun obtainTextPaint(): TextPaint {
        return textPaintPool.pollFirst() ?: TextPaint()
    }

    @Synchronized
    fun recycleTextPaint(paint: TextPaint) {
        paint.set(emptyTextPaint)
        if (textPaintPool.size < MAX_POOL_SIZE) {
            textPaintPool.add(paint)
        }
    }
}
