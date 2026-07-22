package com.morealm.app.domain.render

import android.graphics.Rect
import android.text.TextPaint
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.font.EpubFontRegistry
import com.morealm.epub.layout.LayoutMeasurer
import com.morealm.epub.layout.MeasuredText
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * TextPaint-backed [LayoutMeasurer] — the platform measurement seam for the scroll layout engine.
 *
 * One instance per paint role (content / title / chapter-number). Owns a [TextMeasure] width cache
 * over [paint]; all platform measurement lives here so the engine can compile free of android.text.*.
 *
 * [descent] / [textHeight] / [cjkFullCharWidth] are computed once as `val` — the engine caches descent
 * to avoid a per-line FontMetrics allocation, so these must NOT become live getters.
 */
class PaintLayoutMeasurer(private val paint: TextPaint) : LayoutMeasurer {

    private val measure = TextMeasure(paint)

    /** Custom-font ASCII space fallback width (~0.5 CJK em); see [measureSplitWithFont]. */
    private val asciiSpaceMinWidthWithCustomFont: Float = paint.measureText("一") * 0.5f
    /**
     * 视觉右界参与逐行断行，同一汉字会在整本书里反复出现。按字体族分桶缓存结果，避免
     * 每次排版都分配 Rect 并调用 getTextBounds；ConcurrentHashMap 允许相邻章节并行预排版。
     */
    private val visualRightCache = ConcurrentHashMap<String, ConcurrentHashMap<String, Int>>()
    private val visualBoundsPaint = TextPaint(paint)

    override val cjkFullCharWidth: Float = cjkFullCharWidth(paint)
    override val textHeight: Float = paint.textHeight
    override val descent: Float = paint.fontMetrics.descent
    override val textSize: Float get() = paint.textSize

    override val cacheToken: Long
        get() = (System.identityHashCode(paint.typeface).toLong() shl 32) or
            (paint.textSize.toRawBits().toLong() and 0xFFFF_FFFFL)

    override fun measureSplit(text: String): MeasuredText {
        val (chars, widths) = measure.measureTextSplit(text)
        return MeasuredText(chars, widths)
    }

    override fun measureWidth(text: String): Float = paint.measureText(text)

    override fun visualRight(text: String, fontFamily: String?): Int {
        val familyCache = visualRightCache.getOrPut(fontFamily ?: DEFAULT_FONT_CACHE_KEY) {
            ConcurrentHashMap()
        }
        familyCache[text]?.let { return it }
        val right = synchronized(visualBoundsPaint) {
            val swap = fontFamily?.let { EpubFontRegistry.resolveActive(it) }
            val saved = visualBoundsPaint.typeface
            try {
                if (swap != null) visualBoundsPaint.typeface = swap
                val bounds = Rect()
                visualBoundsPaint.getTextBounds(text, 0, text.length, bounds)
                bounds.right
            } finally {
                visualBoundsPaint.typeface = saved
            }
        }
        familyCache.putIfAbsent(text, right)
        return right
    }

    override fun measureSplitWithFont(text: String, fontFamily: String?): MeasuredText {
        val (chars, defWidths) = measure.measureTextSplit(text)
        if (fontFamily == null) return MeasuredText(chars, defWidths)
        val swapTypeface = EpubFontRegistry.resolveActive(fontFamily)
        if (swapTypeface == null) {
            // swap 字体没注册或加载失败 → 用 boost 兜底防位置全乱
            boostNarrowAsciiForCustomFont(fontFamily, chars, defWidths)
            return MeasuredText(chars, defWidths)
        }
        val widths = ArrayList<Float>(chars.size)
        val origTypeface = paint.typeface
        paint.typeface = swapTypeface
        var diag: StringBuilder? = null
        var diagTrunc = false
        // advance (measureText) vs visual bounds (getTextBounds.right) overhang diag — 描边伸出 advance 识别
        var boundsDiag: StringBuilder? = null
        var boundsDiagTrunc = false
        val tmpBounds = Rect()
        try {
            for (i in chars.indices) {
                val ch = chars[i]
                if (ch.isEmpty()) {
                    widths.add(defWidths[i])
                    continue
                }
                val swapW = paint.measureText(ch)
                widths.add(swapW)
                if (abs(swapW - defWidths[i]) > 1f && !diagTrunc) {
                    if (diag == null) diag = StringBuilder()
                    if (diag.length < 400) {
                        diag.append("[i=$i ch='${ch.take(2)}' def=${defWidths[i]}->swap=$swapW]")
                    } else {
                        diag.append("[...]")
                        diagTrunc = true
                    }
                }
                paint.getTextBounds(ch, 0, ch.length, tmpBounds)
                val overhang = tmpBounds.right - swapW
                if (overhang > 2f && !boundsDiagTrunc) {
                    if (boundsDiag == null) boundsDiag = StringBuilder()
                    if (boundsDiag.length < 400) {
                        boundsDiag.append("[i=$i ch='${ch.take(2)}' adv=$swapW boundsR=${tmpBounds.right} +$overhang]")
                    } else {
                        boundsDiag.append("[...]")
                        boundsDiagTrunc = true
                    }
                }
            }
        } finally {
            paint.typeface = origTypeface
        }
        if (diag != null) {
            AppLog.info("EpubW5H/CustomFontWidth", "family='$fontFamily' swap-measure=$diag")
        }
        if (boundsDiag != null) {
            AppLog.info("EpubW5H/GlyphBounds", "family='$fontFamily' overhang=$boundsDiag")
        }
        return MeasuredText(chars, widths)
    }

    private fun boostNarrowAsciiForCustomFont(
        fontFamily: String?,
        chars: MutableList<String>,
        widths: MutableList<Float>,
    ) {
        if (fontFamily == null) return
        val minW = asciiSpaceMinWidthWithCustomFont
        var diag: StringBuilder? = null
        for (i in chars.indices) {
            val ch = chars[i]
            if (ch.isEmpty()) continue
            val firstCh = ch[0]
            val cp = firstCh.code
            if (cp !in 0x20..0x7E) continue
            if (firstCh.isLetterOrDigit()) continue
            if (widths[i] >= minW) continue
            if (diag == null) diag = StringBuilder()
            val rawW = widths[i]
            widths[i] = minW
            diag.append("[i=$i ch='$ch' raw=$rawW->boost=$minW]")
        }
        if (diag != null) {
            AppLog.info(
                "EpubW5H/CustomFontWidth",
                "family='$fontFamily' minW=$minW boostScan=$diag",
            )
        }
    }

    private companion object {
        const val DEFAULT_FONT_CACHE_KEY = "\u0000"
    }
}
