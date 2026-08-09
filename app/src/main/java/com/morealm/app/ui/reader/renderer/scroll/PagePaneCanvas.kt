package com.morealm.app.ui.reader.renderer.scroll

import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.text.TextPaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.morealm.app.domain.entity.Highlight
import com.morealm.app.domain.render.layout.ScrollHighlightDrawSpec
import com.morealm.app.ui.reader.renderer.ReaderInfoBar
import com.morealm.app.ui.reader.renderer.adaptAuthoredForegroundForReaderBg
import com.morealm.app.ui.reader.renderer.foregroundOnAuthoredSurface
import com.morealm.app.ui.reader.renderer.linkForegroundForReaderBg
import com.morealm.epub.compat.BlockStyle
import com.morealm.epub.render.ScrollPage

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
 * @param readerBgArgb 阅读器纯色背景 argb；用于夜间 EPUB 装饰底色自适应（暗底不闪眼）
 */
@Composable
fun PagePaneCanvas(
    page: ScrollPage,
    chapterViewWidth: Int,
    chapterPaddingLeft: Int,
    /** body 背景百分比尺寸的视口高度；滚动页自身可能远高于屏幕，不能拿 page.height 当 100%。 */
    backgroundViewportHeight: Float? = null,
    /** SCROLL 为 true，分页/仿真保持 false，让 body 背景在每页独立定位。 */
    continuousEpubBackground: Boolean = false,
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
    readerBgArgb: Int = 0xFFFFFFFF.toInt(),
    /**
     * 水平翻页（NONE/SLIDE/COVER）时画进**本页**的页眉页脚，随页一起翻动（对齐参照实现）；
     * null = 不画（垂直滚动模式走原裸 Canvas 路径，零行为变化）。
     */
    pageInfoBar: PageInfoBarSpec? = null,
    modifier: Modifier = Modifier,
) {
    val drawPage: DrawScope.() -> Unit = {
        drawIntoCanvas { canvas ->
            drawScrollPageOnCanvas(
                nc = canvas.nativeCanvas,
                page = page,
                viewHeightF = size.height,
                backgroundViewportHeightF = backgroundViewportHeight ?: size.height,
                continuousEpubBackground = continuousEpubBackground,
                contentPaint = contentPaint,
                titlePaint = titlePaint,
                chapterNumPaint = chapterNumPaint,
                chapterViewWidth = chapterViewWidth,
                chapterPaddingLeft = chapterPaddingLeft,
                highlightSpecs = highlightSpecs,
                bookmarkCps = bookmarkCps,
                revealHighlight = revealHighlight,
                searchHighlightCpRange = searchHighlightCpRange,
                searchHighlightArgb = searchHighlightArgb,
                selectionCpRange = selectionCpRange,
                selectionArgb = selectionArgb,
                bookmarkArgb = bookmarkArgb,
                readerBgArgb = readerBgArgb,
            )
        }
    }
    if (pageInfoBar == null) {
        // 垂直滚动 / 无信息栏：裸 Canvas，与原实现完全等价（零行为变化）
        Canvas(modifier, onDraw = drawPage)
    } else {
        // 水平翻页：正文 + 页眉页脚同在一个页容器内 → 随页 placeRelative 一起翻动；无渐变羽化
        // 整屏封面页不画页眉页脚：封面按 cover 铺满物理页，页眉页脚叠上去既遮画面
        // 也没有信息量（章名=「封面」、进度=1 页章的 100%），对齐成熟阅读器的纯封面页。
        val isCoverPage = page.lines.any { it.isFullPageImage }
        Box(modifier) {
            Canvas(Modifier.fillMaxSize(), onDraw = drawPage)
            if (!isCoverPage) PageInfoBars(pageInfoBar)
        }
    }
}

/** 水平翻页页眉 / 页脚单行高度（dp）。[PageLevelReaderHost] 正文预留复用此常量，保证正文末行紧贴页脚不重叠。 */
internal const val PAGED_INFO_BAR_LINE_DP = 20

/**
 * 画 `<hr/>` 分隔线，消费 hr 段声明的线色 / 线型 / 线宽。
 *
 * `hr.line { border-style: dotted; border-color: #CCC; border-top: 0.5px }` 这类
 * 分隔线声明此前整个被丢（builder 恒发 EMPTY 样式），只能画默认半透明实线 —— 参照
 * 显示的是细密灰点线。现在 builder 带上了 hr 自己的 blockStyle：
 *  - 线色：声明色直用（灰系日夜均可读，同参照），无声明退回正文色降 alpha
 *  - 线宽：声明的 border-top 宽 × 字号倍率，下限 1.5px；无声明按字号缩放
 *  - 线型：DOTTED / DASHED 按段绘制，SOLID / DOUBLE 整条（DOUBLE 双线交给
 *    后续需要的书再做，先不为不存在的样书加复杂度）
 */
internal fun drawHorizontalRuleLine(
    nc: android.graphics.Canvas,
    line: com.morealm.epub.render.ScrollLine,
    cy: Float,
    contentPaint: TextPaint,
    bgFillPaint: android.graphics.Paint,
) {
    val bs = line.blockStyle
    val fontScale = contentPaint.textSize / 16f
    val declaredW = bs.effectiveBorderTopPx
    val th = (if (declaredW > 0f) declaredW * fontScale else contentPaint.textSize / 16f)
        .coerceAtLeast(1.5f)
    bgFillPaint.color = bs.borderColor
        ?: ((contentPaint.color and 0x00FFFFFF) or (0x66 shl 24))
    val top = cy - th / 2f
    val bottom = cy + th / 2f
    when (bs.borderStyle) {
        com.morealm.epub.compat.BlockStyle.BorderStyle.DOTTED,
        com.morealm.epub.compat.BlockStyle.BorderStyle.DASHED,
        -> {
            val dotted = bs.borderStyle == com.morealm.epub.compat.BlockStyle.BorderStyle.DOTTED
            // 段/点尺寸设最小可视下限：`border-top: 0.5px` 这类细线声明算出的段宽只有
            // ~2px，亚像素矩形落在像素栅格上被抗锯齿融合成忽实忽虚的 moiré（实测
            // 「实线和虚线夹杂」）。点径 ≥3px、间距 ≥4px 后点点清晰可辨（对齐参照观感）。
            // dotted 画近方点（高=点径），dashed 保持线高。
            val seg = if (dotted) maxOf(th * 1.6f, 3f) else maxOf(th * 5f, 9f)
            val gap = if (dotted) maxOf(th * 2.2f, 4f) else maxOf(th * 2.6f, 5f)
            val half = if (dotted) seg / 2f else th / 2f
            var x = line.hrLeftPx
            while (x < line.hrRightPx) {
                nc.drawRect(x, cy - half, minOf(x + seg, line.hrRightPx), cy + half, bgFillPaint)
                x += seg + gap
            }
        }
        else -> nc.drawRect(line.hrLeftPx, top, line.hrRightPx, bottom, bgFillPaint)
    }
}

