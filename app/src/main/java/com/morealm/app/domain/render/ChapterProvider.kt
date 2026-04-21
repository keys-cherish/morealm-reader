package com.morealm.app.domain.render

import android.graphics.Paint.FontMetrics
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.roundToInt

/**
 * Layout engine: splits chapter text into pages with full text justification,
 * Chinese typography rules (ZhLayout), image layout, and paragraph indentation.
 *
 * Ported from open-source reading app, adapted for MoRealm's MVVM architecture.
 * This is a pure domain-layer class — no Compose/UI imports.
 */
class ChapterProvider(
    val viewWidth: Int,
    val viewHeight: Int,
    val paddingLeft: Int,
    val paddingRight: Int,
    val paddingTop: Int,
    val paddingBottom: Int,
    val titlePaint: TextPaint,
    val contentPaint: TextPaint,
    val textMeasure: TextMeasure,
    val paragraphIndent: String = "\u3000\u3000",
    val textFullJustify: Boolean = true,
    val titleMode: Int = 0,       // 0=left, 1=center, 2=hidden
    val isMiddleTitle: Boolean = false,
    val useZhLayout: Boolean = true,
    val lineSpacingExtra: Float = 1.2f,
    val paragraphSpacing: Int = 8,
    val titleTopSpacing: Int = 0,
    val titleBottomSpacing: Int = 0,
    val doublePage: Boolean = false,
) {
    companion object {
        const val INDENT_CHAR = "\u3000"
        const val IMG_PATTERN = """<img[^>]+src="([^"]+)"[^>]*>"""
    }

    val visibleWidth: Int = viewWidth - paddingLeft - paddingRight
    val visibleHeight: Int = viewHeight - paddingTop - paddingBottom

    private val titlePaintTextHeight: Float = titlePaint.textHeight
    private val titlePaintFontMetrics: FontMetrics = titlePaint.fontMetrics
    private val contentPaintTextHeight: Float = contentPaint.textHeight
    private val contentPaintFontMetrics: FontMetrics = contentPaint.fontMetrics
    private val indentCharWidth: Float = contentPaint.measureText(INDENT_CHAR)

    private val imgPattern = Regex(IMG_PATTERN, RegexOption.IGNORE_CASE)

    /**
     * Layout a chapter into pages.
     * @param title Chapter title
     * @param content Chapter text content (may contain HTML img tags)
     * @param chapterIndex Index of this chapter
     * @param chaptersSize Total number of chapters
     * @return Fully laid-out TextChapter with all pages
     */
    fun layoutChapter(
        title: String,
        content: String,
        chapterIndex: Int,
        chaptersSize: Int = 0,
    ): TextChapter {
        val textChapter = TextChapter(chapterIndex, title, chaptersSize)
        val textPages = arrayListOf<TextPage>()
        val stringBuilder = StringBuilder()
        var absStartX = paddingLeft
        var durY = 0f
        textPages.add(TextPage())
        val floatArray = FloatArray(256)

        // Parse content into paragraphs
        val isHtml = content.trimStart().let {
            it.startsWith("<") && (it.contains("<p") || it.contains("<div") || it.contains("<img"))
        }
        val paragraphs = if (isHtml) parseHtmlParagraphs(content) else {
            content.lines().filter { it.isNotBlank() }
        }

        // Layout title
        if (titleMode != 2) {
            title.split("\n").filter { it.isNotBlank() }.forEach { text ->
                val result = setTypeText(
                    absStartX, durY, text, textPages, stringBuilder,
                    titlePaint, titlePaintTextHeight, titlePaintFontMetrics,
                    floatArray, isTitle = true,
                    emptyContent = paragraphs.isEmpty(),
                )
                absStartX = result.first
                durY = result.second
            }
            if (textPages.last().lines.isNotEmpty()) {
                textPages.last().lines.last().isParagraphEnd = true
            }
            stringBuilder.append("\n")
            durY += titleBottomSpacing
        }

        // Layout content paragraphs
        for (para in paragraphs) {
            // Check for inline images
            val imgMatcher = imgPattern.find(para)
            if (imgMatcher != null) {
                // Handle mixed text + image content
                var start = 0
                var matchResult = imgMatcher
                while (matchResult != null) {
                    val textBefore = para.substring(start, matchResult.range.first)
                    if (textBefore.isNotBlank()) {
                        val r = setTypeText(
                            absStartX, durY, textBefore, textPages, stringBuilder,
                            contentPaint, contentPaintTextHeight, contentPaintFontMetrics,
                            floatArray, isFirstLine = start == 0,
                        )
                        absStartX = r.first; durY = r.second
                    }
                    val src = matchResult.groupValues[1]
                    val r = setTypeImage(
                        src, absStartX, durY, textPages, contentPaintTextHeight, stringBuilder,
                    )
                    absStartX = r.first; durY = r.second
                    start = matchResult.range.last + 1
                    matchResult = imgPattern.find(para, start)
                }
                if (start < para.length) {
                    val remaining = para.substring(start)
                    if (remaining.isNotBlank()) {
                        val r = setTypeText(
                            absStartX, durY, remaining, textPages, stringBuilder,
                            contentPaint, contentPaintTextHeight, contentPaintFontMetrics,
                            floatArray, isFirstLine = start == 0,
                        )
                        absStartX = r.first; durY = r.second
                    }
                }
            } else {
                val r = setTypeText(
                    absStartX, durY, para, textPages, stringBuilder,
                    contentPaint, contentPaintTextHeight, contentPaintFontMetrics,
                    floatArray,
                )
                absStartX = r.first; durY = r.second
            }
            if (textPages.last().lines.isNotEmpty()) {
                textPages.last().lines.last().isParagraphEnd = true
            }
            stringBuilder.append("\n")
        }

        // Finalize last page
        val lastPage = textPages.last()
        val endPadding = 20
        val durYPadding = durY + endPadding
        if (lastPage.height < durYPadding) {
            lastPage.height = durYPadding
        } else {
            lastPage.height += endPadding
        }
        lastPage.text = stringBuilder.toString()

        // Set page metadata
        textPages.forEachIndexed { index, page ->
            page.index = index
            page.chapterIndex = chapterIndex
            page.chapterSize = chaptersSize
            page.title = title
            page.doublePage = doublePage
            page.paddingTop = paddingTop
            page.isCompleted = true
            page.textChapter = textChapter
            page.upLinesPosition()
        }

        for (page in textPages) {
            textChapter.addPage(page)
        }
        textChapter.isCompleted = true
        return textChapter
    }

    // ── Image layout ──

    private fun setTypeImage(
        src: String,
        x: Int,
        y: Float,
        textPages: ArrayList<TextPage>,
        textHeight: Float,
        stringBuilder: StringBuilder,
    ): Pair<Int, Float> {
        var absStartX = x
        var durY = y
        // Reserve space for image — use content width, aspect ratio 4:3 default
        val imgWidth = visibleWidth
        var imgHeight = (visibleWidth * 0.75f).toInt()
        if (imgHeight > visibleHeight) imgHeight = visibleHeight

        if (durY + imgHeight > visibleHeight) {
            val textPage = textPages.last()
            if (doublePage && absStartX < viewWidth / 2) {
                textPage.leftLineSize = textPage.lineSize
                absStartX = viewWidth / 2 + paddingLeft
            } else {
                if (textPage.leftLineSize == 0) textPage.leftLineSize = textPage.lineSize
                textPage.text = stringBuilder.toString()
                stringBuilder.clear()
                textPages.add(TextPage())
                absStartX = paddingLeft
            }
            if (textPage.height < durY) textPage.height = durY
            durY = 0f
        }

        val textLine = TextLine(isImage = true)
        textLine.text = " "
        textLine.lineTop = durY + paddingTop
        durY += imgHeight
        textLine.lineBottom = durY + paddingTop
        val startOffset = if (visibleWidth > imgWidth) (visibleWidth - imgWidth) / 2f else 0f
        textLine.addColumn(
            ImageColumn(
                start = absStartX + startOffset,
                end = absStartX + startOffset + imgWidth,
                src = src,
            )
        )
        calcTextLinePosition(textPages, textLine, stringBuilder.length)
        stringBuilder.append(" ")
        textPages.last().addLine(textLine)
        return absStartX to durY + textHeight * paragraphSpacing / 10f
    }

    // ── Text layout with full justification ──

    @Suppress("DEPRECATION")
    private fun setTypeText(
        x: Int,
        y: Float,
        text: String,
        textPages: ArrayList<TextPage>,
        stringBuilder: StringBuilder,
        textPaint: TextPaint,
        textHeight: Float,
        fontMetrics: FontMetrics,
        floatArray: FloatArray,
        isTitle: Boolean = false,
        isFirstLine: Boolean = true,
        emptyContent: Boolean = false,
        isVolumeTitle: Boolean = false,
    ): Pair<Int, Float> {
        var absStartX = x
        val widthsArray = allocateFloatArray(text.length, floatArray)
        textPaint.getTextWidths(text, widthsArray)
        val layout = if (useZhLayout) {
            val (words, widths) = measureTextSplit(text, widthsArray)
            val indentSize = if (isFirstLine) paragraphIndent.length else 0
            ZhLayout(text, textPaint, visibleWidth, words, widths, indentSize)
        } else {
            StaticLayout(text, textPaint, visibleWidth, Layout.Alignment.ALIGN_NORMAL, 0f, 0f, true)
        }
        var durY = when {
            emptyContent && textPages.size == 1 -> {
                val textPage = textPages.last()
                if (textPage.lineSize == 0) {
                    val ty = (visibleHeight - layout.lineCount * textHeight) / 2
                    if (ty > titleTopSpacing) ty else titleTopSpacing.toFloat()
                } else {
                    var textLayoutHeight = layout.lineCount * textHeight
                    val firstLine = textPage.getLine(0)
                    if (firstLine.lineTop < textLayoutHeight + titleTopSpacing) {
                        textLayoutHeight = firstLine.lineTop - titleTopSpacing
                    }
                    textPage.lines.forEach {
                        it.lineTop -= textLayoutHeight
                        it.lineBase -= textLayoutHeight
                        it.lineBottom -= textLayoutHeight
                    }
                    y - textLayoutHeight
                }
            }
            isTitle && textPages.size == 1 && textPages.last().lines.isEmpty() ->
                y + titleTopSpacing
            else -> y
        }

        for (lineIndex in 0 until layout.lineCount) {
            val textLine = TextLine(isTitle = isTitle)
            if (durY + textHeight > visibleHeight) {
                val textPage = textPages.last()
                if (doublePage && absStartX < viewWidth / 2) {
                    textPage.leftLineSize = textPage.lineSize
                    absStartX = viewWidth / 2 + paddingLeft
                } else {
                    if (textPage.leftLineSize == 0) textPage.leftLineSize = textPage.lineSize
                    textPage.text = stringBuilder.toString()
                    textPages.add(TextPage())
                    stringBuilder.clear()
                    absStartX = paddingLeft
                }
                if (textPage.height < durY) textPage.height = durY
                durY = 0f
            }
            val lineStart = layout.getLineStart(lineIndex)
            val lineEnd = layout.getLineEnd(lineIndex)
            val lineText = text.substring(lineStart, lineEnd)
            val (words, widths) = measureTextSplit(lineText, widthsArray, lineStart)
            val desiredWidth = widths.sum()
            when {
                lineIndex == 0 && layout.lineCount > 1 && !isTitle -> {
                    textLine.text = lineText
                    addCharsToLineFirst(
                        absStartX, textLine, words, desiredWidth, widths,
                    )
                }
                lineIndex == layout.lineCount - 1 -> {
                    textLine.text = lineText
                    val startXOffset = if (
                        isTitle && (isMiddleTitle || emptyContent || isVolumeTitle)
                    ) {
                        (visibleWidth - desiredWidth) / 2
                    } else 0f
                    addCharsToLineNatural(
                        absStartX, textLine, words, startXOffset,
                        !isTitle && lineIndex == 0, widths,
                    )
                }
                else -> {
                    if (isTitle && (isMiddleTitle || emptyContent || isVolumeTitle)) {
                        val startXOffset = (visibleWidth - desiredWidth) / 2
                        addCharsToLineNatural(
                            absStartX, textLine, words, startXOffset, false, widths,
                        )
                    } else {
                        textLine.text = lineText
                        addCharsToLineMiddle(
                            absStartX, textLine, words, textPaint,
                            desiredWidth, 0f, widths,
                        )
                    }
                }
            }
            if (doublePage) {
                textLine.isLeftLine = absStartX < viewWidth / 2
            }
            calcTextLinePosition(textPages, textLine, stringBuilder.length)
            stringBuilder.append(lineText)
            textLine.upTopBottom(durY, textHeight, fontMetrics)
            val textPage = textPages.last()
            textPage.addLine(textLine)
            durY += textHeight * lineSpacingExtra
            if (textPage.height < durY) textPage.height = durY
        }
        durY += textHeight * paragraphSpacing / 10f
        return Pair(absStartX, durY)
    }

    // ── Line position tracking ──

    private fun calcTextLinePosition(
        textPages: ArrayList<TextPage>,
        textLine: TextLine,
        sbLength: Int,
    ) {
        val lastLine = textPages.last().lines.lastOrNull { it.paragraphNum > 0 }
            ?: textPages.getOrNull(textPages.lastIndex - 1)?.lines?.lastOrNull { it.paragraphNum > 0 }
        val paragraphNum = when {
            lastLine == null -> 1
            lastLine.isParagraphEnd -> lastLine.paragraphNum + 1
            else -> lastLine.paragraphNum
        }
        textLine.paragraphNum = paragraphNum
        textLine.chapterPosition =
            (textPages.getOrNull(textPages.lastIndex - 1)?.lines?.lastOrNull()?.run {
                chapterPosition + charSize + if (isParagraphEnd) 1 else 0
            } ?: 0) + sbLength
        textLine.pagePosition = sbLength
    }

    // ── First line with indent + justify ──

    private fun addCharsToLineFirst(
        absStartX: Int,
        textLine: TextLine,
        words: List<String>,
        desiredWidth: Float,
        textWidths: List<Float>,
    ) {
        var x = 0f
        if (!textFullJustify) {
            addCharsToLineNatural(absStartX, textLine, words, x, true, textWidths)
            return
        }
        val bodyIndent = paragraphIndent
        repeat(bodyIndent.length) {
            val x1 = x + indentCharWidth
            textLine.addColumn(
                TextColumn(charData = INDENT_CHAR, start = absStartX + x, end = absStartX + x1)
            )
            x = x1
            textLine.indentWidth = x
        }
        textLine.indentSize = bodyIndent.length
        if (words.size > bodyIndent.length) {
            val text1 = words.subList(bodyIndent.length, words.size)
            val textWidths1 = textWidths.subList(bodyIndent.length, textWidths.size)
            addCharsToLineMiddle(
                absStartX, textLine, text1, contentPaint,
                desiredWidth, x, textWidths1,
            )
        }
    }

    // ── Middle line: no indent, full justify ──

    private fun addCharsToLineMiddle(
        absStartX: Int,
        textLine: TextLine,
        words: List<String>,
        textPaint: TextPaint,
        desiredWidth: Float,
        startX: Float,
        textWidths: List<Float>,
    ) {
        if (!textFullJustify) {
            addCharsToLineNatural(absStartX, textLine, words, startX, false, textWidths)
            return
        }
        val residualWidth = visibleWidth - desiredWidth
        val spaceSize = words.count { it == " " }
        textLine.startX = absStartX + startX
        if (spaceSize > 1) {
            val d = residualWidth / spaceSize
            textLine.wordSpacing = d
            var x = startX
            for (index in words.indices) {
                val char = words[index]
                val cw = textWidths[index]
                val x1 = if (char == " ") {
                    if (index != words.lastIndex) (x + cw + d) else (x + cw)
                } else (x + cw)
                textLine.addColumn(
                    TextColumn(charData = char, start = absStartX + x, end = absStartX + x1)
                )
                x = x1
            }
        } else {
            val gapCount = words.lastIndex
            val d = if (gapCount > 0) residualWidth / gapCount else 0f
            textLine.extraLetterSpacingOffsetX = -d / 2
            textLine.extraLetterSpacing = d / textPaint.textSize
            var x = startX
            for (index in words.indices) {
                val char = words[index]
                val cw = textWidths[index]
                val x1 = if (index != words.lastIndex) (x + cw + d) else (x + cw)
                textLine.addColumn(
                    TextColumn(charData = char, start = absStartX + x, end = absStartX + x1)
                )
                x = x1
            }
        }
        exceed(absStartX, textLine, words)
    }

    // ── Natural (left-aligned) layout ──

    private fun addCharsToLineNatural(
        absStartX: Int,
        textLine: TextLine,
        words: List<String>,
        startX: Float,
        hasIndent: Boolean,
        textWidths: List<Float>,
    ) {
        val indentLength = paragraphIndent.length
        var x = startX
        textLine.startX = absStartX + startX
        for (index in words.indices) {
            val char = words[index]
            val cw = textWidths[index]
            val x1 = x + cw
            textLine.addColumn(
                TextColumn(charData = char, start = absStartX + x, end = absStartX + x1)
            )
            x = x1
            if (hasIndent && index == indentLength - 1) {
                textLine.indentWidth = x
            }
        }
        exceed(absStartX, textLine, words)
    }

    // ── Boundary overflow correction ──

    private fun exceed(absStartX: Int, textLine: TextLine, words: List<String>) {
        var size = words.size
        if (size < 2) return
        val visibleEnd = absStartX + visibleWidth
        val columns = textLine.columns
        var offset = 0
        val endColumn = if (words.last() == " ") {
            size--; offset++
            columns[columns.lastIndex - 1]
        } else columns.last()
        val endX = endColumn.end.roundToInt()
        if (endX > visibleEnd) {
            textLine.exceed = true
            val cc = (endX - visibleEnd) / size
            for (i in 0..<size) {
                textLine.getColumnReverseAt(i, offset).let {
                    val py = cc * (size - i)
                    it.start -= py
                    it.end -= py
                }
            }
        }
    }

    // ── Text measurement ──

    private fun measureTextSplit(
        text: String,
        widthsArray: FloatArray,
        start: Int = 0,
    ): Pair<ArrayList<String>, ArrayList<Float>> {
        val length = text.length
        var clusterCount = 0
        for (i in start..<start + length) {
            if (widthsArray[i] > 0) clusterCount++
        }
        val widths = ArrayList<Float>(clusterCount)
        val stringList = ArrayList<String>(clusterCount)
        var i = 0
        while (i < length) {
            val clusterBaseIndex = i++
            widths.add(widthsArray[start + clusterBaseIndex])
            while (i < length && widthsArray[start + i] == 0f && !isZeroWidthChar(text[i])) {
                i++
            }
            stringList.add(text.substring(clusterBaseIndex, i))
        }
        return stringList to widths
    }

    private fun isZeroWidthChar(char: Char): Boolean {
        val code = char.code
        return code == 8203 || code == 8204 || code == 8205 || code == 8288
    }

    private fun allocateFloatArray(size: Int, existing: FloatArray): FloatArray {
        return if (size > existing.size) FloatArray(size) else existing
    }

    // ── HTML parsing ──

    private fun parseHtmlParagraphs(html: String): List<String> {
        val text = html
            .replace(Regex("</p>|</div>|</li>|</h[1-6]>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&nbsp;", " ").replace("&quot;", "\"")
        // Keep img tags intact for image extraction, strip other tags
        val cleaned = text.replace(Regex("<(?!img)[^>]+>", RegexOption.IGNORE_CASE), "")
        return cleaned.lines().map { it.trim() }.filter { it.isNotBlank() }
    }
}

// ── Extension: TextPaint.textHeight ──

val TextPaint.textHeight: Float
    get() = fontMetrics.let { it.descent - it.ascent }
