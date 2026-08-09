package com.morealm.app.ui.reader.renderer.scroll

import com.morealm.epub.render.ScrollChapterLayout
import com.morealm.epub.render.findColumnByPixel
import java.text.BreakIterator
import java.util.Locale

/**
 * 选区手势纯函数 —— 长按 → 选词起；drag handle → 移动端点。
 *
 * 抽出纯函数便于单测；UI 层（[ScrollSelectionOverlay]）的 pointerInput
 * 回调内调用这些函数 + 调 setState 触发重组。
 */

/** 选区 handle 端点标识。 */
enum class ScrollHandleSide {
    /** 选区起点 handle（视觉上：左侧 / 较小 cp 端）。 */
    START,
    /** 选区末点 handle（视觉上：右侧 / 较大 cp 端）。 */
    END,
}

/**
 * 长按选词：把屏幕坐标 (x, yInChapter) 转成**按词**选区。
 *
 * 命中字符 column → 选区扩到该字所在词的边界（见 [expandToWordRange]）；
 * 命中空段 / 图片段 line → 选区为该 line.firstChapterPos（视觉是整行选中）；
 * 未命中 → 返 [ScrollSelectionState.Empty]（不激活，取消现有选区）。
 *
 * 分词与翻页路径 [com.morealm.app.ui.reader.renderer.findWordRange] 同源（都走
 * [BreakIterator]），两条渲染路径的长按手感因此一致。
 *
 * @param chapterIndex 当前章 idx（必须匹配 layout）
 */
fun handleLongPress(
    layout: ScrollChapterLayout,
    chapterIndex: Int,
    x: Float,
    yInChapter: Float,
    /**
     * 用户长按 tap 点在 reader Box 本地坐标系下的位置 —— 给 SelectionToolbar 当
     * anchor，让 popup / 箭头始终指向用户最初按下的位置（而非选区末端）。
     * 默认 Offset.Zero 保持单测兼容；UI 路径必须传真实坐标（view-local）。
     */
    anchorInBox: androidx.compose.ui.geometry.Offset = androidx.compose.ui.geometry.Offset.Zero,
): ScrollSelectionState {
    if (chapterIndex != layout.chapterIndex) {
        com.morealm.app.core.log.AppLog.warn(
            "ScrollSelection",
            "handleLongPress mismatched chapter: chapterIdx=$chapterIndex layout.idx=${layout.chapterIndex}",
        )
        return ScrollSelectionState.Empty
    }
    val hit = layout.findColumnByPixel(x, yInChapter)
    if (hit == null) {
        com.morealm.app.core.log.AppLog.warn(
            "ScrollSelection",
            "handleLongPress findColumnByPixel MISS x=$x yInChapter=$yInChapter chapterIdx=$chapterIndex",
        )
        return ScrollSelectionState.Empty
    }
    val cp = hit.column?.chapterPosition ?: hit.line.firstChapterPos
    // 命中真实字符才分词；空段 / 图片段（column == null）保持整行单 cp 语义。
    val range = if (hit.column != null) expandToWordRange(layout, cp) else cp..cp
    com.morealm.app.core.log.AppLog.info(
        "ScrollSelection",
        "handleLongPress HIT input x=$x yInChapter=$yInChapter anchorInBox=$anchorInBox " +
            "→ cp=$cp word=$range hit.column=${hit.column?.let { "start=${it.start} end=${it.end} char='${it.charData}'" }} " +
            "hit.line.lineTop=${hit.line.lineTop} hit.line.lineBottom=${hit.line.lineBottom}",
    )
    return ScrollSelectionState(
        chapterIndex = chapterIndex,
        startCp = range.first,
        endCp = range.last,
        isActive = true,
        anchorInBox = anchorInBox,
    )
}