/**
 * 水平翻页时画进**每页**的页眉页脚数据（per-page）。由 PageLevelReaderHost 为每个可见 page
 * （prev/cur/next/nextPlus）按其所属章 layout 现算 [chapterTitle] / [scrollPercent]，配合全局
 * [batteryLevel] / [currentTime] 组装。垂直滚动模式不用（传 null，PagePaneCanvas 走裸 Canvas）。
 */
data class PageInfoBarSpec(
    val config: ScrollCanvasInfoBarConfig,
    val chapterTitle: String,
    val chapterIndex: Int,
    val pageIndexInChapter: Int,
    val pageCountInChapter: Int,
    val scrollPercent: Float,
    val batteryLevel: Int,
    val batteryCharging: Boolean,
    val currentTime: String,
    val topInsetDp: Dp,
    val bottomInsetDp: Dp,
    /** 底部屏幕圆角（R 角）让位：footer 左右额外内缩，避免角落时间 / 电量被圆角裁切。 */
    val cornerInsetDp: Dp = 0.dp,
)

/**
 * page 内页眉（顶）+ 页脚（底）—— **无渐变背景**（水平翻页不需要垂直滚动那种羽化），随页一起翻。
 * 高度 [PAGED_INFO_BAR_LINE_DP] + 系统栏 inset，与 Host 正文预留对齐，所以正文末行紧贴页脚不重叠。
 */
@Composable
private fun BoxScope.PageInfoBars(spec: PageInfoBarSpec) {
    val cfg = spec.config
    // 横向 page-level 有「页」概念，slot 保留原义（不像 SCROLL 把 "page" 降级到章进度）
    fun mapSlot(s: String): String = s
    // 门控后全空的 bar 直接不组合——正文预留侧（PageLevelReaderHost.effectivePad*）已按
    // headerHasContent/footerHasContent 不再让位，此处若仍放透明 bar 会叠在正文上方。
    if (cfg.headerHasContent()) ReaderInfoBar(
        slotLeft = gateInfoSlot(mapSlot(cfg.headerLeft), cfg.showChapterName, cfg.showTimeBattery),
        slotCenter = gateInfoSlot(mapSlot(cfg.headerCenter), cfg.showChapterName, cfg.showTimeBattery),
        slotRight = gateInfoSlot(mapSlot(cfg.headerRight), cfg.showChapterName, cfg.showTimeBattery),
        chapterTitle = spec.chapterTitle,
        pageIndex = spec.pageIndexInChapter,
        pageCount = spec.pageCountInChapter,
        currentPage = null,
        chapterIndex = spec.chapterIndex,
        chaptersSize = cfg.chaptersSize,
        batteryLevel = spec.batteryLevel,
        batteryCharging = spec.batteryCharging,
        currentTime = spec.currentTime,
        textColor = cfg.textColor,
        scrollPercentOverride = spec.scrollPercent,
        modifier = Modifier
            .align(Alignment.TopStart)
            .fillMaxWidth()
            .height(PAGED_INFO_BAR_LINE_DP.dp + spec.topInsetDp)
            .padding(
                top = spec.topInsetDp,
                start = cfg.paddingHorizontal.dp,
                end = cfg.paddingHorizontal.dp,
                bottom = 4.dp,
            ),
    )
    if (cfg.footerHasContent()) ReaderInfoBar(
        slotLeft = gateInfoSlot(mapSlot(cfg.footerLeft), cfg.showChapterName, cfg.showTimeBattery),
        slotCenter = gateInfoSlot(mapSlot(cfg.footerCenter), cfg.showChapterName, cfg.showTimeBattery),
        slotRight = gateInfoSlot(mapSlot(cfg.footerRight), cfg.showChapterName, cfg.showTimeBattery),
        chapterTitle = spec.chapterTitle,
        pageIndex = spec.pageIndexInChapter,
        pageCount = spec.pageCountInChapter,
        currentPage = null,
        chapterIndex = spec.chapterIndex,
        chaptersSize = cfg.chaptersSize,
        batteryLevel = spec.batteryLevel,
        batteryCharging = spec.batteryCharging,
        currentTime = spec.currentTime,
        textColor = cfg.textColor,
        scrollPercentOverride = spec.scrollPercent,
        modifier = Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .height(PAGED_INFO_BAR_LINE_DP.dp + spec.bottomInsetDp)
            .padding(
                top = 4.dp,
                start = cfg.paddingHorizontal.dp + spec.cornerInsetDp,
                end = cfg.paddingHorizontal.dp + spec.cornerInsetDp,
                bottom = spec.bottomInsetDp,
            ),
    )
}

/**
 * 排版诊断去重集 —— 同一 page（同章同页同字号同色）只打一行，避免每帧刷屏。
 * 保留最近 64 条即可覆盖 prev/cur/next/nextPlus 四窗轮转 + 前后翻若干页。
 */
private val pageDiagLoggedKeys = LinkedHashSet<String>()

/**
 * **排版诊断** —— 覆盖字色链、作者页面背景、行几何（标题间距 / 图片宽度基准 / 浮动环绕）、
 * box 装饰（边框 / 背景图）。真机排查：`adb logcat | grep PagePane/Diag`。
 *
 * 挂在 [drawScrollPageOnCanvas] 而不是 [PagePaneCanvas] 上：仿真翻页走
 * [com.morealm.app.ui.reader.page.ScrollPagePageBitmapProvider] 的离屏 Bitmap 路径，
 * 根本不组合 PagePaneCanvas —— 诊断若挂在 Composable 里，那条路径一行都打不出来。
 * 五种翻页模式共用本绘制函数，挂这里才是全覆盖的唯一位置。
 */
