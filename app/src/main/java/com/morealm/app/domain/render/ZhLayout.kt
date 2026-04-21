package com.morealm.app.domain.render

import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.text.Layout
import android.text.TextPaint
import java.util.WeakHashMap
import kotlin.math.max

/**
 * Chinese typography line-break engine.
 * Handles CJK punctuation rules that StaticLayout doesn't follow correctly.
 * Ported from open-source reading app, adapted for MoRealm.
 */
@Suppress("MemberVisibilityCanBePrivate", "unused")
class ZhLayout(
    text: CharSequence,
    textPaint: TextPaint,
    width: Int,
    words: List<String>,
    widths: List<Float>,
    indentSize: Int
) : Layout(text, textPaint, width, Alignment.ALIGN_NORMAL, 0f, 0f) {
    companion object {
        private val postPanc = hashSetOf(
            "，", "。", "：", "？", "！", "、", "\u201D", "\u2019", "）", "》", "}",
            "】", ")", ">", "]", "}", ",", ".", "?", "!", ":", "\u300D", "；", ";"
        )
        private val prePanc = hashSetOf(
            "\u201C", "（", "《", "【", "\u2018", "\u2019", "(", "<", "[", "{", "\u300C"
        )
        private val cnCharWidthCache = WeakHashMap<Paint, Float>()
    }

    private val defaultCapacity = 10
    var lineStart = IntArray(defaultCapacity)
    var lineWidth = FloatArray(defaultCapacity)
    private var lineCount = 0
    private val curPaint = textPaint
    private val cnCharWidth = cnCharWidthCache[textPaint]
        ?: getDesiredWidth("我", textPaint).also {
            cnCharWidthCache[textPaint] = it
        }

    enum class BreakMod { NORMAL, BREAK_ONE_CHAR, BREAK_MORE_CHAR, CPS_1, CPS_2, CPS_3 }

    init {
        var line = 0
        var lineW = 0f
        var cwPre = 0f
        var length = 0
        words.forEachIndexed { index, s ->
            val cw = widths[index]
            var breakMod: BreakMod
            var breakLine = false
            lineW += cw
            var offset = 0f
            var breakCharCnt = 0

            if (lineW > width) {
                breakMod = if (index >= 1 && isPrePanc(words[index - 1])) {
                    if (index >= 2 && isPrePanc(words[index - 2])) BreakMod.CPS_2
                    else BreakMod.BREAK_ONE_CHAR
                } else if (isPostPanc(words[index])) {
                    if (index >= 1 && isPostPanc(words[index - 1])) BreakMod.CPS_1
                    else if (index >= 2 && isPrePanc(words[index - 2])) BreakMod.CPS_3
                    else BreakMod.BREAK_ONE_CHAR
                } else {
                    BreakMod.NORMAL
                }

                var reCheck = false
                var breakIndex = 0
                if (breakMod == BreakMod.CPS_1 &&
                    (inCompressible(widths[index]) || inCompressible(widths[index - 1]))
                ) reCheck = true
                if (breakMod == BreakMod.CPS_2 &&
                    (inCompressible(widths[index - 1]) || inCompressible(widths[index - 2]))
                ) reCheck = true
                if (breakMod == BreakMod.CPS_3 &&
                    (inCompressible(widths[index]) || inCompressible(widths[index - 2]))
                ) reCheck = true
                if (breakMod > BreakMod.BREAK_MORE_CHAR
                    && index < words.lastIndex && isPostPanc(words[index + 1])
                ) reCheck = true

                var breakLength = 0
                if (reCheck && index > 2) {
                    val startPos = if (line == 0) indentSize else getLineStart(line)
                    breakMod = BreakMod.NORMAL
                    for (i in (index) downTo 1 + startPos) {
                        if (i == index) {
                            breakIndex = 0
                            cwPre = 0f
                        } else {
                            breakIndex++
                            breakLength += words[i].length
                            cwPre += widths[i]
                        }
                        if (!isPostPanc(words[i]) && !isPrePanc(words[i - 1])) {
                            breakMod = BreakMod.BREAK_MORE_CHAR
                            break
                        }
                    }
                }

                when (breakMod) {
                    BreakMod.NORMAL -> {
                        offset = cw
                        lineStart[line + 1] = length
                        breakCharCnt = 1
                    }
                    BreakMod.BREAK_ONE_CHAR -> {
                        offset = cw + cwPre
                        lineStart[line + 1] = length - words[index - 1].length
                        breakCharCnt = 2
                    }
                    BreakMod.BREAK_MORE_CHAR -> {
                        offset = cw + cwPre
                        lineStart[line + 1] = length - breakLength
                        breakCharCnt = breakIndex + 1
                    }
                    BreakMod.CPS_1 -> {
                        offset = 0f
                        lineStart[line + 1] = length + s.length
                        breakCharCnt = 0
                    }
                    BreakMod.CPS_2 -> {
                        offset = 0f
                        lineStart[line + 1] = length + s.length
                        breakCharCnt = 0
                    }
                    BreakMod.CPS_3 -> {
                        offset = 0f
                        lineStart[line + 1] = length + s.length
                        breakCharCnt = 0
                    }
                }
                breakLine = true
            }

            if (breakLine) {
                lineWidth[line] = lineW - offset
                lineW = offset
                addLineArray(++line)
            }
            if ((words.lastIndex) == index) {
                if (!breakLine) {
                    offset = 0f
                    lineStart[line + 1] = length + s.length
                    lineWidth[line] = lineW - offset
                    lineW = offset
                    addLineArray(++line)
                } else if (breakCharCnt > 0) {
                    lineStart[line + 1] = lineStart[line] + breakCharCnt
                    lineWidth[line] = lineW
                    addLineArray(++line)
                }
            }
            length += s.length
            cwPre = cw
        }
        lineCount = line
    }

    private fun addLineArray(line: Int) {
        if (lineStart.size <= line + 1) {
            lineStart = lineStart.copyOf(line + defaultCapacity)
            lineWidth = lineWidth.copyOf(line + defaultCapacity)
        }
    }

    private fun isPostPanc(string: String): Boolean = postPanc.contains(string)
    private fun isPrePanc(string: String): Boolean = prePanc.contains(string)
    private fun inCompressible(width: Float): Boolean = width < cnCharWidth

    fun getDesiredWidth(string: String, paint: TextPaint): Float {
        var width = paint.measureText(string)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            width += paint.letterSpacing * paint.textSize
        }
        return width
    }

    override fun getLineCount(): Int = lineCount
    override fun getLineTop(line: Int): Int = 0
    override fun getLineDescent(line: Int): Int = 0
    override fun getLineStart(line: Int): Int = lineStart[line]
    override fun getParagraphDirection(line: Int): Int = 0
    override fun getLineContainsTab(line: Int): Boolean = true
    override fun getLineDirections(line: Int): Directions? = null
    override fun getTopPadding(): Int = 0
    override fun getBottomPadding(): Int = 0
    override fun getLineWidth(line: Int): Float = lineWidth[line]
    override fun getEllipsisStart(line: Int): Int = 0
    override fun getEllipsisCount(line: Int): Int = 0
}
