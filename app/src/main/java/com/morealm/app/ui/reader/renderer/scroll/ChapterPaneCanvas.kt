package com.morealm.app.ui.reader.renderer.scroll

import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.text.TextPaint
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.entity.Highlight
import com.morealm.app.domain.render.layout.ScrollChapterLayout
import com.morealm.app.domain.render.layout.ScrollHighlightDrawSpec
import com.morealm.app.domain.render.layout.ScrollLine
import com.morealm.app.domain.render.layout.findColumnAt
import com.morealm.epub.compat.BlockStyle

/**
 * 单章 Canvas 子树 —— [ScrollCanvasRenderer] 三块面板的子组件。
 *
 * 三层绘制（按顺序，后画覆盖先画）：
 *   1. **背景层**：KIND_BACKGROUND 高亮 rect
 *   2. **文字层**：按 ScrollColumn 坐标 drawText；KIND_TEXT_COLOR 命中 cp 时
 *      paint.color 替换为高亮 argb；其他文字用默认 paint
 *   3. **下划线层**：KIND_UNDERLINE 在 rect.bottom 下方画线，4 种线型：
 *      SOLID / DASHED / DOTTED / WAVY（quadraticTo 二次贝塞尔波浪）
 *
 * 高度契约：Modifier 由父 Layout（[ScrollCanvasRenderer]）强制
 * `Constraints.fixed(viewWidth, totalHeight)`，Canvas drawScope size 等同。
 *
 * 当前 paint hardcoded；M2.7 接入 ReaderStyle / 主题切换时替换为 ViewModel 注入。
 *
 * @param chapter 整章已排版结果
 * @param highlightSpecs 高亮 spec 列表（由 [com.morealm.app.domain.render.layout.ScrollHighlightProjector]
 *                       投影得到）；该章不含高亮传 emptyList
 * @param viewportTop 当前 viewport 上界（相对章顶 y）—— M2.7 视口剔除用
 * @param viewportBottom 当前 viewport 下界（相对章顶 y）—— M2.7 视口剔除用
 */
