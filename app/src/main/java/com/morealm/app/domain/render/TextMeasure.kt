package com.morealm.app.domain.render

import android.graphics.Typeface
import android.text.TextPaint
import android.util.SparseArray
import androidx.core.util.getOrDefault
import kotlin.math.ceil

/**
 * Text measurement engine.
 * Caches character widths for fast repeated measurement.
 * Handles CJK characters, surrogate pairs, and ASCII efficiently.
 */
class TextMeasure(private var paint: TextPaint) {

    private var cjkWidth = paint.measureText("一")
    private val asciiWidths = FloatArray(128) { -1f }
    private val codePointWidths = SparseArray<Float>()

    fun measureTextSplit(text: String): Pair<ArrayList<String>, ArrayList<Float>> {
        var needMeasure: HashSet<Int>? = null
        val codePoints = text.toCodePoints()
        val widths = ArrayList<Float>(codePoints.size)
        val strings = ArrayList<String>(codePoints.size)
        val buf = IntArray(1)
        for (cp in codePoints) {
            val w = measureCodePoint(cp)
            widths.add(w)
            if (w == -1f) {
                if (needMeasure == null) needMeasure = hashSetOf()
                needMeasure.add(cp)
            }
            buf[0] = cp
            strings.add(String(buf, 0, 1))
        }
        if (!needMeasure.isNullOrEmpty()) {
            batchMeasure(needMeasure.toList())
            for (i in codePoints.indices) {
                if (widths[i] == -1f) widths[i] = measureCodePoint(codePoints[i])
            }
        }
        return strings to widths
    }

    fun measureText(text: String): Float {
        var total = 0f
        var needMeasure: ArrayList<Int>? = null
        for (cp in text.toCodePoints()) {
            val w = measureCodePoint(cp)
            if (w == -1f) {
                if (needMeasure == null) needMeasure = ArrayList()
                needMeasure.add(cp)
            } else total += w
        }
        if (!needMeasure.isNullOrEmpty()) {
            batchMeasure(needMeasure.toHashSet().toList())
            for (cp in needMeasure) total += measureCodePoint(cp)
        }
        return total
    }

    fun setPaint(newPaint: TextPaint) {
        paint = newPaint
        cjkWidth = paint.measureText("一")
        codePointWidths.clear()
        asciiWidths.fill(-1f)
    }

    private fun measureCodePoint(cp: Int): Float = when {
        cp < 128 -> asciiWidths[cp]
        cp in 0x4E00..0x9FA5 -> cjkWidth
        else -> codePointWidths.getOrDefault(cp, -1f)
    }

    private fun batchMeasure(codePoints: List<Int>) {
        val chars = String(codePoints.toIntArray(), 0, codePoints.size).toCharArray()
        val widths = FloatArray(chars.size)
        paint.getTextWidths(chars, 0, chars.size, widths)
        val widthList = ArrayList<Float>(chars.size)
        for (i in chars.indices) {
            if (chars[i].isLowSurrogate()) continue
            widthList.add(ceil(widths[i]))
        }
        for (i in codePoints.indices) {
            val cp = codePoints[i]
            val w = widthList.getOrElse(i) { 0f }
            if (cp < 128) asciiWidths[cp] = w else codePointWidths.put(cp, w)
        }
    }

    private fun String.toCodePoints(): List<Int> {
        val result = ArrayList<Int>(length)
        var i = 0
        while (i < length) {
            val c1 = this[i++]
            val cp = if (c1.isHighSurrogate() && i < length && this[i].isLowSurrogate()) {
                Character.toCodePoint(c1, this[i++])
            } else c1.code
            result.add(cp)
        }
        return result
    }
}
