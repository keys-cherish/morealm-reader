package com.morealm.app.domain.render.scroll

import android.text.TextPaint
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.render.TextMeasure
import com.morealm.app.domain.render.ZhLayout
import com.morealm.app.domain.render.textHeight

/**
 * 滚动 Canvas 引擎 —— 把原始章节文本排版成 [ScrollChapterLayout]（含每字符的像素坐标）。
 *
 * ── 范围与隔离原则 ──
 *
 * 1. **完全独立于 [com.morealm.app.domain.render.ChapterProvider]**：ChapterProvider
 *    服务 SIMULATION/COVER/SLIDE 翻页模式，输出 TextChapter（page-based 翻页）。
 *    本引擎服务新滚动 Canvas 模式（pixelOffset 三块面板），输出 [ScrollChapterLayout]。
 *    两套引擎共存且互不依赖、互不引用，避免一处改影响另一处。
 *
 * 2. **完全独立于 [com.morealm.app.domain.render.ScrollParagraph]**：后者服务旧
 *    LazyColumn 滚动路径（开发期间兜底）。新引擎不复用其数据结构；旧引擎在新引擎
 *    稳定后整体删除。
 *
 * 3. **纯 domain 层**：禁止依赖 Compose / Android UI 类（TextPaint 是 android.text 系统
 *    类，跨 domain/UI 可接受，与 [ChapterProvider] 一致）。
 *
 * 4. **可共用底层纯工具**：[TextMeasure]（字符宽度测量）/ [textHeight]（paint 行高扩展）
 *    / `ZhLayout`（CJK 标点压缩，M1.3 接入）/ `PaintPool` 都是与业务无关的字符级工具，
 *    与 [ChapterProvider] 共用不构成业务耦合（共用工具 ≠ 引擎关联）。
 *
 * ── 与 Legado 的关系 ──
 *
 * 数据结构 [ScrollColumn] / [ScrollLine] / [ScrollPage] / [ScrollChapterLayout] 参考
 * Legado `TextColumn` / `TextLine` / `TextPage` / `TextChapter` 的字段语义，但命名加
 * `Scroll` 前缀避免与本项目既有 `TextLine` / `TextPage`（在 PageLayout 路径下）冲突。
 *
 * 排版算法 M1.2-M1.5 参考 Legado [外部开源阅读器实现]
 * 主流程独立实现；Compose / Canvas 渲染层（M2）走 MoRealm 自有。
 */
