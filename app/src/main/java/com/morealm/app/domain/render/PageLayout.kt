package com.morealm.app.domain.render

import android.graphics.Paint
import com.morealm.app.domain.render.canvasrecorder.CanvasRecorder
import com.morealm.app.domain.render.canvasrecorder.CanvasRecorderFactory

/**
 * Core data models for the Canvas reading system.
 * All models are in the domain layer — no Compose/UI imports.
 *
 * Hierarchy: TextChapter → TextPage → TextLine → BaseColumn (TextColumn | ImageColumn)
 */

// ── Position tracking ──

data class TextPos(
    val relativePagePos: Int,
    val lineIndex: Int,
    val columnIndex: Int,
) {
    fun compare(pos: TextPos): Int {
        return when {
            relativePagePos < pos.relativePagePos -> -3
            relativePagePos > pos.relativePagePos -> 3
            lineIndex < pos.lineIndex -> -2
            lineIndex > pos.lineIndex -> 2
            columnIndex < pos.columnIndex -> -1
            columnIndex > pos.columnIndex -> 1
            else -> 0
        }
    }

    fun compare(relativePos: Int, lineIndex: Int, columnIndex: Int): Int {
        return when {
            relativePagePos < relativePos -> -3
            relativePagePos > relativePos -> 3
            this.lineIndex < lineIndex -> -2
            this.lineIndex > lineIndex -> 2
            this.columnIndex < columnIndex -> -1
            this.columnIndex > columnIndex -> 1
            else -> 0
        }
    }

    fun isSelected(): Boolean = lineIndex >= 0 && columnIndex >= 0

    companion object {
        val EMPTY = TextPos(0, -1, -1)
    }
}

enum class PageDirection { NONE, PREV, NEXT }

// ── Column hierarchy ──

sealed interface BaseColumn {
    var start: Float
    var end: Float
    var textLine: TextLine?
    fun isTouch(x: Float): Boolean = x > start && x < end
}

sealed interface TextBaseColumn : BaseColumn {
    val charData: String
    var selected: Boolean
    var isSearchResult: Boolean
}

data class TextColumn(
    override var charData: String,
    override var start: Float,
    override var end: Float,
    override var selected: Boolean = false,
    override var isSearchResult: Boolean = false,
    override var textLine: TextLine? = null,
) : TextBaseColumn

data class TextHtmlColumn(
    override var start: Float,
    override var end: Float,
    override val charData: String,
    val textSize: Float,
    val textColor: Int?,
    val linkUrl: String?,
    override var selected: Boolean = false,
    override var isSearchResult: Boolean = false,
    override var textLine: TextLine? = null,
) : TextBaseColumn

data class ImageColumn(
    override var start: Float,
    override var end: Float,
    val src: String,
    val height: Float = 0f,
    val width: Float = end - start,
    val click: String? = null,
    override var textLine: TextLine? = null,
) : BaseColumn {
    override fun isTouch(x: Float): Boolean = x > start && x < end + 20f
}

data class ButtonColumn(
    override var start: Float,
    override var end: Float,
    override var textLine: TextLine? = null,
) : BaseColumn

data class ReviewColumn(
    override var start: Float,
    override var end: Float,
    val count: Int = 0,
    override var textLine: TextLine? = null,
) : BaseColumn {
    val countText: String get() = if (count > 999) "999" else count.toString()
}

// ── TextLine ──

