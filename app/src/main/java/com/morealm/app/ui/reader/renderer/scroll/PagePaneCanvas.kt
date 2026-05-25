package com.morealm.app.ui.reader.renderer.scroll

import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.text.TextPaint
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.morealm.app.domain.entity.Highlight
import com.morealm.app.domain.render.layout.ScrollHighlightDrawSpec
import com.morealm.app.domain.render.layout.ScrollPage

/**
 * **单 page Canvas 子树**（Phase 4 新增，对齐 [ChapterPaneCanvas] 的 page-level 等价物）。
 *
 * 与 [ChapterPaneCanvas] 的关键差异：
 * - 输入是单 [ScrollPage]，不是整章 layout（chapter-level 改 page-level 的核心）
 * - 高度契约：`Constraints.fixed(viewWidth, page.height)`，单 page 高度永远 < 18-bit
 *   上限（典型 1800px），**根治 totalHeight=130k cap 截断问题**
 * - 不做视口剔除：page 整体进入视口（由父 Renderer 控制不放置完全在视口外的 pane）
 * - 不做章内 page 累加：所有坐标相对 page 顶（line.lineTop / line.lineBottom 直接用）
 * - 高亮 spec / 书签 cp 已按 page 投影 / 过滤，本组件直接消费
 *
 * 三层绘制（按顺序，后画覆盖先画）：
 *   1. **背景层**：KIND_BACKGROUND 高亮 rect + 搜索高亮 + RevealHighlight + 选区
 *   2. **文字层**：page.lines 按 ScrollColumn 坐标 drawText；KIND_TEXT_COLOR 命中 cp 时
 *      paint.color 替换为高亮 argb
 *   3. **下划线层**：KIND_UNDERLINE 4 种线型
 *   4. **书签层**：书签三角
 *
 * @param page 单 page 已排版结果
 * @param chapterViewWidth 章节视口宽（用于 paddingLeft translate 与 cp 范围 rect 全行宽计算）
 * @param chapterPaddingLeft 章节左边距（translate 用，与 chapter.paddingLeft 一致）
 * @param contentPaint 正文 paint
 * @param titlePaint 标题 paint（章首块大字）
 * @param chapterNumPaint 章号小字 paint
 * @param highlightSpecs 已按 page 投影的高亮 spec（rects.top/bottom 是 page-relative）
 * @param bookmarkCps 该 page 内的书签 cp 列表
 * @param revealHighlight 跳转后呼吸高亮；命中本 page cp 范围才画
 * @param searchHighlightCpRange 搜索高亮 cp 范围（章内绝对 cp）；EMPTY = 不画
 * @param searchHighlightArgb 搜索高亮 argb
 * @param selectionCpRange 选区 cp 范围；EMPTY = 不画
 * @param selectionArgb 选区背景 argb
 * @param bookmarkArgb 书签三角颜色
 */