@Composable
fun ChapterPaneCanvas(
    chapter: ScrollChapterLayout,
    /**
     * 正文 paint —— 必须与排版时 [com.morealm.app.domain.render.layout.ScrollLayoutEngine]
     * 使用的 contentPaint 是同一份（同 fontSize / typeface / letterSpacing / bold），
     * 否则字符宽度与 ScrollColumn.start/end 错位，画出来的字会偏移甚至吃字。
     */
    contentPaint: TextPaint,
    titlePaint: TextPaint,
    chapterNumPaint: TextPaint,
    highlightSpecs: List<ScrollHighlightDrawSpec> = emptyList(),
    /**
     * 跳转后呼吸高亮（V1 RevealHighlight 等价）。命中本章时按 cp 范围画半透明 rect 覆盖
     * 文字（在 highlightSpecs 之上、文字之下），alpha 由 caller 持有的 Animatable 驱动。
     */
    revealHighlight: com.morealm.app.ui.reader.renderer.RevealHighlight? = null,
    /** 搜索高亮 cp 范围；EMPTY = 不画 */
    searchHighlightCpRange: IntRange = IntRange.EMPTY,
    /** 搜索高亮 argb */
    searchHighlightArgb: Int = 0x55FFFF00.toInt(),
    /** 长按 / handle 拖出的选区 cp 范围；EMPTY = 不画 */
    selectionCpRange: IntRange = IntRange.EMPTY,
    /** 选区背景 argb（半透明蓝紫色，与 V1 段级选区同色系） */
    selectionArgb: Int = 0x4D5B6CFE.toInt(),
    /** 书签所在 cp 列表，每个 cp 在对应行左上角画橙色三角标记 */
    bookmarkCps: List<Int> = emptyList(),
    /** 书签三角颜色（默认 Material error 红橙色，与 V1 hasBookmark 一致） */
    bookmarkArgb: Int = 0xFFD32F2F.toInt(),
    /** Viewport y 范围 lambda（相对章顶）。null = 该章完全不在 viewport，整章 skip。 */
    viewportRangeProvider: () -> Pair<Float, Float>? = { null },
    modifier: Modifier = Modifier,
) {
    val contentAscent = remember(contentPaint) { -contentPaint.fontMetrics.ascent }
    val titleAscent = remember(titlePaint) { -titlePaint.fontMetrics.ascent }
    val chapterNumAscent = remember(chapterNumPaint) { -chapterNumPaint.fontMetrics.ascent }

    // 高亮 spec 按 kind 预分组（避免每帧再 filter）
    val bgSpecs = remember(highlightSpecs) { highlightSpecs.filter { it.kind == Highlight.KIND_BACKGROUND } }
    val textColorSpecs = remember(highlightSpecs) { highlightSpecs.filter { it.kind == Highlight.KIND_TEXT_COLOR } }
    val underlineSpecs = remember(highlightSpecs) { highlightSpecs.filter { it.kind == Highlight.KIND_UNDERLINE } }

    // KIND_TEXT_COLOR：cp → argb 映射，O(1) 查；同 cp 多个 spec 取最后一个（后写的覆盖）
    val textColorByCp = remember(textColorSpecs) {
        val map = HashMap<Int, Int>()
        for (spec in textColorSpecs) {
            for (cp in spec.cpRangeFirst..spec.cpRangeLast) {
                map[cp] = spec.argb
            }
        }
        map
    }

    val bgFillPaint = remember {
        Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
        }
    }

    // 诊断日志：每章首帧记录 Canvas drawScope 实际尺寸 vs layout viewWidth，
    // 看是否吻合 —— 若 drawScope.width < chapter.viewWidth → 排版按 view 宽，画
    // 出来的字超出 canvas 实际边被截 = "右边吃掉字"。
    val firstDrawLogged = remember(chapter.chapterIndex, chapter.viewWidth) { booleanArrayOf(false) }

    Canvas(modifier) {
        drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            if (!firstDrawLogged[0]) {
                firstDrawLogged[0] = true
                AppLog.info(
                    "ChapterPaneCanvas",
                    "DRAW idx=${chapter.chapterIndex} canvasSize=${size.width.toInt()}x${size.height.toInt()} " +
                        "layoutViewWidth=${chapter.viewWidth} paddingLeft=${chapter.paddingLeft} " +
                        "overflow=${chapter.viewWidth > size.width.toInt()}",
                )
            }
            // paddingLeft 偏移：column.start 是相对 paddingLeft 内侧的 x（0..visibleWidth），
            // translate 让所有 drawText / drawRect 整体右移 paddingLeft，避免内容贴屏幕左缘。
            nc.save()
            nc.translate(chapter.paddingLeft.toFloat(), 0f)

            // ─── 视口剔除：viewport 范围 lambda 在 draw scope 内读 state，
            //              享受 draw-only re-execution（pixelOffset 变化只重 draw 不 measure）
            val range = viewportRangeProvider()
            if (range == null) {
                nc.restore()
                return@drawIntoCanvas
            }

            // 给一点缓冲（200px）避免边界 page 突然出现/消失闪烁
            val viewportTop = range.first - 200f
            // 兜底：viewportHeightPx 在首帧 onSizeChanged 触发前 = 0，range.second == range.first
            // → 视口范围 ±200 让首页一半被剔除（用户反馈：屏幕显示一半）。
            // 检测到视口高度过小（< 400）时跳过剔除（画整章），首帧不掉内容。
            val rawHeight = range.second - range.first
            val viewportBottom = if (rawHeight < 400f) Float.MAX_VALUE else range.second + 200f

            // ─── 层 1：背景高亮 rect（仅画与 viewport 相交的 rect）───
            for (spec in bgSpecs) {
                bgFillPaint.color = spec.argb
                for (rect in spec.rects) {
                    if (rect.bottom < viewportTop || rect.top > viewportBottom) continue
                    nc.drawRect(rect.left, rect.top, rect.right, rect.bottom, bgFillPaint)
                }
            }

            // ─── 层 1.5：搜索高亮（与背景同层但偏前；alpha 通常较高让用户一眼看到命中）───
            if (!searchHighlightCpRange.isEmpty()) {
                bgFillPaint.color = searchHighlightArgb
                drawCpRangeRects(
                    nc, chapter, searchHighlightCpRange.first, searchHighlightCpRange.last + 1,
                    viewportTop, viewportBottom, bgFillPaint,
                )
            }

            // ─── 层 1.6：RevealHighlight 跳转后呼吸高亮（alpha Animatable 衰减）───
            // 与搜索同款 rect 算法，但 argb 用 reveal.currentArgb()（含 alpha 缩放）。
            revealHighlight?.let { rev ->
                val argb = rev.currentArgb()
                if ((argb ushr 24) > 0) {
                    bgFillPaint.color = argb
                    drawCpRangeRects(
                        nc, chapter, rev.startChapterPos, rev.endChapterPos,
                        viewportTop, viewportBottom, bgFillPaint,
                    )
                }
            }

            // ─── 层 1.7：选区背景（长按 / handle 拖出的范围，半透明覆盖文字）───
            // 修复用户反馈"文字选中标记都没有"：handle dot 是有的，但选区背景之前没画。
            if (!selectionCpRange.isEmpty()) {
                bgFillPaint.color = selectionArgb
                AppLog.info(
                    "ChapterPaneCanvas",
                    "selectionBg ch=${chapter.chapterIndex} cp=${selectionCpRange.first}..${selectionCpRange.last} " +
                        "viewport=$viewportTop..$viewportBottom paddingL=${chapter.paddingLeft}",
                )
                drawCpRangeRects(
                    nc, chapter, selectionCpRange.first, selectionCpRange.last + 1,
                    viewportTop, viewportBottom, bgFillPaint,
                )
            }

            // ─── 层 2：文字（按 line 类型选 paint；KIND_TEXT_COLOR cp 命中时替换 paint.color） ───
            var pageOffsetY = 0f
            for (page in chapter.pages) {
                val pageTop = pageOffsetY
                val pageBottom = pageTop + page.height
                pageOffsetY = pageBottom
                // 视口剔除：page 完全在 viewport 之上 / 之下 → 整页 skip
                if (pageBottom < viewportTop || pageTop > viewportBottom) continue
                // **2026-05-28 Container box group** —— 一页一次，画外层装饰容器 box，在
                // per-line box 之前（让 per-line box 叠在 group box 上层）。
                val visibleW = (chapter.viewWidth - chapter.paddingLeft * 2).toFloat()
                drawScrollContainerBoxes(
                    nc, page.lines, chapter.boxGroupStyles, pageTop, 0f, visibleW,
                    fontSizeScale = contentPaint.textSize / 16f,
                )
                for (line in page.lines) {
                    // P3-5b Phase 3：CSS box 装饰（圆角背景 / 边框）必须画在文字 / 图片之前
                    // **阶段 2-H bugfix v3**：fontSizeScale = contentPaint.textSize / 16f
                    drawScrollLineBlockStyle(
                        nc, line, pageTop, 0f, visibleW,
                        fontSizeScale = contentPaint.textSize / 16f,
                    )
                    if (line.isImage) {
                        // ── 图片段（V1 PageContentDrawer.drawImageColumn 等价路径）──
                        // V2 image line：columns 空，imageSrc 非空，lineHeight = imgHeight
                        // 算法：等比缩放到 slot 内（slot = visibleWidth × imgHeight），居中。
                        val src = line.imageSrc ?: continue
                        val slotW = (chapter.viewWidth - chapter.paddingLeft * 2).toFloat()
                        val slotH = line.lineBottom - line.lineTop
                        val bitmap = com.morealm.app.domain.render.ImageCache.get(
                            src, slotW.toInt().coerceAtLeast(1),
                        ) ?: continue
                        val bmpW = bitmap.width.toFloat()
                        val bmpH = bitmap.height.toFloat()
                        val scale = minOf(slotW / bmpW, slotH / bmpH)
                        val drawW = bmpW * scale
                        val drawH = bmpH * scale
                        val offsetX = (slotW - drawW) / 2f
                        val offsetY = pageTop + line.lineTop + (slotH - drawH) / 2f
                        nc.drawBitmap(
                            bitmap,
                            null,
                            android.graphics.RectF(offsetX, offsetY, offsetX + drawW, offsetY + drawH),
                            null,
                        )
                        continue
                    }
                    val paint: TextPaint
                    val ascent: Float
                    val defaultColor: Int
                    when {
                        line.isChapterNum -> {
                            paint = chapterNumPaint
                            ascent = chapterNumAscent
                            defaultColor = paint.color
                        }
                        line.isTitle -> {
                            paint = titlePaint
                            ascent = titleAscent
                            defaultColor = paint.color
                        }
                        else -> {
                            paint = contentPaint
                            ascent = contentAscent
                            defaultColor = paint.color
                        }
                    }
                    // EPUB 自带字体：block 级 fontFamily → Typeface swap
                    val epubTypeface = com.morealm.app.domain.font.EpubFontRegistry.resolveActive(line.blockStyle.fontFamily)
                    val savedTypeface = if (epubTypeface != null) paint.typeface.also { paint.typeface = epubTypeface } else null

                    val baselineY = pageTop + line.lineTop + ascent
                    // P3-5b Step 2a：段落统一字体色（同 PagePaneCanvas 同位逻辑）
                    val paragraphColor = line.blockStyle.textColor
                    if (paragraphColor != null) paint.color = paragraphColor
                    // P3-5b Step 2b：text-shadow（c-shadow-* 彩色描边光晕，详 PagePaneCanvas 同位）
                    // **bugfix 2026-05-22**：blur < 1px 跳过 setShadowLayer（某 EPUB vol-text
                    // 0.5px 锐影 Android setShadowLayer 渲染异常成大白底），详 PagePaneCanvas
                    val ts = line.blockStyle.textShadow
                    val shadowApplied = ts != null && ts.blurRadius >= 1f
                    if (shadowApplied) {
                        paint.setShadowLayer(ts!!.blurRadius, ts.offsetX, ts.offsetY, ts.color)
                    }
                    // **D1.a DIAG**：仅当本行 line 有 textShadow 时打 log
                    if (ts != null) {
                        com.morealm.app.core.log.AppLog.info(
                            "D1a/Shadow",
                            "ChapterPane ts blur=${ts.blurRadius} dx=${ts.offsetX} dy=${ts.offsetY} " +
                                "color=0x${ts.color.toUInt().toString(16)} applied=$shadowApplied " +
                                "lineText='${line.text.take(15)}'",
                        )
                    }
                    // **D 模型 (阶段 1 重构)** 分发优先级：cells != null → drawCellsRow (table line)
                    // > atoms != null → drawAtomsRow (普通段 atom 路径) > columns 路径
                    val lineCells = line.cells
                    val lineAtoms = line.atoms
                    if (lineCells != null) {
                        drawCellsRow(
                            nc, lineCells, line, paint, pageOffsetY,
                            textColorByCp = textColorByCp,
                            defaultColor = defaultColor,
                        )
                        if (paragraphColor != null) paint.color = defaultColor
                        if (shadowApplied) paint.clearShadowLayer()
                        if (savedTypeface != null) paint.typeface = savedTypeface
                        continue
                    }
                    if (lineAtoms != null) {
                        drawAtomsRow(
                            nc, lineAtoms, line, paint, baselineY, pageOffsetY,
                            textColorByCp = textColorByCp,
                            defaultColor = defaultColor,
                        )
                        if (paragraphColor != null) paint.color = defaultColor
                        if (shadowApplied) paint.clearShadowLayer()
                        if (savedTypeface != null) paint.typeface = savedTypeface
                        continue
                    }
                    for (col in line.columns) {
                        // A4b：inline image 占位列（charData = U+FFFC）→ ImageCache + drawBitmap
                        if (col.inlineImageSrc != null) {
                            val bmp = com.morealm.app.domain.render.ImageCache.get(
                                col.inlineImageSrc, (col.end - col.start).toInt(),
                            )
                            if (bmp != null) {
                                val bmpW = bmp.width.toFloat()
                                val bmpH = bmp.height.toFloat()
                                val slotW = col.end - col.start
                                val slotH = line.lineBottom - line.lineTop
                                val scale = minOf(slotW / bmpW, slotH / bmpH)
                                val drawW = bmpW * scale
                                val drawH = bmpH * scale
                                val offX = col.start + (slotW - drawW) / 2f
                                val offY = pageOffsetY + line.lineTop + (slotH - drawH) / 2f
                                nc.drawBitmap(
                                    bmp, null,
                                    android.graphics.RectF(offX, offY, offX + drawW, offY + drawH),
                                    null,
                                )
                            }
                            continue
                        }
                        // 优先级：用户高亮 > col.colorArgb（CSS char-level） > 段落 paragraphColor > paint 默认
                        val overrideColor = textColorByCp[col.chapterPosition] ?: col.colorArgb
                        if (overrideColor != null) {
                            paint.color = overrideColor
                            nc.drawText(col.charData, col.start, baselineY, paint)
                            paint.color = paragraphColor ?: defaultColor
                        } else {
                            nc.drawText(col.charData, col.start, baselineY, paint)
                        }
                    }
                    if (paragraphColor != null) paint.color = defaultColor
                    if (shadowApplied) paint.clearShadowLayer()
                    if (savedTypeface != null) paint.typeface = savedTypeface
                }
                // pageOffsetY 已在循环顶累加（move 到顶为视口剔除提前）
            }

            // ─── 层 3：下划线（4 种线型：SOLID / DASHED / DOTTED / WAVY），按 viewport 剔除 ───
            // **关键**：rect.top/bottom = line.lineTop/lineBottom，含 lineSpacingExtra。
            // 直接用 rect.bottom 会把线画到行距底部（远离字符）。改用 rect.top + ascent + descent
            // = 字符底沿，让下划线紧贴文字本体而不是行距。
            val underlineStroke = (contentPaint.textSize * 0.1f).coerceAtLeast(2.5f)
            val fm = contentPaint.fontMetrics
            val textHeight = -fm.ascent + fm.descent  // 从 lineTop 到字底沿
            val wavyAmplitude = (contentPaint.textSize * 0.12f).coerceAtLeast(4f)
            val wavyPeriod = (contentPaint.textSize * 0.6f).coerceAtLeast(12f)
            for (spec in underlineSpecs) {
                val linePaint = underlinePaintFor(spec.argb, spec.underlineStyle, underlineStroke)
                for (rect in spec.rects) {
                    if (rect.bottom < viewportTop || rect.top > viewportBottom) continue
                    val underlineY = rect.top + textHeight + underlineStroke * 0.5f
                    when (spec.underlineStyle) {
                        Highlight.UNDERLINE_STYLE_WAVY -> drawWavyUnderline(
                            nc, linePaint, rect.left, rect.right, underlineY,
                            wavyAmplitude, wavyPeriod,
                        )
                        else -> nc.drawLine(rect.left, underlineY, rect.right, underlineY, linePaint)
                    }
                }
            }

            // ─── 层 4：书签三角（V1 PageContentDrawer.hasBookmark 等价）───
            if (bookmarkCps.isNotEmpty()) {
                drawBookmarkTriangles(
                    nc, chapter, bookmarkCps, bookmarkArgb,
                    contentPaint.textSize * 0.5f,
                    viewportTop, viewportBottom, bgFillPaint,
                )
            }

            nc.restore()  // 平衡前面 nc.save() + translate(paddingLeft)
        }
    }
}