class TextLine(
    var text: String = "",
    var isTitle: Boolean = false,
    var isImage: Boolean = false,
    var isReadAloud: Boolean = false,
    var isParagraphEnd: Boolean = false,
    var isLeftLine: Boolean = true,
    /** True for the chapter-number line (e.g. "第一章"), drawn with chapterNumPaint. */
    var isChapterNum: Boolean = false,
    /** True for the last title line — triggers the decorative accent bar below it. */
    var isTitleEnd: Boolean = false,
) {
    val columns = arrayListOf<BaseColumn>()

    var lineTop: Float = 0f
    var lineBase: Float = 0f
    var lineBottom: Float = 0f

    var paragraphNum: Int = 0
    var chapterPosition: Int = 0
    var pagePosition: Int = 0

    var startX: Float = 0f
    var indentWidth: Float = 0f
    var indentSize: Int = 0
    var wordSpacing: Float = 0f
    var extraLetterSpacing: Float = 0f
    var extraLetterSpacingOffsetX: Float = 0f
    var exceed: Boolean = false

    val charSize: Int get() = columns.sumOf {
        if (it is TextBaseColumn) it.charData.length else 0
    }

    val lineSize: Int get() = columns.size

    fun addColumn(column: BaseColumn) {
        column.textLine = this
        columns.add(column)
    }

    fun getColumn(index: Int): BaseColumn = columns[index]

    fun getColumnReverseAt(index: Int, offset: Int = 0): BaseColumn {
        return columns[columns.lastIndex - offset - index]
    }

    fun upTopBottom(durY: Float, textHeight: Float, fontMetrics: Paint.FontMetrics) {
        lineTop = durY
        lineBase = durY + textHeight - fontMetrics.descent
        lineBottom = durY + textHeight
    }

    fun isTouchY(y: Float): Boolean = y in lineTop..lineBottom

    fun columnAtX(x: Float): Int {
        for (i in columns.indices) {
            if (columns[i].isTouch(x)) return i
        }
        return -1
    }

    fun isVisible(relativeOffset: Int, visibleHeight: Int, paddingTop: Int): Boolean {
        val top = lineTop + paddingTop + relativeOffset
        val bottom = lineBottom + paddingTop + relativeOffset
        return bottom > 0 && top < visibleHeight
    }
}

// ── TextPage ──