private fun logPageLayoutDiag(
    page: ScrollPage,
    chapterViewWidth: Int,
    chapterPaddingLeft: Int,
    viewHeightF: Float,
    contentPaint: TextPaint,
    readerBgArgb: Int,
) {
    if (page.lines.isEmpty()) return
    val key = "${page.chapterIndex}/${page.pageIndex}/${page.lines.size}/" +
        "${contentPaint.color}/${contentPaint.textSize}/$readerBgArgb"
    synchronized(pageDiagLoggedKeys) {
        if (!pageDiagLoggedKeys.add(key)) return
        while (pageDiagLoggedKeys.size > 64) {
            pageDiagLoggedKeys.remove(pageDiagLoggedKeys.first())
        }
    }
    fun hex(v: Int?): String = v?.let { "0x${it.toUInt().toString(16)}" } ?: "null"
    val bg = page.background
    val bgDesc = "color=${hex(bg.colorArgb)} layers=${bg.layers.size}" +
        bg.layers.firstOrNull()?.let { " L0=${it.image}" }.orEmpty()
    var prevBottom: Float? = null
    val sample = page.lines.take(14).mapIndexed { i, line ->
        val s = line.blockStyle
        val kind = when {
            line.isImage -> "IMG"
            line.cells != null -> "CELLS"
            line.isHorizontalRule -> "HR"
            line.isTitle || line.isChapterNum -> "TITLE"
            else -> "TEXT"
        }
        // gap = 与上一行的垂直间隙。标题被 `<br/>` 拆开、或段间距失控时这里直接读得出来。
        val gap = prevBottom?.let { "%.1f".format(line.lineTop - it) } ?: "-"
        prevBottom = line.lineBottom
        val geo = "top=%.1f bot=%.1f gap=%s".format(line.lineTop, line.lineBottom, gap)
        val x = if (line.columns.isEmpty()) {
            "img=[%.1f,%.1f]".format(line.imageLeftPx, line.imageRightPx)
        } else {
            "x=[%.1f,%.1f]".format(line.columns.first().start, line.columns.last().end)
        }
        // 排版意图字段：margin / 定宽 / 浮动 / 标题续行 / 边框 / 背景
        val box = buildList {
            if (s.marginTopPx != 0f) add("mt=${s.marginTopPx}")
            if (s.marginBottomPx != 0f) add("mb=${s.marginBottomPx}")
            s.widthPx?.let { add("w=$it${if (s.widthIsPercent) "%" else ""}") }
            if (s.floatSide != BlockStyle.FloatSide.NONE) add("flt=${s.floatSide}")
            if (s.joinsNextBlock) add("jn")
            if (s.borderWidthPx != 0f) add("bw=${s.borderWidthPx}/${hex(s.borderColor)}")
            s.backgroundImageSrc?.let { add("bgImg=${it.substringAfterLast('/')}") }
            s.backgroundColor?.let { add("bg=${hex(it)}") }
        }.joinToString(",").ifEmpty { "-" }
        "  L$i $kind $geo $x fc=${hex(s.textColor)} " +
            "col0=${hex(line.columns.firstOrNull()?.colorArgb)} [$box] " +
            "'${line.text.take(12).replace("\n", "\\n")}'"
    }.joinToString("\n")
    com.morealm.app.core.log.AppLog.info(
        "PagePane/Diag",
        "ch=${page.chapterIndex} pg=${page.pageIndex} lines=${page.lines.size} " +
            "view=${chapterViewWidth}x${viewHeightF.toInt()} padL=$chapterPaddingLeft " +
            "readerBg=${hex(readerBgArgb)} paint=${hex(contentPaint.color)} " +
            "textSize=${contentPaint.textSize}\n  pageBg: $bgDesc\n$sample",
    )
}

/**
 * 纯函数版 page 绘制 —— 把 [PagePaneCanvas] 内 `drawIntoCanvas { ... }` 块抽出，方便
 * 离屏 [Bitmap] 渲染共用（仿真翻页 BitmapProvider 用这条路径生成位图）。
 *
 * 调用方负责传入 native [android.graphics.Canvas]（无论来源是 Compose Canvas 还是
 * `Canvas(bitmap)`）以及视图高度（Compose 内取 `size.height`；Bitmap 内取 `bitmap.height`）。
 *
 * 函数内部完整保留 PagePaneCanvas 的 4 层绘制（块装饰 / 背景高亮 / 文字 / 下划线 / 书签）
 * + paddingLeft translate + clipRect 兜底；调用方画完之后 nativeCanvas state 无残留
 * （函数末尾 `nc.restore()` 与开头 `nc.save()` 配对）。
 */