/**
 * 把单个 cp 扩到它所在**词**的 cp 范围。
 *
 * 为什么要有它：滚动路径长按此前恒返回单字符选区（`startCp == endCp`），用户长按
 * 一个词只选中其中一个字，必须再拖游标才能凑齐——参照阅读器与我们自己的翻页路径
 * 都是长按即选词。
 *
 * 算法与翻页路径 [com.morealm.app.ui.reader.renderer.findWordRange] 一致：
 *  1. 取 cp 所在**整段**（同 [com.morealm.epub.render.ScrollLine.paragraphNum] 的连续行）
 *     的文本——不能只取所在行，否则跨行折断的词会被行尾切开；
 *  2. 用 [BreakIterator] 按当前 locale 切词，取覆盖 cp 的那一段。
 *
 * 边界处理：
 *  - 段文本为空 / 反查不到 cp / BreakIterator 未覆盖 → 退回单字符 `cp..cp`（不比现状差）；
 *  - 切出来的段是纯空白 → 同样退回单字符，避免长按空格选中一片看不见的东西。
 *
 * 复杂度 O(段内字符数)，每次长按跑一次。
 */
internal fun expandToWordRange(layout: ScrollChapterLayout, cp: Int): IntRange {
    // 1. 定位 cp 所在行，拿段号
    var paragraphNum = -1
    outer@ for (page in layout.pages) {
        for (line in page.lines) {
            if (line.containsChapterPos(cp)) {
                paragraphNum = line.paragraphNum
                break@outer
            }
            if (line.firstChapterPos > cp) break@outer
        }
    }
    if (paragraphNum < 0) return cp..cp

    // 2. 拼整段文本，同时记下每个 char 对应的 cp（column 可能承载多字符，
    //    其所有字符统一映射到该 column 的 cp——与翻页路径的 charMap 同语义）
    val sb = StringBuilder()
    val cpOfChar = ArrayList<Int>()
    var tapCharIndex = -1
    collect@ for (page in layout.pages) {
        for (line in page.lines) {
            if (line.paragraphNum != paragraphNum) {
                // 段是连续的：已经收过又遇到别的段号，说明本段结束
                if (sb.isNotEmpty()) break@collect
                continue
            }
            for (col in line.columns) {
                if (col.charData.isEmpty()) continue
                if (col.chapterPosition == cp) tapCharIndex = sb.length
                for (ch in col.charData) {
                    sb.append(ch)
                    cpOfChar.add(col.chapterPosition)
                }
            }
        }
    }
    if (sb.isEmpty() || tapCharIndex < 0) return cp..cp

    // 3. 切词
    val boundary = BreakIterator.getWordInstance(Locale.getDefault())
    boundary.setText(sb.toString())
    var start = boundary.first()
    var end = boundary.next()
    while (end != BreakIterator.DONE) {
        if (tapCharIndex in start until end) {
            if (sb.substring(start, end).isBlank()) return cp..cp
            return cpOfChar[start]..cpOfChar[end - 1]
        }
        start = end
        end = boundary.next()
    }
    return cp..cp
}

/**
 * Drag handle：移动选区某端点到屏幕坐标 (x, yInChapter) 命中的 cp。
 *
 * 选区 inactive 或坐标越界时不动 selection（返原值）。
 *
 * @param side START → 更新 selection.startCp；END → 更新 selection.endCp
 */
fun handleHandleDrag(
    selection: ScrollSelectionState,
    layout: ScrollChapterLayout,
    side: ScrollHandleSide,
    x: Float,
    yInChapter: Float,
): ScrollSelectionState {
    if (!selection.isActive) return selection
    if (selection.chapterIndex != layout.chapterIndex) return selection
    val hit = layout.findColumnByPixel(x, yInChapter) ?: return selection
    val cp = hit.column?.chapterPosition ?: hit.line.firstChapterPos
    return when (side) {
        ScrollHandleSide.START -> selection.copy(startCp = cp)
        ScrollHandleSide.END -> selection.copy(endCp = cp)
    }
}

/** 取消选区（点击空白处）。 */
fun handleCancelSelection(): ScrollSelectionState = ScrollSelectionState.Empty