class TextPage(
    var index: Int = 0,
    var title: String = "",
    var chapterIndex: Int = 0,
    var chapterSize: Int = 0,
    var doublePage: Boolean = false,
    var paddingTop: Int = 0,
    var isCompleted: Boolean = false,
) {
    val lines = arrayListOf<TextLine>()
    val searchResult = arrayListOf<TextColumn>()

    /** 页面级绘制录制器 — 录制一次，后续帧直接回放，避免重复绘制。 */
    val canvasRecorder: CanvasRecorder = CanvasRecorderFactory.create(locked = true)

    var text: String = ""
    var height: Float = 0f
    var leftLineSize: Int = 0
    var textChapter: TextChapter? = null
    var hasReadAloudSpan: Boolean = false

    val lineSize: Int get() = lines.size

    val charSize: Int get() {
        var size = 0
        lines.forEach { size += it.charSize + if (it.isParagraphEnd) 1 else 0 }
        return size
    }

    val chapterPosition: Int get() {
        return lines.firstOrNull()?.chapterPosition ?: 0
    }

    val pageSize: Int get() = textChapter?.pageSize ?: 0

    val readProgress: String get() {
        val chapter = textChapter ?: return ""
        val chapterSize = chapter.chaptersSize
        if (chapterSize <= 0) return ""
        val percent = if (chapterSize > 0) {
            (chapterIndex + (index + 1f) / pageSize.coerceAtLeast(1)) / chapterSize * 100
        } else 0f
        return "%.1f%%".format(percent)
    }

    fun addLine(line: TextLine) {
        lines.add(line)
    }

    fun getLine(index: Int): TextLine = lines[index]

    /**
     * Update line positions for bottom alignment.
     * Adjusts lineTop/lineBase/lineBottom so content is bottom-aligned on the page.
     */
    fun upLinesPosition() {
        if (lines.isEmpty()) return
        if (!doublePage) {
            val lastLine = lines.last()
            if (lastLine.lineBottom < height) {
                val offset = (height - lastLine.lineBottom) / 2
                lines.forEach {
                    it.lineTop += offset
                    it.lineBase += offset
                    it.lineBottom += offset
                }
            }
        }
    }

    fun upRenderHeight() {
        if (lines.isEmpty()) return
        height = lines.last().lineBottom
    }

    fun removePageAloudSpan(): TextPage {
        if (!hasReadAloudSpan && lines.none { it.isReadAloud }) return this
        hasReadAloudSpan = false
        lines.forEach { it.isReadAloud = false }
        return this
    }

    /** Ported from Legado TextPage.upPageAloudSpan. */
    fun upPageAloudSpan(aloudSpanStart: Int) {
        removePageAloudSpan()
        hasReadAloudSpan = true
        var lineStart = 0
        for (index in lines.indices) {
            val textLine = lines[index]
            val lineLength = textLine.text.length + if (textLine.isParagraphEnd) 1 else 0
            if (aloudSpanStart >= lineStart && aloudSpanStart < lineStart + lineLength) {
                for (i in index - 1 downTo 0) {
                    if (lines[i].isParagraphEnd) break else lines[i].isReadAloud = true
                }
                for (i in index until lines.size) {
                    lines[i].isReadAloud = true
                    if (lines[i].isParagraphEnd) break
                }
                break
            }
            lineStart += lineLength
        }
    }

    /** Group lines into paragraphs by paragraphNum (ported from Legado TextPage.paragraphs). */
    val paragraphs: List<TextParagraph> by lazy {
        val result = arrayListOf<TextParagraph>()
        val filtered = lines.filter { it.paragraphNum > 0 }
        if (filtered.isEmpty()) return@lazy result
        val offset = filtered.first().paragraphNum - 1
        for (line in filtered) {
            val idx = line.paragraphNum - offset - 1
            while (result.size <= idx) {
                result.add(TextParagraph(result.size))
            }
            result[idx].textLines.add(line)
        }
        result
    }

    fun format(): TextPage {
        val message = text.ifBlank { title }.ifBlank { "加载中..." }
        if (lines.isEmpty()) {
            // Legado treats line-less TextPage instances as message pages. MoRealm
            // must do the same so chapter-boundary/loading placeholders never draw
            // as a pure white page during fast page turns.
            text = message
            val line = TextLine(text = message, isTitle = title.isNotBlank())
            line.lineTop = paddingTop.toFloat()
            line.lineBase = paddingTop + 30f
            line.lineBottom = paddingTop + 36f
            line.addColumn(TextColumn(charData = message, start = 16f, end = 300f))
            lines.add(line)
            height = line.lineBottom
            isCompleted = true
        }
        return this
    }

    /**
     * Get text content between two positions on this page.
     */
    fun getTextBetween(startLine: Int, startCol: Int, endLine: Int, endCol: Int): String {
        val sb = StringBuilder()
        for (li in startLine..endLine.coerceAtMost(lines.lastIndex)) {
            val line = lines[li]
            val colStart = if (li == startLine) startCol.coerceAtLeast(0) else 0
            val colEnd = if (li == endLine) endCol.coerceAtMost(line.columns.lastIndex) else line.columns.lastIndex
            for (ci in colStart..colEnd.coerceAtMost(line.columns.lastIndex)) {
                val col = line.columns[ci]
                if (col is TextBaseColumn) sb.append(col.charData)
            }
            if (li < endLine && line.isParagraphEnd) sb.append('\n')
        }
        return sb.toString()
    }

    /** Ported from Legado TextPage.getPosByLineColumn. */
    fun getPosByLineColumn(lineIndex: Int, columnIndex: Int): Int {
        if (lines.isEmpty()) return 0
        var length = 0
        val maxIndex = lineIndex.coerceIn(0, lines.lastIndex)
        for (index in 0 until maxIndex) {
            length += lines[index].charSize
            if (lines[index].isParagraphEnd) length++
        }
        val columns = lines[maxIndex].columns
        for (index in 0 until columnIndex.coerceIn(0, columns.size)) {
            val column = columns[index]
            if (column is TextBaseColumn) {
                length += column.charData.length
            }
        }
        return length
    }
}

// ── TextParagraph ──