/**
 * 在每个书签 cp 对应行的左上角画三角标记（V1 PageContentDrawer.hasBookmark 等价）。
 * 用 findColumnAt 一次定位到 hit.page+line，避免嵌套 for 循环。
 * 复杂度：O(bookmarks × pages) — pages 数远小于 lines。
 */
/**
 * **A5 atoms 骨架**（前进性双轨）：[ScrollLine.atoms] 非 null 时由本函数渲染一行 atoms。
 *
 * 当前阶段（A5 骨架）：所有 emit 仍走 columns 路径，line.atoms 永远 null，本函数
 * 暂时是 dead code。A4c 起 emit 真填 atoms 时立即激活。
 *
 * 跟 PagePaneCanvas.drawByAtoms 的差异：本函数承载 chapter-level pageOffsetY 偏移
 * （ChapterPaneCanvas 是 SCROLL 模式整章渲染，每条 line 的 lineTop 是 page-relative，
 * 需要加上 pageOffsetY 拿到 chapter-relative y 坐标）。
 */
private fun drawAtomsRow(
    canvas: android.graphics.Canvas,
    atoms: List<com.morealm.app.domain.render.layout.Atom>,
    line: com.morealm.app.domain.render.layout.ScrollLine,
    basePaint: android.text.TextPaint,
    baselineY: Float,
    pageOffsetY: Float,
    textColorByCp: Map<Int, Int> = emptyMap(),
    defaultColor: Int = basePaint.color,
) {
    // **bugfix 2026-05-22**：用 line.columns[0].start 作初始 x，让 atoms 路径 honor
    // CSS text-align cascade 算出的居中偏移（见 PagePaneCanvas.drawByAtoms 同款修复）
    var x = line.columns.firstOrNull()?.start ?: 0f
    var atomStartCp = line.firstChapterPos  // A5 Step 2：atom 起始 cp 用于 textColorByCp 查询
    for (atom in atoms) {
        when (atom) {
            is com.morealm.app.domain.render.layout.TextRun -> {
                val baseSize = basePaint.textSize
                val scale = atom.sizeScale
                if (scale != 1f) basePaint.textSize = baseSize * scale
                // sizeScale ≠ 1 时 vertical-align: top fallback；= 1 沿用 caller baselineY
                val effectiveBaselineY = if (scale != 1f) {
                    pageOffsetY + line.lineTop + basePaint.textSize * 0.8f
                } else baselineY
                val hasOverride = if (textColorByCp.isNotEmpty()) {
                    (0 until atom.cpCount).any { textColorByCp.containsKey(atomStartCp + it) }
                } else false
                if (hasOverride) {
                    var cx = x
                    val baseColor = atom.colorArgb ?: defaultColor
                    for (ci in atom.text.indices) {
                        val cp = atomStartCp + ci
                        val color = textColorByCp[cp] ?: baseColor
                        basePaint.color = color
                        val ch = atom.text[ci].toString()
                        canvas.drawText(ch, cx, effectiveBaselineY, basePaint)
                        cx += basePaint.measureText(ch)
                    }
                    basePaint.color = defaultColor
                } else {
                    val origColor = basePaint.color
                    if (atom.colorArgb != null) basePaint.color = atom.colorArgb
                    canvas.drawText(atom.text, x, effectiveBaselineY, basePaint)
                    if (atom.colorArgb != null) basePaint.color = origColor
                }
                if (scale != 1f) basePaint.textSize = baseSize
                x += atom.width
            }
            is com.morealm.app.domain.render.layout.InlineImage -> {
                val bmp = com.morealm.app.domain.render.ImageCache.get(atom.src, atom.width.toInt())
                if (bmp != null) {
                    val slotH = line.lineBottom - line.lineTop
                    val scale = minOf(atom.width / bmp.width, slotH / bmp.height)
                    val drawW = bmp.width * scale
                    val drawH = bmp.height * scale
                    val offX = x + (atom.width - drawW) / 2f
                    val offY = pageOffsetY + line.lineTop + (slotH - drawH) / 2f
                    canvas.drawBitmap(
                        bmp, null,
                        android.graphics.RectF(offX, offY, offX + drawW, offY + drawH),
                        null,
                    )
                }
                x += atom.width
            }
        }
        atomStartCp += atom.cpCount
    }
}