@Composable
fun PagePaneCanvas(
    page: ScrollPage,
    chapterViewWidth: Int,
    chapterPaddingLeft: Int,
    contentPaint: TextPaint,
    titlePaint: TextPaint,
    chapterNumPaint: TextPaint,
    highlightSpecs: List<ScrollHighlightDrawSpec> = emptyList(),
    bookmarkCps: List<Int> = emptyList(),
    revealHighlight: com.morealm.app.ui.reader.renderer.RevealHighlight? = null,
    searchHighlightCpRange: IntRange = IntRange.EMPTY,
    searchHighlightArgb: Int = 0x55FFFF00.toInt(),
    selectionCpRange: IntRange = IntRange.EMPTY,
    selectionArgb: Int = 0x4D5B6CFE.toInt(),
    bookmarkArgb: Int = 0xFFD32F2F.toInt(),
    modifier: Modifier = Modifier,
) {
    val contentAscent = remember(contentPaint) { -contentPaint.fontMetrics.ascent }
    val titleAscent = remember(titlePaint) { -titlePaint.fontMetrics.ascent }
    val chapterNumAscent = remember(chapterNumPaint) { -chapterNumPaint.fontMetrics.ascent }

    val bgSpecs = remember(highlightSpecs) { highlightSpecs.filter { it.kind == Highlight.KIND_BACKGROUND } }
    val textColorSpecs = remember(highlightSpecs) { highlightSpecs.filter { it.kind == Highlight.KIND_TEXT_COLOR } }
    val underlineSpecs = remember(highlightSpecs) { highlightSpecs.filter { it.kind == Highlight.KIND_UNDERLINE } }

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

    // 提前算 page cp 范围（用于 search / selection / reveal 与 page 求交，page 外不画）
    val pageFirstCp = page.lines.firstOrNull()?.firstChapterPos ?: -1
    val pageLastCp = page.lines.lastOrNull()?.lastChapterPos ?: -1
    val visibleWidthF = (chapterViewWidth - chapterPaddingLeft * 2).toFloat()

    // 字体颜色诊断（EPUB CharColor / 段落 textColor / atom.colorArgb / col.colorArgb）
    // —— 每 page 切换打 1 行 INFO，列前 3 行关键字段。便于对比 SCROLL vs page-level
    // 横向模式渲染时颜色是否一致。开销可忽略：page 切换才 fire。
    LaunchedEffect(page.chapterIndex, page.pageIndex, page.lines.size) {
        if (page.lines.isEmpty()) return@LaunchedEffect
        val sample = page.lines.take(3).mapIndexed { i, line ->
            val paragraphColor = line.blockStyle.textColor?.let { "0x${it.toUInt().toString(16)}" } ?: "null"
            val col0Color = line.columns.firstOrNull()?.colorArgb?.let { "0x${it.toUInt().toString(16)}" } ?: "null"
            val atom0Color = (line.atoms?.firstOrNull() as? com.morealm.app.domain.render.layout.TextRun)
                ?.colorArgb?.let { "0x${it.toUInt().toString(16)}" } ?: "null"
            val text15 = line.text.take(15).replace("\n", "\\n")
            "  L$i: paraC=$paragraphColor atoms=${line.atoms != null} cells=${line.cells != null}" +
                " col0C=$col0Color atom0C=$atom0Color text='$text15'"
        }.joinToString("\n")
        com.morealm.app.core.log.AppLog.info(
            "PagePane/ColorDiag",
            "ch=${page.chapterIndex} pg=${page.pageIndex} lines=${page.lines.size} paint.color=0x${contentPaint.color.toUInt().toString(16)}\n$sample",
        )
    }

    Canvas(modifier) {
        drawIntoCanvas { canvas ->
            val nc = canvas.nativeCanvas
            nc.save()
            nc.translate(chapterPaddingLeft.toFloat(), 0f)

            // ─── 层 0：P3-5b Phase 3 块装饰（圆角背景 / 边框） ───
            // 最底层 —— 用户高亮 / 选区 / 搜索高亮 / 文字都画在装饰之上
            // **阶段 2-H bugfix v3**：fontSizeScale = contentPaint.textSize / 16f 让 BlockStyle 内
            // 设计 px (16f base) 的 box 装饰字段 (widthPx/heightPx/padding/borderRadius/borderWidth)
            // 缩放到 user 字号下的真实 px (margin 不缩放，保持设计微间距 layout)。
            val bsScale = contentPaint.textSize / 16f
            for (line in page.lines) {
                drawScrollLineBlockStyle(
                    nc, line, pageTop = 0f,
                    fallbackLeft = 0f, fallbackRight = visibleWidthF,
                    fontSizeScale = bsScale,
                )
            }

            // ─── 层 1：背景高亮 rect ───
            for (spec in bgSpecs) {
                bgFillPaint.color = spec.argb
                for (rect in spec.rects) {
                    nc.drawRect(rect.left, rect.top, rect.right, rect.bottom, bgFillPaint)
                }
            }

            // ─── 层 1.5：搜索高亮（动态 cp 范围 → page 内 rect） ───
            if (!searchHighlightCpRange.isEmpty() && rangeIntersectsPage(searchHighlightCpRange, pageFirstCp, pageLastCp)) {
                bgFillPaint.color = searchHighlightArgb
                drawCpRangeRectsInPage(
                    nc, page, searchHighlightCpRange.first, searchHighlightCpRange.last + 1,
                    visibleWidthF, bgFillPaint,
                )
            }

            // ─── 层 1.6：RevealHighlight 跳转后呼吸高亮 ───
            revealHighlight?.let { rev ->
                if (rev.chapterIndex == page.chapterIndex && rev.endChapterPos >= pageFirstCp && rev.startChapterPos <= pageLastCp) {
                    val argb = rev.currentArgb()
                    if ((argb ushr 24) > 0) {
                        bgFillPaint.color = argb
                        drawCpRangeRectsInPage(
                            nc, page, rev.startChapterPos, rev.endChapterPos,
                            visibleWidthF, bgFillPaint,
                        )
                    }
                }
            }

            // ─── 层 1.7：选区背景 ───
            if (!selectionCpRange.isEmpty() && rangeIntersectsPage(selectionCpRange, pageFirstCp, pageLastCp)) {
                bgFillPaint.color = selectionArgb
                drawCpRangeRectsInPage(
                    nc, page, selectionCpRange.first, selectionCpRange.last + 1,
                    visibleWidthF, bgFillPaint,
                )
            }

            // ─── 层 2：文字 ───
            for (line in page.lines) {
                if (line.isImage) {
                    val src = line.imageSrc ?: continue
                    // **fullpage cover 整屏渲染**：某 EPUB等 EPUB 用 `<svg width="100%" height="100%">`
                    // 包裹的封面 image 通过 [com.morealm.app.domain.render.layout.ScrollLine.isFullPageImage]
                    // 透传到本层，slot 用整屏宽（chapterViewWidth）+ 绕过 paddingLeft translate 让
                    // 封面图占满屏（视觉效果优化 cover）。普通 `<p><img/></p>`（示例 LN B 01 等）
                    // isFullPageImage=false 仍走 visibleWidthF 段落图行为。
                    val isFullPage = line.isFullPageImage
                    val slotW: Float
                    val baseX: Float
                    val cacheTargetW: Int
                    if (isFullPage) {
                        slotW = chapterViewWidth.toFloat()
                        baseX = -chapterPaddingLeft.toFloat()  // 抵消外层 nc.translate(chapterPaddingLeft, 0)
                        cacheTargetW = chapterViewWidth.coerceAtLeast(1)
                    } else {
                        slotW = visibleWidthF
                        baseX = 0f
                        cacheTargetW = slotW.toInt().coerceAtLeast(1)
                    }
                    val slotH = line.lineBottom - line.lineTop
                    val bitmap = com.morealm.app.domain.render.ImageCache.get(
                        src, cacheTargetW,
                    ) ?: continue
                    val bmpW = bitmap.width.toFloat()
                    val bmpH = bitmap.height.toFloat()
                    val scale = minOf(slotW / bmpW, slotH / bmpH)
                    val drawW = bmpW * scale
                    val drawH = bmpH * scale
                    val offsetX = baseX + (slotW - drawW) / 2f
                    val offsetY = line.lineTop + (slotH - drawH) / 2f
                    // 诊断日志（某 EPUB全屏 vs 示例 LN B 01 非全屏，看渲染层数据差异）：
                    // page 上下文 + isFullPage + slot 大小 + bmp 大小 + scale + 最终 draw 区域。
                    com.morealm.app.core.log.AppLog.info(
                        "PagePane/Image",
                        "ch=${page.chapterIndex} pg=${page.pageIndex} pageH=${page.height} " +
                            "isFullPage=$isFullPage pageLines=${page.lines.size} " +
                            "lineTop=${line.lineTop.toInt()} lineBottom=${line.lineBottom.toInt()} " +
                            "src='${src.takeLast(40)}' slot=${slotW.toInt()}x${slotH.toInt()} " +
                            "bmp=${bmpW.toInt()}x${bmpH.toInt()} scale=${"%.3f".format(scale)} " +
                            "draw=${drawW.toInt()}x${drawH.toInt()} offset=(${offsetX.toInt()},${offsetY.toInt()})",
                    )
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
                val baselineY = line.lineTop + ascent
                // P3-5b Step 2a：段落统一字体色 —— RichText 所有 span 共享同色时
                // (`<span class="c-co-lan1">[ 角色 A [</span>` 之类) flattenToString 已经把
                // 颜色合并到 blockStyle.textColor，这里读出来当 paint 默认色，让角色 A 4 字带
                // c-co-lan1 蓝色。优先级：用户高亮 (textColorByCp) > paragraph textColor > paint 默认
                val paragraphColor = line.blockStyle.textColor
                if (paragraphColor != null) paint.color = paragraphColor
                // P3-5b Step 2b：text-shadow（c-shadow-* 的彩色描边光晕）
                // **bugfix 2026-05-22**：blur < 1px 跳过 setShadowLayer ——
                // Android 在 radius < 1f 小数 blur 下渲染异常（截图显示整字宽度白色矩形覆盖
                // 文字，根因 hardware-accelerated Canvas + setShadowLayer 小数边界）。
                // 某 EPUB `text-shadow: 0.5px 0.5px 0 white` 这种"锐影"完美方案是 stroke 描边
                // 二次 drawText（先 paint.style=STROKE strokeWidth=max(|dx|,|dy|) 描边色，
                // 后正常字），但本次 D1.a margin scope 仅做防御：blur < 1 跳过不画。
                // 视觉效果优化（参考实现也没画 0.5px 描边）。示例 LN B c-shadow-* `0 0 3px ...`
                // blur=3 不受影响仍画彩色光晕。
                val ts = line.blockStyle.textShadow
                if (ts != null && ts.blurRadius >= 1f) {
                    paint.setShadowLayer(ts.blurRadius, ts.offsetX, ts.offsetY, ts.color)
                }
                val shadowApplied = ts != null && ts.blurRadius >= 1f
                // **D1.a DIAG**：仅当本行 line 有 textShadow 时打 log
                if (ts != null) {
                    com.morealm.app.core.log.AppLog.info(
                        "D1a/Shadow",
                        "PagePane ts blur=${ts.blurRadius} dx=${ts.offsetX} dy=${ts.offsetY} " +
                            "color=0x${ts.color.toUInt().toString(16)} applied=$shadowApplied " +
                            "lineText='${line.text.take(15)}'",
                    )
                }
                // **D 模型 (阶段 1 重构)** 分发优先级：cells != null → drawByCells (table line)
                // > atoms != null → drawByAtoms (普通段 atom 路径) > columns 路径（旧 char-level）
                val lineCells = line.cells
                val lineAtoms = line.atoms
                if (lineCells != null) {
                    drawByCells(nc, lineCells, line, paint, defaultColor, textColorByCp)
                    if (paragraphColor != null) paint.color = defaultColor
                    if (shadowApplied) paint.clearShadowLayer()
                    continue
                }
                if (lineAtoms != null) {
                    drawByAtoms(nc, lineAtoms, line, paint, baselineY, defaultColor, textColorByCp)
                    if (paragraphColor != null) paint.color = defaultColor
                    if (shadowApplied) paint.clearShadowLayer()
                    continue
                }
                for (col in line.columns) {
                    // A4b：inline image 占位列（charData = U+FFFC）→ 调 ImageCache + drawBitmap
                    // 替代 drawText。bitmap 缓存命中是 O(1)，未命中第一次解码会阻塞但走 LRU 后续 free。
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
                            val offY = line.lineTop + (slotH - drawH) / 2f
                            nc.drawBitmap(
                                bmp, null,
                                android.graphics.RectF(offX, offY, offX + drawW, offY + drawH),
                                null,
                            )
                        }
                        // bitmap=null（未加载/失败）→ 不画任何东西（U+FFFC 占位字符位置留空）
                        continue
                    }
                    // 优先级：用户高亮 textColorByCp > col.colorArgb（CSS char-level） > 段落 paragraphColor > paint 默认
                    val overrideColor = textColorByCp[col.chapterPosition] ?: col.colorArgb
                    if (overrideColor != null) {
                        paint.color = overrideColor
                        nc.drawText(col.charData, col.start, baselineY, paint)
                        paint.color = paragraphColor ?: defaultColor
                    } else {
                        nc.drawText(col.charData, col.start, baselineY, paint)
                    }
                }
                // 还原 paint —— 避免段落色 / shadow 污染下一 line 共享 paint
                if (paragraphColor != null) paint.color = defaultColor
                if (shadowApplied) paint.clearShadowLayer()
            }

            // ─── 层 3：下划线 ───
            // 用 rect.top + (ascent + descent) 锚到字符底沿，避开 lineSpacingExtra 的影响。
            val underlineStroke = (contentPaint.textSize * 0.1f).coerceAtLeast(2.5f)
            val fm = contentPaint.fontMetrics
            val textHeight = -fm.ascent + fm.descent
            val wavyAmplitude = (contentPaint.textSize * 0.12f).coerceAtLeast(4f)
            val wavyPeriod = (contentPaint.textSize * 0.6f).coerceAtLeast(12f)
            for (spec in underlineSpecs) {
                val linePaint = underlinePaintFor(spec.argb, spec.underlineStyle, underlineStroke)
                for (rect in spec.rects) {
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

            // ─── 层 4：书签三角 ───
            if (bookmarkCps.isNotEmpty()) {
                bgFillPaint.color = bookmarkArgb
                val triSize = contentPaint.textSize * 0.5f
                val path = Path()
                for (bmCp in bookmarkCps) {
                    val line = page.lines.firstOrNull { line ->
                        bmCp >= line.firstChapterPos && bmCp <= line.lastChapterPos
                    } ?: continue
                    val rectTop = line.lineTop
                    path.reset()
                    path.moveTo(-triSize, rectTop)
                    path.lineTo(0f, rectTop)
                    path.lineTo(-triSize, rectTop + triSize)
                    path.close()
                    nc.drawPath(path, bgFillPaint)
                }
            }

            nc.restore()
        }
    }
}

/**
 * **A5 atoms 骨架**（前进性双轨）：[ScrollLine.atoms] 非 null 时由本函数渲染一行 atoms。
 *
 * 当前阶段（A5 骨架）：所有 emit 仍走 columns 路径，line.atoms 永远 null，本函数
 * 暂时是 dead code。A4c 起 emit 真填 atoms 时立即激活。
 *
 * 跟旧 column 渲染逻辑的差异：
 *  - **TextRun.color** 是直接覆写值（不再走 textColorByCp / col.colorArgb 优先级链）—— atom
 *    模型把所有 styling 显式表达在 atom 字段里，无需再分层 fallback
 *  - **TextRun.sizeScale** 缩放 paint.textSize 渲染（A4c 起接入字号通路 = em25/em30 大字）
 *  - **InlineImage** 直接拿 src + width/height fit 渲染（不再读 column.inlineImageSrc）
 *
 * 用户高亮的 textColorByCp 在 atoms 路径**暂未实现**（A4c+ 实测后再补）。
 */
private fun drawByAtoms(
    canvas: android.graphics.Canvas,
    atoms: List<com.morealm.app.domain.render.layout.Atom>,
    line: com.morealm.app.domain.render.layout.ScrollLine,
    basePaint: android.text.TextPaint,
    baselineY: Float,
    defaultColor: Int,
    textColorByCp: Map<Int, Int> = emptyMap(),
) {
    // **bugfix 2026-05-22**：用 line.columns[0].start 作初始 x（emit 阶段算的对齐起点，
    // 含 CSS text-align center/right 居中偏移）。之前 var x = 0f 让 atoms 路径无视
    // startX，CENTER 段（如某 EPUB h2.head「惊蛰」）退化成 left-aligned。
    var x = line.columns.firstOrNull()?.start ?: 0f
    val baseSize = basePaint.textSize
    var atomStartCp = line.firstChapterPos  // A5 Step 2：atom 起始 cp 用于 textColorByCp 查询
    for (atom in atoms) {
        when (atom) {
            is com.morealm.app.domain.render.layout.TextRun -> {
                val scale = atom.sizeScale
                if (scale != 1f) basePaint.textSize = baseSize * scale
                // sizeScale ≠ 1 时 vertical-align: top fallback 让小字号字符顶贴 line 顶。
                // sizeScale = 1 → 沿用 caller baselineY 兼容现有路径（H2/SampleLN em15 等）。
                val effectiveBaselineY = if (scale != 1f) {
                    line.lineTop + basePaint.textSize * 0.8f
                } else baselineY
                // A5 Step 2：检查 atom range 内有无 user 高亮 textColorByCp override
                // 命中 → 退化 char-by-char 按 cp 独立涂色；无 → fast path 整 atom drawText
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
                    val offY = line.lineTop + (slotH - drawH) / 2f
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
 * **D 模型 (阶段 1 重构)** —— table line 的 cells 路径绘制。
 *
 * 几何关系：
 *  - effective atom 左 x = cell.contentLeft + cell.padding + atom.cellLocalX
 *  - effective baseline y = line.lineTop + cell.contentTop + cell.padding + atom.cellLocalY +
 *    atom.baseline
 *
 * 与 [drawByAtoms] 的区别：
 *  - drawByAtoms 走 line.atoms 扁平 list，atoms 间用 `x += atom.width` 水平累加
 *  - drawByCells 走 line.cells 嵌套结构，每 cell 内 atom 由 cell-local 坐标精确定位（不依赖累加）
 *
 * 这让 cell 内多 line 字符堆叠（CJK 单字 1 行）、跨 cell 字号差大、未来 rowspan / vertical-align
 * middle 等场景的几何计算解耦到 emit 阶段，drawing 端只做坐标加和。
 *
 * **未来扩展（Task 2-D）**：cell.backgroundColor / borderRadiusPx 启用时在 atom 前画 cell box
 * 装饰盒（drawRoundRect）。当前 D2.b 场景全 null/0f → 跳过。
 */
private fun drawByCells(
    canvas: android.graphics.Canvas,
    cells: List<com.morealm.app.domain.render.layout.ScrollLineCell>,
    line: com.morealm.app.domain.render.layout.ScrollLine,
    basePaint: android.text.TextPaint,
    defaultColor: Int,
    textColorByCp: Map<Int, Int> = emptyMap(),
) {
    val baseSize = basePaint.textSize
    var atomStartCp = line.firstChapterPos
    for (cell in cells) {
        // 未来 Task 2-D：cell.backgroundColor != null 时画 RoundRect bg + cell.borderRadiusPx 圆角
        for (atom in cell.atoms) {
            when (atom) {
                is com.morealm.app.domain.render.layout.TextRun -> {
                    val scale = atom.sizeScale
                    if (scale != 1f) basePaint.textSize = baseSize * scale
                    val effectiveX = cell.contentLeft + cell.padding + atom.cellLocalX
                    val effectiveBaselineY = line.lineTop + cell.contentTop + cell.padding +
                        atom.cellLocalY + atom.baseline
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
                        val drawY = line.lineTop + cell.contentTop + cell.padding + atom.cellLocalY
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

/** 判断 [range] 与 page cp 范围是否有重叠（避免 page 外的 cp 也跑 rect 算法）。 */
private fun rangeIntersectsPage(range: IntRange, pageFirstCp: Int, pageLastCp: Int): Boolean {
    if (pageFirstCp < 0 || pageLastCp < 0) return false
    return !(range.last < pageFirstCp || range.first > pageLastCp)
}

/**
 * page 内画 [startCp, endCp) 范围 rect（动态算，不预存 spec）。
 * 与 ChapterPaneCanvas.drawCpRangeRects 类似但简化：page-relative 坐标 + 无视口剔除。
 */
private fun drawCpRangeRectsInPage(
    canvas: android.graphics.Canvas,
    page: ScrollPage,
    startCp: Int,
    endCp: Int,  // exclusive
    visibleWidth: Float,
    paint: Paint,
) {
    if (endCp <= startCp) return
    for (line in page.lines) {
        if (line.lastChapterPos < startCp || line.firstChapterPos >= endCp) continue
        if (line.columns.isEmpty()) {
            canvas.drawRect(0f, line.lineTop, visibleWidth, line.lineBottom, paint)
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
            canvas.drawRect(leftX, line.lineTop, rightX, line.lineBottom, paint)
        }
    }
}

/** 与 ChapterPaneCanvas 同款 paint 工厂，本地 private 避免跨文件 visibility。 */
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

/** 波浪下划线（与 ChapterPaneCanvas 同款；振幅 / 周期由调用方按字号传入）。 */
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
    var phaseUp = true
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