data class TextParagraph(
    var num: Int = 0,
) {
    val textLines = arrayListOf<TextLine>()

    val text: String get() = textLines.joinToString("") { it.text }
    val length: Int get() = text.length
    val firstLine: TextLine get() = textLines.first()
    val lastLine: TextLine get() = textLines.last()
    val chapterPosition: Int get() = textLines.firstOrNull()?.chapterPosition ?: 0
    val isParagraphEnd: Boolean get() = textLines.lastOrNull()?.isParagraphEnd == true

    val chapterIndices: IntRange get() {
        val first = textLines.firstOrNull()?.chapterPosition ?: 0
        val last = textLines.lastOrNull()?.let { it.chapterPosition + it.charSize } ?: 0
        return first..last
    }
}

data class SearchSelectionRange(
    val pageIndex: Int,
    val start: TextPos,
    val end: TextPos,
)

// ── TextChapter ──

class TextChapter(
    val chapterIndex: Int,
    val title: String,
    val chaptersSize: Int,
) {
    private val pageLock = Any()
    private val textPages = arrayListOf<TextPage>()
    val pages: List<TextPage> get() = snapshotPages()

    @Volatile
    var isCompleted = false

    fun snapshotPages(): List<TextPage> {
        return synchronized(pageLock) { textPages.toList() }
    }

    fun addPage(page: TextPage) {
        synchronized(pageLock) { textPages.add(page) }
    }

    fun getPage(index: Int): TextPage? {
        return synchronized(pageLock) { textPages.getOrNull(index) }
    }

    val lastPage: TextPage? get() = synchronized(pageLock) { textPages.lastOrNull() }
    val lastIndex: Int get() = synchronized(pageLock) { textPages.lastIndex }
    val pageSize: Int get() = synchronized(pageLock) { textPages.size }

    fun isLastIndex(index: Int): Boolean = isCompleted && index >= pageSize - 1

    fun getReadLength(pageIndex: Int): Int {
        if (pageIndex < 0) return 0
        val snapshot = snapshotPages()
        if (snapshot.isEmpty()) return 0
        return snapshot[pageIndex.coerceAtMost(snapshot.lastIndex)].chapterPosition
    }

    fun getPageIndexByCharIndex(charIndex: Int): Int {
        val snapshot = snapshotPages()
        if (snapshot.isEmpty()) return -1
        for (i in snapshot.indices) {
            val page = snapshot[i]
            val pageEnd = page.chapterPosition + page.charSize
            if (charIndex < pageEnd) return i
        }
        return snapshot.lastIndex
    }

    fun getContent(): String {
        return snapshotPages().joinToString("") { it.text }
    }

    fun getUnRead(pageIndex: Int): String {
        val sb = StringBuilder()
        val snapshot = snapshotPages()
        for (i in pageIndex.coerceAtLeast(0)..snapshot.lastIndex) {
            sb.append(snapshot[i].text)
        }
        return sb.toString()
    }

    fun getNeedReadAloud(pageIndex: Int, startPos: Int = 0): String {
        val sb = StringBuilder()
        val snapshot = snapshotPages()
        for (i in pageIndex.coerceAtLeast(0)..snapshot.lastIndex) {
            sb.append(snapshot[i].text)
        }
        return if (startPos < sb.length) sb.substring(startPos) else ""
    }

    /** Ported from Legado TextChapter.getNeedReadAloud(pageSplit). */
    fun getNeedReadAloud(
        pageIndex: Int,
        pageSplit: Boolean,
        startPos: Int,
        pageEndIndex: Int = pageSize - 1,
    ): String {
        val sb = StringBuilder()
        val snapshot = snapshotPages()
        if (snapshot.isNotEmpty()) {
            for (index in pageIndex.coerceAtLeast(0)..minOf(pageEndIndex, snapshot.lastIndex)) {
                sb.append(snapshot[index].text.replace(Regex("[袮꧁]"), " "))
                if (pageSplit && !sb.endsWith("\n")) sb.append("\n")
            }
        }
        return if (startPos < sb.length) sb.substring(startPos) else ""
    }

    fun getParagraphs(pageSplit: Boolean): List<TextParagraph> {
        return if (pageSplit) {
            if (isCompleted) pageParagraphs else pageParagraphsInternal
        } else {
            if (isCompleted) paragraphs else paragraphsInternal
        }
    }

    fun getParagraphNum(position: Int, pageSplit: Boolean): Int {
        val paragraphs = getParagraphs(pageSplit)
        paragraphs.forEach { paragraph ->
            if (position in paragraph.chapterIndices) return paragraph.num
        }
        return -1
    }

    fun getLastParagraphPosition(): Int {
        return pageParagraphs.lastOrNull()?.chapterPosition ?: 0
    }

    /** Ported from Legado ReadBookViewModel.searchResultPositions. */
    fun searchSelectionRange(contentPosition: Int, queryLength: Int): SearchSelectionRange? {
        val snapshot = snapshotPages()
        if (contentPosition < 0 || queryLength <= 0 || snapshot.isEmpty()) return null
        var pageIndex = 0
        var length = snapshot[pageIndex].text.length
        while (length < contentPosition && pageIndex + 1 < snapshot.size) {
            pageIndex += 1
            length += snapshot[pageIndex].text.length
        }
        val currentPage = snapshot.getOrNull(pageIndex) ?: return null
        if (currentPage.lines.isEmpty()) return null
        var lineIndex = 0
        var currentLine = currentPage.lines[lineIndex]
        length = length - currentPage.text.length + currentLine.text.length
        if (currentLine.isParagraphEnd) length++
        while (length <= contentPosition && lineIndex + 1 < currentPage.lines.size) {
            lineIndex += 1
            currentLine = currentPage.lines[lineIndex]
            length += currentLine.text.length
            if (currentLine.isParagraphEnd) length++
        }
        var currentLineLength = currentLine.text.length
        if (currentLine.isParagraphEnd) currentLineLength++
        length -= currentLineLength
        val charIndex = contentPosition - length
        var addLine = 0
        var charIndex2 = 0
        if (charIndex + queryLength > currentLineLength) {
            addLine = 1
            charIndex2 = charIndex + queryLength - currentLineLength - 1
        }
        if (lineIndex + addLine + 1 > currentPage.lines.size) {
            addLine = -1
            charIndex2 = charIndex + queryLength - currentLineLength - 1
        }
        val start = TextPos(0, lineIndex, charIndex)
        val end = when (addLine) {
            0 -> TextPos(0, lineIndex, charIndex + queryLength - 1)
            1 -> TextPos(0, lineIndex + 1, charIndex2)
            -1 -> TextPos(1, 0, charIndex2)
            else -> TextPos(0, lineIndex, charIndex)
        }
        return SearchSelectionRange(pageIndex, start, end)
    }

    val paragraphs: List<TextParagraph> get() = if (isCompleted) completedParagraphs else paragraphsInternal

    val pageParagraphs: List<TextParagraph> get() = if (isCompleted) completedPageParagraphs else pageParagraphsInternal

    private val completedParagraphs: List<TextParagraph> by lazy { paragraphsInternal }

    private val completedPageParagraphs: List<TextParagraph> by lazy { pageParagraphsInternal }

    private val paragraphsInternal: List<TextParagraph>
        get() {
            val result = arrayListOf<TextParagraph>()
            for (page in snapshotPages()) {
                for (line in page.lines) {
                    if (line.paragraphNum <= 0) continue
                    while (result.size < line.paragraphNum) {
                        result.add(TextParagraph(result.size + 1))
                    }
                    result[line.paragraphNum - 1].textLines.add(line)
                }
            }
            return result
        }

    private val pageParagraphsInternal: List<TextParagraph>
        get() {
            val result = arrayListOf<TextParagraph>()
            for (page in snapshotPages()) {
                result.addAll(page.paragraphs)
            }
            for (index in result.indices) {
                result[index].num = index + 1
            }
            return result
        }

    companion object {
        val EMPTY = TextChapter(-1, "", 0).apply { isCompleted = true }
    }
}

// ── AsyncLayoutHandle ──

// ── PageImage (for backward compat with simple image rendering) ──

data class PageImage(
    val path: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)