/**
 * **D 模型 (阶段 1 重构)** —— ChapterPane (SCROLL 模式) 的 table line cells 路径绘制。
 *
 * 跟 [drawAtomsRow] 的差异：本函数承载 chapter-level pageOffsetY 偏移（每条 line.lineTop
 * 是 page-relative，加上 pageOffsetY 拿到 chapter-relative y 坐标）。
 *
 * 几何关系与 [PagePaneCanvas.drawByCells] 同：
 *  - effective atom 左 x = cell.contentLeft + cell.padding + atom.cellLocalX
 *  - effective baseline y = pageOffsetY + line.lineTop + cell.contentTop + cell.padding +
 *    atom.cellLocalY + atom.baseline
 */
private fun drawCellsRow(
    canvas: android.graphics.Canvas,
    cells: List<com.morealm.app.domain.render.layout.ScrollLineCell>,
    line: com.morealm.app.domain.render.layout.ScrollLine,
    basePaint: android.text.TextPaint,
    pageOffsetY: Float,
    textColorByCp: Map<Int, Int> = emptyMap(),
    defaultColor: Int = basePaint.color,
) {
    val baseSize = basePaint.textSize
    var atomStartCp = line.firstChapterPos
    for (cell in cells) {
        // 未来 Task 2-D：cell.backgroundColor / borderRadiusPx 装饰盒
        for (atom in cell.atoms) {
            when (atom) {
                is com.morealm.app.domain.render.layout.TextRun -> {
                    val scale = atom.sizeScale
                    if (scale != 1f) basePaint.textSize = baseSize * scale
                    val effectiveX = cell.contentLeft + cell.padding + atom.cellLocalX
                    val effectiveBaselineY = pageOffsetY + line.lineTop + cell.contentTop +
                        cell.padding + atom.cellLocalY + atom.baseline
                    val hasOverride = if (textColorByCp.isNotEmpty()) {
                        (0 until atom.cpCount).any { textColorByCp.containsKey(atomStartCp + it) }
                    } else false
                    if (hasOverride) {
                        var cx = effectiveX
                        val baseColor = atom.colorArgb ?: defaultColor
                        for (ci in atom.text.indices) {
                            val cp = atomStartCp + ci
                            basePaint.color = textColorByCp[cp] ?: baseColor
                            val ch = atom.text[ci].toString()
                            canvas.drawText(ch, cx, effectiveBaselineY, basePaint)
                            cx += basePaint.measureText(ch)
                        }
                        basePaint.color = defaultColor
                    } else {
                        val origColor = basePaint.color
                        if (atom.colorArgb != null) basePaint.color = atom.colorArgb
                        canvas.drawText(atom.text, effectiveX, effectiveBaselineY, basePaint)
                        if (atom.colorArgb != null) basePaint.color = origColor
                    }
                    if (scale != 1f) basePaint.textSize = baseSize
                }
                is com.morealm.app.domain.render.layout.InlineImage -> {
                    val bmp = com.morealm.app.domain.render.ImageCache.get(atom.src, atom.width.toInt())
                    if (bmp != null) {
                        val drawX = cell.contentLeft + cell.padding + atom.cellLocalX
                        val drawY = pageOffsetY + line.lineTop + cell.contentTop + cell.padding +
                            atom.cellLocalY
                        val scaleF = minOf(atom.width / bmp.width, atom.height / bmp.height)
                        val drawW = bmp.width * scaleF
                        val drawH = bmp.height * scaleF
                        canvas.drawBitmap(
                            bmp, null,
                            android.graphics.RectF(drawX, drawY, drawX + drawW, drawY + drawH),
                            null,
                        )
                    }
                }
            }
            atomStartCp += atom.cpCount
        }
    }
}

