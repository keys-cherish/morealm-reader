package com.morealm.app.domain.render

import android.graphics.BitmapFactory
import android.graphics.Paint.FontMetrics
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import com.morealm.app.core.text.AppPattern
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
    /** Paint for the chapter-number sub-line (smaller, accent-colored). Null = use titlePaint. */
    val chapterNumPaint: TextPaint? = null,
) {

    val visibleWidth: Int = viewWidth - paddingLeft - paddingRight
    val visibleHeight: Int = viewHeight - paddingTop - paddingBottom

    private val titlePaintTextHeight: Float = titlePaint.textHeight
    private val titlePaintFontMetrics: FontMetrics = titlePaint.fontMetrics
    private val chapterNumPaintTextHeight: Float = chapterNumPaint?.textHeight ?: titlePaintTextHeight
    private val chapterNumPaintFontMetrics: FontMetrics = chapterNumPaint?.fontMetrics ?: titlePaintFontMetrics
    private val contentPaintTextHeight: Float = contentPaint.textHeight
    private val contentPaintFontMetrics: FontMetrics = contentPaint.fontMetrics
    private val indentCharWidth: Float = contentPaint.measureText(INDENT_CHAR)
    private val drawPaddingTop: Int = if (paddingTop > 0) maxOf(0, paddingTop - titlePaintTextHeight.roundToInt() / 2) else 0

    private val imgPattern = Regex(IMG_PATTERN.pattern(), RegexOption.IGNORE_CASE)

    /**
     * Layout a chapter into pages (synchronous).
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
        layoutInternal(title, content, chapterIndex, chaptersSize, textChapter, textPages)
        return textChapter
    }

    /**
     * 异步流式排版 — 移植自 Legado TextChapterLayout。
     * 排完一页立即通过 Channel 发送，UI 可以立即显示第一页，无需等待整章排完。
     *
     * @param scope 协程作用域，用于启动排版协程
     * @param onPageReady 每排完一页的回调（在排版线程调用）
     * @param onCompleted 全部排完的回调
     * @param onError 排版异常回调
     * @return AsyncLayoutHandle，可用于取消排版和读取 Channel
     */
    fun layoutChapterAsync(
        title: String,
        content: String,
        chapterIndex: Int,
        chaptersSize: Int = 0,
        scope: CoroutineScope,
        onPageReady: ((Int, TextPage) -> Unit)? = null,
        onCompleted: (() -> Unit)? = null,
        onError: ((Throwable) -> Unit)? = null,
    ): AsyncLayoutHandle {
        val textChapter = TextChapter(chapterIndex, title, chaptersSize)
        val channel = Channel<TextPage>(Channel.UNLIMITED)

        val job = scope.launch(Dispatchers.Default) {
            try {
                val textPages = arrayListOf<TextPage>()
                layoutInternal(
                    title, content, chapterIndex, chaptersSize,
                    textChapter, textPages, channel, onPageReady,
                )
                channel.close()
                onCompleted?.invoke()
            } catch (e: Exception) {
                channel.close(e)
                if (e !is kotlinx.coroutines.CancellationException) {
                    onError?.invoke(e)
                }
            }
        }

        return AsyncLayoutHandle(textChapter, channel, job)
    }

    /**
     * 内部排版逻辑，同步和异步共用。
     * 当 channel 不为 null 时，每排完一页就发送到 channel。
     */
    private fun layoutInternal(
        title: String,
        content: String,
        chapterIndex: Int,
        chaptersSize: Int,
        textChapter: TextChapter,
        textPages: ArrayList<TextPage>,
        channel: Channel<TextPage>? = null,
        onPageReady: ((Int, TextPage) -> Unit)? = null,
    ) {
        val stringBuilder = StringBuilder()
        var absStartX = paddingLeft
        var durY = 0f
        textPages.add(TextPage())
        val floatArray = FloatArray(256)

        // 内部辅助：完成一页时的处理
        fun finalizePage(page: TextPage) {
            page.index = textPages.indexOf(page)
            page.chapterIndex = chapterIndex
            page.chapterSize = chaptersSize
            page.title = title
            page.doublePage = doublePage
            page.paddingTop = drawPaddingTop
            page.isCompleted = true
            page.textChapter = textChapter
            page.upLinesPosition()
            textChapter.addPage(page)
            channel?.trySend(page)
            onPageReady?.invoke(page.index, page)
        }

        // Parse content into paragraphs
        val isHtml = content.trimStart().let {
            it.startsWith("<") && (it.contains("<p") || it.contains("<div") || it.contains("<img"))
        }
        val paragraphs = if (isHtml) parseHtmlParagraphs(content) else {
            content.lines().mapNotNull { normalizeParagraph(it)?.let(::LayoutParagraph) }
        }
        val contentProvidesChapterTitle = paragraphs.firstOrNull()?.let { first ->
            first.isChapterTitle || isSameChapterTitle(first.text, title)
        } == true

        val pageCountBefore = textPages.size

        // Layout title — split into chapter-num line + title line (4.htm style)
        if (titleMode != 2 && !contentProvidesChapterTitle) {
            val titleParts = splitChapterNumAndTitle(title)
            val chapterNumText = titleParts.first   // e.g. "第一章" or null
            val titleText = titleParts.second        // e.g. "山边小村" or full title

            // 1) Chapter number sub-line (small accent text)
            if (chapterNumText != null && chapterNumPaint != null) {
                val result = setTypeText(
                    absStartX, durY, chapterNumText, textPages, stringBuilder,
                    chapterNumPaint, chapterNumPaintTextHeight, chapterNumPaintFontMetrics,
                    floatArray, isTitle = true, isChapterNum = true, forceLeftTitle = true,
                    emptyContent = paragraphs.isEmpty(),
                )
                absStartX = result.first
                durY = result.second
                if (textPages.last().lines.isNotEmpty()) {
                    textPages.last().lines.last().isParagraphEnd = true
                }
                stringBuilder.append("\n")
                // 4.htm: chapter-num margin-bottom: 0.5rem; use the smaller
                // chapter-number line height so the gap does not balloon with body text.
                durY += chapterNumPaintTextHeight * 0.20f
            }

            // 2) Title text (main color, medium weight)
            val titleLines = titleText.split("\n").filter { it.isNotBlank() }
            titleLines.forEach { text ->
                val result = setTypeText(
                    absStartX, durY, text, textPages, stringBuilder,
                    titlePaint, titlePaintTextHeight, titlePaintFontMetrics,
                    floatArray, isTitle = true, forceLeftTitle = true,
                    emptyContent = paragraphs.isEmpty(),
                )
                absStartX = result.first
                durY = result.second
            }
            // Mark the last title line for the decorative accent bar
            if (textPages.last().lines.isNotEmpty()) {
                textPages.last().lines.last().isTitleEnd = true
                textPages.last().lines.last().isParagraphEnd = true
            }
            stringBuilder.append("\n")
            // Reserve room for the decorative line and keep the title block compact.
            durY += (contentPaintTextHeight * 0.75f).coerceAtLeast(titleBottomSpacing.toFloat() * 0.5f)
        }

        // 检查排版过程中是否产生了新页（分页），如果有就 finalize 已完成的页
        fun flushCompletedPages() {
            // 除了最后一页（正在排版中），之前的页都已完成
            while (textChapter.pageSize < textPages.size - 1) {
                val idx = textChapter.pageSize
                finalizePage(textPages[idx])
            }
        }

        // Layout content paragraphs
        for (paragraph in paragraphs) {
            val para = paragraph.text
            if (paragraph.isChapterTitle) {
                if (textPages.last().lines.isNotEmpty()) {
                    durY += titleTopSpacing.coerceAtLeast(paragraphSpacing).toFloat()
                }
                val paint = if (paragraph.isChapterNum && chapterNumPaint != null) chapterNumPaint else titlePaint
                val paintHeight = if (paragraph.isChapterNum && chapterNumPaint != null) chapterNumPaintTextHeight else titlePaintTextHeight
                val paintFontMetrics = if (paragraph.isChapterNum && chapterNumPaint != null) chapterNumPaintFontMetrics else titlePaintFontMetrics
                val r = setTypeText(
                    absStartX, durY, para, textPages, stringBuilder,
                    paint, paintHeight, paintFontMetrics,
                    floatArray,
                    isTitle = true,
                    isChapterNum = paragraph.isChapterNum,
                    forceLeftTitle = true,
                )
                absStartX = r.first
                durY = r.second
                if (textPages.last().lines.isNotEmpty()) {
                    if (paragraph.isChapterSubTitle) {
                        textPages.last().lines.last().isTitleEnd = true
                    }
                    textPages.last().lines.last().isParagraphEnd = true
                }
                stringBuilder.append("\n")
                durY += if (paragraph.isChapterNum) {
                    chapterNumPaintTextHeight * 0.20f
                } else {
                    (contentPaintTextHeight * 0.75f).coerceAtLeast(titleBottomSpacing.toFloat() * 0.5f)
                }
                flushCompletedPages()
                continue
            }

            val imgMatcher = imgPattern.find(para)
            if (imgMatcher != null) {
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

            // 流式：每处理完一个段落，flush 已完成的页
            flushCompletedPages()
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
        finalizePage(lastPage)

        textChapter.isCompleted = true
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
        // Read actual image dimensions for correct aspect ratio (ported from Legado)
        val path = src.removePrefix("file://")
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        val intrinsicW = opts.outWidth
        val intrinsicH = opts.outHeight
        var imgWidth: Int
        var imgHeight: Int
        if (intrinsicW > 0 && intrinsicH > 0) {
            imgWidth = visibleWidth
            imgHeight = (intrinsicH.toFloat() * visibleWidth / intrinsicW).roundToInt()
            if (imgHeight > visibleHeight) {
                imgWidth = (imgWidth.toFloat() * visibleHeight / imgHeight).roundToInt()
                imgHeight = visibleHeight
            }
        } else {
            // Fallback: 4:3 if dimensions unreadable
            imgWidth = visibleWidth
            imgHeight = (visibleWidth * 0.75f).toInt().coerceAtMost(visibleHeight)
        }

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
        isChapterNum: Boolean = false,
        forceLeftTitle: Boolean = false,
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
            val textLine = TextLine(isTitle = isTitle, isChapterNum = isChapterNum)
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
                lineIndex == 0 && layout.lineCount > 1 && !isTitle && isFirstLine -> {
                    textLine.text = lineText
                    addCharsToLineFirst(
                        absStartX, textLine, words, desiredWidth, widths,
                    )
                }
                lineIndex == layout.lineCount - 1 -> {
                    textLine.text = lineText
                    val startXOffset = if (
                        isTitle && !forceLeftTitle && (isMiddleTitle || emptyContent || isVolumeTitle)
                    ) {
                        ((visibleWidth - desiredWidth) / 2).coerceAtLeast(0f)
                    } else 0f
                    addCharsToLineNatural(
                        absStartX, textLine, words, startXOffset,
                        !isTitle && lineIndex == 0, widths,
                    )
                }
                else -> {
                    if (isTitle && !forceLeftTitle && (isMiddleTitle || emptyContent || isVolumeTitle)) {
                        val startXOffset = ((visibleWidth - desiredWidth) / 2).coerceAtLeast(0f)
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
        if (words.size <= 1 || residualWidth <= 0f || desiredWidth < visibleWidth * 0.65f) {
            addCharsToLineNatural(absStartX, textLine, words, startX, false, textWidths)
            return
        }
        textLine.startX = absStartX + startX
        if (spaceSize > 1) {
            if (residualWidth > visibleWidth * 0.25f) {
                addCharsToLineNatural(absStartX, textLine, words, startX, false, textWidths)
                return
            }
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

    private fun parseHtmlParagraphs(html: String): List<LayoutParagraph> {
        val markedHtml = html
            .replace(chapterNumOpenRegex, "\n$chapterTitleMarker$chapterNumMarker")
            .replace(chapterSubOpenRegex, "\n$chapterTitleMarker$chapterSubMarker")
        val text = markedHtml
            .replace(AppPattern.htmlDivCloseRegex, "\n")
            .replace(AppPattern.htmlBrRegex, "\n")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&nbsp;", " ").replace("&quot;", "\"")
        val cleaned = text.replace(nonImgTagRegex, "")
        return cleaned.lines().mapNotNull { line ->
            val trimmed = line.trim { it.code <= 0x20 || it == '\u3000' }
            if (trimmed.startsWith(chapterTitleMarker)) {
                val markedTitle = trimmed.removePrefix(chapterTitleMarker).trim()
                val isChapterNum = markedTitle.startsWith(chapterNumMarker)
                val isChapterSubTitle = markedTitle.startsWith(chapterSubMarker)
                val title = markedTitle
                    .removePrefix(chapterNumMarker)
                    .removePrefix(chapterSubMarker)
                    .trim()
                if (title.isEmpty()) null else LayoutParagraph(
                    title,
                    isChapterTitle = true,
                    isChapterNum = isChapterNum,
                    isChapterSubTitle = isChapterSubTitle,
                )
            } else {
                normalizeParagraph(line)?.let(::LayoutParagraph)
            }
        }
    }

    private fun normalizeParagraph(paragraph: String): String? {
        val trimmed = paragraph.trim { it.code <= 0x20 || it == '\u3000' }
        return if (trimmed.isEmpty()) null else paragraphIndent + trimmed
    }

    private fun isSameChapterTitle(paragraph: String, title: String): Boolean {
        val normalizedParagraph = normalizeTitleForCompare(paragraph)
        val normalizedTitle = normalizeTitleForCompare(title)
        if (normalizedParagraph.isEmpty() || normalizedTitle.isEmpty()) return false
        return normalizedParagraph == normalizedTitle ||
            normalizedParagraph.endsWith(normalizedTitle) ||
            normalizedTitle.endsWith(normalizedParagraph)
    }

    private fun normalizeTitleForCompare(value: String): String = value
        .replace(chapterTitleMarker, "")
        .replace(INDENT_CHAR, "")
        .replace(AppPattern.whitespaceRegex, "")
        .trim()

    private data class LayoutParagraph(
        val text: String,
        val isChapterTitle: Boolean = false,
        val isChapterNum: Boolean = false,
        val isChapterSubTitle: Boolean = false,
    )

    /**
     * Split a chapter title like "第一章 山边小村" into ("第一章", "山边小村").
     * If no recognizable chapter-number prefix, returns (null, fullTitle).
     */
    private fun splitChapterNumAndTitle(title: String): Pair<String?, String> {
        val trimmed = title.trim()
        // Match common chapter number patterns: 第X章, 第X节, Chapter X, etc.
        val match = chapterNumSplitRegex.find(trimmed)
        if (match != null) {
            val num = match.value.trim()
            val rest = trimmed.substring(match.range.last + 1).trim()
            return if (rest.isNotEmpty()) num to rest else null to trimmed
        }
        return null to trimmed
    }

    companion object {
        const val INDENT_CHAR = "\u3000"
        val IMG_PATTERN = AppPattern.imgSrcPattern
        private val nonImgTagRegex = Regex("<(?!img)[^>]+>", RegexOption.IGNORE_CASE)
        private const val chapterTitleMarker = "__MOREALM_CHAPTER_TITLE__"
        private const val chapterNumMarker = "__MOREALM_CHAPTER_NUM__"
        private const val chapterSubMarker = "__MOREALM_CHAPTER_SUB__"
        private val chapterNumOpenRegex = Regex("<div\\s+class=[\"']chapter-num[\"']\\s*>", RegexOption.IGNORE_CASE)
        private val chapterSubOpenRegex = Regex("<div\\s+class=[\"']chapter-sub[\"']\\s*>", RegexOption.IGNORE_CASE)
        private val chapterNumSplitRegex = Regex(
            """^(第[零一二三四五六七八九十百千万亿\d]+[章节卷集部篇回话幕折场]|[Cc]hapter\s+\d+|[Vv]ol(?:ume)?\s*\.?\s*\d+|序[章言]|终章|尾声|楔子|番外|引[子章]|\d+[.、]\s*)""",
        )
    }
}

/**
 * 异步排版句柄。
 * - textChapter: 排版结果（页面会逐步添加）
 * - channel: 流式接收已排完的页面
 * - job: 排版协程，可用于取消
 */
class AsyncLayoutHandle(
    val textChapter: TextChapter,
    val channel: Channel<TextPage>,
    val job: Job,
) {
    fun cancel() {
        job.cancel()
        channel.close()
    }
}

// ── Extension: TextPaint.textHeight ──

val TextPaint.textHeight: Float
    get() = fontMetrics.let { it.descent - it.ascent }