internal fun drawScrollPageOnCanvas(
    nc: android.graphics.Canvas,
    page: ScrollPage,
    viewHeightF: Float,
    backgroundViewportHeightF: Float = viewHeightF,
    continuousEpubBackground: Boolean = false,
    contentPaint: TextPaint,
    titlePaint: TextPaint,
    chapterNumPaint: TextPaint,
    chapterViewWidth: Int,
    chapterPaddingLeft: Int,
    highlightSpecs: List<ScrollHighlightDrawSpec> = emptyList(),
    bookmarkCps: List<Int> = emptyList(),
    revealHighlight: com.morealm.app.ui.reader.renderer.RevealHighlight? = null,
    searchHighlightCpRange: IntRange = IntRange.EMPTY,
    searchHighlightArgb: Int = 0x55FFFF00.toInt(),
    selectionCpRange: IntRange = IntRange.EMPTY,
    selectionArgb: Int = 0x4D5B6CFE.toInt(),
    bookmarkArgb: Int = 0xFFD32F2F.toInt(),
    readerBgArgb: Int = 0xFFFFFFFF.toInt(),
) {
    logPageLayoutDiag(
        page = page,
        chapterViewWidth = chapterViewWidth,
        chapterPaddingLeft = chapterPaddingLeft,
        viewHeightF = viewHeightF,
        contentPaint = contentPaint,
        readerBgArgb = readerBgArgb,
    )
    val contentAscent = -contentPaint.fontMetrics.ascent
    val titleAscent = -titlePaint.fontMetrics.ascent
    val chapterNumAscent = -chapterNumPaint.fontMetrics.ascent

    // **落笔处的实际底色**。作者页面背景（`body { background: #fff url(...) }`）由
    // drawEpubPageBackground 原样铺满整页 —— 铺完之后，字和框压在**作者的底色**上，
    // 阅读器主题背景已经不在画面里了。夜间自适应若还拿主题背景当判据，就会在作者的
    // 白纸上写浅灰白字（用户实测：内容介绍页整页字看不见）、把作者的边框也一并调亮。
    //
    // 只认不透明颜色层：半透明底会与主题背景混合、图层无法采样，判据都不成立。
    val authoredPageSurfaceArgb = page.background.colorArgb?.takeIf { (it ushr 24) == 0xFF }
    val decorBgArgb = authoredPageSurfaceArgb ?: readerBgArgb

    val bgSpecs = highlightSpecs.filter { it.kind == Highlight.KIND_BACKGROUND }
    val textColorSpecs = highlightSpecs.filter { it.kind == Highlight.KIND_TEXT_COLOR }
    val underlineSpecs = highlightSpecs.filter { it.kind == Highlight.KIND_UNDERLINE }

    val textColorByCp = HashMap<Int, Int>().apply {
        for (spec in textColorSpecs) {
            for (cp in spec.cpRangeFirst..spec.cpRangeLast) {
                this[cp] = spec.argb
            }
        }
    }

    val bgFillPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    val pageFirstCp = page.lines.firstOrNull()?.firstChapterPos ?: -1
    val pageLastCp = page.lines.lastOrNull()?.lastChapterPos ?: -1
    val visibleWidthF = (chapterViewWidth - chapterPaddingLeft * 2).toFloat()

    // EPUB 文档背景属于页内 section region；必须在内容坐标 translate 之前按整页宽绘制。
    // SCROLL 的 page pane 会随内容移动，横向/仿真则各自绘制目标 page，因此五种模式同源。
    drawEpubPageBackground(
        canvas = nc,
        page = page,
        pageWidth = chapterViewWidth.toFloat(),
        pageHeight = viewHeightF,
        viewportHeight = backgroundViewportHeightF,
        fontSizePx = contentPaint.textSize,
        continuousSectionCoordinates = continuousEpubBackground,
    )

    nc.save()
    nc.translate(chapterPaddingLeft.toFloat(), 0f)
    // **渲染层 clip 兜底**（rendering invariant）：translate 后 nativeCanvas
    // 不自动 clip 到 layout bounds，让 ScrollLayoutEngine emit 端的极端边界
    // （CSS 负 margin / inline-block 越界 / 行末 exceed 压缩让首字 start<0 等）
    // 有可能 drawText 到 page bounds 之外。COVER 翻页静止状态 prev 在
    // placeRelative(-viewWidth, 0)，prev 内 x = viewWidth + N 的越界字会被
    // layout 平移到屏幕 x = N（viewport 左缘出现"上一页字片段"残影，用户
    // 实测样本：某 EPUB 章节左缘半字"京 / 人记载 / 好绘"等）。
    // clipRect 是 page bound 的固有不变量，不依赖排版层 100% 正确。
    //
    // **右界放宽 glyphOverhangPad**：精排 EPUB 自带宋体部分字的笔画视觉右缘比 advance 宽 1~3px，
    // justify 末字精确贴 visibleWidth 后那点笔尖会被裁掉（用户实测末字「家」右缘缺角）。放宽约
    // 字号 0.15（6~8px）落在右页边距留白内、不超屏；远小于残影所在的 x≈viewWidth 极端越界，
    // 故 COVER prev 左缘残影防护仍生效（prev 的该区仍在屏幕外）。
    val glyphOverhangPad = contentPaint.textSize * 0.15f
    // **出血图放宽到物理页边**：`duokan-bleed` 声明的章头图由排版层给出越过内容区的
    // imageLeftPx/imageRightPx（负值 / 超过 visibleWidth）。内容区 clip 会把越界那部分裁掉，
    // 图看起来仍缩在页边距内 —— 出血就白做了。含此类图的页把 clip 放宽到物理页边界：
    // 上界仍是屏幕本身，COVER 翻页 prev 页 x≈viewWidth 的越界残影仍在界外，防护不失效。
    //
    // 整屏封面页（isFullPageImage）同样放宽：它的绘制区就是整个物理页
    // （baseX=-paddingLeft，cover 裁切超出部分正靠这个 clip 完成）；此前被排除在放宽外，
    // 内容区 clip 把封面左右各裁掉一条 padding 宽。
    val hasBleedingImage = page.lines.any {
        it.isImage && (it.isFullPageImage || it.imageLeftPx < 0f || it.imageRightPx > visibleWidthF)
    }
    if (hasBleedingImage) {
        nc.clipRect(
            -chapterPaddingLeft.toFloat(), 0f,
            (chapterViewWidth - chapterPaddingLeft).toFloat(), viewHeightF,
        )
    } else {
        nc.clipRect(0f, 0f, visibleWidthF + glyphOverhangPad, viewHeightF)
    }

    // ─── 层 0：P3-5b Phase 3 块装饰（圆角背景 / 边框） ───
    val bsScale = contentPaint.textSize / 16f
    // **2026-05-28 Container box group** —— 先画外层装饰容器 box，让 per-line box 叠在 group box 上。
    drawScrollContainerBoxes(
        nc, page.lines, page.boxGroupStyles, pageTop = 0f,
        fallbackLeft = 0f, fallbackRight = visibleWidthF,
        fontSizeScale = bsScale,
        readerBgArgb = decorBgArgb,
    )
    drawScrollParagraphBlockStyles(
        canvas = nc,
        lines = page.lines,
        pageTop = 0f,
        fallbackLeft = 0f,
        fallbackRight = visibleWidthF,
        fontSizeScale = bsScale,
        readerBgArgb = decorBgArgb,
    )

    // ─── 层 1：背景高亮 rect ───
    for (spec in bgSpecs) {
        bgFillPaint.color = spec.argb
        for (rect in spec.rects) {
            nc.drawRect(rect.left, rect.top, rect.right, rect.bottom, bgFillPaint)
        }
    }

    if (!searchHighlightCpRange.isEmpty() && rangeIntersectsPage(searchHighlightCpRange, pageFirstCp, pageLastCp)) {
        bgFillPaint.color = searchHighlightArgb
        drawCpRangeRectsInPage(
            nc, page, searchHighlightCpRange.first, searchHighlightCpRange.last + 1,
            visibleWidthF, bgFillPaint,
        )
    }

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

    // [诊断 SelBgDiag] 覆盖翻页 txt 选区背景不显示 —— 绘制判定（排查完删）
    if (!selectionCpRange.isEmpty()) {
        com.morealm.app.core.log.AppLog.info(
            "SelBgDiag",
            "canvas pageChapter=${page.chapterIndex} pageCp=$pageFirstCp..$pageLastCp selCp=$selectionCpRange " +
                "intersects=${rangeIntersectsPage(selectionCpRange, pageFirstCp, pageLastCp)}",
        )
    }
    if (!selectionCpRange.isEmpty() && rangeIntersectsPage(selectionCpRange, pageFirstCp, pageLastCp)) {
        bgFillPaint.color = selectionArgb
        drawCpRangeRectsInPage(
            nc, page, selectionCpRange.first, selectionCpRange.last + 1,
            visibleWidthF, bgFillPaint,
        )
    }

    // ─── 层 2：文字 ───
    // 链接可解析性：着色与虚线共用同一判定 —— 目标确认可解析才提示（着链接色 + 虚线），
    // 死链完全不提示（与点击端「未找到链接目标」同一语义源）。判定异步完成前按普通文字画。
    val resolvableLinkRanges = LinkTargetResolvability.resolvableRangesFor(page.chapterIndex)
    fun cpIsResolvableLink(cp: Int): Boolean =
        resolvableLinkRanges.any { cp >= it.startCp && cp < it.endCpExclusive }
    val linkStroke = (contentPaint.textSize * 0.045f).coerceAtLeast(1.5f)
    // 链接虚线段 [left, right, y]，在层 2 逐字符收集、层 3 统一绘制。
    //
    // y 取的是**画这个字符时用的那条基线**（colBaselineY），而不是行底：注号是被
    // baselineShift 抬高的上标，用 line.lineBottom 会把虚线留在正文基线上，看起来像
    // 飘到了下一行。table cell 分支早就按 atom 自身基线画（见层 3），普通行同理。
    val linkDashSegments = ArrayList<FloatArray>()
    // 作者把整页涂成自己的底色之后，主题前景笔（夜间=浅灰白）会整段消失在白纸上。
    // 三支笔一次性换成「在该底色上可读」的颜色，画完本页原样还回去 —— 它们是跨 page
    // 复用的共享对象，绝不能留残留色。对比度够时 foregroundOnAuthoredSurface 原样返回，
    // 无作者底色的页（绝大多数）走 null 分支，一个字节都不动。
    val themePaintColors = authoredPageSurfaceArgb?.let { surface ->
        val paints = listOf(contentPaint, titlePaint, chapterNumPaint)
        // 先全部读出再统一写入：三支笔在某些配置下可能是同一个对象，边读边写会把
        // 上一支已改过的颜色当成原色存下来，还原时静默写错。
        val saved = paints.map { it.color }
        paints.forEachIndexed { i, p -> p.color = foregroundOnAuthoredSurface(surface, saved[i]) }
        saved
    }
    for (line in page.lines) {
        if (line.isImage) {
            val src = line.imageSrc ?: continue
            val isFullPage = line.isFullPageImage
            val slotW: Float
            val baseX: Float
            val cacheTargetW: Int
            if (isFullPage) {
                slotW = chapterViewWidth.toFloat()
                baseX = -chapterPaddingLeft.toFloat()
                // cover 铺满整屏时以短边为准放大，需要的位图宽可能超过屏宽
                // （竖屏放 3:4 封面：宽 = viewHeight × 原图宽高比）。按原图 dims 预算，
                // 拿不到 dims 退回屏宽（等价旧行为，只是 cover 放大后略糊）。
                cacheTargetW = com.morealm.app.domain.render.ImageCache.getBounds(src)
                    ?.let { (w, h) ->
                        if (w > 0 && h > 0) {
                            maxOf(
                                chapterViewWidth,
                                (viewHeightF * w / h).toInt() + 1,
                            )
                        } else null
                    }
                    ?.coerceAtLeast(1) ?: chapterViewWidth.coerceAtLeast(1)
            } else {
                // box 内的图按 box 内容区取 slot（定宽容器 `width:300px` 里的 `<img width="85%">`
                // 该量容器的 85%，且容器不居中时图要跟着容器走）；引擎未给区间则退化为整屏。
                val boxLeft = line.imageLeftPx
                val boxRight = line.imageRightPx
                if (boxRight > boxLeft) {
                    slotW = boxRight - boxLeft
                    baseX = boxLeft
                } else {
                    slotW = visibleWidthF
                    baseX = 0f
                }
                cacheTargetW = slotW.toInt().coerceAtLeast(1)
            }
            // FULLSCREEN/FIT_WINDOW 图片属于独立页面展示语义，不应被标题段的 lineTop 或
            // content 行高挤到页面中间。整屏封面按**cover**铺满物理页（对齐成熟阅读器的
            // 全覆盖封面）：短边贴满、长边居中裁切 —— svg 封面（1000x1333）在 9:16 屏上
            // 若按 fit 只能铺满宽度，上下各留一条黑边，观感是"半屏图"而不是封面页。
            // 超出部分由上方放宽到物理页边的 clip 裁掉。普通插图行保持 fit（不裁内容）。
            val slotTop = if (isFullPage) 0f else line.lineTop
            val slotH = if (isFullPage) viewHeightF else line.lineBottom - line.lineTop
            val bitmap = com.morealm.app.domain.render.ImageCache.get(
                src, cacheTargetW,
            ) ?: continue
            val bmpW = bitmap.width.toFloat()
            val bmpH = bitmap.height.toFloat()
            val scale = if (isFullPage) {
                maxOf(slotW / bmpW, slotH / bmpH)
            } else {
                minOf(slotW / bmpW, slotH / bmpH)
            }
            val drawW = bmpW * scale
            val drawH = bmpH * scale
            val offsetX = baseX + (slotW - drawW) / 2f
            val offsetY = slotTop + (slotH - drawH) / 2f
            nc.drawBitmap(
                bitmap,
                null,
                android.graphics.RectF(offsetX, offsetY, offsetX + drawW, offsetY + drawH),
                null,
            )
            continue
        }
        if (line.isHorizontalRule) {
            // ── <hr/> 横线 ── 同 ChapterPaneCanvas：line 垂直中线画贴 box 内容宽的水平线。
            // canvas 已 translate paddingLeft，hrLeftPx/hrRightPx 直接用；复用 bgFillPaint（FILL）。
            val cy = line.lineTop + (line.lineBottom - line.lineTop) / 2f
            drawHorizontalRuleLine(nc, line, cy, contentPaint, bgFillPaint)
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
        val epubTypeface = com.morealm.app.domain.font.EpubFontRegistry.resolveActive(line.blockStyle.fontFamily)
        val savedTypeface = if (epubTypeface != null) paint.typeface.also { paint.typeface = epubTypeface } else null

        // **P1 fix (2026-05-27)** — swap 后用 swap 字体的 ascent 重算 baselineY，避免 columns
        // path 用 fontMetrics 函数顶部预算的「默认 paint」ascent 常量让自带字体 descent 超出
        // lineBottom 余量被 clipRect 切。cells/atoms path 的 baseline 是 emit 时按 innerLH×0.8
        // 算好的（atom 局部坐标），不受此修影响。
        val effectiveAscent: Float = if (savedTypeface != null) -paint.fontMetrics.ascent else ascent

        // **EpubW5H/FontSwap diag** — swap 前后 metrics 对比，用于验证 P1 修复
        if (savedTypeface != null) {
            val fm = paint.fontMetrics
            val newDescent = fm.descent
            val newAscent = -fm.ascent
            val baselineYDbg = line.lineTop + effectiveAscent
            val roomBelow = line.lineBottom - baselineYDbg
            com.morealm.app.core.log.AppLog.info(
                "EpubW5H/FontSwap",
                "family='${line.blockStyle.fontFamily}' " +
                    "oldAscent=$ascent newAscent=$newAscent newDescent=$newDescent " +
                    "effAscent=$effectiveAscent " +
                    "lineTop=${line.lineTop} lineBottom=${line.lineBottom} " +
                    "baselineY=$baselineYDbg roomBelow=$roomBelow " +
                    "overflow=${newDescent > roomBelow} " +
                    "text40='${line.text.take(40).replace("\n", "\\n")}'",
            )
        }

        val baselineY = line.lineTop + effectiveAscent
        val paragraphColor = line.blockStyle.textColor?.let {
            adaptAuthoredForegroundForReaderBg(it, decorBgArgb, line.blockStyle.backgroundColor)
        }
        if (paragraphColor != null) paint.color = paragraphColor
        val ts = line.blockStyle.textShadow
        if (ts != null && ts.blurRadius >= 1f) {
            paint.setShadowLayer(
                ts.blurRadius,
                ts.offsetX,
                ts.offsetY,
                adaptAuthoredForegroundForReaderBg(ts.color, decorBgArgb),
            )
        }
        val shadowApplied = ts != null && ts.blurRadius >= 1f
        val blockRotationSave = saveScrollLineRotation(nc, page.lines, line)
        val lineCells = line.cells
        val lineAtoms = line.atoms
        if (lineCells != null) {
            drawByCells(
                nc, lineCells, line, paint, defaultColor, textColorByCp, decorBgArgb,
                ::cpIsResolvableLink,
            )
            if (blockRotationSave != null) nc.restoreToCount(blockRotationSave)
            if (paragraphColor != null) paint.color = defaultColor
            if (shadowApplied) paint.clearShadowLayer()
            if (savedTypeface != null) paint.typeface = savedTypeface
            continue
        }
        if (lineAtoms != null) {
            drawByAtoms(nc, lineAtoms, line, paint, baselineY, defaultColor, textColorByCp, decorBgArgb, ::cpIsResolvableLink)
            if (blockRotationSave != null) nc.restoreToCount(blockRotationSave)
            if (paragraphColor != null) paint.color = defaultColor
            if (shadowApplied) paint.clearShadowLayer()
            if (savedTypeface != null) paint.typeface = savedTypeface
            continue
        }
        for (col in line.columns) {
            if (col.inlineImageSrc != null) {
                val bmp = com.morealm.app.domain.render.ImageCache.get(
                    col.inlineImageSrc!!, (col.end - col.start).toInt(),
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
                continue
            }
            // 优先级：用户高亮 > 链接色（脚注号/跳转可辨识，日夜自适应）> CSS char-level 色
            val overrideColor = textColorByCp[col.chapterPosition]
                ?: when {
                    col.isLink && cpIsResolvableLink(col.chapterPosition) ->
                        linkForegroundForReaderBg(decorBgArgb)
                    // 死链按普通正文色画，不能让 EPUB authored `<a>` 蓝色泄漏成误导提示。
                    col.isLink -> defaultColor
                    else -> col.colorArgb?.let {
                        adaptAuthoredForegroundForReaderBg(it, decorBgArgb, line.blockStyle.backgroundColor)
                    }
                }
            // 上下标：sup 上移 0.35×字号 / sub 下移 0.15×字号（宽度缩放已在排版期完成）
            val colBaselineY = when {
                col.baselineShift > 0 -> baselineY - paint.textSize * 0.35f
                col.baselineShift < 0 -> baselineY + paint.textSize * 0.15f
                else -> baselineY
            }
            // 可解析链接的虚线提示：记下这个字符**实际**的基线，层 3 据此画。
            // 空白列不计入 —— 行尾对齐填充的空格会让虚线拖出一截。
            if (col.isLink && col.charData.isNotBlank() && cpIsResolvableLink(col.chapterPosition)) {
                linkDashSegments += floatArrayOf(col.start, col.end, colBaselineY + linkStroke * 1.5f)
            }
            paint.withEpubTypeface(col.fontFamily) {
                if (overrideColor != null) {
                    paint.color = overrideColor
                    nc.drawText(col.charData, col.start, colBaselineY, paint)
                    paint.color = paragraphColor ?: defaultColor
                } else {
                    nc.drawText(col.charData, col.start, colBaselineY, paint)
                }
            }
        }
        if (blockRotationSave != null) nc.restoreToCount(blockRotationSave)
        if (paragraphColor != null) paint.color = defaultColor
        if (shadowApplied) paint.clearShadowLayer()
        if (savedTypeface != null) paint.typeface = savedTypeface
    }

    // ─── 层 3：下划线 ───
    val underlineStroke = (contentPaint.textSize * 0.1f).coerceAtLeast(2.5f)
    val fm = contentPaint.fontMetrics
    val textHeight = -fm.ascent + fm.descent
    // ── 书内链接虚线提示 ── 只画「目标确认可解析」的链接（死链不画不着色提示）；
    // 注号图标（inline image link）不画 —— 虚线是文字链接的可点提示，图标本身已是提示。
    val linkRanges = resolvableLinkRanges
    if (linkRanges.isNotEmpty()) {
        val linkArgb = (linkForegroundForReaderBg(decorBgArgb) and 0x00FFFFFF) or (0xB3 shl 24)
        val linkDashPaint = underlinePaintFor(linkArgb, Highlight.UNDERLINE_STYLE_DASHED, linkStroke)
        for (range in linkRanges) {
            for (line in page.lines) {
                if (line.lastChapterPos < range.startCp || line.firstChapterPos >= range.endCpExclusive) continue
                val cells = line.cells ?: continue
                // table row 的 lineBottom 是整行最高 cell 的底；逐 atom 用自身基线，
                // 否则短 cell / 多行 cell 的链接虚线会掉到整张表格行底。
                var atomStartCp = line.firstChapterPos
                for (cell in cells) {
                    for (atom in cell.atoms) {
                        val atomEndCp = atomStartCp + atom.cpCount
                        if (atom is com.morealm.epub.render.TextRun && atom.isLink &&
                            atom.text.isNotBlank() && atomStartCp < range.endCpExclusive &&
                            atomEndCp > range.startCp
                        ) {
                            val left = cell.contentLeft + cell.paddingLeft + atom.cellLocalX
                            val right = left + atom.width
                            val baseline = line.lineTop + cell.contentTop + cell.paddingTop +
                                cell.contentOffsetY + atom.cellLocalY + atom.baseline
                            val atomBottom = line.lineTop + cell.contentTop + cell.paddingTop +
                                cell.contentOffsetY + atom.cellLocalY + atom.height
                            val y = (baseline + linkStroke * 1.5f)
                                .coerceAtMost(atomBottom - linkStroke)
                            if (right > left) nc.drawLine(left, y, right, y, linkDashPaint)
                        }
                        atomStartCp = atomEndCp
                    }
                }
            }
        }
        // 普通行：层 2 已按每个字符**实际**的基线收集好线段。这里把横向相接的
        // 相邻段并成一条再画 —— 逐字符各画一次会让 DashPathEffect 的相位在每个
        // 字边界重置，虚线呈现出不均匀的断点。
        var i = 0
        while (i < linkDashSegments.size) {
            val seg = linkDashSegments[i]
            var right = seg[1]
            var j = i + 1
            while (j < linkDashSegments.size) {
                val next = linkDashSegments[j]
                if (next[2] != seg[2] || next[0] > right + 0.5f) break
                right = maxOf(right, next[1])
                j++
            }
            if (right > seg[0]) nc.drawLine(seg[0], seg[2], right, seg[2], linkDashPaint)
            i = j
        }
    }
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

    themePaintColors?.let { saved ->
        contentPaint.color = saved[0]
        titlePaint.color = saved[1]
        chapterNumPaint.color = saved[2]
    }
    nc.restore()
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
    atoms: List<com.morealm.epub.render.Atom>,
    line: com.morealm.epub.render.ScrollLine,
    basePaint: android.text.TextPaint,
    baselineY: Float,
    defaultColor: Int,
    textColorByCp: Map<Int, Int> = emptyMap(),
    readerBgArgb: Int,
    /** 链接可解析性判定（cp → 是否着链接色）。死链不提示，语义同 columns 路径。 */
    isCpResolvableLink: (Int) -> Boolean = { true },
) {
    // **bugfix 2026-05-22**：用 line.columns[0].start 作初始 x（emit 阶段算的对齐起点，
    // 含 CSS text-align center/right 居中偏移）。之前 var x = 0f 让 atoms 路径无视
    // startX，CENTER 段（如某仙侠 h2.head「惊蛰」）退化成 left-aligned。
    var x = line.columns.firstOrNull()?.start ?: 0f
    val baseSize = basePaint.textSize
    var atomStartCp = line.firstChapterPos  // A5 Step 2：atom 起始 cp 用于 textColorByCp 查询
    for (atom in atoms) {
        when (atom) {
            is com.morealm.epub.render.TextRun -> {
                val scale = atom.sizeScale
                if (scale != 1f) basePaint.textSize = baseSize * scale
                // 上下标优先（sup 从父字号基线上移 0.33×、sub 下移 0.15×，对齐浏览器
                // vertical-align: super/sub 视觉）；否则 sizeScale ≠ 1 时沿用
                // vertical-align: top fallback 让小字号字符顶贴 line 顶；
                // sizeScale = 1 → caller baselineY 兼容现有路径（H2/某日轻 em15 等）。
                val effectiveBaselineY = when {
                    atom.baselineShift > 0 -> baselineY - baseSize * 0.33f
                    atom.baselineShift < 0 -> baselineY + baseSize * 0.15f
                    scale != 1f -> line.lineTop + basePaint.textSize * 0.8f
                    else -> baselineY
                }
                // 链接色 > CSS char-level 色；死链强制回正文色，避免 authored 蓝色误导。
                val atomOverrideColor = when {
                    atom.isLink && isCpResolvableLink(atomStartCp) ->
                        linkForegroundForReaderBg(readerBgArgb)
                    atom.isLink -> defaultColor
                    else -> atom.colorArgb?.let {
                        adaptAuthoredForegroundForReaderBg(it, readerBgArgb, atom.inlineBgArgb)
                    }
                }
                basePaint.withEpubTypeface(atom.fontFamily) {
                    val rotationSave = if (atom.rotationDegrees != 0f) {
                        canvas.save().also {
                            canvas.rotate(
                                atom.rotationDegrees,
                                x + atom.width / 2f,
                                effectiveBaselineY - basePaint.textSize * 0.3f,
                            )
                        }
                    } else null
                    // Phase 4：字符级 inline 背景盒子（底层方块），无 bg 时零开销返回
                    drawInlineBg(canvas, atom, x, effectiveBaselineY, basePaint, readerBgArgb)
                    val textX = x + atom.inlineBgPaddingLeftPx
                    // A5 Step 2：检查 atom range 内有无 user 高亮 textColorByCp override
                    // 命中 → 退化 char-by-char 按 cp 独立涂色；无 → fast path 整 atom drawText
                    val hasOverride = if (textColorByCp.isNotEmpty()) {
                        (0 until atom.cpCount).any { textColorByCp.containsKey(atomStartCp + it) }
                    } else false
                    if (hasOverride) {
                        var cx = textX
                        val baseColor = atomOverrideColor ?: defaultColor
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
                        if (atomOverrideColor != null) {
                            basePaint.color = atomOverrideColor
                        }
                        canvas.drawText(atom.text, textX, effectiveBaselineY, basePaint)
                        if (atomOverrideColor != null) basePaint.color = origColor
                    }
                    if (rotationSave != null) canvas.restoreToCount(rotationSave)
                }
                if (scale != 1f) basePaint.textSize = baseSize
                x += atom.width
            }
            is com.morealm.epub.render.InlineImage -> {
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
    cells: List<com.morealm.epub.render.ScrollLineCell>,
    line: com.morealm.epub.render.ScrollLine,
    basePaint: android.text.TextPaint,
    defaultColor: Int,
    textColorByCp: Map<Int, Int> = emptyMap(),
    readerBgArgb: Int,
    /** 链接可解析性判定，确保 table-cells 路径与 columns/atoms 路径视觉语义一致。 */
    isCpResolvableLink: (Int) -> Boolean = { true },
) {
    val baseSize = basePaint.textSize
    val fontScale = baseSize / 16f
    var atomStartCp = line.firstChapterPos
    for (cell in cells) {
        // **Task 2-D（聊天气泡 div.kuang-hei 边框）**：cell 有 box 装饰 → 画字前画圆角边框盒。
        // rect = cell 内容包围盒向外扩 padding（CSS content-box：padding/border 在 content 之外）。
        // 复用 drawBoxDecorations（边框 / 圆角 / 若有 bg 走夜间自适应）。
        cell.boxStyle?.let { bs ->
            val decorationTop = line.lineTop + cell.contentTop + cell.decorationTopOffsetY
            drawBoxDecorations(
                canvas, bs,
                cell.contentLeft,
                decorationTop,
                cell.contentLeft + cell.decorationWidth,
                decorationTop + cell.decorationHeight,
                fontScale,
                readerBgArgb = readerBgArgb,
                dashPhasePx = cell.borderDashPhasePx,
            )
        }
        for (atom in cell.atoms) {
            when (atom) {
                is com.morealm.epub.render.TextRun -> {
                    val scale = atom.sizeScale
                    if (scale != 1f) basePaint.textSize = baseSize * scale
                    val effectiveX = cell.contentLeft + cell.paddingLeft + atom.cellLocalX
                    val effectiveBaselineY = line.lineTop + cell.contentTop + cell.paddingTop +
                        cell.contentOffsetY + atom.cellLocalY + atom.baseline
                    basePaint.withEpubTypeface(atom.fontFamily) {
                        val rotationSave = if (atom.rotationDegrees != 0f) {
                            canvas.save().also {
                                canvas.rotate(
                                    atom.rotationDegrees,
                                    effectiveX + atom.width / 2f,
                                    effectiveBaselineY - basePaint.textSize * 0.3f,
                                )
                            }
                        } else null
                        // Phase 4：字符级 inline 背景盒子（底层方块），无 bg 时零开销返回
                        drawInlineBg(canvas, atom, effectiveX, effectiveBaselineY, basePaint, readerBgArgb)
                        val textX = effectiveX + atom.inlineBgPaddingLeftPx
                        val hasOverride = if (textColorByCp.isNotEmpty()) {
                            (0 until atom.cpCount).any { textColorByCp.containsKey(atomStartCp + it) }
                        } else false
                        if (hasOverride) {
                            var cx = textX
                            val baseColor = when {
                                atom.isLink && isCpResolvableLink(atomStartCp) ->
                                    linkForegroundForReaderBg(readerBgArgb)
                                atom.isLink -> defaultColor
                                else -> atom.colorArgb?.let {
                                    adaptAuthoredForegroundForReaderBg(it, readerBgArgb, atom.inlineBgArgb)
                                } ?: defaultColor
                            }
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
                            val atomColor = when {
                                atom.isLink && isCpResolvableLink(atomStartCp) ->
                                    linkForegroundForReaderBg(readerBgArgb)
                                atom.isLink -> defaultColor
                                atom.colorArgb != null -> adaptAuthoredForegroundForReaderBg(
                                    atom.colorArgb!!, readerBgArgb, atom.inlineBgArgb,
                                )
                                else -> null
                            }
                            if (atomColor != null) {
                                basePaint.color = atomColor
                            }
                            canvas.drawText(atom.text, textX, effectiveBaselineY, basePaint)
                            if (atomColor != null) basePaint.color = origColor
                        }
                        if (rotationSave != null) canvas.restoreToCount(rotationSave)
                    }
                    if (scale != 1f) basePaint.textSize = baseSize
                }
                is com.morealm.epub.render.InlineImage -> {
                    val bmp = com.morealm.app.domain.render.ImageCache.get(atom.src, atom.width.toInt())
                    if (bmp != null) {
                        val drawX = cell.contentLeft + cell.paddingLeft + atom.cellLocalX
                        val drawY = line.lineTop + cell.contentTop + cell.paddingTop +
                            cell.contentOffsetY + atom.cellLocalY
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