private fun drawBookmarkTriangles(
    canvas: android.graphics.Canvas,
    chapter: ScrollChapterLayout,
    bookmarkCps: List<Int>,
    argb: Int,
    triSize: Float,
    viewportTop: Float,
    viewportBottom: Float,
    paint: Paint,
) {
    // 预算 pageOffsetY[pageIndex] = sum(pages[0..pageIndex-1].height)
    val pageOffsets = FloatArray(chapter.pages.size)
    var acc = 0f
    for (i in chapter.pages.indices) {
        pageOffsets[i] = acc
        acc += chapter.pages[i].height
    }
    paint.color = argb
    val path = Path()
    for (bmCp in bookmarkCps) {
        val hit = chapter.findColumnAt(bmCp) ?: continue
        val rectTop = pageOffsets[hit.page.pageIndex] + hit.line.lineTop
        if (rectTop < viewportTop || rectTop > viewportBottom) continue
        path.reset()
        path.moveTo(-triSize, rectTop)
        path.lineTo(0f, rectTop)
        path.lineTo(-triSize, rectTop + triSize)
        path.close()
        canvas.drawPath(path, paint)
    }
}

/**
 * 按线型返回 Paint。SOLID 无 pathEffect；DASHED / DOTTED 用 [DashPathEffect]；
 * WAVY 用默认 Paint（绘制时走 [drawWavyUnderline] 单独 Path 路径）。
 *
 * 每次调用 new Paint：调用频率 = 下划线 spec 数（同章一般几个），可接受。
 * 若 M6 性能压测发现热点可改 remember 缓存。
 */
