package com.morealm.app.domain.render

import android.graphics.Paint

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
    companion object {
        val EMPTY = TextPos(0, 0, 0)
    }
}

enum class PageDirection { NONE, PREV, NEXT }

// ── Column hierarchy ──

sealed interface BaseColumn {
    var start: Float
    var end: Float
    fun isTouch(x: Float): Boolean = x in start..end
}

data class TextColumn(
    var charData: String,
    override var start: Float,
    override var end: Float,
    var selected: Boolean = false,
    var isSearchResult: Boolean = false,
) : BaseColumn

data class ImageColumn(
    override var start: Float,
    override var end: Float,
    val src: String,
) : BaseColumn

// ── TextLine ──

class TextLine(
    var text: String = "",
    var isTitle: Boolean = false,
    var isImage: Boolean = false,
    var isReadAloud: Boolean = false,
    var isParagraphEnd: Boolean = false,
    var isLeftLine: Boolean = true,
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
        if (it is TextColumn) it.charData.length else 0
    }

    val lineSize: Int get() = columns.size

    fun addColumn(column: BaseColumn) {
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

    var text: String = ""
    var height: Float = 0f
    var leftLineSize: Int = 0
    var textChapter: TextChapter? = null

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
        lines.forEach { it.isReadAloud = false }
        return this
    }

    fun format(): TextPage {
        if (text.isNotEmpty() && lines.isEmpty()) {
            // Simple text-only page (e.g., error message)
            val line = TextLine(text = text)
            line.lineTop = paddingTop.toFloat()
            line.lineBase = paddingTop + 30f
            line.lineBottom = paddingTop + 36f
            line.addColumn(TextColumn(charData = text, start = 16f, end = 300f))
            lines.add(line)
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
            val colStart = if (li == startLine) startCol else 0
            val colEnd = if (li == endLine) endCol else line.columns.lastIndex
            for (ci in colStart..colEnd.coerceAtMost(line.columns.lastIndex)) {
                val col = line.columns[ci]
                if (col is TextColumn) sb.append(col.charData)
            }
            if (li < endLine && line.isParagraphEnd) sb.append('\n')
        }
        return sb.toString()
    }
}

// ── TextParagraph ──

data class TextParagraph(
    var num: Int = 0,
) {
    val textLines = arrayListOf<TextLine>()

    val chapterPosition: Int get() = textLines.firstOrNull()?.chapterPosition ?: 0

    val chapterIndices: IntRange get() {
        val first = textLines.firstOrNull()?.chapterPosition ?: 0
        val last = textLines.lastOrNull()?.let { it.chapterPosition + it.charSize } ?: 0
        return first..last
    }
}

// ── TextChapter ──

class TextChapter(
    val chapterIndex: Int,
    val title: String,
    val chaptersSize: Int,
) {
    private val textPages = arrayListOf<TextPage>()
    val pages: List<TextPage> get() = textPages

    var isCompleted = false

    fun addPage(page: TextPage) {
        textPages.add(page)
    }

    fun getPage(index: Int): TextPage? = pages.getOrNull(index)

    val lastPage: TextPage? get() = pages.lastOrNull()
    val lastIndex: Int get() = pages.lastIndex
    val pageSize: Int get() = pages.size

    fun isLastIndex(index: Int): Boolean = isCompleted && index >= pages.size - 1

    fun getReadLength(pageIndex: Int): Int {
        if (pageIndex < 0) return 0
        return pages[pageIndex.coerceAtMost(lastIndex)].chapterPosition
    }

    fun getPageIndexByCharIndex(charIndex: Int): Int {
        if (pages.isEmpty()) return -1
        for (i in pages.indices) {
            val page = pages[i]
            val pageEnd = page.chapterPosition + page.charSize
            if (charIndex < pageEnd) return i
        }
        return pages.lastIndex
    }

    fun getContent(): String {
        return pages.joinToString("") { it.text }
    }

    fun getUnRead(pageIndex: Int): String {
        val sb = StringBuilder()
        for (i in pageIndex..pages.lastIndex) {
            sb.append(pages[i].text)
        }
        return sb.toString()
    }

    fun getNeedReadAloud(pageIndex: Int, startPos: Int = 0): String {
        val sb = StringBuilder()
        for (i in pageIndex..pages.lastIndex) {
            sb.append(pages[i].text)
        }
        return if (startPos < sb.length) sb.substring(startPos) else ""
    }

    val paragraphs: List<TextParagraph> by lazy {
        val result = arrayListOf<TextParagraph>()
        for (page in pages) {
            for (line in page.lines) {
                if (line.paragraphNum <= 0) continue
                while (result.size < line.paragraphNum) {
                    result.add(TextParagraph(result.size + 1))
                }
                result[line.paragraphNum - 1].textLines.add(line)
            }
        }
        result
    }

    companion object {
        val EMPTY = TextChapter(-1, "", 0).apply { isCompleted = true }
    }
}

// ── PageImage (for backward compat with simple image rendering) ──

data class PageImage(
    val path: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)
