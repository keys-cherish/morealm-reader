package com.morealm.app.domain.render.layout

import android.text.TextPaint
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.render.TextMeasure
import com.morealm.app.domain.render.ZhLayout
import com.morealm.app.domain.render.textHeight
// **R1 (阶段 R1)** —— 核心 marker 解析 + 数据类迁到独立仓库 epub-layout。主仓只调用 entry point
// + 引用 public data class。internal marker 字面值 / parser helper 全藏在 epub-layout module。
import com.morealm.epub.layout.InlineMarkersResult
import com.morealm.epub.layout.ParsedTable
import com.morealm.epub.layout.ParsedTableCell
import com.morealm.epub.layout.ParsedTableRow
import com.morealm.epub.layout.hasTableMarker
import com.morealm.epub.layout.parseInlineMarkers
import com.morealm.epub.layout.parseTableMarker

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
    /**
     * **Atom-mode layout entry（A3 stub）** —— 跟 [layoutChapter] 同输入同 cp 契约，
     * 输出每行的 [Atom] 列表。
     *
     * **当前实现**：纯 forward 到 [layoutChapter]，每行调 [ScrollAtomBridge.toAtoms]
     * 派生 atoms。语义跟 A2 等价，**零排版行为变化**。
     *
     * **API 现在就位的意义**：让 A4 / A5 callers 可以 import + 调用这个入口，将来
     * A3 真改 ScrollLayoutEngine 内部用 atom 单元排版（支持 inline image / mixed line）
     * 时调用方零修改。
     *
     * **返回类型选择**：`List<List<Atom>>` 行级聚合 —— 保留行边界（A5 Renderer 需要
     * 行高 / y 坐标，按行 index 反查 [layoutChapter] 结果的 ScrollLine 拿元数据）。不
     * 引入 AtomLine / AtomChapterLayout 包装类型避免过度开发，等 A4 真用 atom 排版
     * 时再决定要不要新数据模型。
     *
     * **CP 契约保证**：`result.flatten().sumOf { it.cpCount }` 等于 [ScrollChapterLayout.
     * totalCharCount]（含空段 / 图片各 1 cp）。空段 line 产 empty list，cp 由 [ScrollLine.
     * firstChapterPos] 间接持有 —— 详见 [AtomCpContractTest] empty paragraph 断言。
     *
     * @return 每行一个 List<Atom>，跟 `layoutChapter(...).pages.flatMap { it.lines }` 一一对应
     */
    fun layoutAtoms(
        chapterIndex: Int,
        title: String,
        content: String,
        omitChapterTitleBlock: Boolean = false,
    ): List<List<Atom>> {
        val legacy = layoutChapter(chapterIndex, title, content, omitChapterTitleBlock)
        return legacy.pages.flatMap { it.lines }.map { ScrollAtomBridge.toAtoms(it) }
    }

    fun layoutChapter(
        chapterIndex: Int,
        title: String,
        content: String,
        omitChapterTitleBlock: Boolean = false,
    ): ScrollChapterLayout {
        // **C1/C2 chapter bg image marker strip**：检查 content 头部是否携带
        // __MOREALM_CH_BG__<src>__/MOREALM_CH_BG__ 前缀 → 剥掉 + 提取 src 存到 layout
        // 字段。本步骤在段切分前必须做，否则 marker 会被当作首段文本占 cp。
        val (cleanedContent, chapterBgSrc) = stripChapterBgMarker(content)
        // 段切分语义（精确对齐持久化坐标语义）：
        //   - 按 `\n` 拆，段末 `\r` 去除（容忍 CRLF）
        //   - 空段（连续 `\n\n` 之间的空字符串）**保留**：产生空 ScrollLine（columns 空、
        //     高 = contentLineHeight）+ chapterPosition += 1（用户决策 2026-05-17：
        //     空段占 1 个 cp，与原文 \n 位置 1:1 对齐）
        val rawParagraphs = cleanedContent.split('\n').map { it.trimEnd('\r') }

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
        // **D2.a Commit 2b**: 含 __MOREALM_TBL__ marker 的 paragraph 由主循环 table 分支解析 ParsedTable + 调 layoutTable（cell.widthPx 切行 → CJK 1字/行竖排）。
        // expandTableMarkersStub 展平 fallback 已下线
        //
        // **阶段 2-F**：双条件检测"封面 / 卷首页 / BookName" 等特殊章 → 跳过自画 title 大字。
        //
        // 参考实现 不在这些章正文画 toc navLabel，仅 InfoBar 显示。MoRealm 之前自画
        // toc navLabel ("书名" / "封面" / "第一卷 剑起风云") 让封面页顶部多出小字与大字布局
        // 重复 → 视觉跟参考图 38 / 16 不一致。
        //
        // 条件 1：title 是常见的"非内容章节"关键字（精确匹配 trim 后的 title）→
        //   覆盖 SampleLN BookName/cover/封面 等 toc navLabel 是 generic tag 的场景。
        //   (SampleLN 5 sibling tables 被 TableMergeVisitor merge 成普通 RichText paragraph
        //    不产 table marker，所以走条件 1 而非条件 2。)
        //
        // 条件 2：paragraphs 前 3 段任意段含 table marker → 覆盖某 EPUB chapter-1 卷扉页
        //   (toc navLabel "第一卷 剑起风云" 不是 generic tag，但内容是 vol-title table)。
        val titleTrimmed = title.trim()
        val isSpecialChapterTitle = titleTrimmed in SPECIAL_CHAPTER_TITLES
        val firstFewParas = paragraphs.asSequence().filter { it.isNotBlank() }.take(3).toList()
        val hasEarlyTableMarker = firstFewParas.any { hasTableMarker(it) }
        val contentProvidesChapterTitle = isSpecialChapterTitle || hasEarlyTableMarker

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

        // P3-5b Step 2c char-level color：当前 paragraph 的字符级颜色数组（per code-point）。
        // 解码自 flattenToString 内嵌的 SPAN_COLOR_START..END marker。null = 本段无字符级
        // 颜色覆盖；非 null 时按 (chapterPositionCounter - paragraphCpStart) 索引拿到该字符
        // 的 ARGB 颜色（0 = 用 paint 默认色）。
        var currentParaCharColors: IntArray? = null
        // A4b：当前 paragraph 的字符级 inline image src 数组（per code-point）。U+FFFC 占位
        // 字符位置填 src，其他位置 null。emitOneLine 看 imageSrcPerCp[relIdx] 非 null →
        // ScrollColumn.inlineImageSrc = src + width 替换为 inline image fit 宽度。
        var currentParaImageSrcs: Array<String?>? = null
        // A4c：当前 paragraph 的字符级 sizeScale 数组（per code-point）。null = 全 1f
        // （无 em25/em30 之类的字号变化）；非 null 触发 emit 切到 atoms 路径，width 跟
        // sizeScale 联动（measureTextSplit 后再乘 sizeScale）。
        var currentParaSizeScales: FloatArray? = null
        // H1+H2：当前 paragraph 是否为 heading 段（1..6，0 = 非 heading 正文）。
        // emit line 时透传到 ScrollLine.headingLevel，让 H3 渲染识别 + 用 titlePaint 大字。
        var currentParaHeadingLevel: Int = 0
        var currentParaCpStart: Int = 0

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
            atoms: List<Atom>? = null,
            headingLevel: Int = 0,
            // **D 模型 (阶段 1 重构)** — table line cells；null = 非 table line。
            // 详 ScrollLine.cells / ScrollLineCell 注释。
            cells: List<ScrollLineCell>? = null,
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
                    atoms = atoms,
                    headingLevel = headingLevel,
                    cells = cells,
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

        // **D2.a Commit 2b** —— layoutCellLines：把 cell 内 paragraph 列表按 cell.widthPx
        // 切成行，返回 List<List<(char, width)>>。某 EPUB vol-title td.widthPx=19.2 (1.2em)
        // → CJK 字符 ~16-24 px 宽，一字一行 → 视觉竖排。
        fun layoutCellLines(cell: ParsedTableCell): List<List<Pair<String, Float>>> {
            val widthCap = cell.widthPx ?: visibleWidth.toFloat()
            val sizeScale = cell.sizeScale
            val out = ArrayList<List<Pair<String, Float>>>()
            for (para in cell.contentParagraphs) {
                val (chars, rawWidths) = contentTextMeasure.measureTextSplit(para)
                if (chars.isEmpty()) continue
                // **D2.b**：cell.sizeScale 缩放字符宽度，让 wrap 按真实字号算（1.4em 字
                // ~24×1.4=34px > cellW → 1字1行；0.9em 字 ~24×0.9=22px 可能塞 2字/行）。
                val widths: List<Float> = if (sizeScale != 1f) rawWidths.map { it * sizeScale } else rawWidths
                var lineBuf = ArrayList<Pair<String, Float>>()
                var lineW = 0f
                for (i in chars.indices) {
                    val w = widths[i]
                    if (lineW + w > widthCap && lineBuf.isNotEmpty()) {
                        out.add(lineBuf.toList())
                        lineBuf = ArrayList()
                        lineW = 0f
                    }
                    lineBuf.add(chars[i] to w)
                    lineW += w
                }
                if (lineBuf.isNotEmpty()) out.add(lineBuf.toList())
            }
            return out
        }

        // **D2.a Commit 2b** —— layoutTable：emit table 所有行（cell.widthPx 切 + row 横排）。
        //
        // 算每个 cell 的行列表 → row 高度 = max(cell 行数) × lineHeight。每 cell 顶对齐
        // （CSS vertical-align: top 简化）。逐行 emit：row 内的 line i 由所有 cell 在
        // line i 的字符横排组成（cell 间用 cellWidth 偏移 startX）。
        //
        // table 整体水平对齐：CSS margin auto 检测 ——
        //  - margin-left auto + margin-right 非 auto → table 整体右贴（某 EPUB vol-title
        //    margin: 20% 0 0 auto 把 table 推到右边）
        //  - margin-left 非 auto + margin-right auto → 左对齐
        //  - 双 auto → 居中
        //  - 都非 auto → 左对齐 (mlIndent 段缩进 + 0)
        //
        // 段内 chapterPositionCounter 累加：每字符 1 cp，每 row 末加 1 cp 虚换行（跟普通段
        // 末 chapterPositionCounter++ 对齐）；空 cell line 走 emit 空行占 1 cp 避免坍塌。
        fun layoutTable(parsed: ParsedTable) {
            for (row in parsed.rows) {
                if (row.cells.isEmpty()) continue
                val cellLines: List<List<List<Pair<String, Float>>>> = row.cells.map { layoutCellLines(it) }
                // **D2.a Commit 2d fix**：CSS spec — td.width 是最小宽度；实际 cell width =
                // max(declared widthPx, actual content max line width)。某 EPUB td.width=1.2em
                // ≈ 19.2px < CJK 字符 ~24-30px → 字符会溢出 + cellCursorX 累加用 19.2 让
                // 相邻 cell 字符重叠（视觉看到两 cell 字符叠在一起）。修：cellWidths 取
                // max(declared, content max)。
                val cellWidths: List<Float> = row.cells.mapIndexed { idx, cell ->
                    val declared = cell.widthPx ?: 0f
                    val contentMax = cellLines[idx].maxOfOrNull { line ->
                        line.sumOf { it.second.toDouble() }.toFloat()
                    } ?: 0f
                    maxOf(declared, contentMax)
                }
                // **D2.b**：cell 之间间距 = CSS `border-spacing` 默认 2px（border-collapse: separate
                // 默认值）。某 EPUB table.vol-title 未声明 border-spacing 也未声明 border-collapse →
                // 走 CSS spec 默认 2px。不硬编 0.3em（之前 hack）— 真按 CSS 解析。
                // TODO(D3.a)：解析 CSS `border-spacing` 当 caller 显式设时（某 EPUB场景默认即可）。
                val cellGap: Float = 2f
                val maxLines = cellLines.maxOfOrNull { it.size } ?: 0
                if (maxLines == 0) continue

                // table 整体水平 offset（基于 currentBlockStyle margin auto 检测）
                val totalWidth = cellWidths.sum() + cellGap * (cellWidths.size - 1)
                val mlAuto = currentBlockStyle.marginLeftPx.isNaN()
                val mrAuto = currentBlockStyle.marginRightPx.isNaN()
                val tableXOffset = when {
                    mlAuto && !mrAuto -> (visibleWidth - totalWidth).coerceAtLeast(0f)  // 右贴
                    !mlAuto && mrAuto -> 0f  // 左贴
                    mlAuto && mrAuto -> ((visibleWidth - totalWidth) / 2f).coerceAtLeast(0f)  // 居中
                    else -> 0f
                }

                // **D 模型 (阶段 1 重构)** —— layoutTable emit D 模型：每 cell 是子 box
                // ([ScrollLineCell]) 含 contentTop / contentHeight / atoms。drawByAtoms 走 cells
                // 分支按 cell.contentLeft + atom.cellLocalX / cell.contentTop + atom.cellLocalY +
                // atom.baseline 算字符位置。
                //
                // 几何零视觉变化：当前 vertical-align: top → cell.contentTop = 0；atom.cellLocalY =
                // lineIdxInCell × cellStride；atom.baseline = cellAscent。三者之和 = lineIdxInCell ×
                // cellStride + cellAscent = A 模型 hack 的 atom.baseline absolute 数值。
                //
                // cellStrides[i] / cellHeights[i] / rowLineHeight 同 A 模型（per-cell stride 算法）。
                val cellStrides = FloatArray(row.cells.size) { idx ->
                    contentTextHeight * row.cells[idx].sizeScale * 1.05f
                }
                val cellHeights = FloatArray(row.cells.size) { idx ->
                    cellLines[idx].size * cellStrides[idx]
                }
                val rowLineHeight = maxOf(contentTextHeight, cellHeights.maxOrNull() ?: contentTextHeight)

                // 构建每 cell 的 ScrollLineCell 子对象 + 所属 atoms（含 cellLocalX/Y）+
                // 同时 flatten 全局 columns 供反查（globalX = cellCursorX + localX）
                val rowCells = ArrayList<ScrollLineCell>()
                val allColumns = ArrayList<ScrollColumn>()
                val sb = StringBuilder()
                val firstCpInTable = chapterPositionCounter
                var cellCursorX = tableXOffset
                for ((cIdx, cell) in row.cells.withIndex()) {
                    val cellW = cellWidths[cIdx]
                    val cellStride = cellStrides[cIdx]
                    val cellAscent = contentTextHeight * cell.sizeScale * 0.8f
                    val cellLineList = cellLines[cIdx]
                    val cellAtoms = ArrayList<Atom>()
                    for ((lineIdxInCell, cellLine) in cellLineList.withIndex()) {
                        if (cellLine.isEmpty()) continue
                        val lineW = cellLine.sumOf { it.second.toDouble() }.toFloat()
                        // cell 内文字水平居中（CSS td.vol-title-* text-align: center）
                        var localX = ((cellW - lineW) / 2f).coerceAtLeast(0f)
                        val localY = lineIdxInCell * cellStride
                        for ((ch, w) in cellLine) {
                            val globalX = cellCursorX + localX
                            allColumns.add(
                                ScrollColumn(
                                    charData = ch,
                                    start = globalX,
                                    end = globalX + w,
                                    chapterPosition = chapterPositionCounter,
                                    colorArgb = cell.textColor,
                                ),
                            )
                            cellAtoms.add(
                                TextRun(
                                    text = ch,
                                    colorArgb = cell.textColor,
                                    sizeScale = cell.sizeScale,
                                    width = w,
                                    height = cellStride,
                                    baseline = cellAscent,
                                    cellLocalX = localX,
                                    cellLocalY = localY,
                                ),
                            )
                            sb.append(ch)
                            chapterPositionCounter++
                            localX += w
                        }
                    }
                    rowCells.add(
                        ScrollLineCell(
                            contentTop = 0f,  // vertical-align: top；阶段 4 加 middle 时算 (row.h - cell.h) × {0.5/...}
                            contentLeft = cellCursorX,
                            contentWidth = cellW,
                            contentHeight = cellLineList.size * cellStride,
                            padding = 0f,
                            atoms = cellAtoms,
                        ),
                    )
                    cellCursorX += cellW
                    if (cIdx < row.cells.lastIndex) cellCursorX += cellGap
                }

                if (allColumns.isNotEmpty()) {
                    emitLine(
                        lineColumns = allColumns,
                        lineText = sb.toString(),
                        paragraphNum = paragraphCounter,
                        firstChapterPos = firstCpInTable,
                        lastChapterPos = allColumns.last().chapterPosition,
                        lineHeightOverride = rowLineHeight,
                        cells = rowCells,
                        // table line 的 atoms 留 null —— cells 已含真正的 atoms（per cell）
                    )
                } else {
                    val emptyCp = chapterPositionCounter++
                    emitLine(
                        lineColumns = emptyList(),
                        lineText = "",
                        paragraphNum = paragraphCounter,
                        firstChapterPos = emptyCp,
                        lastChapterPos = emptyCp,
                        lineHeightOverride = rowLineHeight,
                        cells = rowCells,
                    )
                }
                // row 末隐式 \n cp 占 1 cp（跟普通段对齐）
                chapterPositionCounter++
            }
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
                    // **D1.a DIAG**：仅当 margin 非默认时打 log，减少正文章节噪声（D2a debug 时
                    // log buffer 5000 行被普通段 margin 0.0 填满）。
                    val anyMargin = currentBlockStyle.marginTopPx != 0f ||
                        currentBlockStyle.marginBottomPx != 0f ||
                        currentBlockStyle.marginLeftPx != 0f ||
                        currentBlockStyle.marginRightPx != 0f
                    if (anyMargin) {
                        com.morealm.app.core.log.AppLog.info(
                            "D1a/Margin",
                            "para#$paragraphCounter payload='${payload.take(200)}' " +
                                "mt=${currentBlockStyle.marginTopPx} mr=${currentBlockStyle.marginRightPx} " +
                                "mb=${currentBlockStyle.marginBottomPx} ml=${currentBlockStyle.marginLeftPx} " +
                                "ta=${currentBlockStyle.textAlign} ts=${currentBlockStyle.textShadow} " +
                                "body40='${paragraphRaw.substring(endIdx + com.morealm.epub.compat.StructuredChapterContent.BLOCK_STYLE_END.length).take(40)}'",
                        )
                    }
                    body
                }
            } else {
                paragraphRaw
            }

            // **D2.a Commit 2b table 分支** —— body 含 __MOREALM_TBL__ marker → 解析
            // ParsedTable 走 layoutTable。currentBlockStyle 已在 BLOCK_STYLE strip 时设置
            // （table 自身的 margin/auto 等装饰）。layoutTable 调 emitLine 共享主 currentY /
            // currentPageLines / chapterPositionCounter 状态。parse 失败兜底当普通段处理
            // （视觉会出 marker 文本，提示损坏数据，比直接吞段更安全）。
            if (hasTableMarker(paragraphText)) {
                val parsed = parseTableMarker(paragraphText)
                if (parsed != null) {
                    // **阶段 2-A**：layoutTable 接 CSS margin-top / margin-bottom（之前固定加
                    // paragraphSpacingPx 当尾距，吞了 SampleLN 5 sibling table 的 margin-top:
                    // -1em / -1.5em / -10em 让视觉层叠失效）。
                    //
                    // 优先级（与 D1.a 段间 margin 路径同款）：
                    //  - mt 非 NaN(AUTO) 且非 0f → currentY += mt（允许负，让段重叠）
                    //  - mb 非 NaN 且非 0f → currentY += mb（替代 paragraphSpacingPx default）
                    //  - mt/mb 任一缺失 → 默认 0 / paragraphSpacingPx
                    //
                    // CSS spec：margin-top/bottom 不参与 collapse（table 元素的 margin 跟普通
                    // block 不同，跨 table 不 collapse），所以纯累加（跟 D1.a 段间 margin 一致）。
                    val mt = currentBlockStyle.marginTopPx
                    if (!mt.isNaN() && mt != 0f) currentY += mt
                    layoutTable(parsed)
                    val mb = currentBlockStyle.marginBottomPx
                    currentY += if (!mb.isNaN() && mb != 0f) mb else paragraphSpacingPx
                    continue
                }
                // parse 失败 fallthrough（极少见 — encodeTable 总产合法 marker）
            }

            // P3-5b Step 2c char-level color + A4b inline image：解码 SPAN_COLOR_* / INLINE_IMG_*
            // 内联 marker 拿到「干净文本 + 每字符颜色数组 + 每字符 inline image src 数组」。
            // 剥掉 marker 后用 cleanText 进行布局，emitOneLine 按 (chapterPositionCounter -
            // paragraphCpStart) 查 colors[relIdx] 上色 + imageSrcs[relIdx] 填 inlineImageSrc。
            val parsed = parseInlineMarkers(paragraphText)
            val cleanedText = parsed.cleanText
            val colorPerCp = parsed.colorPerCp
            currentParaCharColors = colorPerCp
            currentParaImageSrcs = parsed.imageSrcPerCp
            currentParaSizeScales = parsed.sizeScalePerCp
            currentParaHeadingLevel = parsed.headingLevel
            currentParaCpStart = chapterPositionCounter
            val processedText = cleanedText

            // ── D1.a margin-top（段前间距 / 段重叠）──
            // CSS `margin-top: 2em` → 段前留白；`margin-top: -1em` → 段往上偏移（SampleLN
            // 章首 table 重叠效果）。NaN = AUTO（垂直方向 CSS spec 等同 0，跳过）；0 = 未设置
            // 或显式 0，沿用 paragraphSpacingPx 默认（不改 currentY）。
            // 注：CSS margin collapse 简化 —— 不与上段 margin-bottom 取 max，纯累加（视觉
            // 上 prev.mb + curr.mt 都生效，CSS spec 严格 max 留 D1.b 完善）。
            val marginTopPx = currentBlockStyle.marginTopPx
            if (!marginTopPx.isNaN() && marginTopPx != 0f) {
                val beforeY = currentY
                currentY += marginTopPx
                com.morealm.app.core.log.AppLog.info(
                    "D1a/Margin",
                    "para#$paragraphCounter applied margin-top=$marginTopPx currentY=$beforeY → $currentY",
                )
            }
            // P3-5b Step 2c diag：仅当原 paragraphText 含 SOH 时才打 log（多色段稀有）
            if (paragraphText.contains('')) {
                com.morealm.app.core.log.AppLog.info(
                    "P3-5b/CharColor",
                    "paragraph has SOH markers rawLen=${paragraphText.length} " +
                        "cleanLen=${cleanedText.length} colorsSize=${colorPerCp?.size} " +
                        "rawHead40='${paragraphText.take(40).map { if (it.code < 0x20) "\\x%02x".format(it.code) else it.toString() }.joinToString("")}'",
                )
            }

            // ── 空段处理（用户决策 2026-05-17）──
            // 输出空 ScrollLine（columns 空 + text 空 + 高 = contentLineHeight），并占 1 cp。
            // 用户选中空段 = 选中那 1 个 cp；视觉上空段表现为「一行空白」。
            if (processedText.isEmpty()) {
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

            // ── **方案 C inline-block container** ──
            // BlockStyle.widthPx + heightPx 非 null → 段落是 inline-block 元素 (div.qipao /
            // .cont-qipao / .chara-qipao / .qipao-hei 等 CSS class)。设计意图：固定 box 尺寸
            // + 内含多行文字（按 boxWidth 切行竖排堆叠）+ box 装饰（圆角/背景由 drawer 画）。
            //
            // 与普通段差异：
            //  - 行高不用 contentLineHeight，用 max(boxH × fontScale, contentH + padding)
            //  - 文字按 boxWidth × fontScale - 2 × padding 切行（贪心 wrap，CJK 1-3 字/行）
            //  - emit 1 ScrollLine 含 1 ScrollLineCell（drawer 用 cells[0] 算 box rect 中心）
            //  - atoms 含 cellLocalX/Y 让 drawByCells 路径精确定位每字符
            //  - margin-left/right 推开 box (cell.contentLeft = mlScaled)
            //
            // 当前简化（后续 task 完善）：
            //  - sizeScale 用 1f (不读 currentParaSizeScales，简化逻辑)
            //  - 文字水平居中（CSS .qipao 内层 table center 都是 text-align: center）
            //  - vertical-align: middle in box（CSS table-cell default）
            val bsW = currentBlockStyle.widthPx
            val bsH = currentBlockStyle.heightPx
            if (bsW != null && bsH != null && bsW > 0f && bsH > 0f) {
                val fontScale = contentPaint.textSize / 16f
                val boxW = bsW * fontScale
                val boxH = bsH * fontScale
                val padScaled = currentBlockStyle.paddingLeftPx * fontScale
                val innerW = (boxW - 2f * padScaled).coerceAtLeast(0f)

                val (ibChars, ibWidths) = contentTextMeasure.measureTextSplit(processedText)
                if (ibChars.isNotEmpty() && innerW > 0f) {
                    // 按 innerW 贪心切行 (CJK 字符 ~24-30px × scale，56px box → 1-2 字/行)
                    val ibLines = ArrayList<List<Pair<String, Float>>>()
                    var lineBuf = ArrayList<Pair<String, Float>>()
                    var lineCumW = 0f
                    for (i in ibChars.indices) {
                        val w = ibWidths[i]
                        if (lineCumW + w > innerW && lineBuf.isNotEmpty()) {
                            ibLines.add(lineBuf.toList())
                            lineBuf = ArrayList()
                            lineCumW = 0f
                        }
                        lineBuf.add(ibChars[i] to w)
                        lineCumW += w
                    }
                    if (lineBuf.isNotEmpty()) ibLines.add(lineBuf.toList())

                    // 内 line stride - 内层 table class="em08" 0.8x 字号，紧凑堆叠
                    val innerLH = contentTextHeight * 0.95f
                    val contentH = ibLines.size * innerLH
                    val lineH = maxOf(boxH, contentH + 2f * padScaled)

                    // box 水平位置 = margin-left × scale (NaN = 0)
                    val mlRaw = currentBlockStyle.marginLeftPx
                    val mlScaled = if (mlRaw.isNaN()) 0f else mlRaw * fontScale
                    val cellLeft = mlScaled

                    // vertical-align: middle - 内容垂直居中
                    val contentTopInCell = (lineH - contentH) / 2f

                    val ibAtoms = ArrayList<Atom>(ibChars.size)
                    val ibCols = ArrayList<ScrollColumn>(ibChars.size)
                    val ibSb = StringBuilder()
                    val ibFirstCp = chapterPositionCounter
                    for ((rowIdx, rowChars) in ibLines.withIndex()) {
                        val realLineW = rowChars.sumOf { it.second.toDouble() }.toFloat()
                        // 水平居中 (CSS text-align: center)
                        var localX = padScaled + ((innerW - realLineW) / 2f).coerceAtLeast(0f)
                        val localY = contentTopInCell + rowIdx * innerLH
                        for ((ch, w) in rowChars) {
                            val globalX = cellLeft + localX
                            ibCols.add(
                                ScrollColumn(
                                    charData = ch,
                                    start = globalX,
                                    end = globalX + w,
                                    chapterPosition = chapterPositionCounter,
                                    colorArgb = null,
                                ),
                            )
                            ibAtoms.add(
                                TextRun(
                                    text = ch,
                                    colorArgb = null,
                                    sizeScale = 1f,
                                    width = w,
                                    height = innerLH,
                                    baseline = innerLH * 0.8f,
                                    cellLocalX = localX,
                                    cellLocalY = localY,
                                ),
                            )
                            ibSb.append(ch)
                            chapterPositionCounter++
                            localX += w
                        }
                    }
                    val ibCell = ScrollLineCell(
                        contentTop = 0f,
                        contentLeft = cellLeft,
                        contentWidth = boxW,
                        contentHeight = lineH,
                        padding = 0f,
                        atoms = ibAtoms,
                    )
                    emitLine(
                        lineColumns = ibCols,
                        lineText = ibSb.toString(),
                        paragraphNum = paragraphCounter,
                        firstChapterPos = ibFirstCp,
                        lastChapterPos = chapterPositionCounter - 1,
                        lineHeightOverride = lineH,
                        cells = listOf(ibCell),
                    )
                    val ibMb = currentBlockStyle.marginBottomPx
                    currentY += if (!ibMb.isNaN() && ibMb != 0f) ibMb else paragraphSpacingPx
                    chapterPositionCounter++  // 段末虚换行
                    continue
                }
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
                val colors = currentParaCharColors  // local snapshot
                val imageSrcs = currentParaImageSrcs  // A4b local snapshot
                val sizeScales = currentParaSizeScales  // A4c local snapshot
                val paraStartCp = currentParaCpStart
                // A4b：inline image 占位 fit 宽度（最简策略：行高 1.5 倍宽，让图占 ~1.5 字宽
                // 视觉协调）。height = contentLineHeight 由 emitLine 行高决定。A5+ 重构成
                // Atom 时改成按 ScrollImageDimensionsResolver 算原图比例。
                val inlineImageWidth = contentLineHeight * 1.5f
                // A5 Step 1：扩展 atoms 路径触发条件加 colors（含 char-color marker 的段也走
                // atoms），让 atoms 路径覆盖率从 ~10% (仅 sizeScale/image) 涨到 ~50% (含 color)。
                // drawByAtoms 已支持 TextRun.colorArgb，无需改渲染端。
                // 普通正文（无任何 marker）继续走 columns 路径，零行为变化。
                val emitAtoms = sizeScales != null || imageSrcs != null || colors != null
                val atomList = if (emitAtoms) ArrayList<Atom>(chars.size) else null
                for (i in chars.indices) {
                    val relIdx = chapterPositionCounter - paraStartCp
                    val charColor = colors?.getOrNull(relIdx)?.takeIf { it != 0 }
                    val inlineSrc = imageSrcs?.getOrNull(relIdx)
                    val sizeScale = sizeScales?.getOrNull(relIdx) ?: 1f
                    // A4c：sizeScale 缩放字符宽度（图片例外仍走 inlineImageWidth）
                    val w = when {
                        inlineSrc != null -> inlineImageWidth
                        sizeScale != 1f -> widths[i] * sizeScale
                        else -> widths[i]
                    }
                    cols.add(
                        ScrollColumn(
                            charData = chars[i],
                            start = x,
                            end = x + w,
                            chapterPosition = chapterPositionCounter,
                            colorArgb = charColor,
                            inlineImageSrc = inlineSrc,
                        ),
                    )
                    // A4c：构造 atom（每 char 1 个，A6 优化时再合并同 styling 区段）
                    if (atomList != null) {
                        atomList.add(
                            if (inlineSrc != null) {
                                InlineImage(src = inlineSrc, width = w, height = contentLineHeight)
                            } else {
                                TextRun(
                                    text = chars[i],
                                    colorArgb = charColor,
                                    sizeScale = sizeScale,
                                    width = w,
                                    height = contentLineHeight,
                                    baseline = contentLineHeight * 0.8f,
                                )
                            },
                        )
                    }
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
                    atoms = atomList,
                    headingLevel = currentParaHeadingLevel,
                )
            }

            // emitTextChunk：用 ZhLayout（CJK 标点压缩 / 行打断）切行 + textFullJustify 末行不对齐。
            // useZhLayout=false 时走简单贪心 fallback（visibleWidth 满则换行）。
            // isFirstChunkOfPara=true → 段首 chunk 的首行用 indentWidth 起；续行 / 后续 chunk = 0 起。
            fun emitTextChunk(textChunk: String, isFirstChunkOfPara: Boolean) {
                if (textChunk.isEmpty()) return
                val (chars, rawWidths) = contentTextMeasure.measureTextSplit(textChunk)
                if (chars.isEmpty()) return

                // **A4c+ 字体跨页修**：把 sizeScale 反映到 widths 让 ZhLayout 按真实缩放后
                // 宽度算行打断。否则 em30 大字按基础字号算「10 字一行」，emit 时实际占 25
                // 字宽 → 末尾字 x 坐标超出 visibleWidth → 字符跨页。
                //
                // chunk 内字符 i 对应 paragraph cp = chunkStartCp + i - paraStartCp。
                // currentParaSizeScales 跟 chars (code-point split) 1:1 对齐（parseInlineMarkers
                // 在 surrogate pair 时 sizes.add 1 次），所以可直接按 i 索引。
                val sizeScalesSnap = currentParaSizeScales
                val chunkStartCp = chapterPositionCounter
                val paraStartCp = currentParaCpStart
                val widths: ArrayList<Float> = if (sizeScalesSnap != null) {
                    val scaled = ArrayList<Float>(rawWidths.size)
                    val baseOffset = chunkStartCp - paraStartCp
                    for (i in rawWidths.indices) {
                        val scale = sizeScalesSnap.getOrNull(baseOffset + i) ?: 1f
                        scaled.add(if (scale != 1f) rawWidths[i] * scale else rawWidths[i])
                    }
                    scaled
                } else {
                    rawWidths
                }

                if (useZhLayout) {
                    // P3-5b Step 2c：CSS text-indent 覆盖默认 paragraphIndent；text-align 覆盖默认左对齐
                    val cssIndentPx = currentBlockStyle.textIndentPx
                    val effectiveFirstLineIndent = if (cssIndentPx > 0f) cssIndentPx else indentWidth
                    val cssAlign = currentBlockStyle.textAlign
                    // 修复用户反馈"首行缩进太大"：之前 indentSize=0 让 ZhLayout 按完整 visibleWidth
                    // 切行，但 emitOneLine 又把首行 startX = indentWidth → 实际可用宽 = visibleWidth -
                    // indentWidth，首行字数过多 → exceed 强力压缩 → 视觉感受"缩进很大字间距窄"。
                    // 修：传 paragraphIndent.length 让 ZhLayout 知道首行少 indentSize 个字位置。
                    // D1.a：margin:auto 居中段不要给段首 indent（行打断按完整 visibleWidth）。
                    // 居中检测下移到行循环内 cssAlign 上方需要 currentBlockStyle 可见 —— 这里
                    // 用 isNaN() 直查同源字段，与下方循环 marginCenter 计算一致。
                    val blockMarginAuto = currentBlockStyle.marginLeftPx.isNaN() &&
                        currentBlockStyle.marginRightPx.isNaN()
                    val indentSize = if (isFirstChunkOfPara &&
                        cssAlign != com.morealm.epub.compat.BlockStyle.TextAlign.CENTER &&
                        !blockMarginAuto
                    ) paragraphIndent.length else 0
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
                        val desiredWidth = lineWidths.sum()
                        // D1.a margin: auto 检测 —— marginLeft AUTO && marginRight AUTO。
                        // **bugfix 2026-05-22**：CSS spec 真值 —— margin: auto 仅当块有显式
                        // width 时才居中；无 width 时 margin:auto 失效，text-align 才生效。
                        // 某 EPUB h2.head1 是 `text-align: left; margin: 0 auto 0 auto` —— 应该
                        // 走 text-align:left 而非 margin-center。让 cssAlign 优先于 marginCenter，
                        // 仅当 cssAlign null（CSS 没显式 text-align）时 marginCenter 兜底。
                        val marginLeftAuto = currentBlockStyle.marginLeftPx.isNaN()
                        val marginRightAuto = currentBlockStyle.marginRightPx.isNaN()
                        val marginCenter = marginLeftAuto && marginRightAuto
                        // D1.a margin-left 段缩进：marginLeft > 0 时段整体右移 marginLeftPx
                        // （CSS 块级元素相对父容器的左偏移）。NaN/0 跳过。
                        val mlIndent = if (marginLeftAuto || currentBlockStyle.marginLeftPx <= 0f) 0f
                                       else currentBlockStyle.marginLeftPx
                        // P3-5b Step 2c：startX 计算按 CSS text-align + D1.a margin
                        val startX: Float = when {
                            cssAlign == com.morealm.epub.compat.BlockStyle.TextAlign.CENTER ->
                                ((visibleWidth - desiredWidth) / 2f).coerceAtLeast(0f)
                            cssAlign == com.morealm.epub.compat.BlockStyle.TextAlign.RIGHT ->
                                (visibleWidth - desiredWidth).coerceAtLeast(0f)
                            cssAlign == com.morealm.epub.compat.BlockStyle.TextAlign.LEFT ||
                                cssAlign == com.morealm.epub.compat.BlockStyle.TextAlign.JUSTIFY -> {
                                // 显式 LEFT/JUSTIFY：mlIndent 段缩进 + 段首 indent（heading 段不 indent）
                                mlIndent + (if (isFirstLine && currentParaHeadingLevel == 0) effectiveFirstLineIndent else 0f)
                            }
                            // cssAlign null（CSS 没显式 text-align）→ marginCenter 兜底（某 EPUB惊蛰
                            // h2.head 实际有 text-align:center 走上面分支；此分支留给纯 margin:auto
                            // 居中场景如 table.vol-title）
                            marginCenter -> ((visibleWidth - desiredWidth) / 2f).coerceAtLeast(0f)
                            // 全空：沿用旧默认（首行 indent 兜底），叠加 mlIndent
                            else -> mlIndent + (if (isFirstLine) effectiveFirstLineIndent else 0f)
                        }
                        // **D1.a DIAG**：仅当本段有 margin 属性时打 log（避免每行噪声）
                        if (marginCenter || mlIndent > 0f) {
                            com.morealm.app.core.log.AppLog.info(
                                "D1a/Margin",
                                "line emit marginCenter=$marginCenter mlIndent=$mlIndent " +
                                    "desiredWidth=$desiredWidth visibleWidth=$visibleWidth startX=$startX " +
                                    "lineText='${lineText.take(15)}'",
                            )
                        }
                        val availableWidth = visibleWidth - startX
                        val residualWidth = availableWidth - desiredWidth
                        // Justify 条件（与旧 addCharsToLineMiddle 同款）：
                        //   - 非末行
                        //   - 余宽 > 0（行未满才需要分摊；满 / 溢出不分摊）
                        //   - 余宽 ≤ availableWidth × 0.25（防止过散行；如最后短句不该 justify）
                        //   - 行宽 ≥ availableWidth × 0.65（防止极短行被强行拉宽，视觉不自然）
                        //   - chars.size > 1（单字符无间隙可分）
                        // CENTER / RIGHT 时不 justify（视觉冲突）
                        val shouldJustify = textFullJustify && !isLastLine &&
                            cssAlign != com.morealm.epub.compat.BlockStyle.TextAlign.CENTER &&
                            cssAlign != com.morealm.epub.compat.BlockStyle.TextAlign.RIGHT &&
                            !marginCenter &&  // D1.a：margin:auto 居中段不 justify
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
            // 注意：Step 2c char-level color 用 processedText 而非 paragraphText，避免
            // SPAN_COLOR_* 控制字符干扰 img 正则。混合 img+文本段会让颜色 index 偏移
            // （img 也吃 1 cp），暂可接受 —— 纯文本段是常态（标题页用 img+文本极少）。
            val imgMatches = imgRegex.findAll(processedText).toList()
            if (imgMatches.isEmpty()) {
                emitTextChunk(processedText, isFirstChunkOfPara = true)
            } else {
                var cursor = 0
                var isFirstChunk = true
                for (m in imgMatches) {
                    val before = processedText.substring(cursor, m.range.first)
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
                if (cursor < processedText.length) {
                    val tail = processedText.substring(cursor)
                    if (tail.isNotEmpty()) emitTextChunk(tail, isFirstChunkOfPara = isFirstChunk)
                }
            }

            // 段末隐式 \n 占 1 cp（与旧 ChapterProvider stringBuilder.append('\n') 严格对齐，
            // 保证跨引擎 DB 高亮 chapterPos 兼容）
            chapterPositionCounter++

            // **H3 + D1.a margin-bottom**：段末间距三优先级 ——
            //  1. CSS 显式 margin-bottom 非零且非 NaN → 用 CSS 值（允许负）；H3 默认被覆盖
            //  2. heading 段（H1-H6）→ paragraphSpacingPx × 3（H3 章首大字与正文区分）
            //  3. 正文 → paragraphSpacingPx（默认）
            // NaN (AUTO) 在垂直方向等同 0（CSS spec），归入"未显式"走 default。
            val marginBottomPx = currentBlockStyle.marginBottomPx
            val spacing = when {
                !marginBottomPx.isNaN() && marginBottomPx != 0f -> marginBottomPx
                currentParaHeadingLevel > 0 -> paragraphSpacingPx * 3
                else -> paragraphSpacingPx
            }
            // 段间空白：纯累加，不补跨页（方案 1 强硬纠正）。允许负值
            currentY += spacing
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
                "overflow=${maxColumnEnd > visibleWidth} pages=${pages.size} totalHeight=$totalHeight " +
                "contentLen=${cleanedContent.length} totalChars=$chapterPositionCounter " +
                "chapterBgImage=${chapterBgSrc != null}",
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
            chapterBgImageSrc = chapterBgSrc,
        )
    }

    /**
     * **C1/C2**：检查 content 是否以 `__MOREALM_CH_BG__<src>__/MOREALM_CH_BG__` 开头
     * 的 chapter bg marker。是 → 返回 (剥掉 marker 的 cleanedContent, src)；否 → 返回
     * (content, null)。
     *
     * marker 后紧随 \n（flattenToString 内 `if (sb.isNotEmpty()) sb.append('\n')` 触发），
     * 需要一并剥掉避免产生首段空段误占 1 cp（破坏旧 highlight 数据反查）。
     */
    private fun stripChapterBgMarker(content: String): Pair<String, String?> {
        val marker = com.morealm.epub.compat.StructuredChapterContent.CHAPTER_BG_MARKER
        val end = com.morealm.epub.compat.StructuredChapterContent.CHAPTER_BG_END
        if (!content.startsWith(marker)) return content to null
        val endIdx = content.indexOf(end, marker.length)
        if (endIdx < 0) {
            AppLog.warn("ScrollLayoutEngine", "stripChapterBgMarker: marker open but no close, content head='${content.take(60)}'")
            return content to null
        }
        val src = content.substring(marker.length, endIdx)
        val cleaned = content.substring(endIdx + end.length).removePrefix("\n")
        AppLog.info("ScrollLayoutEngine/BG", "stripped chapter bg src='${src.take(80)}' cleanedLen=${cleaned.length} originalLen=${content.length}")
        return cleaned to src.takeIf { it.isNotEmpty() }
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

    /**
     * **P3-5b Step 2c**：把 flattenToString 内联的 SPAN_COLOR marker 解析为
     * `(cleanText, perCpColor: IntArray?)`。
     *
     * 输入语义（与 epub-compat [com.morealm.epub.compat.StructuredChapterContent.richTextToBody] 配对）：
     *  - `<argbHex8><text>` 三段
     *  - 等于 SPAN_COLOR_START (SOH 0x01)
     *  - 等于 SPAN_MARKER_DELIM (STX 0x02)
     *  - 等于 SPAN_COLOR_END (ETX 0x03)
     *  - hex 总长 8 ARGB
     *
     * 返回的 cleanText 跟 [com.morealm.app.domain.render.TextMeasure.measureTextSplit] 一致按
     * code point 切；colorPerCp 长度 == cleanText 的 code-point 数（surrogate pair 合并 1 项）。
     * 无任何 color span 时返回 (text, null) 零开销。
     */
    private fun parseCharColors(text: String): Pair<String, IntArray?> {
        val parsed = parseInlineMarkers(text)
        return parsed.cleanText to parsed.colorPerCp
    }

    companion object {
        /**
         * **阶段 2-F**：常见"非内容章节"标题关键字集合 — 这些 toc navLabel 是 generic tag
         * (不是真正的章节标题)，不应在正文画大字 chapter title。
         *
         * 精确匹配 trim 后的 title (不做 substring 模糊匹配，避免"第三卷：剑起风云"含"卷"
         * 被误判)。覆盖 SampleLN "书名" / "封面" / EPUB 通用 "目录" / "前言" / "后记" 等。
         */
        private val SPECIAL_CHAPTER_TITLES: Set<String> = setOf(
            // 中文
            "书名", "封面", "封底", "扉页", "目录", "版权", "版权页",
            "前言", "序", "序言", "序章", "导言", "弁言", "引言", "引子",
            "后记", "跋", "结语", "尾声", "致谢", "致辞",
            "楔子", "卷首", "卷首语", "卷尾", "卷末",
            // 英文
            "Cover", "Title", "Title Page", "Contents", "Table of Contents",
            "Foreword", "Preface", "Prologue", "Epilogue",
            "Afterword", "Acknowledgments", "Dedication",
        )

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

        // **R1 (阶段 R1.2)**：TABLE_MARKER_* 常量已迁到 com.morealm.epub.layout.TableMarkers
        // (internal object)。主仓 contains 检测改用 hasTableMarker(text) entry point，不再
        // 暴露 marker 字面值。jadx 字节码分析主仓只能看到函数调用，不见 "__MOREALM_TBL__" 字面。
    }
}