private fun underlinePaintFor(argb: Int, underlineStyle: Int, strokeWidth: Float): Paint = Paint().apply {
    color = argb
    this.strokeWidth = strokeWidth
    style = Paint.Style.STROKE
    strokeCap = Paint.Cap.ROUND
    isAntiAlias = true
    pathEffect = when (underlineStyle) {
        Highlight.UNDERLINE_STYLE_DASHED -> DashPathEffect(floatArrayOf(strokeWidth * 4f, strokeWidth * 2f), 0f)
        Highlight.UNDERLINE_STYLE_DOTTED -> DashPathEffect(floatArrayOf(strokeWidth * 0.7f, strokeWidth * 2f), 0f)
        else -> null
    }
}

/**
 * 波浪下划线 —— 用 quadraticTo 二次贝塞尔曲线段拼接 sin 波形。
 * 振幅 / 周期由调用方按字号传入，保证大字号下波形足够起伏。
 */
private fun drawWavyUnderline(
    canvas: android.graphics.Canvas,
    paint: Paint,
    left: Float,
    right: Float,
    baselineY: Float,
    amplitude: Float,
    period: Float,
) {
    if (right - left <= 0f) return
    val halfPeriod = period / 2f
    val path = Path()
    path.moveTo(left, baselineY)
    var x = left
    var phaseUp = true  // 控制点交替在 baseline 上方 / 下方
    while (x < right) {
        val nextX = minOf(x + halfPeriod, right)
        val controlX = (x + nextX) / 2f
        val controlY = baselineY + if (phaseUp) -amplitude else amplitude
        path.quadTo(controlX, controlY, nextX, baselineY)
        x = nextX
        phaseUp = !phaseUp
    }
    canvas.drawPath(path, paint)
}

