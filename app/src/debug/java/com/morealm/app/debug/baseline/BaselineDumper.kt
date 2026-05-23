package com.morealm.app.debug.baseline

import com.morealm.app.domain.render.layout.Atom
import com.morealm.app.domain.render.layout.InlineImage
import com.morealm.app.domain.render.layout.ScrollLine
import com.morealm.app.domain.render.layout.ScrollLineCell
import com.morealm.app.domain.render.layout.TextRun
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * **R1.3.5 Baseline 锁定工具** —— 把 [ScrollLine] 序列化成 JSON 用于 baseline diff。
 *
 * 仅在 debug build 编译（位于 app/src/debug/），release APK 不含本类。设计目标：
 *
 *  1. **fields enumerate**：把 layout 算法的所有几何输出字段（lineTop/lineBottom/cell.contentLeft/
 *     atom.cellLocalX/Y/baseline 等）逐一序列化，让任何字段变化都能在 JSON diff 中捕获。
 *  2. **diff 友好**：用 [kotlinx.serialization] + prettyPrint，每字段独立行让 git/text diff
 *     工具能精确定位"哪条 line 的哪个 atom 的哪个字段变了"。
 *  3. **decouple production**：不污染主仓 [ScrollLine] / [Atom] / [ScrollLineCell] —— 不加
 *     `@Serializable` 注解到 production 数据类，本文件单独定义 dump-only data class。
 *  4. **stable schema**：dump 字段名按 [BaselineLineDump] / [BaselineAtomDump] / [BaselineCellDump]
 *     稳定（schema 改动等同于 baseline 失效要重新 lock）。
 *
 * 使用流程（详 scripts/baseline/README.md）：
 *   1. baseline lock 阶段：装机翻关键页 → adb 触发 dump (或临时改 reader 自动 dump) →
 *      `/sdcard/morealm_baseline/<book>/<chapter>.json` → adb pull → freeze 入 testdata/baselines/
 *   2. 后续 commit：装机重 dump → diff vs baseline JSON → 任何字段 diff 失败 = regression
 */
@Serializable
public data class BaselineLineDump(
    val lineTop: Float,
    val lineBottom: Float,
    val paragraphNum: Int,
    val isTitle: Boolean,
    val isChapterNum: Boolean,
    val isTitleEnd: Boolean,
    val isImage: Boolean,
    val imageSrc: String? = null,
    val text: String,
    val firstChapterPos: Int,
    val lastChapterPos: Int,
    val headingLevel: Int,
    val columns: List<BaselineColumnDump>,
    val atoms: List<BaselineAtomDump>? = null,
    val cells: List<BaselineCellDump>? = null,
)

@Serializable
public data class BaselineColumnDump(
    val char: String,
    val start: Float,
    val end: Float,
    val cp: Int,
    val colorArgb: Int? = null,
)

@Serializable
public data class BaselineAtomDump(
    val type: String, // "text" / "image"
    val text: String? = null,
    val src: String? = null,
    val colorArgb: Int? = null,
    val sizeScale: Float = 1f,
    val width: Float,
    val height: Float,
    val baseline: Float,
    val cellLocalX: Float = 0f,
    val cellLocalY: Float = 0f,
)

@Serializable
public data class BaselineCellDump(
    val contentTop: Float,
    val contentLeft: Float,
    val contentWidth: Float,
    val contentHeight: Float,
    val padding: Float = 0f,
    val atoms: List<BaselineAtomDump>,
    val backgroundColor: Int? = null,
    val borderRadiusPx: Float = 0f,
)

/** Wrap top-level: page metadata + lines array. */
@Serializable
public data class BaselinePageDump(
    val pageIndex: Int,
    val lines: List<BaselineLineDump>,
)

@Serializable
public data class BaselineChapterDump(
    val bookName: String,
    val chapterIndex: Int,
    val chapterTitle: String,
    val viewWidth: Int,
    val viewHeight: Int,
    val pages: List<BaselinePageDump>,
)

public object BaselineDumper {
    private val jsonFmt: Json = Json {
        prettyPrint = true
        encodeDefaults = false
    }

    /**
     * Dump a chapter's [pages] (list of pages, each page = list of [ScrollLine]) to JSON.
     */
    public fun dumpChapter(
        bookName: String,
        chapterIndex: Int,
        chapterTitle: String,
        viewWidth: Int,
        viewHeight: Int,
        pages: List<List<ScrollLine>>,
    ): String {
        val pageDumps = pages.mapIndexed { idx, lines ->
            BaselinePageDump(pageIndex = idx, lines = lines.map { lineToDump(it) })
        }
        val chapterDump = BaselineChapterDump(
            bookName = bookName,
            chapterIndex = chapterIndex,
            chapterTitle = chapterTitle,
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            pages = pageDumps,
        )
        return jsonFmt.encodeToString(chapterDump)
    }

    private fun lineToDump(line: ScrollLine): BaselineLineDump {
        return BaselineLineDump(
            lineTop = line.lineTop,
            lineBottom = line.lineBottom,
            paragraphNum = line.paragraphNum,
            isTitle = line.isTitle,
            isChapterNum = line.isChapterNum,
            isTitleEnd = line.isTitleEnd,
            isImage = line.isImage,
            imageSrc = line.imageSrc,
            text = line.text,
            firstChapterPos = line.firstChapterPos,
            lastChapterPos = line.lastChapterPos,
            headingLevel = line.headingLevel,
            columns = line.columns.map { col ->
                BaselineColumnDump(
                    char = col.charData,
                    start = col.start,
                    end = col.end,
                    cp = col.chapterPosition,
                    colorArgb = col.colorArgb,
                )
            },
            atoms = line.atoms?.map { atomToDump(it) },
            cells = line.cells?.map { cellToDump(it) },
        )
    }

    private fun atomToDump(atom: Atom): BaselineAtomDump = when (atom) {
        is TextRun -> BaselineAtomDump(
            type = "text",
            text = atom.text,
            colorArgb = atom.colorArgb,
            sizeScale = atom.sizeScale,
            width = atom.width,
            height = atom.height,
            baseline = atom.baseline,
            cellLocalX = atom.cellLocalX,
            cellLocalY = atom.cellLocalY,
        )
        is InlineImage -> BaselineAtomDump(
            type = "image",
            src = atom.src,
            width = atom.width,
            height = atom.height,
            baseline = atom.baseline,
        )
    }

    private fun cellToDump(cell: ScrollLineCell): BaselineCellDump = BaselineCellDump(
        contentTop = cell.contentTop,
        contentLeft = cell.contentLeft,
        contentWidth = cell.contentWidth,
        contentHeight = cell.contentHeight,
        padding = cell.padding,
        atoms = cell.atoms.map { atomToDump(it) },
        backgroundColor = cell.backgroundColor,
        borderRadiusPx = cell.borderRadiusPx,
    )
}