class ScrollLayoutEngine(
    val viewWidth: Int,
    val viewHeight: Int,
    val paddingLeft: Int,
    val paddingRight: Int,
    val paddingTop: Int,
    val paddingBottom: Int,
    val titlePaint: TextPaint,
    val contentPaint: TextPaint,
    /**
     * 段首缩进字符（默认空）—— 注意：实际场景中 [com.morealm.app.domain.webbook.ContentProcessor]
     * 已经在每段文本前加了 "　　"，因此 engine 默认不再 额外加 indentWidth offset，否则
     * 会变成双重缩进（用户反馈 image #11 缩进 ≈ 4 字宽）。
     * 若上层传入未经 ContentProcessor 的 raw content（如某些路径），可显式设 "　　"。
     */
    val paragraphIndent: String = "",
    val textFullJustify: Boolean = true,
    val titleMode: Int = 0,         // 0=left, 1=center, 2=hidden
    val titleAlign: Int = 0,        // 0=left, 1=center, 2=right
    val useZhLayout: Boolean = true,
    val lineSpacingExtra: Float = 1.2f,
    val paragraphSpacing: Int = 8,
    val titleTopSpacing: Int = 0,
    val titleBottomSpacing: Int = 0,
    /** 章首块（橙色章序号 + 大字主标题 + 装饰线）专用 paint。null = 用 [titlePaint]。 */
    val chapterNumPaint: TextPaint? = null,
    /**
     * 图片像素 dims 解析器 —— 注入式避免 domain 层耦合 ImageCache / BitmapFactory。
     * 默认 [ScrollImageDimensionsResolver.NoOp]（走 4:3 fallback）；生产代码在 DI 模块注入
     * 桥接 [com.morealm.app.domain.render.ImageCache] 的实现，单测可注入 mock。
     */
    val imageDimensionsResolver: ScrollImageDimensionsResolver = ScrollImageDimensionsResolver.NoOp,
    /**
     * 翻页布局模式：false = SCROLL 连续滚动（章内 page 之间 currentY=0 紧贴无留白，让滚动连续）；
     * true = page-level 翻页 (NONE/COVER/SLIDE)，章内每个 page 第一行 lineTop = paddingTop，
     * 给顶部 InfoBar 渐变让位避免半透盖文（参 bug-casebook 2026-05-20 案例：
     * P4.6 失败的原因是只动了 flushPage 没动 needNewPage 路径，本次两处都按 mode fork）。
     */
    val pageLevelMode: Boolean = false,
) {

    val visibleWidth: Int = viewWidth - paddingLeft - paddingRight
    val visibleHeight: Int = viewHeight - paddingTop - paddingBottom

    private val contentTextMeasure: TextMeasure = TextMeasure(contentPaint)
    private val contentTextHeight: Float = contentPaint.textHeight
    private val contentLineHeight: Float = contentTextHeight * lineSpacingExtra

    /**
     * 章首块 title 主行用 paint / 测量器 / 行高。复用 [titlePaint]。
     * 章首块行高直接用 textHeight（**不**乘 lineSpacingExtra），与旧
     * [com.morealm.app.domain.render.ChapterProvider] 对齐——标题行紧凑。
     */
    private val titleTextMeasure: TextMeasure = TextMeasure(titlePaint)
    private val titleTextHeight: Float = titlePaint.textHeight

    /**
     * 章首块章序号小字行用 paint / 测量器 / 行高。复用 [chapterNumPaint]，null 时 fallback
     * [titlePaint]。
     */
    private val chapterNumTextMeasureSafe: TextMeasure = TextMeasure(chapterNumPaint ?: titlePaint)
    private val chapterNumTextHeightSafe: Float = (chapterNumPaint ?: titlePaint).textHeight

    /**
     * 段首缩进的像素宽度 —— 作为**排版属性**应用（首行 lineCursor 起点 = indentWidth），
     * 而**不**作为字符拼进 measureTextSplit。
     *
     * 设计理由（见 user feedback 2026-05-17）：
     * - 缩进是 layout margin，不是原文字符 → 不占 ScrollColumn 位 → 高亮 rect 不延伸缩进区域
     * - findColumnByPixel 命中段首 x < indentWidth 时吸附到段首第一个真实字符（M1.7 实现）
     * - 持久化 chapterPosition 与原文字符 offset 严格 1:1，跨引擎一致
     */
    private val indentWidth: Float = contentPaint.measureText(paragraphIndent)

    /**
     * 段间空白像素值 —— 与旧 [com.morealm.app.domain.render.ChapterProvider] 量级对齐：
     * `textHeight * paragraphSpacing / 10f`（Legado 同款单位语义）。
     * paragraphSpacing 字段值 8 在 textSize 48 下 ≈ 0.8 × 行高，视觉自然。
     *
     * **跨页行为**：段末刚好跨页时**不**补到新页顶（强硬方案 1，纠正旧引擎可能的累加行为）。
     * 视觉合理：新页从 paddingTop 起排下一段第一行，无额外段间距污染。
     */
    private val paragraphSpacingPx: Float = contentTextHeight * paragraphSpacing / 10f

    /**
     * 排版入口：把章节文本切成页 / 行 / 字符坐标。
     *
     * M1 实施进度（每个微步完成后此文档同步更新）：
     *   M1.2 ✓ 纯文本路径：段切分 + 字符级 measure + 行打断 + visibleHeight 触发分页
     *   M1.3 ⨯ ZhLayout CJK 行打断 + textFullJustify 末行对齐
     *   M1.4 ✓ 章首样式块（章序号 + 主标题 + 装饰横条），cp 占用与旧引擎严格对齐
     *   M1.5 ⨯ 图片段（占位 column + 实际像素高）
     *
     * @param chapterIndex 章 idx（全书内 0-based）
     * @param title 章节显示标题（用于章首块；空 / titleMode==2 / omitChapterTitleBlock 时跳过）
     * @param content 章节正文（已经 contentProcessor 处理；M1.5 前图片占位标记当普通文本）
     * @param omitChapterTitleBlock true 时跳过章首块（用于本地 TXT 自动分章场景，
     *        每段被当独立章但不画 N 次相同伪章名标题）；与 [titleMode] = 2 等价但作用域只在本次调用
     * @return [ScrollChapterLayout]，含每字符的像素坐标和 chapterPosition
     */
    fun layoutChapter(
        chapterIndex: Int,
        title: String,
        content: String,
        omitChapterTitleBlock: Boolean = false,
    ): ScrollChapterLayout {
        // 段切分语义（精确对齐持久化坐标语义）：
        //   - 按 `\n` 拆，段末 `\r` 去除（容忍 CRLF）
        //   - 空段（连续 `\n\n` 之间的空字符串）**保留**：产生空 ScrollLine（columns 空、
        //     高 = contentLineHeight）+ chapterPosition += 1（用户决策 2026-05-17：
        //     空段占 1 个 cp，与原文 \n 位置 1:1 对齐）
        val rawParagraphs = content.split('\n').map { it.trimEnd('\r') }

        // ── 重复标题去重（V1 ChapterProvider.stripDuplicateTitleSegments + isSameChapterTitle 等价）──
        // 书源书籍 / EPUB 常常把章名重复写在正文开头，与自画 title 块同框 → 用户看到两次。
        // 算法 1：stripDuplicateTitleSegments —— N=3..1 尝试，前 N 段拼接 normalized == title → drop。
        // 算法 2：isSameChapterTitle —— 第 1 段单独 normalized == title → contentProvidesChapterTitle=true，跳过自画块。
        // 重复标题去重 —— 保留自画 title 块（大字），剥掉 content 里重复的 title 文本。
        // 3 种场景：
        //   A. 第 1 段 normalized 完整等于 title → drop 该段
        //   B. 前 N 段 normalized 拼起来 == title → drop 那 N 段
        //   C. 第 1 段以 title 开头（含装饰字符）→ 段内剥掉 title prefix，保留剩余正文
        //      例（用户截图 V2-WEB）：title="第61章 大明群星闪耀！（求票票）"
        //      content[0]="第61章大明群星闪耀！（求票票~）思虑已定..."
        //      title 字符按顺序在段首出现，"~" 是装饰 → 剥掉 "第61章...票票）" 留 "思虑已定..."
        val normalizedTitle = normalizeTitleForCompare(title)
        val paragraphs = stripTitleFromParagraphs(rawParagraphs, normalizedTitle)
        val contentProvidesChapterTitle = false  // 保留自画 title 块；正文 title 已被 strip 掉

        val pages = mutableListOf<ScrollPage>()
        var currentPageLines = mutableListOf<ScrollLine>()
        var currentY = paddingTop.toFloat()
        var chapterPositionCounter = 0
        var paragraphCounter = 0

        fun flushPage() {
            // 修复用户反馈"段间距过大"+ totalHeight 暴涨（285701/395359 px）：
            // V2 是滚动模式（pixelOffset 像素滚），pages 仅是 viewport culling 单元，
            // 内部不应该有"翻页"留白。之前 page.height = lineBottom + paddingBottom，
            // flushPage 后 currentY = paddingTop → 内部 page 之间多 paddingBottom +
            // paddingTop ≈ 600 px 视觉留白，223 个内部 page × 600 = 140k px 纯空白。
            //
            // 修：内部 page 之间不留白（page.height = lineBottom），currentY = 0 让下
            // page 第一行紧贴上 page 末。仅章首 / 章末由 ScrollChapterLayout.paddingTop /
            // paddingBottom 字段（Renderer placement 处理章间重叠）。
            val height = currentPageLines.lastOrNull()?.lineBottom
                ?: (paddingTop + paddingBottom).toFloat()
            pages.add(
                ScrollPage(
                    pageIndex = pages.size,
                    lines = currentPageLines.toList(),
                    height = height,
                    chapterIndex = chapterIndex,
                )
            )
            currentPageLines = mutableListOf()
            // page-level 模式：章内每 page 都给 paddingTop 让位 InfoBar；SCROLL 模式保持 0 紧贴
            currentY = if (pageLevelMode) paddingTop.toFloat() else 0f
        }

        // emitLine：把一行 columns 打包成 ScrollLine 追加到 currentPageLines；
        // 行底超出 viewHeight - paddingBottom 时先 flushPage 再排该行到新页顶。
        // firstCp / lastCp 由调用方根据 line 类型决定：
        //   - 非空文本行：lineColumns.first/last.chapterPosition
        //   - 空段 / 图片段：该 line 占的那 1 cp
        // lineHeightOverride: 章首块用 titleTextHeight / chapterNumTextHeightSafe 替代
        // contentLineHeight。null = 用默认 contentLineHeight（正文 / 空段）。
        // P3-5b Phase 3：当前正在处理的 paragraph 的 blockStyle。每次进入新 paragraph 时
        // 在循环顶部设值，emitLine 直接读这个共享变量挂到 ScrollLine 上（避免改 emitLine
        // 入参 + 所有调用方的级联改动）。EMPTY = 无装饰（章首块 / 正常段默认）。
        var currentBlockStyle: com.morealm.epub.compat.BlockStyle = com.morealm.epub.compat.BlockStyle.EMPTY

        fun emitLine(
            lineColumns: List<ScrollColumn>,
            lineText: String,
            paragraphNum: Int,
            firstChapterPos: Int,
            lastChapterPos: Int,
            isTitle: Boolean = false,
            isChapterNum: Boolean = false,
            isTitleEnd: Boolean = false,
            isImage: Boolean = false,
            imageSrc: String? = null,
            lineHeightOverride: Float? = null,
        ) {
            val effectiveLineHeight = lineHeightOverride ?: contentLineHeight
            val proposedTop = currentY
            val proposedBottom = proposedTop + effectiveLineHeight
            val needNewPage = proposedBottom + paddingBottom > viewHeight && currentPageLines.isNotEmpty()
            val finalTop: Float
            val finalBottom: Float
            if (needNewPage) {
                flushPage()
                // page-level 模式：新 page 第一行用 paddingTop 让位 InfoBar；SCROLL 模式紧贴 0
                finalTop = if (pageLevelMode) paddingTop.toFloat() else 0f
                finalBottom = finalTop + effectiveLineHeight
            } else {
                finalTop = proposedTop
                finalBottom = proposedBottom
            }
            currentPageLines.add(
                ScrollLine(
                    columns = lineColumns,
                    lineTop = finalTop,
                    lineBottom = finalBottom,
                    paragraphNum = paragraphNum,
                    isTitle = isTitle,
                    text = lineText,
                    firstChapterPos = firstChapterPos,
                    lastChapterPos = lastChapterPos,
                    isChapterNum = isChapterNum,
                    isTitleEnd = isTitleEnd,
                    isImage = isImage,
                    imageSrc = imageSrc,
                    blockStyle = currentBlockStyle,
                )
            )
            currentY = finalBottom
        }

        // emitImage：识别 `<img src="...">` → emit 单个 ScrollLine（columns 空 +
        // isImage=true + imageSrc=src + height = 图片像素高度）。
        // dims 解码走 [imageDimensionsResolver]；null 时 fallback 4:3（visibleWidth × 0.75）。
        // 占 1 cp 与旧引擎 stringBuilder.append(" ") 严格对齐。
        // 返回累加后的 chapterPositionCounter（含图片占的 1 cp）。
        fun emitImage(src: String, paragraphNum: Int, startCp: Int): Int {
            val dims = imageDimensionsResolver.resolve(src, visibleWidth)
            val imgWidth: Int
            val imgHeight: Int
            if (dims != null && dims.first > 0 && dims.second > 0) {
                val (intW, intH) = dims
                var w = visibleWidth
                var h = (intH.toFloat() * visibleWidth / intW).toInt()
                if (h > visibleHeight) {
                    w = (w.toFloat() * visibleHeight / h).toInt()
                    h = visibleHeight
                }
                imgWidth = w; imgHeight = h
            } else {
                // Fallback 4:3，与旧 ChapterProvider.setTypeImage line 684 兜底一致
                imgWidth = visibleWidth
                imgHeight = (visibleWidth * 0.75f).toInt().coerceAtMost(visibleHeight)
            }

            // emitLine 用 lineHeightOverride 让图片行高 = imgHeight（不走 contentLineHeight）
            emitLine(
                lineColumns = emptyList(),  // 图片不含字符 column
                lineText = " ",              // 占位文本与旧引擎对齐
                paragraphNum = paragraphNum,
                firstChapterPos = startCp,
                lastChapterPos = startCp,
                isImage = true,
                imageSrc = src,
                lineHeightOverride = imgHeight.toFloat(),
            )
            // 图片占 1 cp（旧引擎 stringBuilder.append(" ")）
            return startCp + 1
        }

        // emitTitleParagraph：排版一段 title 文本（可跨行换行），段末追加 \n cp。
        // 与正文段语义对齐：每字符占 1 cp，段末 \n 占 1 cp（旧 ChapterProvider 兼容关键）。
        // 返回累加后的 chapterPositionCounter（含段末 \n）。
        fun emitTitleParagraph(
            text: String,
            textMeasure: TextMeasure,
            lineHeight: Float,
            isChapterNum: Boolean,
            isTitleEnd: Boolean,
            paragraphNum: Int,
            startCp: Int,
        ): Int {
            val (chars, widths) = textMeasure.measureTextSplit(text)
            var lineColumns = mutableListOf<ScrollColumn>()
            var lineCursorX = 0f  // 章首块不缩进
            val lineTextBuilder = StringBuilder()
            var cp = startCp

            // 收集多行 emit 数据（章首块单段可跨行，但 isTitleEnd 只标末行）
            data class TitleLineEmit(
                val cols: List<ScrollColumn>,
                val text: String,
                val firstCp: Int,
                val lastCp: Int,
            )
            val emitted = mutableListOf<TitleLineEmit>()

            fun captureLine() {
                if (lineColumns.isEmpty()) return
                emitted.add(
                    TitleLineEmit(
                        cols = lineColumns.toList(),
                        text = lineTextBuilder.toString(),
                        firstCp = lineColumns.first().chapterPosition,
                        lastCp = lineColumns.last().chapterPosition,
                    ),
                )
                lineColumns = mutableListOf()
                lineTextBuilder.clear()
                lineCursorX = 0f
            }

            for (i in chars.indices) {
                val w = widths[i]
                if (lineCursorX + w > visibleWidth && lineColumns.isNotEmpty()) {
                    captureLine()
                }
                lineColumns.add(
                    ScrollColumn(
                        charData = chars[i],
                        start = lineCursorX,
                        end = lineCursorX + w,
                        chapterPosition = cp,
                    ),
                )
                lineCursorX += w
                lineTextBuilder.append(chars[i])
                cp++
            }
            captureLine()

            // 真正 emit：仅末行带 isTitleEnd（装饰横条只画一次）
            for ((idx, e) in emitted.withIndex()) {
                emitLine(
                    lineColumns = e.cols,
                    lineText = e.text,
                    paragraphNum = paragraphNum,
                    firstChapterPos = e.firstCp,
                    lastChapterPos = e.lastCp,
                    isTitle = true,
                    isChapterNum = isChapterNum,
                    isTitleEnd = isTitleEnd && idx == emitted.lastIndex,
                    lineHeightOverride = lineHeight,
                )
            }

            // 段末隐式 \n 占 1 cp（与旧 ChapterProvider stringBuilder.append('\n') 对齐）
            cp++
            return cp
        }

        // ── 章首样式块（M1.4）──
        // 旧 ChapterProvider 对齐：titleMode != 2 且未 omit 且 title 非空时画章首块。
        // 章首块字符占 cp（chapter-num + title + 各自段末 \n），cp 累加到 chapterPositionCounter。
        // 这与旧引擎 stringBuilder 累加规则严格对齐，保证已存 Highlight.startChapterPos 在
        // 新引擎下反查到同字符（跨引擎兼容关键）。
        if (titleMode != 2 && !omitChapterTitleBlock && !contentProvidesChapterTitle && title.isNotBlank()) {
            val (chapterNumText, titleText) = splitChapterNumAndTitle(title)
            val hasChapterNum = chapterNumText != null
            val hasTitle = titleText.isNotBlank()

            // 1) chapter-num 行（如有）
            if (hasChapterNum) {
                paragraphCounter++
                chapterPositionCounter = emitTitleParagraph(
                    text = chapterNumText!!,
                    textMeasure = chapterNumTextMeasureSafe,
                    lineHeight = chapterNumTextHeightSafe,
                    isChapterNum = true,
                    isTitleEnd = !hasTitle,  // 没 title 时 chapter-num 是章首块末行
                    paragraphNum = paragraphCounter,
                    startCp = chapterPositionCounter,
                )
                // chapter-num 与 title 之间留 0.20 × chapterNumTextHeight 间距（旧引擎对齐）
                if (hasTitle) currentY += chapterNumTextHeightSafe * 0.20f
            }

            // 2) title 主行（可多行：title 内含 \n 切分）
            if (hasTitle) {
                val titleLines = titleText.split('\n').filter { it.isNotBlank() }
                for ((idx, line) in titleLines.withIndex()) {
                    paragraphCounter++
                    chapterPositionCounter = emitTitleParagraph(
                        text = line,
                        textMeasure = titleTextMeasure,
                        lineHeight = titleTextHeight,
                        isChapterNum = false,
                        isTitleEnd = idx == titleLines.lastIndex,  // 最后一行 title 标 isTitleEnd
                        paragraphNum = paragraphCounter,
                        startCp = chapterPositionCounter,
                    )
                }
            }

            // 章首块结束后留间距：(contentTextHeight × 0.75f) coerceAtLeast (titleBottomSpacing / 2f)
            // 对齐旧 ChapterProvider line 321：装饰横条空间 + 与正文的视觉分隔
            currentY += maxOf(contentTextHeight * 0.75f, titleBottomSpacing / 2f)
        }

        for (paragraphRaw in paragraphs) {
            paragraphCounter++

            // ── P3-5b Phase 3：BlockStyle inline marker 解码 ──
            // EpubParser flattenToString 在带非空 BlockStyle 的 paragraph 文本前内联了
            // `__MOREALM_BLOCK_STYLE__<payload>__/MOREALM_BLOCK_STYLE__` 标记。这里识别
            // 后剥出 payload 解码成 BlockStyle 挂到 currentBlockStyle，剩余文本是真正的
            // paragraph 内容。emitLine 读 currentBlockStyle 写到 ScrollLine。
            // 每 paragraph 开始重置 currentBlockStyle，避免上一段污染。
            currentBlockStyle = com.morealm.epub.compat.BlockStyle.EMPTY
            val paragraphText: String = if (paragraphRaw.startsWith(
                    com.morealm.epub.compat.StructuredChapterContent.BLOCK_STYLE_MARKER,
                )
            ) {
                val endIdx = paragraphRaw.indexOf(
                    com.morealm.epub.compat.StructuredChapterContent.BLOCK_STYLE_END,
                )
                if (endIdx < 0) {
                    // Malformed —— 兜底当纯文本（保留前缀避免数据丢失）
                    paragraphRaw
                } else {
                    val payload = paragraphRaw.substring(
                        com.morealm.epub.compat.StructuredChapterContent.BLOCK_STYLE_MARKER.length,
                        endIdx,
                    )
                    val body = paragraphRaw.substring(
                        endIdx + com.morealm.epub.compat.StructuredChapterContent.BLOCK_STYLE_END.length,
                    )
                    currentBlockStyle = com.morealm.epub.compat.StructuredChapterContent
                        .decodeBlockStyle(payload)
                    body
                }
            } else {
                paragraphRaw
            }

            // ── 空段处理（用户决策 2026-05-17）──
            // 输出空 ScrollLine（columns 空 + text 空 + 高 = contentLineHeight），并占 1 cp。
            // 用户选中空段 = 选中那 1 个 cp；视觉上空段表现为「一行空白」。
            if (paragraphText.isEmpty()) {
                val emptyCp = chapterPositionCounter
                emitLine(
                    lineColumns = emptyList(),
                    lineText = "",
                    paragraphNum = paragraphCounter,
                    firstChapterPos = emptyCp,
                    lastChapterPos = emptyCp,
                )
                chapterPositionCounter++
                // 段末 paragraphSpacing 跨页不补到新页顶（强硬方案 1）
                currentY += paragraphSpacingPx
                continue
            }

            // ── 非空段：缩进作为排版属性，不生成 column ──
            // 段首第一行 lineCursorX 起点 = indentWidth；img 之后的 chunk 起点 = 0（无缩进）。
            // 每个 chunk 独立 emit 完所有行（ZhLayout 行打断 / 简单贪心 fallback），chunk 间换行。

            // emit 一条最终 ScrollLine：负责 emit 调用 + chapterPositionCounter 累加 +
            // text/column 拼装。startX 控制首字符 x 偏移（段首=indentWidth，续行/img后=0）。
            // gap 控制 textFullJustify 非末行的字符间隙增量；末行 / 自然排列 = 0。
            fun emitOneLine(
                chars: List<String>,
                widths: List<Float>,
                startX: Float,
                gap: Float,
            ) {
                if (chars.isEmpty()) return
                val cols = ArrayList<ScrollColumn>(chars.size)
                val sb = StringBuilder()
                var x = startX
                for (i in chars.indices) {
                    val w = widths[i]
                    cols.add(
                        ScrollColumn(
                            charData = chars[i],
                            start = x,
                            end = x + w,
                            chapterPosition = chapterPositionCounter,
                        ),
                    )
                    sb.append(chars[i])
                    chapterPositionCounter++
                    x += w + (if (i < chars.lastIndex) gap else 0f)
                }

                // ── exceed 行末压缩（移植 V1 ChapterProvider.exceed L1034-1056 等价）──
                // ZhLayout 处理 CJK 标点悬挂时会让 lineEnd 包含超出 visibleWidth 的标点。
                // exceed 检测末 column.end > visibleWidth 时，按位移让末 column 贴 visibleWidth：
                // 最后一列位移最大 (cc * size = excess)，最左列位移最小 (cc * 1)。
                // 视觉效果：字符间距微缩，相邻 column 略重叠，行末刚好齐 visibleWidth。
                val lastEnd = cols.last().end
                val excess = lastEnd - visibleWidth.toFloat()
                if (excess > 0f && cols.size >= 2) {
                    val cc = excess / cols.size
                    for (i in cols.indices) {
                        // i=0 (first col) 位移 cc * 1；i=last 位移 cc * size = excess
                        val py = cc * (i + 1)
                        val c = cols[i]
                        cols[i] = c.copy(start = c.start - py, end = c.end - py)
                    }
                }

                emitLine(
                    lineColumns = cols,
                    lineText = sb.toString(),
                    paragraphNum = paragraphCounter,
                    firstChapterPos = cols.first().chapterPosition,
                    lastChapterPos = cols.last().chapterPosition,
                )
            }

            // emitTextChunk：用 ZhLayout（CJK 标点压缩 / 行打断）切行 + textFullJustify 末行不对齐。
            // useZhLayout=false 时走简单贪心 fallback（visibleWidth 满则换行）。
            // isFirstChunkOfPara=true → 段首 chunk 的首行用 indentWidth 起；续行 / 后续 chunk = 0 起。
            fun emitTextChunk(textChunk: String, isFirstChunkOfPara: Boolean) {
                if (textChunk.isEmpty()) return
                val (chars, widths) = contentTextMeasure.measureTextSplit(textChunk)
                if (chars.isEmpty()) return

                if (useZhLayout) {
                    // 修复用户反馈"首行缩进太大"：之前 indentSize=0 让 ZhLayout 按完整 visibleWidth
                    // 切行，但 emitOneLine 又把首行 startX = indentWidth → 实际可用宽 = visibleWidth -
                    // indentWidth，首行字数过多 → exceed 强力压缩 → 视觉感受"缩进很大字间距窄"。
                    // 修：传 paragraphIndent.length 让 ZhLayout 知道首行少 indentSize 个字位置。
                    val indentSize = if (isFirstChunkOfPara) paragraphIndent.length else 0
                    val layout = ZhLayout(textChunk, contentPaint, visibleWidth, chars, widths, indentSize)
                    for (lineIndex in 0 until layout.lineCount) {
                        // ZhLayout.lineStart/lineEnd 是 UTF-16 char index（基于 text.length），
                        // 而 chars/widths 是 code-point 切分（surrogate pair 合并 1 元素）。
                        // 不能直接 chars.subList(lineStart, lineEnd)——会越界（emoji 等代理对场景）。
                        // 改用 textChunk.substring + 重新 measureTextSplit 拿对齐的 lineChars/lineWidths。
                        val lineStart = layout.getLineStart(lineIndex)
                        val lineEnd = layout.getLineEnd(lineIndex)
                        if (lineEnd <= lineStart) continue
                        val lineText = textChunk.substring(lineStart, lineEnd)
                        val (lineChars, lineWidths) = contentTextMeasure.measureTextSplit(lineText)
                        if (lineChars.isEmpty()) continue
                        val isFirstLine = isFirstChunkOfPara && lineIndex == 0
                        val isLastLine = lineIndex == layout.lineCount - 1
                        val startX = if (isFirstLine) indentWidth else 0f
                        val availableWidth = visibleWidth - startX
                        val desiredWidth = lineWidths.sum()
                        val residualWidth = availableWidth - desiredWidth
                        // Justify 条件（与旧 addCharsToLineMiddle 同款）：
                        //   - 非末行
                        //   - 余宽 > 0（行未满才需要分摊；满 / 溢出不分摊）
                        //   - 余宽 ≤ availableWidth × 0.25（防止过散行；如最后短句不该 justify）
                        //   - 行宽 ≥ availableWidth × 0.65（防止极短行被强行拉宽，视觉不自然）
                        //   - chars.size > 1（单字符无间隙可分）
                        val shouldJustify = textFullJustify && !isLastLine &&
                            residualWidth > 0f && residualWidth <= availableWidth * 0.25f &&
                            desiredWidth >= availableWidth * 0.65f && lineChars.size > 1
                        val gap = if (shouldJustify) residualWidth / (lineChars.size - 1) else 0f
                        emitOneLine(lineChars, lineWidths, startX, gap)
                    }
                } else {
                    // Greedy fallback：与 M1.2 原逻辑等价。
                    val lineChars = ArrayList<String>()
                    val lineWidths = ArrayList<Float>()
                    var cursorX = if (isFirstChunkOfPara) indentWidth else 0f
                    var firstLineEmitted = false

                    fun flushGreedyLine() {
                        if (lineChars.isEmpty()) return
                        val startX = if (!firstLineEmitted && isFirstChunkOfPara) indentWidth else 0f
                        emitOneLine(lineChars, lineWidths, startX, 0f)
                        lineChars.clear()
                        lineWidths.clear()
                        firstLineEmitted = true
                        cursorX = 0f
                    }
                    for (i in chars.indices) {
                        val w = widths[i]
                        if (cursorX + w > visibleWidth && lineChars.isNotEmpty()) {
                            flushGreedyLine()
                        }
                        lineChars.add(chars[i])
                        lineWidths.add(widths[i])
                        cursorX += w
                    }
                    flushGreedyLine()
                }
            }

            // 段内 img 拆分：识别 `<img src="...">` 把段拆为 [text1, img1, text2, ...]
            // 顺序 emit。img 占整行，前后 chunk 不共享行（chunk 独立 emit）。
            val imgMatches = imgRegex.findAll(paragraphText).toList()
            if (imgMatches.isEmpty()) {
                emitTextChunk(paragraphText, isFirstChunkOfPara = true)
            } else {
                var cursor = 0
                var isFirstChunk = true
                for (m in imgMatches) {
                    val before = paragraphText.substring(cursor, m.range.first)
                    if (before.isNotEmpty()) {
                        emitTextChunk(before, isFirstChunkOfPara = isFirstChunk)
                        isFirstChunk = false
                    }
                    chapterPositionCounter = emitImage(
                        src = m.groupValues[1],
                        paragraphNum = paragraphCounter,
                        startCp = chapterPositionCounter,
                    )
                    isFirstChunk = false  // img 后续 chunk 不是段首
                    cursor = m.range.last + 1
                }
                if (cursor < paragraphText.length) {
                    val tail = paragraphText.substring(cursor)
                    if (tail.isNotEmpty()) emitTextChunk(tail, isFirstChunkOfPara = isFirstChunk)
                }
            }

            // 段末隐式 \n 占 1 cp（与旧 ChapterProvider stringBuilder.append('\n') 严格对齐，
            // 保证跨引擎 DB 高亮 chapterPos 兼容）
            chapterPositionCounter++

            // 段间空白：纯累加，不补跨页（方案 1 强硬纠正）
            currentY += paragraphSpacingPx
        }

        if (currentPageLines.isNotEmpty()) {
            flushPage()
        }
        // 章末 paddingBottom：flushPage 现在 page.height 不含 paddingBottom（修留白暴涨），
        // 但**最后一页**章末应该保留底部留白让正文不贴章节边界。给末 page.height 加回。
        if (pages.isNotEmpty()) {
            val last = pages.removeAt(pages.lastIndex)
            pages.add(last.copy(height = last.height + paddingBottom))
        }
        // 空章节兜底：至少一空页，渲染层据此画"内容为空"占位
        if (pages.isEmpty()) {
            pages.add(
                ScrollPage(
                    pageIndex = 0,
                    lines = emptyList(),
                    height = (paddingTop + paddingBottom).toFloat(),
                    chapterIndex = chapterIndex,
                )
            )
        }

        val totalHeight = pages.fold(0f) { acc, p -> acc + p.height }

        // ── 诊断日志（章中 page 首行被 InfoBar 盖根因排查 2026-05-20）──
        // 列出每 page 的第一行 lineTop。期望章首 page = paddingTop，章中 page = 0（bug）。
        // page-level 模式下 page 1+ 第一行 lineTop=0 → 紧贴 viewport 顶 → 被 InfoBar 渐变盖半透。
        AppLog.info(
            "PageTopDiag",
            "ch=$chapterIndex paddingTop=$paddingTop pages=${pages.size} " +
                "firstLineTops=${pages.map { p -> p.lines.firstOrNull()?.lineTop?.toInt() ?: -1 }}",
        )

        // ── 诊断日志（吞字根因排查 2026-05-17）──
        // 排版结束记录关键参数：viewWidth（外层传入）/ visibleWidth（扣 padding 后实际可排区）/
        // maxColumnEnd（章内所有 column.end 最大值，应 ≤ visibleWidth，否则即吞字）。
        // 如果 maxColumnEnd > visibleWidth → 排版算法有 bug；
        // 如果 maxColumnEnd ≤ visibleWidth 但画面仍吞字 → 渲染层 Canvas 实际宽 < viewWidth。
        var maxColumnEnd = 0f
        for (page in pages) {
            for (line in page.lines) {
                for (col in line.columns) {
                    if (col.end > maxColumnEnd) maxColumnEnd = col.end
                }
            }
        }
        AppLog.info(
            "ScrollLayoutEngine",
            "layoutChapter idx=$chapterIndex viewWidth=$viewWidth padding=L${paddingLeft}/R${paddingRight} " +
                "visibleWidth=$visibleWidth maxColumnEnd=$maxColumnEnd " +
                "overflow=${maxColumnEnd > visibleWidth} pages=${pages.size} totalHeight=$totalHeight",
        )

        return ScrollChapterLayout(
            chapterIndex = chapterIndex,
            title = title,
            pages = pages,
            totalHeight = totalHeight,
            viewWidth = viewWidth,
            paddingLeft = paddingLeft,
            paddingTop = paddingTop,
            paddingBottom = paddingBottom,
            styleSignature = computeStyleSignature(),
            totalCharCount = chapterPositionCounter,
        )
    }

    /**
     * 反查：给定章内字符 offset，找到承载该字符的 [ScrollHitResult]。
     *
     * 用于：高亮 / 字体色 / 下划线 / 选区 handle / 搜索高亮 等所有按 chapterPosition
     * 持久化的功能，渲染时反查到 line / column 后画 rect / 改色 / 拉 handle。
     *
     * 算法：线性扫 page→line，命中 line 后在 columns 内找精确 column。
     * 复杂度 O(N × M)（N=page 数 < 50，M=line 数 < 30/page），平均 < 0.1ms。
     * 不做二分：page/line cp range 虽单调但分支多（空 page / 空 line corner case），
     * 线性实现可读性 + 正确性双优；性能差距可忽略。
     *
     * @return 命中 [ScrollHitResult]：
     *   - 文本行命中：column 非 null（精确字符）
     *   - 空段 / 图片段 line 命中：column = null（整行 rect 由调用方画）
     *   越界（cp < 0 或 cp >= totalCharCount）返 null
     */
    fun findColumnAt(
        layout: ScrollChapterLayout,
        chapterPosition: Int,
    ): ScrollHitResult? = layout.findColumnAt(chapterPosition)

    /**
     * 反查反向：给定屏幕坐标（相对章顶 0..[ScrollChapterLayout.totalHeight]），找到承载
     * 该坐标的 [ScrollHitResult]。
     *
     * 用于：长按选区起点 / handle drag 时定位字符 / 双击选词。
     *
     * 吸附规则（用户决策 2026-05-17，"不让用户觉得点在了空气上"）：
     *
     * **纵向（y）**：
     * - y 落在 line.lineTop..lineBottom → 命中该 line
     * - y 在 line 间空白（段间 spacing / page 内行间） → 吸附到最近的 line（midY 距离）
     * - y 在 page padding 区（paddingTop 之上、paddingBottom 之下） → 吸附到该 page
     *   首 / 末 line
     * - y 越界（< 0 或 > totalHeight） → null（用户点击章节外）
     *
     * **横向（x）**：
     * - 命中 line 是空段 / 图片段（columns 空） → column = null
     * - x < line.columns.first().start（缩进区 / 行首左侧） → 吸附到 first column
     * - x >= line.columns.last().end（行尾右侧） → 吸附到 last column
     * - x 落在某 column.start..end → 命中该 column
     */
    fun findColumnByPixel(
        layout: ScrollChapterLayout,
        x: Float,
        yWithinChapter: Float,
    ): ScrollHitResult? = layout.findColumnByPixel(x, yWithinChapter)

    /**
     * 当前排版参数的样式签名 —— 所有影响**字符坐标**的字段的全量、稳定、可比较拼接。
     *
     * 上层缓存判定规则：`layout.styleSignature == engine.computeStyleSignature()` → 复用；
     * 不等 → 触发整章重排版（[layoutChapter]）。
     *
     * 设计要点（必须满足，不留兼容口子）：
     *
     * 1. **完整性**：列出所有进入坐标计算的字段。颜色 / paint alpha 等不影响 metrics
     *    的字段**不进**——它们变化只触发重绘，不触发重排版。漏字段 = 视觉残留 bug
     *    （字段变了但缓存命中），多字段 = 不必要重排版（性能损失，但正确性 OK）。
     * 2. **Float 精度稳定**：用 [Float.toBits] 输出 IEEE 754 位表示，消除跨 JVM
     *    `Float.toString()` 表达差异（如 `48.0` vs `48`）。
     * 3. **Typeface 实例敏感**：用 [System.identityHashCode] 而非 [Typeface.hashCode]。
     *    [Typeface.equals] 在 SDK 间行为不一致；同字体不同 instance 的 metrics 也可能
     *    异（OEM 自定义 fontFallback），因此 identity 敏感是保守正确选择。
     *    上层应通过 PaintPool 复用 Typeface instance 以避免频繁失效。
     * 4. **String 字段原文拼接**：[paragraphIndent] 直接拼内容（不拼长度）—— 缩进字符
     *    类型（全角 / 半角 / Tab）也影响 measure 结果。
     * 5. **直接字符串相等比较**：不做 SHA / hashCode 压缩。字段量级（~20 个）拼出
     *    字符串约 200-300 字符，相等比较 O(N) 可忽略；hash 压缩反而引入碰撞风险。
     */
    fun computeStyleSignature(): String = buildString {
        append("vw=").append(viewWidth).append(';')
        append("vh=").append(viewHeight).append(';')
        append("pl=").append(paddingLeft).append(';')
        append("pr=").append(paddingRight).append(';')
        append("pt=").append(paddingTop).append(';')
        append("pb=").append(paddingBottom).append(';')
        append("cts=").append(contentPaint.textSize.toBits()).append(';')
        append("ctf=").append(System.identityHashCode(contentPaint.typeface)).append(';')
        append("tts=").append(titlePaint.textSize.toBits()).append(';')
        append("ttf=").append(System.identityHashCode(titlePaint.typeface)).append(';')
        append("cnts=").append((chapterNumPaint?.textSize ?: titlePaint.textSize).toBits()).append(';')
        append("cntf=").append(System.identityHashCode(chapterNumPaint?.typeface ?: titlePaint.typeface)).append(';')
        append("lse=").append(lineSpacingExtra.toBits()).append(';')
        append("ps=").append(paragraphSpacing).append(';')
        append("indent='").append(paragraphIndent).append("';")
        append("tm=").append(titleMode).append(';')
        append("ta=").append(titleAlign).append(';')
        append("tts2=").append(titleTopSpacing).append(';')
        append("tbs=").append(titleBottomSpacing).append(';')
        append("zh=").append(useZhLayout).append(';')
        append("fj=").append(textFullJustify)
    }

    /**
     * 标题归一化（V1 ChapterProvider.normalizeTitleForCompare 等价）。
     * 用于 stripDuplicateTitleSegments + isSameChapterTitle 比对：
     *   - 去掉 ideographic space "　"
     *   - 去掉所有空白
     *   - trim
     */
    private fun normalizeTitleForCompare(value: String): String = value
        .replace("　", "")
        .replace(Regex("\\s+"), "")
        .trim()

    /**
     * 剥掉 rawParagraphs 中重复 title 的部分。3 种场景：
     *   A. 第 1 段 normalized 整段 == title → drop 该段
     *   B. 前 N 段（N=2..3）拼起来 == title → drop 那 N 段
     *   C. 第 1 段以 title 开头（含装饰字符）→ 段内剥掉 title prefix
     *
     * 返回的列表已剥除 title 段 / prefix，可能为空（章节只有 title 无正文场景）。
     */
    private fun stripTitleFromParagraphs(
        rawParagraphs: List<String>,
        normalizedTitle: String,
    ): List<String> {
        if (normalizedTitle.isEmpty() || rawParagraphs.isEmpty()) return rawParagraphs

        // 跳过段首空段，找到第一个非空段位置
        val firstNonEmptyIdx = rawParagraphs.indexOfFirst { it.isNotBlank() }
        if (firstNonEmptyIdx < 0) return rawParagraphs

        // 场景 B：前 N 段非空段拼接 == title → drop 那 N 段
        val nonEmpty = rawParagraphs.drop(firstNonEmptyIdx).filter { it.isNotBlank() }
        val maxN = minOf(3, nonEmpty.size)
        for (n in maxN downTo 1) {
            val joined = nonEmpty.take(n).joinToString("") { normalizeTitleForCompare(it) }
            if (joined == normalizedTitle) {
                // drop 前 firstNonEmptyIdx 个段（前导空段）+ n 个非空段
                return dropFirstNNonEmpty(rawParagraphs, firstNonEmptyIdx, n)
            }
        }

        // 场景 C：段内 prefix 剥除（subsequence match）
        val firstPara = rawParagraphs[firstNonEmptyIdx]
        val titleEndIdx = findTitlePrefixEnd(firstPara, normalizedTitle)
        if (titleEndIdx > 0) {
            val rest = firstPara.substring(titleEndIdx).trimStart { it == '　' || it.isWhitespace() }
            // 若整段被 title 吃光（rest 空）→ drop 整段
            val result = rawParagraphs.toMutableList()
            if (rest.isEmpty()) {
                result.removeAt(firstNonEmptyIdx)
            } else {
                result[firstNonEmptyIdx] = rest
            }
            return result
        }
        return rawParagraphs
    }

    /** drop 前 firstNonEmptyIdx 个段 + 接下来 n 个非空段（保留期间空段）。 */
    private fun dropFirstNNonEmpty(list: List<String>, startIdx: Int, n: Int): List<String> {
        val result = mutableListOf<String>()
        var skipped = 0
        for ((i, p) in list.withIndex()) {
            if (i < startIdx) continue
            if (skipped < n) {
                if (p.isNotBlank()) skipped++
                continue
            }
            result.add(p)
        }
        return result
    }

    /**
     * 子序列匹配：normalizedTitle 的字符是否按顺序出现在 firstPara 段首（允许装饰字符插入）。
     * 命中返回 title 末字符在 firstPara 中的 index + 1（用于 substring strip）。
     * 不匹配返 -1。
     *
     * 限制：title 字符必须在 firstPara 的前 N 字符内完成匹配（N = title.length × 2 + 5），
     * 防止 title 字符碰巧零散出现在正文中导致误剥。
     */
    private fun findTitlePrefixEnd(firstPara: String, normalizedTitle: String): Int {
        if (normalizedTitle.isEmpty()) return -1
        val maxScan = normalizedTitle.length * 2 + 5
        var titleIdx = 0
        for (i in firstPara.indices) {
            if (i >= maxScan) return -1  // 超出搜索范围视为不匹配
            val ch = firstPara[i]
            if (ch == '　' || ch.isWhitespace()) continue  // 跳过空白（normalized 一致）
            if (titleIdx < normalizedTitle.length && ch == normalizedTitle[titleIdx]) {
                titleIdx++
                if (titleIdx == normalizedTitle.length) {
                    return i + 1  // 全部 title 字符匹配到末尾
                }
            }
            // 不匹配则忽略（视为装饰字符），继续找下一个 title 字符
        }
        return -1
    }

    /**
     * 拆分章节标题为 (chapter-num, title) 两部分。
     *
     * 示例：
     *   "第一章 山边小村"  → ("第一章", "山边小村")
     *   "Chapter 5 Hello"  → ("Chapter 5", "Hello")
     *   "山边小村"         → (null, "山边小村")
     *   "第一章"           → (null, "第一章")  // 无 rest，整串当 title
     *
     * 与旧 [com.morealm.app.domain.render.ChapterProvider.splitChapterNumAndTitle]
     * 算法严格对齐（同正则），保证章首块视觉与 cp 占用语义一致。
     */
    private fun splitChapterNumAndTitle(title: String): Pair<String?, String> {
        val trimmed = title.trim()
        val match = chapterNumSplitRegex.find(trimmed)
        if (match != null) {
            val num = match.value.trim()
            val rest = trimmed.substring(match.range.last + 1).trim()
            return if (rest.isNotEmpty()) num to rest else null to trimmed
        }
        return null to trimmed
    }

    companion object {
        /**
         * 章序号识别正则 —— 抄自 [com.morealm.app.domain.render.ChapterProvider]
         * companion 内同款表达式，保持识别行为完全一致。
         * 覆盖：中文章节（第N章/节/卷/集/部/篇/回/话/幕/折/场）、英文 Chapter/Volume、
         * 序章 / 终章 / 尾声 / 楔子 / 番外 / 引子 / 数字编号等。
         */
        private val chapterNumSplitRegex = Regex(
            """^(第[零一二三四五六七八九十百千万亿\d]+[章节卷集部篇回话幕折场]|[Cc]hapter\s+\d+|[Vv]ol(?:ume)?\s*\.?\s*\d+|序[章言]|终章|尾声|楔子|番外|引[子章]|\d+[.、]\s*)""",
        )

        /**
         * 图片占位标记识别正则 —— 与 [com.morealm.app.core.text.AppPattern.imgSrcPattern]
         * 同款表达式，group 1 = src。
         * 直接定义为 Regex 而非引用 Pattern 转换，避免每次调用开销。
         */
        private val imgRegex = Regex(
            "<img[^>]+src=['\"]([^'\"]*)['\"][^>]*>",
            RegexOption.IGNORE_CASE,
        )
    }
}