/**
 * 把 [startCp, endCp) 范围的字符按行画成 rect，已视口剔除。用于搜索高亮 /
 * RevealHighlight 等"按 cp 范围实时算 rect"场景（与 ScrollHighlightDrawSpec.rects
 * 预算缓存的场景不同 —— 那些是稳定状态，这些是动态状态没必要每帧重算 spec）。
 *
 * 算法（与 ScrollHighlightProjector 等价）：
 *   1. 遍历 chapter.pages 累加 pageOffsetY
 *   2. 每页内遍历 line；按 line.firstChapterPos / lastChapterPos 与 [startCp, endCp)
 *      求交，命中行扫 columns 找 [leftX, rightX]，画 rect(leftX, pageTop+lineTop,
 *      rightX, pageTop+lineBottom)
 *   3. 视口剔除：page 或 line 完全在 viewport 之外 → skip
 */
private fun drawCpRangeRects(
    canvas: android.graphics.Canvas,
    chapter: ScrollChapterLayout,
    startCp: Int,
    endCp: Int,  // exclusive
    viewportTop: Float,
    viewportBottom: Float,
    paint: Paint,
) {
    if (endCp <= startCp) return
    var pageOffsetY = 0f
    for (page in chapter.pages) {
        val pageTop = pageOffsetY
        val pageBottom = pageTop + page.height
        pageOffsetY = pageBottom
        if (pageBottom < viewportTop || pageTop > viewportBottom) continue
        for (line in page.lines) {
            // 行 cp 范围与目标范围求交：line.firstChapterPos..line.lastChapterPos 与
            // [startCp, endCp) 重叠才命中
            if (line.lastChapterPos < startCp || line.firstChapterPos >= endCp) continue
            val rectTop = pageTop + line.lineTop
            val rectBottom = pageTop + line.lineBottom
            if (rectBottom < viewportTop || rectTop > viewportBottom) continue
            if (line.columns.isEmpty()) {
                // 空段 / 图片段：整行宽 rect（用 chapter visibleWidth 作宽度近似）
                canvas.drawRect(0f, rectTop, (chapter.viewWidth - chapter.paddingLeft * 2).toFloat(), rectBottom, paint)
                continue
            }
            var leftX: Float? = null
            var rightX: Float? = null
            for (col in line.columns) {
                if (col.chapterPosition >= startCp && col.chapterPosition < endCp) {
                    if (leftX == null) leftX = col.start
                    rightX = col.end
                }
            }
            if (leftX != null && rightX != null) {
                canvas.drawRect(leftX, rectTop, rightX, rectBottom, paint)
                com.morealm.app.core.log.AppLog.info(
                    "ChapterPaneCanvas",
                    "  drawCpRangeRect line.cp=${line.firstChapterPos}..${line.lastChapterPos} " +
                        "rect=L${leftX}..R${rightX} T${rectTop}..B${rectBottom}",
                )
            }
        }
    }
}
