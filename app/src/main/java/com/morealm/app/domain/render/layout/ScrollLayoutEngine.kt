package com.morealm.app.domain.render.layout

import com.morealm.epub.layout.LayoutLog
import com.morealm.epub.layout.LayoutMeasurer
import com.morealm.epub.layout.ZhLayout
import com.morealm.epub.layout.stripLeadingBlockStyleMarker
import com.morealm.app.domain.render.color.RuleColorScanner
// **R1 (阶段 R1)** —— 核心 marker 解析 + 数据类迁到独立仓库 epub-layout。主仓只调用 entry point
// + 引用 public data class。internal marker 字面值 / parser helper 全藏在 epub-layout module。
import com.morealm.epub.layout.BoxGeometry
import com.morealm.epub.layout.ContentRun
import com.morealm.epub.layout.EmGeometry
import com.morealm.epub.layout.InlineBgRun
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
 * 排版算法 M1.2-M1.5 参考成熟开源阅读器实现的章节排版主流程独立实现；
 * Compose / Canvas 渲染层（M2）走 MoRealm 自有。
 */
class ScrollLayoutEngine(
    val viewWidth: Int,
    val viewHeight: Int,
    val paddingLeft: Int,
    val paddingRight: Int,
    val paddingTop: Int,
    val paddingBottom: Int,
    val titleMeasurer: LayoutMeasurer,
    val contentMeasurer: LayoutMeasurer,
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
    /** 章首块（橙色章序号 + 大字主标题 + 装饰线）专用测量器。null = 用 [titleMeasurer]。 */
    val chapterNumMeasurer: LayoutMeasurer? = null,
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
    /**
     * 正文规则上色扫描器（PRD「文字上色」）。null = 关闭（默认，零开销 fast path）。
     * 非 null 时排版期对每段 cleanText 现算规则色，按 CSS 优先 merge 进 per-cp 前景色，
     * 走 columns 路径绘制（[ScrollColumn.colorArgb]，col.start 含 justify gap → 两端对齐保留）。
     */
    val ruleColorScanner: RuleColorScanner? = null,
    val layoutLog: LayoutLog = LayoutLog.NONE,
) {

    val visibleWidth: Int = viewWidth - paddingLeft - paddingRight
    val visibleHeight: Int = viewHeight - paddingTop - paddingBottom

    // ZhLayout compressible 阈值（全角 CJK 字宽）—— 测量在此完成，喂进纯化后的 ZhLayout 决策内核。
    private val cnCharWidth: Float = contentMeasurer.cjkFullCharWidth
    private val contentTextHeight: Float = contentMeasurer.textHeight
    private val contentLineHeight: Float = contentTextHeight * lineSpacingExtra

    /**
     * **2026-05-29 横线对齐** —— 缓存 base 字号 descent，避免 emitLine 每行调 `fontMetrics` 分配。
     * em 段文字视觉底 = `lineTop + scaledTextSize×0.8 + contentDescent×scale`（对齐
     * drawer scaled-text baseline，详 [ScrollLine.textBottomRel]）。
     */
    private val contentDescent: Float = contentMeasurer.descent

    /**
     * 章首块 title 主行行高 —— 复用 [titleMeasurer]。直接用 textHeight（**不**乘 lineSpacingExtra），
     * 与旧 [com.morealm.app.domain.render.ChapterProvider] 对齐——标题行紧凑。
     */
    private val titleTextHeight: Float = titleMeasurer.textHeight

    /**
     * 章首块章序号小字行测量器 —— 复用 [chapterNumMeasurer]，null 时 fallback [titleMeasurer]。
     */
    private val chapterNumMeasurerSafe = chapterNumMeasurer ?: titleMeasurer
    private val chapterNumTextHeightSafe: Float = chapterNumMeasurerSafe.textHeight

    /**
     * 段首缩进的像素宽度 —— 作为**排版属性**应用（首行 lineCursor 起点 = indentWidth），
     * 而**不**作为字符拼进 measureTextSplit。
     *
     * 设计理由（见 user feedback 2026-05-17）：
     * - 缩进是 layout margin，不是原文字符 → 不占 ScrollColumn 位 → 高亮 rect 不延伸缩进区域
     * - findColumnByPixel 命中段首 x < indentWidth 时吸附到段首第一个真实字符（M1.7 实现）
     * - 持久化 chapterPosition 与原文字符 offset 严格 1:1，跨引擎一致
     */
    private val indentWidth: Float = contentMeasurer.measureWidth(paragraphIndent)

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
        // 参考实现不在这些章正文画 toc navLabel，仅 InfoBar 显示。MoRealm 之前自画
        // toc navLabel ("书名" / "封面" / "第一卷 卷起风云") 让封面页顶部多出小字与大字布局
        // 重复 → 视觉跟参考图 38 / 16 不一致。
        //
        // 条件 1：title 是常见的"非内容章节"关键字（精确匹配 trim 后的 title）→
        //   覆盖 某日轻 BookName/cover/封面 等 toc navLabel 是 generic tag 的场景。
        //   (某日轻 5 sibling tables 被 TableMergeVisitor merge 成普通 RichText paragraph
        //    不产 table marker，所以走条件 1 而非条件 2。)
        //
        // 条件 2：paragraphs 前 3 段任意段含 table marker → 覆盖某仙侠 chapter-1 卷扉页
        //   (toc navLabel "第一卷 剑起风云" 不是 generic tag，但内容是 vol-title table)。
        val titleTrimmed = title.trim()
        val isSpecialChapterTitle = titleTrimmed in SPECIAL_CHAPTER_TITLES
        val firstFewParas = paragraphs.asSequence().filter { it.isNotBlank() }.take(3).toList()
        val hasEarlyTableMarker = firstFewParas.any { hasTableMarker(it) }
        // **Step 7 v7 (2026-05-24)**: 条件 3 — image-only 章 (第 1 段是纯 <img src="...">) →
        // 视为 "封面 / 卷首页" → skip self-draw title。某日轻 cover.xhtml 内容只 1 个
        // ChapterBlock.Image，flatten 后 paragraph = "<img src=\"img:Images/cover.jpg\">"
        // (单 line 含 img tag)。caller 传 title="Cover" (或其他) 都该 skip。
        val imgOnlyPattern = Regex("^\\s*<img(?:fp)?\\s+[^>]*>\\s*$")
        // 剥掉首段可能的 leading BLOCK_STYLE marker 再判 image-only：某 EPUB 封面/卷首/简介页
        // 的首图带装饰（如 img { width:100% }）→ flatten 在 <img> 前挂 __MOREALM_BLOCK_STYLE__
        // marker，裸 imgOnlyPattern 不匹配 → firstParaIsImageOnly 误判 false → 与页面自带 h1
        // 标题重复自画章节标题（简介页「内容简介」h1 + logo 图场景，2026-05-29 真机 log 实证）。
        val firstParaForImgCheck = firstFewParas.firstOrNull()?.let(::stripLeadingBlockStyleMarker) ?: ""
        val firstParaIsImageOnly = imgOnlyPattern.matches(firstParaForImgCheck)
        val contentProvidesChapterTitle = isSpecialChapterTitle || hasEarlyTableMarker || firstParaIsImageOnly

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

        // **2026-05-28 Container box group** —— 当前 paragraph 所属外层装饰容器 group id 栈。
        //
        // 解析 `__MOREALM_BOX_START__...__/MOREALM_BOX_HEADER__` paragraph 时：分配新 id +
        // decodeBlockStyle 取 outer style → push 到 boxStack + 记到 boxGroupStyles，**continue**
        // 不产 line。后续普通 paragraph emit line 时 emitLine 读 boxStack.lastOrNull() 作
        // ScrollLine.boxGroupId（innermost group）。解析 `__/MOREALM_BOX_END__` paragraph 时
        // pop 栈，**continue** 不产 line。
        //
        // 章末 layoutChapter 返回时把 boxGroupStyles.toMap() 塞到 ScrollChapterLayout.boxGroupStyles
        // 供 ScrollBlockStyleDrawer 查 group 装饰。
        val boxStack: ArrayDeque<Int> = ArrayDeque()
        var nextBoxGroupId: Int = 0
        val boxGroupStyles: MutableMap<Int, com.morealm.epub.compat.BlockStyle> = mutableMapOf()

        // **2026-05-28 Step 9.X — Container box scope awareness**：
        //
        // box 内 emit paragraph 时 line 应排在 box 内侧（box.paddingLeft / borderWidth /
        // marginLeft 三层之后），而非 0..visibleWidth。这里维护 box 嵌套时每层对左 / 右
        // 留白的累计贡献：
        //   - currentBoxLeftPad  = ΣboxStack 每层 (paddingLeft + ml(NaN 跳过) + borderWidth)
        //   - currentBoxRightPad = ΣboxStack 每层 (paddingRight + mr(NaN 跳过) + borderWidth)
        // 用 contribStack 记录每层 push 时的增量值（boxStack pop 时减回去，避免精度丢失）。
        //
        // effectiveVisibleWidth = visibleWidth - currentBoxLeftPad - currentBoxRightPad
        // emit paragraph 路径（CENTER / JUSTIFY / wrap / startX）用 effective；layoutChapter
        // 章级 visibleWidth 不动。
        var currentBoxLeftPad: Float = 0f
        var currentBoxRightPad: Float = 0f
        // 栈：每层 push 时 (leftAdd, rightAdd) 增量，pop 时减回。
        val boxPadContribStack: ArrayDeque<Pair<Float, Float>> = ArrayDeque()
        // 当前 effective 可排版宽度 = visibleWidth - 累计左右 pad；用 lambda 让取值始终是最新栈状态
        fun effectiveVisibleWidth(): Float =
            (visibleWidth.toFloat() - currentBoxLeftPad - currentBoxRightPad).coerceAtLeast(1f)

        // **margin collapse 简化版（box scope 内）**：
        // 上段 marginBottom 与下段 marginTop 取 max，实现：
        //   下段 emit margin-top 时 effectiveMt = max(0, mt - lastSegMarginBottom)
        //   段末 emit margin-bottom 后 lastSegMarginBottom = mb
        //   BOX_START / BOX_END 时重置 lastSegMarginBottom = 0（跨 box 不 collapse）
        var lastSegMarginBottom: Float = 0f

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
        // **Step 9.2 Phase B / Phase 3**：当前 paragraph 的字符级 inline 背景盒子 run 列表
        // （来自 epub-layout parseInlineMarkers.bgRuns）。null = 本段无 SPAN_BG。emitOneLine
        // 按 (chapterPositionCounter - paraStartCp) 落在哪个 run 区间取 argb/padding/boxId，
        // 同 box 连续字符 coalesce 成单 TextRun（各画各 rect）。
        var currentParaBgRuns: List<InlineBgRun>? = null
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
            isFullPageImage: Boolean = false,
            isHorizontalRule: Boolean = false,
            hrLeftPx: Float = 0f,
            hrRightPx: Float = 0f,
            lineHeightOverride: Float? = null,
            atoms: List<Atom>? = null,
            headingLevel: Int = 0,
            // **D 模型 (阶段 1 重构)** — table line cells；null = 非 table line。
            // 详 ScrollLine.cells / ScrollLineCell 注释。
            cells: List<ScrollLineCell>? = null,
        ) {
            // **2026-05-30 em 小字段行高收紧**：currentParaSizeScales 只缩字宽不缩行高，
            // em09（90% 字，如人物简介）用全字号行高 → 行距过松（溢出）。对齐 CSS 无单位
            // line-height 随 font-size 缩放：整行都是小 em（atoms 最大 sizeScale ≤1）按它收紧；
            // 表格行（lineHeightOverride 非空）/ 含大字（标题）/ 混排不动（expand 方向 PRD #1 后做）。
            val emLineScale: Float = run {
                if (lineHeightOverride != null) return@run 1f
                val atomList = atoms ?: return@run 1f
                var maxS = 0f
                var hasText = false
                for (a in atomList) if (a is TextRun) { hasText = true; if (a.sizeScale > maxS) maxS = a.sizeScale }
                EmGeometry.lineHeightScale(if (hasText) maxS else null)
            }
            val effectiveLineHeight = (lineHeightOverride ?: contentLineHeight) * emLineScale
            // **2026-05-29 横线对齐**：em 段（atoms 含 sizeScale>1）算文字视觉底相对 lineTop 的偏移，
            // 对齐 drawer `effectiveBaselineY = lineTop + scaledTextSize×0.8`（见 [ScrollLine.textBottomRel]）。
            // 非 em 段保持 NaN，消费方（#2 横线 / #3 clamp）回退 lineBottom，零行为变化。
            val lineTextBottomRel: Float = run {
                val atomList = atoms ?: return@run Float.NaN
                var maxScale = 1f
                for (atom in atomList) {
                    if (atom is TextRun && atom.sizeScale > maxScale) maxScale = atom.sizeScale
                }
                EmGeometry.textBottomRel(contentMeasurer.textSize, contentDescent, maxScale)
            }
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
                    isFullPageImage = isFullPageImage,
                    isHorizontalRule = isHorizontalRule,
                    hrLeftPx = hrLeftPx,
                    hrRightPx = hrRightPx,
                    blockStyle = currentBlockStyle,
                    boxGroupId = boxStack.lastOrNull(),
                    atoms = atoms,
                    headingLevel = headingLevel,
                    cells = cells,
                    textBottomRel = lineTextBottomRel,
                )
            )
            currentY = finalBottom
        }

        // ── **方案 C / Step 5 inline-block container helper** —— qipao / cont-qipao 等
        // CSS class 装饰盒 (含 widthPx + heightPx + borderRadius + bg) emit 单 ScrollLine +
        // 单 ScrollLineCell：box 按 currentBlockStyle.widthPx × fontSizeScale 宽 → 内文按
        // box 宽切行竖排堆叠。
        //
        // caller 在两条路径触发：
        //  1. 普通 RichText 段 (currentBlockStyle.widthPx 非 null)
        //  2. hasTableMarker 分支内 (Table.blockStyle 含 widthPx，div-wraps-Table 经
        //     mergeAncestorBlockStyle 传入)
        //
        // 不处理 margin-top / margin-bottom (由 caller 应用)，也不应用 chapterPositionCounter++
        // 末位虚换行 — 由 caller 做。
        /**
         * **Step 7 v5 (2026-05-24)** — emitInlineBlockContainer 接 List<List<ContentRun>> (rows)。
         *
         * 之前 v4 用 List<ContentRun> flatten — 跨 row/cell 丢边界让 wrap 算法把 row 内字符
         * 串联当一行处理，"女神大人" cell 跟 "啊啊，" cell 拼一起后按 innerW 贪心切 → "大人"
         * 被拆 "大"/"人" 两行 (image 66 bug)。
         *
         * v5 接收 List<List<ContentRun>> — 每 inner list 是一 row 的 runs (HTML 内嵌 table 一
         * row 一行)。每 row 内 wrap (单 row 太宽时 innerW 切多行 fallback)，row 间强制新 line。
         *
         * @param rowRuns List<row's-runs>，每个 row 一份 ContentRun list。e.g. qipao 3 row
         *   → 3 个 inner lists: [["啊啊，"], ["没用","的"], ["女神","大人"]]
         * @param cellLevelColor Table.blockStyle.textColor fallback (qipao 白 #ffffff)
         */
        fun emitInlineBlockContainer(
            rowRuns: List<List<ContentRun>>,
            designW: Float,
            designH: Float,
            cellLevelColor: Int? = null,
        ) {
            val fontScale = contentMeasurer.textSize / 16f
            // **Step 7 v6 (2026-05-24)**: CSS box-sizing: content-box (default) — padding 加在
            // width 外。box rect 视觉宽高 = inner content 宽高 + 2 × padding。
            // 之前 (v5) `innerW = boxW - 2 × pad` 把 padding 算 box 内 (border-box)，让
            // innerW = 252 - 28.8 = 223 不够装一行字 → 末字折行。
            // CSS spec: .qipao { width:3.5em; padding:0.2em } → content width = 3.5em，
            // box visual width = 3.5em + 2×0.2em = 3.9em。
            //
            // **P2 fix (2026-05-27)**：var 而非 val，下方圆形溢出检测时可动态扩 padding 让
            // 内切正方形装下内文（详 contentH 计算后的 isCircle 分支注释）。
            val innerW = designW * fontScale
            var padScaled = currentBlockStyle.paddingLeftPx * fontScale
            var boxW = innerW + 2f * padScaled
            var boxH = designH * fontScale + 2f * padScaled

            // **Step 7 v5**: 每 row 独立 wrap。row 内 runs 拼成 glyphs，按 innerW 贪心切行
            // (单 row 太宽时仍 wrap)。row 间强制新 line。
            fun runsToGlyphs(runs: List<ContentRun>): List<CellGlyph> {
                val glyphs = ArrayList<CellGlyph>()
                for (run in runs) {
                    when (run) {
                        is ContentRun.Text -> {
                            // **P3 v2 (2026-05-27)** — 自带字体段用 swap typeface 真实测量 ASCII
                            // 标点宽度。取代 P3 v1 的「boost 到 0.5×CJK」(实测过松散)。详
                            // [swapTypefaceMeasure] kdoc。
                            val (chars, rawWidths) = contentMeasurer.measureSplitWithFont(run.text, currentBlockStyle.fontFamily)
                            val effScale = run.style.fontSizeEm ?: 1f
                            val effColor = run.style.color ?: cellLevelColor
                            for (i in chars.indices) {
                                val w = if (effScale != 1f) rawWidths[i] * effScale else rawWidths[i]
                                glyphs.add(CellGlyph(chars[i], w, effColor, null, effScale))
                            }
                        }
                        is ContentRun.Image -> {
                            val imgW = contentLineHeight * 1.5f
                            glyphs.add(CellGlyph("￼", imgW, null, run.src, 1f))
                        }
                        is ContentRun.NestedTable -> { /* 跳过 */ }
                    }
                }
                return glyphs
            }

            val ibLines = ArrayList<List<CellGlyph>>()
            if (innerW <= 0f) return
            for (rowRunList in rowRuns) {
                val rowGlyphs = runsToGlyphs(rowRunList)
                if (rowGlyphs.isEmpty()) continue
                // row 内贪心 wrap (单 row 超 innerW 时切多行)
                var lineBuf = ArrayList<CellGlyph>()
                var lineCumW = 0f
                for (g in rowGlyphs) {
                    if (lineCumW + g.width > innerW && lineBuf.isNotEmpty()) {
                        ibLines.add(lineBuf.toList())
                        lineBuf = ArrayList()
                        lineCumW = 0f
                    }
                    lineBuf.add(g)
                    lineCumW += g.width
                }
                if (lineBuf.isNotEmpty()) ibLines.add(lineBuf.toList())
                // row 间强制 line break (lineBuf 提交后，下一 row 起新 line)
            }
            if (ibLines.isEmpty()) return

            val innerLH = contentTextHeight * 0.95f
            val contentH = ibLines.size * innerLH

            // **2026-05-30 删 P2 √2 内切正方形膨胀**：参照主流阅读器引擎实证——
            // border-radius 是纯绘制层装饰、不参与测量，盒子尺寸只由 width/height(+padding) 定，
            // 放不下就溢出/裁，绝不为圆角撑大。之前 P2 为「文字不碰圆边」把圆放大 √2≈1.41×
            // （4em→5.7em），偏离参照让 qipao 圆全部过大。现让 border-radius 退出测量：圆径锁
            // 声明 width/height；文字放得下即 declared 尺寸，真比圆宽才 wrap + 长高（不裁字兜底）。
            val lineH = maxOf(boxH, contentH + 2f * padScaled)

            // **EpubW5H/CircleBox/Emit diag** — 排版后最终几何（含 P2 enlarge 后），用于验证。
            run {
                val isCircle = currentBlockStyle.borderRadiusPx.isInfinite()
                val inscribedSide = minOf(boxW, boxH) / 1.41421356f
                val widthOverflow = innerW > inscribedSide
                val heightOverflow = contentH > inscribedSide
                layoutLog.info(
                    "EpubW5H/CircleBox/Emit",
                    "para#$paragraphCounter isCircle=$isCircle designW=$designW designH=$designH " +
                        "fontScale=$fontScale padScaled=$padScaled " +
                        "innerW=$innerW boxW=$boxW boxH=$boxH " +
                        "lines=${ibLines.size} contentH=$contentH lineH=$lineH " +
                        "inscribedSide=$inscribedSide " +
                        "widthOverflow=$widthOverflow heightOverflow=$heightOverflow " +
                        "family='${currentBlockStyle.fontFamily}'",
                )
            }

            // margin:auto 水平定位（同 layoutTable）：双 auto 居中、仅左 auto 右贴、否则按 marginLeft。
            // **2026-05-30 box 内定位**：相对 box 内容区（cbLeft=currentBoxLeftPad，cbW=内容宽），
            // 不是整个 visibleWidth —— 否则 box 内左对齐圆标（chara-qipao2 margin=0）会跑到屏幕左缘/
            // 框外（左对齐时 marginLeft=0=屏左；之前居中靠「全宽中≈框中」巧合才对）。box 外
            // （currentBoxLeftPad=0、cbW=visibleWidth）退化为整宽，行为不变（如框外名字圆标居中）。
            val cbLeft = currentBoxLeftPad
            val cbW = effectiveVisibleWidth()
            val mlAuto = currentBlockStyle.marginLeftPx.isNaN()
            val mrAuto = currentBlockStyle.marginRightPx.isNaN()
            val cellLeft = when {
                mlAuto && mrAuto -> cbLeft + ((cbW - boxW) / 2f).coerceAtLeast(0f)
                mlAuto && !mrAuto -> cbLeft + (cbW - boxW).coerceAtLeast(0f)
                else -> cbLeft + currentBlockStyle.marginLeftPx * fontScale
            }

            val contentTopInCell = (lineH - contentH) / 2f

            val totalGlyphs = ibLines.sumOf { it.size }
            val ibAtoms = ArrayList<Atom>(totalGlyphs)
            val ibCols = ArrayList<ScrollColumn>(totalGlyphs)
            val ibSb = StringBuilder()
            val ibFirstCp = chapterPositionCounter
            for ((rowIdx, rowGlyphs) in ibLines.withIndex()) {
                val realLineW = rowGlyphs.sumOf { it.width.toDouble() }.toFloat()
                // **Step 7 v6**: localX 不再加 padScaled — cell.contentLeft 已经是 rect 内容区
                // 左边界 (drawer rect 算法: rectLeft = cell.contentLeft - padLeft)，atom 中心
                // = cell.contentLeft + innerW/2 等于 rect 中心 (padLeft==padRight)。
                var localX = ((innerW - realLineW) / 2f).coerceAtLeast(0f)
                val localY = contentTopInCell + rowIdx * innerLH
                for (g in rowGlyphs) {
                    val globalX = cellLeft + localX
                    ibCols.add(
                        ScrollColumn(
                            charData = g.text,
                            start = globalX,
                            end = globalX + g.width,
                            chapterPosition = chapterPositionCounter,
                            colorArgb = g.color,
                            inlineImageSrc = g.imageSrc,
                        ),
                    )
                    if (g.imageSrc != null) {
                        ibAtoms.add(
                            InlineImage(
                                src = g.imageSrc, width = g.width, height = g.width,
                                cellLocalX = localX, cellLocalY = localY,
                            ),
                        )
                    } else {
                        ibAtoms.add(
                            TextRun(
                                text = g.text,
                                colorArgb = g.color,
                                sizeScale = g.sizeScale,
                                width = g.width,
                                height = innerLH,
                                baseline = innerLH * 0.8f,
                                cellLocalX = localX,
                                cellLocalY = localY,
                            ),
                        )
                    }
                    ibSb.append(g.text)
                    chapterPositionCounter++
                    localX += g.width
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
        }

        // emitImage：识别 `<img src="...">` → emit 单个 ScrollLine（columns 空 +
        // isImage=true + imageSrc=src + height = 图片像素高度）。
        // dims 解码走 [imageDimensionsResolver]；null 时 fallback 4:3（visibleWidth × 0.75）。
        // 占 1 cp 与旧引擎 stringBuilder.append(" ") 严格对齐。
        // 返回累加后的 chapterPositionCounter（含图片占的 1 cp）。
        fun emitImage(src: String, paragraphNum: Int, startCp: Int, isFullPage: Boolean = false, widthFraction: Float? = null): Int {
            val dims = imageDimensionsResolver.resolve(src, visibleWidth)
            val imgWidth: Int
            val imgHeight: Int
            val origW = dims?.first ?: -1
            val origH = dims?.second ?: -1
            // **fullpage 整屏渲染分支**：某仙侠等 EPUB 用 `<svg width="100%" height="100%">`
            // 包裹封面 image，[SvgImageRewriteVisitor] 把 svg 容器转 `<imgfp>` marker；
            // 调用方设 isFullPage=true 后，slot 用 viewWidth (整屏宽，不减 padding) 替代
            // visibleWidth，让封面图占满整个屏幕宽度（封面整屏渲染）。
            // 普通 `<img>` (某轻小说 01 等) isFullPage=false 走原 visibleWidth 段落图行为。
            val baseW = if (isFullPage) viewWidth else visibleWidth
            // 声明显示宽度（widthFraction = 占视口比例，epub-lib 从 img `width='60%'` / CSS width 算）
            // → 起始宽；无声明（null）走满宽 baseW（封面 / 插画行为不变）。fullpage 封面忽略 fraction。
            val declaredW = if (!isFullPage && widthFraction != null && widthFraction > 0f) {
                (baseW * widthFraction).toInt().coerceIn(1, baseW)
            } else {
                baseW
            }
            if (dims != null && dims.first > 0 && dims.second > 0) {
                val (intW, intH) = dims
                var w = declaredW
                var h = (intH.toFloat() * declaredW / intW).toInt()
                // 高度上限仍按 visibleHeight clamp（不超过 page 可用高），避免被 InfoBar 裁掉
                if (h > visibleHeight) {
                    w = (w.toFloat() * visibleHeight / h).toInt()
                    h = visibleHeight
                }
                imgWidth = w; imgHeight = h
            } else {
                // Fallback 4:3，与旧 ChapterProvider.setTypeImage line 684 兜底一致
                imgWidth = declaredW
                imgHeight = (declaredW * 0.75f).toInt().coerceAtMost(visibleHeight)
            }
            // 诊断日志：原图 W/H、resolver 返回值、visible/view 区、声明宽度比例、emit 后的尺寸。
            layoutLog.info(
                "Engine/Image",
                "emitImage src='${src.takeLast(40)}' orig=${origW}x${origH} " +
                    "view=${viewWidth}x${viewHeight} visible=${visibleWidth}x${visibleHeight} " +
                    "wFrac=$widthFraction isFullPage=$isFullPage → emit=${imgWidth}x${imgHeight} " +
                    "paraNum=$paragraphNum startCp=$startCp pageLevelMode=$pageLevelMode",
            )

            // emitLine 用 lineHeightOverride 让图片行高 = imgHeight（不走 contentLineHeight）
            emitLine(
                lineColumns = emptyList(),  // 图片不含字符 column
                lineText = " ",              // 占位文本与旧引擎对齐
                paragraphNum = paragraphNum,
                firstChapterPos = startCp,
                lastChapterPos = startCp,
                isImage = true,
                imageSrc = src,
                isFullPageImage = isFullPage,
                lineHeightOverride = imgHeight.toFloat(),
            )
            // 图片占 1 cp（旧引擎 stringBuilder.append(" ")）
            return startCp + 1
        }

        // emitHorizontalRule：`<hr/>` flatten 的 <morealmhr/> 段 → emit 一条横线 line。
        // x 范围贴当前 box 内容宽（currentBoxLeftPad..visibleWidth-currentBoxRightPad，与同
        // box 内文字左缘一致）；box 外 = 整个 visibleWidth。行高用 contentLineHeight 给上下留白，
        // 渲染层在垂直中线画线。columns 空、占 1 cp（与图片占位对齐）。
        fun emitHorizontalRule(paragraphNum: Int, startCp: Int) {
            val left = currentBoxLeftPad
            val right = (visibleWidth.toFloat() - currentBoxRightPad).coerceAtLeast(left + 1f)
            emitLine(
                lineColumns = emptyList(),
                lineText = "",
                paragraphNum = paragraphNum,
                firstChapterPos = startCp,
                lastChapterPos = startCp,
                isHorizontalRule = true,
                hrLeftPx = left,
                hrRightPx = right,
                lineHeightOverride = contentLineHeight,
            )
        }

        // emitTitleParagraph：排版一段 title 文本（可跨行换行），段末追加 \n cp。
        // 与正文段语义对齐：每字符占 1 cp，段末 \n 占 1 cp（旧 ChapterProvider 兼容关键）。
        // 返回累加后的 chapterPositionCounter（含段末 \n）。
        fun emitTitleParagraph(
            text: String,
            measurer: LayoutMeasurer,
            lineHeight: Float,
            isChapterNum: Boolean,
            isTitleEnd: Boolean,
            paragraphNum: Int,
            startCp: Int,
        ): Int {
            val (chars, widths) = measurer.measureSplit(text)
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
                    measurer = chapterNumMeasurerSafe,
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
                        measurer = titleMeasurer,
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

        /**
         * **Step 5 (2026-05-24)**: strip nested table markers (`__MOREALM_TBL__` ...
         * `__/MOREALM_TBL__`) from cell paragraph 字符串。避免 nested table marker 字面渲染
         * 成 "__MOREALM_TBL__" 等文本（image 56 bug）。
         *
         * 简化：跳过 nested table 内容 (字符丢失) — 完整 nested layout 留 Step 7 做。
         */
        fun stripNestedTableMarkers(raw: String): String {
            if (raw.isEmpty()) return raw
            val tblStart = "__MOREALM_TBL__"
            val tblEnd = "__/MOREALM_TBL__"
            if (!raw.contains(tblStart)) return raw
            val sb = StringBuilder()
            var pos = 0
            while (pos < raw.length) {
                val startIdx = raw.indexOf(tblStart, pos)
                if (startIdx < 0) {
                    sb.append(raw, pos, raw.length)
                    break
                }
                if (startIdx > pos) sb.append(raw, pos, startIdx)
                val endIdx = raw.indexOf(tblEnd, startIdx)
                if (endIdx < 0) break
                pos = endIdx + tblEnd.length
            }
            return sb.toString()
        }

        // **Step 7 (2026-05-24)** —— layoutCellLines 升级 ContentRun 路径。
        // 之前用 cell.contentParagraphs (List<String> 含 cleanText 含 U+FFFC 占位 +
        // 字面 markers) — image src 丢失 (cell 内 chibi 渲染成 [OBJ] placeholder)，cell
        // 内 per-cp color 丢失 (只用 cell.textColor 首字色)。
        //
        // 切换到 cell.paragraphs (List<List<ContentRun>>) 让 inline image / per-cp color/size /
        // nested table 都有结构化数据。NestedTable 当前 strip (Step 7+ 真递归)。
        //
        // CellGlyph 携带 (text 或 image src + width + color + size)，layoutTable emit 时
        // 识别 imageSrc 非 null → emit InlineImage atom 取代 TextRun。
        fun layoutCellLines(cell: ParsedTableCell, cellWidthScale: Float = 1f): List<List<CellGlyph>> {
            val fontScale = contentMeasurer.textSize / 16f
            // cell.widthPx 是设计 px（epub-compat rootFontSize=16）；glyph 量度是渲染 px，
            // 需 × fontScale 同单位，让气泡（内层 div `width:18em` 已传到 cell.widthPx）在当前
            // 字号下按 18em 换行而非整屏宽（修「气泡文字溢出被切」）。
            val widthCap = cell.widthPx?.let { it * fontScale * cellWidthScale } ?: visibleWidth.toFloat()
            val cellLevelSizeScale = cell.sizeScale
            // **Step 7 bugfix v2 (2026-05-24)**: 不 fallback to cell.textColor (首字 color)。
            // ContentRun.Text.style.color 已携带 per-cp color (parseInlineMarkers 出的 colorPerCp
            // coalesced 进 InlineStyle)。null = 用默认色 (黑)，不应被首字色污染。
            // 某日轻 sibling table 「某标题」cell 内 "为" "的" color=null (默认黑) vs
            // "美""好" color=粉/橙 — 之前 fallback cellLevelColor 让 "为" 也粉色 (bug)。
            val out = ArrayList<List<CellGlyph>>()

            for (paraRuns in cell.paragraphs) {
                // 把 paraRuns (List<ContentRun>) 扁平化成 CellGlyph 序列，然后按 widthCap 切行
                val glyphs = ArrayList<CellGlyph>()
                for (run in paraRuns) {
                    when (run) {
                        is ContentRun.Text -> {
                            val (chars, rawWidths) = contentMeasurer.measureSplit(run.text)
                            val effScale = run.style.fontSizeEm ?: cellLevelSizeScale
                            // null = 默认色 (黑)；非 null = per-cp color
                            val effColor = run.style.color
                            for (i in chars.indices) {
                                val w = if (effScale != 1f) rawWidths[i] * effScale else rawWidths[i]
                                glyphs.add(CellGlyph(text = chars[i], width = w, color = effColor, imageSrc = null, sizeScale = effScale))
                            }
                        }
                        is ContentRun.Image -> {
                            // **Step 7 bugfix v2**: chibi 占 cell.widthPx × fontScale (CSS img
                            // width:100% inherits cell width)。无 cell.widthPx 时 fallback
                            // contentLineHeight × 1.5。 某日轻 sibling 2 chibi sy2.png 在
                            // cell style="width:4em" → cell.widthPx=64 → image width = 64*4.5 ≈ 288。
                            val imgW = cell.widthPx?.let { it * fontScale * cellWidthScale } ?: (contentLineHeight * 1.5f)
                            glyphs.add(CellGlyph(text = "￼", width = imgW, color = null, imageSrc = run.src, sizeScale = 1f))
                        }
                        is ContentRun.NestedTable -> {
                            // Step 7+：暂跳过 nested table content (留独立 task 做 nested 递归 layout)
                            // 让 marker 字面不显示 + 字符 (5 字「测试作者 B」) 暂丢
                        }
                    }
                }
                if (glyphs.isEmpty()) continue
                // 按 widthCap 贪心切行
                var lineBuf = ArrayList<CellGlyph>()
                var lineW = 0f
                for (g in glyphs) {
                    if (lineW + g.width > widthCap && lineBuf.isNotEmpty()) {
                        out.add(lineBuf.toList())
                        lineBuf = ArrayList()
                        lineW = 0f
                    }
                    lineBuf.add(g)
                    lineW += g.width
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
        //  - margin-left auto + margin-right 非 auto → table 整体右贴（某仙侠 vol-title
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
                // **2026-05-30 气泡装进屏幕**：本行声明总宽（Σ cell.widthPx×fontScale + gap）超视口时
                // 按 visibleWidth 等比收缩各 cell（聊天气泡 头像5em + 气泡18em = 23em，大字号下超屏，
                // 之前整表溢出被 clipRect 裁掉右半边）。不溢出时 scale=1，正常表格零行为变化。
                val rowWidthScale = run {
                    val fs = contentMeasurer.textSize / 16f
                    val declaredSum = row.cells.sumOf { ((it.widthPx ?: 0f) * fs).toDouble() }.toFloat()
                    val gaps = 2f * (row.cells.size - 1).coerceAtLeast(0)
                    val budget = (visibleWidth.toFloat() - gaps).coerceAtLeast(1f)
                    if (declaredSum > budget && declaredSum > 0f) budget / declaredSum else 1f
                }
                val cellLines: List<List<List<CellGlyph>>> = row.cells.map { layoutCellLines(it, rowWidthScale) }
                // **D2.a Commit 2d fix**：CSS spec — td.width 是最小宽度；实际 cell width =
                // max(declared widthPx, actual content max line width)。某仙侠 td.width=1.2em
                // ≈ 19.2px < CJK 字符 ~24-30px → 字符会溢出 + cellCursorX 累加用 19.2 让
                // 相邻 cell 字符重叠（视觉看到两 cell 字符叠在一起）。修：cellWidths 取
                // max(declared, content max)。
                val cellWidths: List<Float> = row.cells.mapIndexed { idx, cell ->
                    val declared = cell.widthPx ?: 0f
                    val contentMax = cellLines[idx].maxOfOrNull { line ->
                        line.sumOf { it.width.toDouble() }.toFloat()
                    } ?: 0f
                    maxOf(declared, contentMax)
                }
                // **D2.b**：cell 之间间距 = CSS `border-spacing` 默认 2px（border-collapse: separate
                // 默认值）。某仙侠 table.vol-title 未声明 border-spacing 也未声明 border-collapse →
                // 走 CSS spec 默认 2px。不硬编 0.3em（之前 hack）— 真按 CSS 解析。
                // TODO(D3.a)：解析 CSS `border-spacing` 当 caller 显式设时（某仙侠场景默认即可）。
                val cellGap: Float = 2f
                val maxLines = cellLines.maxOfOrNull { it.size } ?: 0
                if (maxLines == 0) continue

                // table 整体水平 offset（基于 currentBlockStyle margin auto 检测）
                // **2026-05-26 fix**：marginLeftPx > 0 (非 auto) 时应用为水平左偏移
                // 之前 else 分支固定 0f → `<div class="qipao" style="margin-left:6em">` 和
                // `<table style="margin:-1em 0 0 7em">` 的 marginLeftPx 数值被吞，table 紧贴左边
                // 而不是参考实现的居中偏右 / 右侧。也覆盖原 mrAuto+mlNumeric 场景：之前 ml 数值
                // 被吞，表"左贴"实为"左缘 0"，参考实现是"距左 N em"。
                val totalWidth = cellWidths.sum() + cellGap * (cellWidths.size - 1)
                val mlAuto = currentBlockStyle.marginLeftPx.isNaN()
                val mrAuto = currentBlockStyle.marginRightPx.isNaN()
                val mlValue = if (!mlAuto && currentBlockStyle.marginLeftPx > 0f) currentBlockStyle.marginLeftPx else 0f
                val tableXOffset = when {
                    mlAuto && !mrAuto -> (visibleWidth - totalWidth).coerceAtLeast(0f)  // 右贴
                    !mlAuto && mrAuto -> mlValue  // 左偏 marginLeftPx
                    mlAuto && mrAuto -> ((visibleWidth - totalWidth) / 2f).coerceAtLeast(0f)  // 居中
                    else -> mlValue  // 双非 auto: 应用 marginLeftPx 数值（CSS spec 标准）
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
                // **Step 7 bugfix v3 (2026-05-24)**: cellHeights 取 max(字符总高, image 最大高)。
                // 单 line cell 含 chibi (square width × width) 时 image height = imgW
                // (~280px) >> cellStride (~60px)。cell 高度需扩到 image 高让 chibi 不被 cell 裁。
                val cellHeights = FloatArray(row.cells.size) { idx ->
                    val charLineH = cellLines[idx].size * cellStrides[idx]
                    val maxImageH = cellLines[idx].maxOfOrNull { line ->
                        line.maxOfOrNull { g -> if (g.imageSrc != null) g.width else 0f } ?: 0f
                    } ?: 0f
                    maxOf(charLineH, maxImageH)
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
                        val lineW = cellLine.sumOf { it.width.toDouble() }.toFloat()
                        // cell 内文字水平居中（CSS td.vol-title-* text-align: center）
                        var localX = ((cellW - lineW) / 2f).coerceAtLeast(0f)
                        val localY = lineIdxInCell * cellStride
                        // **Step 7 (2026-05-24)** —— cell glyph 携带 per-cp color/imageSrc/size：
                        // - glyph.imageSrc != null → emit InlineImage atom (chibi 真显示)
                        // - glyph.color != null → 用 glyph.color (vs fallback cell.textColor)
                        // - glyph.sizeScale != 1f → 用 glyph.sizeScale (vs cell.sizeScale)
                        for (g in cellLine) {
                            val globalX = cellCursorX + localX
                            // **Step 7 bugfix v2**: 不 fallback cell.textColor (首字色)。
                            // ContentRun 已 per-cp 携带 color；首字 fallback 会污染 cell 内非首字
                            // (如 某日轻「某标题」"为""的" 是 null 应该默认黑而非粉首字色)。
                            val effColor = g.color
                            val effScale = if (g.sizeScale != 1f) g.sizeScale else cell.sizeScale
                            allColumns.add(
                                ScrollColumn(
                                    charData = g.text,
                                    start = globalX,
                                    end = globalX + g.width,
                                    chapterPosition = chapterPositionCounter,
                                    colorArgb = effColor,
                                    inlineImageSrc = g.imageSrc,
                                ),
                            )
                            if (g.imageSrc != null) {
                                // **Step 7 bugfix v3 (2026-05-24)**: image height = width
                                // (square chibi)，不是 cellStride (字符 line height)。drawer 算
                                // scaleF = min(atom.width/bmp.w, atom.height/bmp.h)，atom.height
                                // = cellStride ~60px 让 chibi 缩到 ~60px (image 63 太小)。
                                // CSS spec：img width:100% + height:auto → 保持 aspect ratio。
                                // chibi 通常 square 让 height = width 视觉等价 aspect-preserve
                                // (drawer 自己 minOf width 和 height 让 image 填到 box)。
                                cellAtoms.add(
                                    InlineImage(
                                        src = g.imageSrc,
                                        width = g.width,
                                        height = g.width,
                                        cellLocalX = localX,
                                        cellLocalY = localY,
                                    ),
                                )
                            } else {
                                cellAtoms.add(
                                    TextRun(
                                        text = g.text,
                                        colorArgb = effColor,
                                        sizeScale = effScale,
                                        width = g.width,
                                        height = cellStride,
                                        baseline = cellAscent,
                                        cellLocalX = localX,
                                        cellLocalY = localY,
                                    ),
                                )
                            }
                            sb.append(g.text)
                            chapterPositionCounter++
                            localX += g.width
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
                            // 聊天气泡 div.kuang-hei 等 cell 内 box 装饰：解码 payload → BlockStyle，
                            // renderer 画 cell 边框 / 圆角。多段气泡走 Container BOX（boxStylePayload）；
                            // 单段气泡边框 merge 进段落 BLOCK_STYLE → 兜底解码，仅含真边框时当 cell 盒子
                            // （避免给只有 align/color 的普通段落画空盒）。null → 无装饰零开销。
                            boxStyle = cell.boxStylePayload?.let {
                                com.morealm.epub.compat.StructuredChapterContent.decodeBlockStyle(it)
                            } ?: cell.cellBlockStylePayload?.let {
                                com.morealm.epub.compat.StructuredChapterContent.decodeBlockStyle(it)
                            }?.takeIf { it.borderColor != null && it.borderWidthPx > 0f },
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
            // **2026-05-28 BOX marker handling** —— 识别 Container box 嵌套 marker。
            // BOX marker paragraph 自身不 emit line（早于 paragraphCounter++ continue），
            // 不占 paragraphNum / chapterPosition。
            val trimmedStart = paragraphRaw.trimStart()
            if (trimmedStart.startsWith(
                    com.morealm.epub.compat.StructuredChapterContent.BOX_START_MARKER,
                )
            ) {
                val headerEnd = trimmedStart.indexOf(
                    com.morealm.epub.compat.StructuredChapterContent.BOX_HEADER_END,
                )
                if (headerEnd > 0) {
                    val payload = trimmedStart.substring(
                        com.morealm.epub.compat.StructuredChapterContent.BOX_START_MARKER.length,
                        headerEnd,
                    )
                    val style = com.morealm.epub.compat.StructuredChapterContent
                        .decodeBlockStyle(payload)
                    val id = nextBoxGroupId++
                    boxGroupStyles[id] = style
                    boxStack.addLast(id)

                    // **Step 9.X**：计算本层 box 对左 / 右 pad 的贡献，push contribStack
                    // 让 BOX_END 准确减回。margin NaN（AUTO）跳过；负 margin 也跳过（让段落
                    // 真的能从 box 内侧排，避免 ml=-1em 等场景让 line 跨到 box 外）。
                    val mlContrib = if (style.marginLeftPx.isNaN() || style.marginLeftPx < 0f) 0f
                                    else style.marginLeftPx
                    val mrContrib = if (style.marginRightPx.isNaN() || style.marginRightPx < 0f) 0f
                                    else style.marginRightPx
                    // **2026-05-30 widthPx 定宽 box 居中几何**（A 级保护：公式提取到 epub-layout
                    // [BoxGeometry]，主仓只调纯函数）—— declWidthPx>0 渲染成 widthPx×fontScale 宽、
                    // 屏内居中（margin:auto）窄框，子段缩到 bg 内侧；与 drawer bgRectX 共用同核心 →
                    // 两边严格一致（根治文字溢出灰框）。无 widthPx 走旧满宽路径。
                    val (leftAdd, rightAdd) = BoxGeometry.contentInset(
                        declWidthPx = style.widthPx,
                        fontScale = contentMeasurer.textSize / 16f,
                        paddingLeftRaw = style.paddingLeftPx,
                        paddingRightRaw = style.paddingRightPx,
                        borderWidthRaw = style.borderWidthPx,
                        marginLeftContrib = mlContrib,
                        marginRightContrib = mrContrib,
                        availWidth = visibleWidth - currentBoxLeftPad - currentBoxRightPad,
                    )
                    currentBoxLeftPad += leftAdd
                    currentBoxRightPad += rightAdd
                    boxPadContribStack.addLast(leftAdd to rightAdd)

                    // **2026-05-30 #3 box 垂直上间距预留**：drawer 把 bg 顶画在 firstContent.lineTop -
                    // paddingTop（bg 向上探 padTop），但布局之前没为 marginTop + paddingTop 留垂直空间
                    // → bg 顶探进上方元素（如名字圆标）重叠。在此推进 currentY（marginTop + paddingTop，
                    // × fontScale），让 bg 顶落到上方元素下方留隙。NaN(AUTO) 垂直向等同 0。
                    val boxVScale = contentMeasurer.textSize / 16f
                    val boxMarginTopV = (if (style.marginTopPx.isNaN()) 0f else style.marginTopPx) * boxVScale
                    currentY += boxMarginTopV + style.paddingTopPx * boxVScale

                    // Step 9.X margin collapse：进 box 时重置 lastSegMarginBottom（box 边界
                    // 视为新的 BFC，不与上一段 mb collapse）
                    lastSegMarginBottom = 0f

                    layoutLog.info(
                        "BoxGroup/Parse",
                        "BOX_START ch=$chapterIndex id=$id stackDepth=${boxStack.size} payload='${payload.take(120)}' " +
                            "bg=${style.backgroundColor} bc=${style.borderColor} bw=${style.borderWidthPx} " +
                            "br=${style.borderRadiusPx} pad=(${style.paddingTopPx},${style.paddingRightPx},${style.paddingBottomPx},${style.paddingLeftPx})",
                    )
                    layoutLog.info(
                        "BoxScope",
                        "BOX_START id=$id +leftAdd=$leftAdd +rightAdd=$rightAdd " +
                            "→ currentBoxLeftPad=$currentBoxLeftPad currentBoxRightPad=$currentBoxRightPad " +
                            "effectiveVisibleWidth=${effectiveVisibleWidth()} (visibleWidth=$visibleWidth)",
                    )
                } else {
                    layoutLog.warn(
                        "BoxGroup/Parse",
                        "BOX_START malformed (no BOX_HEADER_END) ch=$chapterIndex paraHead='${trimmedStart.take(80)}'",
                    )
                }
                // BOX_START 的 paragraph 仅作 marker，不 emit line
                continue
            }
            if (trimmedStart.startsWith(
                    com.morealm.epub.compat.StructuredChapterContent.BOX_END_MARKER,
                )
            ) {
                val poppedId = boxStack.lastOrNull()
                if (boxStack.isNotEmpty()) boxStack.removeLast()
                // **Step 9.X**：BOX_END pop contribStack，减回这一层的左/右 pad 贡献
                if (boxPadContribStack.isNotEmpty()) {
                    val (leftSub, rightSub) = boxPadContribStack.removeLast()
                    currentBoxLeftPad -= leftSub
                    currentBoxRightPad -= rightSub
                    // 防漏洞负值（理论不会，加 guard）
                    if (currentBoxLeftPad < 0f) currentBoxLeftPad = 0f
                    if (currentBoxRightPad < 0f) currentBoxRightPad = 0f
                    layoutLog.info(
                        "BoxScope",
                        "BOX_END poppedId=$poppedId -leftSub=$leftSub -rightSub=$rightSub " +
                            "→ currentBoxLeftPad=$currentBoxLeftPad currentBoxRightPad=$currentBoxRightPad " +
                            "effectiveVisibleWidth=${effectiveVisibleWidth()}",
                    )
                }
                // Step 9.X margin collapse：出 box 时也重置 lastSegMarginBottom
                lastSegMarginBottom = 0f
                layoutLog.info(
                    "BoxGroup/Parse",
                    "BOX_END ch=$chapterIndex poppedId=$poppedId stackDepthAfter=${boxStack.size}",
                )
                continue
            }

            // **2026-05-30 <hr/> 横线** —— flatten 的 <morealmhr/> 独占段落 → emit 一条横线 line
            // （贴当前 box 内容宽）。hr 是 ChapterBlock.Paragraph（epub-compat），与正文段一样占
            // paragraphNum + 2 cp（hr 占位 1 cp + 段末隐式 \n 1 cp），保持跨引擎 cp 兼容。
            if (trimmedStart.startsWith(
                    com.morealm.epub.compat.StructuredChapterContent.HR_MARKER,
                )
            ) {
                paragraphCounter++
                val hrCp = chapterPositionCounter++
                emitHorizontalRule(paragraphNum = paragraphCounter, startCp = hrCp)
                chapterPositionCounter++  // 段末隐式 \n 占 1 cp（对齐正文段 emit 末尾）
                continue
            }

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
                        layoutLog.info(
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
                    // **Step 5 / Plan B-2 (2026-05-24) inline-block container 优先**：
                    // Table 顶层 BlockStyle.widthPx + heightPx 非 null → div-wraps-Table 场景
                    // (e.g. div.qipao 含 box 装饰内嵌 table)。装饰已通过 mergeAncestorBlockStyle
                    // attach 到 Table.blockStyle。这种段走 inline-block container：box 装饰
                    // (圆球) + 内文按 widthPx 切行竖排。跳过 layoutTable row-by-row 排版。
                    //
                    // qipao 内层 table 实际是用 row 表达"行竖排"(每 row 1 cell 1 行)，跟
                    // inline-block container 按 widthPx 切行视觉等价。简化优先 — 不重写
                    // layoutTable 加 box 装饰。
                    val bsW = currentBlockStyle.widthPx
                    val bsH = currentBlockStyle.heightPx
                    if (bsW != null && bsH != null && bsW > 0f && bsH > 0f) {
                        // **Step 7 v5 (2026-05-24)**: 拼 List<List<ContentRun>> (每 row 一组)
                        // 保留 row 边界 → emit 时 row 间强制换行。qipao 内 HTML <table> 3 row 1
                        // cell → 3 inner lists 各为 1 row 的 runs。让 "女神大人" 在同一 line 不
                        // 拆 "大"/"人" 分行 (image 66 vs 参考图 67)。
                        val rowsRuns = ArrayList<List<ContentRun>>()
                        for (row in parsed.rows) {
                            // 每 row 跨 cell 拼接 (qipao 1 cell/row，sibling table 可多 cell)
                            val rowRunList = ArrayList<ContentRun>()
                            for (cell in row.cells) {
                                for (paraRuns in cell.paragraphs) {
                                    rowRunList.addAll(paraRuns)
                                }
                            }
                            if (rowRunList.isNotEmpty()) rowsRuns.add(rowRunList)
                        }
                        // **2026-05-30 #2 值表上提同行**：① em margin 是 raw px(16/em)，必须 ×
                        // fontScale 进缩放布局空间 —— 否则 -1.7em=-27.2px 相对 pill 缩放高(108px)太小、
                        // 上提不到同行；② box 内仅正 mt collapse，负 mt 直接上提（CSS 语义，旧
                        // coerceAtLeast(0f) 把负值钳 0）。mb 同步缩放保持单位一致。
                        val mScale = contentMeasurer.textSize / 16f
                        val mt = currentBlockStyle.marginTopPx * mScale
                        if (!mt.isNaN() && mt != 0f) {
                            val effectiveMt = if (boxStack.isNotEmpty() && mt > 0f) {
                                (mt - lastSegMarginBottom).coerceAtLeast(0f)
                            } else mt
                            currentY += effectiveMt
                        }
                        emitInlineBlockContainer(rowsRuns, bsW, bsH, currentBlockStyle.textColor)
                        val mb = currentBlockStyle.marginBottomPx * mScale
                        val mbDefault = if (boxStack.isNotEmpty()) paragraphSpacingPx * 0.3f else paragraphSpacingPx
                        val spacing = if (!mb.isNaN() && mb != 0f) mb else mbDefault
                        currentY += spacing
                        lastSegMarginBottom = spacing
                        chapterPositionCounter++  // 段末虚换行
                        continue
                    }

                    // **阶段 2-A**：layoutTable 接 CSS margin-top / margin-bottom（之前固定加
                    // paragraphSpacingPx 当尾距，吞了 某日轻 5 sibling table 的 margin-top:
                    // -1em / -1.5em / -10em 让视觉层叠失效）。
                    //
                    // CSS spec：margin-top/bottom 不参与 collapse（table 元素的 margin 跟普通
                    // block 不同，跨 table 不 collapse），所以纯累加（跟 D1.a 段间 margin 一致）。
                    // **2026-05-30 #2 值表上提同行**：em margin 是 raw px(16/em) → × fontScale 进缩放
                    // 布局空间（-1.7em=-27.2px 不缩放相对 pill 108px 太小、上提不到同行）；table 不
                    // collapse，box 内仅正 mt collapse-clamp，负 mt 直接上提（旧 coerceAtLeast(0f) 钳 0）。
                    val mScale = contentMeasurer.textSize / 16f
                    val mt = currentBlockStyle.marginTopPx * mScale
                    if (!mt.isNaN() && mt != 0f) {
                        val effectiveMt = if (boxStack.isNotEmpty() && mt > 0f) {
                            (mt - lastSegMarginBottom).coerceAtLeast(0f)
                        } else mt
                        currentY += effectiveMt
                    }
                    layoutTable(parsed)
                    val mb = currentBlockStyle.marginBottomPx * mScale
                    val mbDefault = if (boxStack.isNotEmpty()) paragraphSpacingPx * 0.3f else paragraphSpacingPx
                    val spacing = if (!mb.isNaN() && mb != 0f) mb else mbDefault
                    currentY += spacing
                    lastSegMarginBottom = spacing
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
            val headingLevel = parsed.headingLevel
            // 「文字上色」：规则色排版期对 cleanText 现算，按 CSS 优先 merge（EPUB 显式 CSS 色盖过
            // 规则色；TXT 无 CSS → 全用规则色）。scanner==null（关）走原 colorPerCp，零开销 fast path。
            //
            // EPUB h1/h2/h3 等标题段走正文循环，但视觉上属于章节标题；若继续扫规则色会把标题
            // 里的数字、标点、引号内容染成正文规则色，破坏标题统一样式。因此 heading 段只保留
            // 原有 CSS/inline 显式色，不叠加正文规则色。
            currentParaCharColors = if (ruleColorScanner != null && headingLevel <= 0) {
                RuleColorScanner.mergeCssPriority(colorPerCp, ruleColorScanner.scan(cleanedText))
            } else {
                colorPerCp
            }
            currentParaImageSrcs = parsed.imageSrcPerCp
            currentParaSizeScales = parsed.sizeScalePerCp
            currentParaBgRuns = parsed.bgRuns
            currentParaHeadingLevel = headingLevel
            currentParaCpStart = chapterPositionCounter
            val processedText = cleanedText

            // ── D1.a margin-top（段前间距 / 段重叠）──
            // **Step 9.X margin collapse（box 内）**：上段 mb 与本段 mt 取 max（不累加）。
            // 实施：effectiveMt = max(0, mt - lastSegMarginBottom)。CSS spec 真正的 collapse
            // 行为是 max(prev.mb, curr.mt)，prev.mb 已经在上一段 emit 后加进 currentY；下段
            // 只需补到 max 即可，即 max - prev.mb = max(0, mt - prev.mb)。仅在 box scope 内
            // 启用避免改变默认章节段间累加行为（用户长期视觉）。
            // CSS `margin-top: 2em` → 段前留白；`margin-top: -1em` → 段往上偏移（某日轻
            // 章首 table 重叠效果）。NaN = AUTO（垂直方向 CSS spec 等同 0，跳过）；0 = 未设置
            // 或显式 0，沿用 paragraphSpacingPx 默认（不改 currentY）。
            // 注：CSS margin collapse 简化 —— 不与上段 margin-bottom 取 max，纯累加（视觉
            // 上 prev.mb + curr.mt 都生效，CSS spec 严格 max 留 D1.b 完善）。
            val marginTopPx = currentBlockStyle.marginTopPx
            if (!marginTopPx.isNaN() && marginTopPx != 0f) {
                val beforeY = currentY
                val effectiveMt = if (boxStack.isNotEmpty()) {
                    // box 内段 margin collapse：max(0, mt - lastSegMarginBottom)
                    (marginTopPx - lastSegMarginBottom).coerceAtLeast(0f)
                } else {
                    marginTopPx
                }
                currentY += effectiveMt
                // **2026-05-29 #3 负 margin 窄 clamp**：上一行是 bottom-only border（如 introduction
                // `.jj { border-bottom }`）且本段负 margin 上提时，禁止其钻进横线上方（参考实现里副标题
                // 在横线下方）。**仅 bottom-only border 触发** —— 不误伤封面 poster 故意做的负 margin
                // 叠层（上一行无 bottom-only border → 不 clamp，保留叠层语义，避免「修 1 制造 2」）。
                if (effectiveMt < 0f) {
                    val prevLine = currentPageLines.lastOrNull()
                    if (prevLine != null && !prevLine.textBottomRel.isNaN()) {
                        val pbs = prevLine.blockStyle
                        val bottomOnlyBorder = pbs.borderBottomColor != null &&
                            pbs.borderTopColor == null &&
                            pbs.borderLeftColor == null &&
                            pbs.borderRightColor == null
                        if (bottomOnlyBorder) {
                            // clamp 底线必须与 drawer 横线**实际绘制位置同口径**（对齐 PRD #4 两套坐标系）：
                            // 横线画在 rectBottom = lineTop + textBottomRel + paddingBottomPx×scale
                            // （bottom-only 时 uniform halfBorder=0），描边宽 borderBottomWidthPx×scale。
                            // scale = contentMeasurer.textSize/16f（与 ChapterPaneCanvas / PagePaneCanvas 的
                            // fontSizeScale 同源）。底线取「横线中心 + 半描边」让副标题落横线下方留小缝。
                            // **第一版漏算 paddingBottomPx×scale → 横线仍切在副标题顶（古风）上**。
                            val borderScale = contentMeasurer.textSize / 16f
                            val borderFloorY = prevLine.lineTop + prevLine.textBottomRel +
                                (pbs.paddingBottomPx + pbs.borderBottomWidthPx) * borderScale
                            if (currentY < borderFloorY) {
                                layoutLog.info(
                                    "D1a/Margin",
                                    "para#$paragraphCounter neg-margin clamp ↓border: " +
                                        "currentY=$currentY → $borderFloorY " +
                                        "(textBottomRel=${prevLine.textBottomRel} " +
                                        "padB=${pbs.paddingBottomPx} bw=${pbs.borderBottomWidthPx} scale=$borderScale)",
                                )
                                currentY = borderFloorY
                            }
                        }
                    }
                }
                layoutLog.info(
                    "D1a/Margin",
                    "para#$paragraphCounter applied margin-top=$marginTopPx (effective=$effectiveMt " +
                        "boxScope=${boxStack.isNotEmpty()} lastMb=$lastSegMarginBottom) " +
                        "currentY=$beforeY → $currentY",
                )
            }
            // P3-5b Step 2c diag：仅当原 paragraphText 含 SOH 时才打 log（多色段稀有）
            if (paragraphText.contains('')) {
                layoutLog.info(
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
                // **Step 9.X**：box scope 内 paragraphSpacing × 0.3（段已由 CSS margin 主导间距）
                val effectiveSpacing = if (boxStack.isNotEmpty()) paragraphSpacingPx * 0.3f
                                       else paragraphSpacingPx
                currentY += effectiveSpacing
                // 空段无 CSS margin-bottom，仅 paragraphSpacing 作 mb 记到 lastSegMarginBottom
                lastSegMarginBottom = effectiveSpacing
                continue
            }

            // ── **方案 C inline-block container** (普通 RichText 路径) ──
            // BlockStyle.widthPx + heightPx 非 null → div.qipao 等 inline-block 元素。
            // 调 emitInlineBlockContainer helper（hasTableMarker 路径也调同 helper 保持一致）。
            //
            // **Step 7 v4 (2026-05-24)**: emitInlineBlockContainer 签名升级为 List<ContentRun>。
            // 普通段路径（Plan B-1 之前 qipao 走 RichText 情况；Step 5 后罕见）兜底把 processedText
            // 包装成单 ContentRun.Text。per-cp color/size 由 currentParaCharColors/sizeScales
            // 补充（如果需要可后续从 colorPerCp 重建 ContentRun.Text 序列）。
            val bsW = currentBlockStyle.widthPx
            val bsH = currentBlockStyle.heightPx
            if (bsW != null && bsH != null && bsW > 0f && bsH > 0f) {
                // Step 7 v5: 普通段路径 — 包装成单 row 单 ContentRun.Text
                val singleRowRuns = listOf(
                    listOf<ContentRun>(
                        ContentRun.Text(processedText, com.morealm.epub.layout.InlineStyle.DEFAULT),
                    ),
                )
                emitInlineBlockContainer(singleRowRuns, bsW, bsH, currentBlockStyle.textColor)
                val ibMb = currentBlockStyle.marginBottomPx
                currentY += if (!ibMb.isNaN() && ibMb != 0f) ibMb else paragraphSpacingPx
                chapterPositionCounter++  // 段末虚换行
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
                centerEffWidth: Float? = null,
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
                val bgRuns = currentParaBgRuns // Phase 3 local snapshot
                // 注：colors（per-cp 前景色：EPUB CSS 色 / 规则上色）**不**触发 atoms —— 前景色由
                // columns 路径的 col.colorArgb 绘制（PagePaneCanvas drawText 用 col.start，含 justify
                // gap，两端对齐保留）；atoms 路径用 atom.width 累加会丢 gap，不适合全文上色。size/
                // image/bg 仍走 atoms（各自需要 atom 字段表达）。
                val emitAtoms = sizeScales != null || imageSrcs != null || bgRuns != null
                val atomList = if (emitAtoms) ArrayList<Atom>(chars.size) else null
                // **Step 9.2 Phase B / Phase 3** —— bg-only coalesce：仅把同 boxId 连续同 styling
                // 字符并成 1 个带 bg 字段的 TextRun（drawByAtoms 各画各 rect）；非 bg 字符仍每字符
                // 1 atom（现有 color/size/image 路径零行为变化，blast radius 最小）。
                val bgRunText = StringBuilder()
                var bgRunWidth = 0f
                var bgRunColor: Int? = null
                var bgRunSize = 1f
                var bgRunArgb: Int? = null
                var bgRunPadL = 0f
                var bgRunPadT = 0f
                var bgRunPadR = 0f
                var bgRunPadB = 0f
                var bgRunBoxId: Int? = null
                fun flushBgRun() {
                    if (atomList != null && bgRunBoxId != null && bgRunText.isNotEmpty()) {
                        atomList.add(
                            TextRun(
                                text = bgRunText.toString(),
                                colorArgb = bgRunColor,
                                sizeScale = bgRunSize,
                                width = bgRunWidth + bgRunPadL + bgRunPadR,
                                height = contentLineHeight,
                                baseline = contentLineHeight * 0.8f,
                                inlineBgArgb = bgRunArgb,
                                inlineBgPaddingLeftPx = bgRunPadL,
                                inlineBgPaddingTopPx = bgRunPadT,
                                inlineBgPaddingRightPx = bgRunPadR,
                                inlineBgPaddingBottomPx = bgRunPadB,
                                inlineBgBoxId = bgRunBoxId,
                            ),
                        )
                    }
                    bgRunText.setLength(0)
                    bgRunWidth = 0f
                    bgRunBoxId = null
                }
                for (i in chars.indices) {
                    val relIdx = chapterPositionCounter - paraStartCp
                    val charColor = colors?.getOrNull(relIdx)?.takeIf { it != 0 }
                    val inlineSrc = imageSrcs?.getOrNull(relIdx)
                    val sizeScale = sizeScales?.getOrNull(relIdx) ?: 1f
                    val bgRun = bgRuns?.firstOrNull { relIdx >= it.cpStart && relIdx < it.cpEnd }
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
                    if (atomList != null) {
                        when {
                            inlineSrc != null -> {
                                flushBgRun()
                                atomList.add(InlineImage(src = inlineSrc, width = w, height = contentLineHeight))
                            }
                            bgRun != null -> {
                                // bg 字符：同 box + 同 color + 同 size 才并入当前 run，否则 flush 起新 run
                                val sameRun = bgRunBoxId == bgRun.boxId &&
                                    bgRunColor == charColor && bgRunSize == sizeScale
                                if (!sameRun) {
                                    flushBgRun()
                                    bgRunColor = charColor
                                    bgRunSize = sizeScale
                                    bgRunArgb = bgRun.argb
                                    bgRunPadL = bgRun.paddingLeftPx
                                    bgRunPadT = bgRun.paddingTopPx
                                    bgRunPadR = bgRun.paddingRightPx
                                    bgRunPadB = bgRun.paddingBottomPx
                                    bgRunBoxId = bgRun.boxId
                                }
                                bgRunText.append(chars[i])
                                bgRunWidth += w
                            }
                            else -> {
                                // 非 bg 字符：每字符 1 atom（零行为变化）
                                flushBgRun()
                                atomList.add(
                                    TextRun(
                                        text = chars[i],
                                        colorArgb = charColor,
                                        sizeScale = sizeScale,
                                        width = w,
                                        height = contentLineHeight,
                                        baseline = contentLineHeight * 0.8f,
                                    ),
                                )
                            }
                        }
                    }
                    sb.append(chars[i])
                    chapterPositionCounter++
                    x += w + (if (i < chars.lastIndex) gap else 0f)
                }
                flushBgRun()

                // ── exceed 行末压缩（移植 V1 ChapterProvider.exceed L1034-1056 等价）──
                // ZhLayout 处理 CJK 标点悬挂时会让 lineEnd 包含超出 visibleWidth 的标点。
                // exceed 检测末 column.end > visibleWidth 时，按位移让末 column 贴 visibleWidth：
                // 最后一列位移最大 (cc * size = excess)，最左列位移最小 (cc * 1)。
                // 视觉效果：字符间距微缩，相邻 column 略重叠，行末刚好齐 visibleWidth。
                val lastEnd = cols.last().end
                val excess = lastEnd - visibleWidth.toFloat()
                val excessFired = excess > 0f && cols.size >= 2
                if (excessFired) {
                    val cc = excess / cols.size
                    for (i in cols.indices) {
                        // i=0 (first col) 位移 cc * 1；i=last 位移 cc * size = excess
                        val py = cc * (i + 1)
                        val c = cols[i]
                        cols[i] = c.copy(start = c.start - py, end = c.end - py)
                    }
                }

                // **EpubW5H/LineEnd diag (2026-05-27)** — 每行末字位置 + visual bounds 对比，
                // 用于排查 "P 被吃" 等 clipRect 切字嫌疑。仅自带字体段或 col.end 接近 / 超
                // visibleWidth 时 fire（正文短行零噪声）。
                val finalLastEnd = cols.last().end
                if (currentBlockStyle.fontFamily != null ||
                    finalLastEnd > visibleWidth.toFloat() * 0.95f
                ) {
                    val lastCol = cols.last()
                    val boundsRight = contentMeasurer.visualRight(lastCol.charData, currentBlockStyle.fontFamily)
                    val advance: Float = lastCol.end - lastCol.start
                    val visualRightOffset: Float = boundsRight.toFloat() - advance  // > 0 = 描边伸出 advance
                    val visualRightAbs: Float = lastCol.end + maxOf(0f, visualRightOffset)
                    val clipPotential = visualRightAbs > visibleWidth.toFloat()
                    layoutLog.info(
                        "EpubW5H/LineEnd",
                        "family='${currentBlockStyle.fontFamily}' " +
                            "lastCh='${lastCol.charData.take(2)}' " +
                            "lastStart=${lastCol.start} lastEnd=${lastCol.end} adv=$advance " +
                            "boundsR=$boundsRight visualOverhang=$visualRightOffset " +
                            "visualRightAbs=$visualRightAbs visibleWidth=$visibleWidth " +
                            "excessFired=$excessFired clipPotential=$clipPotential " +
                            "lineText40='${sb.toString().take(40).replace("\n", "\\n")}'",
                    )
                }

                // **2026-05-29 居中修正**：CENTER 段 startX 用纯文字宽预估，漏算 inline bg 字符背景
                // padding（cols 实际含每字 padL+padR）→ 文字偏右半个 padding（简介页「内容简介」粉块
                // 标题实证 startX=295 应 259）。emit 后按 cols 实际宽重新居中；无字符背景时 actualW ==
                // 预估宽，delta≈0 不动，零行为变化。
                if (centerEffWidth != null && cols.isNotEmpty()) {
                    val actualW = cols.last().end - cols.first().start
                    val targetStart = currentBoxLeftPad + ((centerEffWidth - actualW) / 2f).coerceAtLeast(0f)
                    val delta = targetStart - cols.first().start
                    if (kotlin.math.abs(delta) > 0.5f) {
                        for (i in cols.indices) {
                            val c = cols[i]
                            cols[i] = c.copy(start = c.start + delta, end = c.end + delta)
                        }
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
                // **P3 v3 (2026-05-27)** — 自带字体段所有字符（含 CJK）走 swap typeface 测量，
                // 修复纯 CJK 段 ZhLayout 按窄宽算行末字超 visibleWidth 被切（末字
                // P 被吃实测）。详 [swapTypefaceMeasure] kdoc v3 注释。
                val (chars, rawWidths) = contentMeasurer.measureSplitWithFont(textChunk, currentBlockStyle.fontFamily)
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
                    // **Step 9.X**：box scope 内 ZhLayout 按 effective 宽切行（让 line wrap 提前到
                    // box 内侧边界）；非 box scope 仍用 visibleWidth。
                    val effWidth = effectiveVisibleWidth()
                    val wrapWidth = effWidth.toInt().coerceAtLeast(1)
                    val layout = ZhLayout(wrapWidth, chars, widths, indentSize, cnCharWidth)
                    for (lineIndex in 0 until layout.lineCount) {
                        // ZhLayout.lineStart/lineEnd 是 UTF-16 char index（基于 text.length），
                        // 而 chars/widths 是 code-point 切分（surrogate pair 合并 1 元素）。
                        // 不能直接 chars.subList(lineStart, lineEnd)——会越界（emoji 等代理对场景）。
                        // 改用 textChunk.substring + 重新 measureTextSplit 拿对齐的 lineChars/lineWidths。
                        val lineStart = layout.getLineStart(lineIndex)
                        val lineEnd = layout.getLineEnd(lineIndex)
                        if (lineEnd <= lineStart) continue
                        val lineText = textChunk.substring(lineStart, lineEnd)
                        // **P3 v3** — 同 emitTextChunk 入口的 swapTypefaceMeasure，保持
                        // ZhLayout 算行后的 emit 测量与 wrap 测量一致（避免拼凑跨字体的位置序列）
                        val (lineChars, lineWidths) = contentMeasurer.measureSplitWithFont(lineText, currentBlockStyle.fontFamily)
                        if (lineChars.isEmpty()) continue
                        val isFirstLine = isFirstChunkOfPara && lineIndex == 0
                        val isLastLine = lineIndex == layout.lineCount - 1
                        val desiredWidth = lineWidths.sum()
                        // D1.a margin: auto 检测 —— marginLeft AUTO && marginRight AUTO。
                        // **bugfix 2026-05-22**：CSS spec 真值 —— margin: auto 仅当块有显式
                        // width 时才居中；无 width 时 margin:auto 失效，text-align 才生效。
                        // 某仙侠 h2.head1 是 `text-align: left; margin: 0 auto 0 auto` —— 应该
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
                        // **阶段 2-I (2026-05-23)**：marginLeftAuto + mrNonAuto → 段右贴（CSS spec：
                        // `<table style="margin:-10em 0 0 auto">` 让 table 推到右边）。某日轻 作者名
                        // 表 `margin: -10em 0 0 auto` 让段从 visibleWidth - desiredWidth - mrPx 开始，
                        // 不再跟 qipao 重叠。修反对称：marginLeftAuto only → 右贴；mrAuto only → 左对齐
                        // 不动；双 auto → 居中保留旧逻辑。
                        // **Step 9.X**：CENTER / JUSTIFY / RIGHT / margin auto 计算用 effective 宽
                        // （effWidth）替代 visibleWidth；emitOneLine 再加 currentBoxLeftPad 让字真
                        // 排在 box 内侧。非 box scope effWidth == visibleWidth，零行为变化。
                        val startX: Float = when {
                            cssAlign == com.morealm.epub.compat.BlockStyle.TextAlign.CENTER ->
                                ((effWidth - desiredWidth) / 2f).coerceAtLeast(0f)
                            cssAlign == com.morealm.epub.compat.BlockStyle.TextAlign.RIGHT ->
                                (effWidth - desiredWidth).coerceAtLeast(0f)
                            cssAlign == com.morealm.epub.compat.BlockStyle.TextAlign.LEFT ||
                                cssAlign == com.morealm.epub.compat.BlockStyle.TextAlign.JUSTIFY -> {
                                // 显式 LEFT/JUSTIFY：mlIndent 段缩进 + 段首 indent（heading 段不 indent）
                                mlIndent + (if (isFirstLine && currentParaHeadingLevel == 0) effectiveFirstLineIndent else 0f)
                            }
                            // 阶段 2-I：margin-left:auto + margin-right 非 auto → 段右贴
                            // CSS spec：mr 非 auto 是显式右边 margin，从 effWidth 减去 desiredWidth + mr
                            marginLeftAuto && !marginRightAuto -> {
                                val mrEffective = if (currentBlockStyle.marginRightPx > 0f) currentBlockStyle.marginRightPx else 0f
                                (effWidth - desiredWidth - mrEffective).coerceAtLeast(0f)
                            }
                            // 阶段 2-I：margin-right:auto + margin-left 非 auto → 段左对齐 + mlIndent
                            // (CSS spec 等同 ml 显式 + mr=auto 把多余空间放右，视觉=左对齐)
                            !marginLeftAuto && marginRightAuto -> {
                                mlIndent + (if (isFirstLine && currentParaHeadingLevel == 0) effectiveFirstLineIndent else 0f)
                            }
                            // cssAlign null（CSS 没显式 text-align）→ marginCenter 兜底（某仙侠惊蛰
                            // h2.head 实际有 text-align:center 走上面分支；此分支留给纯 margin:auto
                            // 居中场景如 table.vol-title）
                            marginCenter -> ((effWidth - desiredWidth) / 2f).coerceAtLeast(0f)
                            // 全空：沿用旧默认（首行 indent 兜底），叠加 mlIndent
                            else -> mlIndent + (if (isFirstLine) effectiveFirstLineIndent else 0f)
                        }
                        // **D1.a DIAG**：仅当本段有 margin 属性时打 log（避免每行噪声）
                        if (marginCenter || mlIndent > 0f || boxStack.isNotEmpty()) {
                            layoutLog.info(
                                "D1a/Margin",
                                "line emit marginCenter=$marginCenter mlIndent=$mlIndent " +
                                    "desiredWidth=$desiredWidth visibleWidth=$visibleWidth effWidth=$effWidth " +
                                    "startX=$startX boxLeftPad=$currentBoxLeftPad finalStartX=${startX + currentBoxLeftPad} " +
                                    "lineText='${lineText.take(15)}'",
                            )
                        }
                        // **Step 9.X**：availableWidth 按 effective 宽（CENTER/JUSTIFY/RIGHT 等所
                        // 有路径都基于 effWidth）；emit 时再加 currentBoxLeftPad 让 startX 真挂到
                        // box 内侧。
                        val availableWidth = effWidth - startX
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
                        // Step 9.X：box scope 内字真排在 box 内侧 → startX += currentBoxLeftPad
                        emitOneLine(
                            lineChars, lineWidths, startX + currentBoxLeftPad, gap,
                            centerEffWidth = if (cssAlign == com.morealm.epub.compat.BlockStyle.TextAlign.CENTER) effWidth else null,
                        )
                    }
                } else {
                    // Greedy fallback：与 M1.2 原逻辑等价。
                    // **Step 9.X**：wrap 阈值 + emit offset 也按 effective 宽（与 ZhLayout 分支一致）
                    val effWidth = effectiveVisibleWidth()
                    val lineChars = ArrayList<String>()
                    val lineWidths = ArrayList<Float>()
                    var cursorX = if (isFirstChunkOfPara) indentWidth else 0f
                    var firstLineEmitted = false

                    fun flushGreedyLine() {
                        if (lineChars.isEmpty()) return
                        val startX = if (!firstLineEmitted && isFirstChunkOfPara) indentWidth else 0f
                        emitOneLine(lineChars, lineWidths, startX + currentBoxLeftPad, 0f)
                        lineChars.clear()
                        lineWidths.clear()
                        firstLineEmitted = true
                        cursorX = 0f
                    }
                    for (i in chars.indices) {
                        val w = widths[i]
                        if (cursorX + w > effWidth && lineChars.isNotEmpty()) {
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
                    val tagName = m.groupValues[1]
                    val srcVal = m.groupValues[2]
                    val isFullPage = tagName.equals("imgfp", ignoreCase = true)
                    // `w="0.60"` attr（epub-lib flatten 写入）= 声明宽度占视口比例；无则 null 走满宽。
                    val widthFraction = imgWidthRegex.find(m.value)?.groupValues?.getOrNull(1)?.toFloatOrNull()
                    chapterPositionCounter = emitImage(
                        src = srcVal,
                        paragraphNum = paragraphCounter,
                        startCp = chapterPositionCounter,
                        isFullPage = isFullPage,
                        widthFraction = widthFraction,
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
            //
            // **Step 9.X**：box scope 内的"默认 paragraphSpacingPx" × 0.3（段间距由 CSS margin 主导）。
            // 显式 CSS margin-bottom 仍按 CSS 值（用户在 EPUB 里写多少留多少）。
            val marginBottomPx = currentBlockStyle.marginBottomPx
            val inBox = boxStack.isNotEmpty()
            val spacing = when {
                !marginBottomPx.isNaN() && marginBottomPx != 0f -> marginBottomPx
                currentParaHeadingLevel > 0 -> paragraphSpacingPx * 3
                else -> if (inBox) paragraphSpacingPx * 0.3f else paragraphSpacingPx
            }
            // 段间空白：纯累加，不补跨页（方案 1 强硬纠正）。允许负值
            currentY += spacing
            // **Step 9.X**：记录本段 mb 让下段 margin-collapse 取 max
            lastSegMarginBottom = spacing
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
        layoutLog.info(
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
        layoutLog.info(
            "ScrollLayoutEngine",
            "layoutChapter idx=$chapterIndex viewWidth=$viewWidth padding=L${paddingLeft}/R${paddingRight} " +
                "visibleWidth=$visibleWidth maxColumnEnd=$maxColumnEnd " +
                "overflow=${maxColumnEnd > visibleWidth} pages=${pages.size} totalHeight=$totalHeight " +
                "contentLen=${cleanedContent.length} totalChars=$chapterPositionCounter " +
                "chapterBgImage=${chapterBgSrc != null}",
        )

        // **2026-05-28 Container box group** —— 把最终 boxGroupStyles map 注入到每个 ScrollPage，
        // 让 PagePaneCanvas 单页渲染时无需访问整 ScrollChapterLayout 即可拿 group 装饰字典。
        val finalGroupStyles: Map<Int, com.morealm.epub.compat.BlockStyle> = boxGroupStyles.toMap()
        val pagesWithGroupStyles = if (finalGroupStyles.isEmpty()) pages
            else pages.map { it.copy(boxGroupStyles = finalGroupStyles) }
        // **BoxGroup/Summary diag** —— layout 结束时打印 group 数量 + 每页含 group line 数
        if (finalGroupStyles.isNotEmpty()) {
            val pageLineSummary = pagesWithGroupStyles.joinToString(",") { p ->
                "p${p.pageIndex}=${p.lines.count { it.boxGroupId != null }}/${p.lines.size}"
            }
            layoutLog.info(
                "BoxGroup/Summary",
                "ch=$chapterIndex title='$title' groups=${finalGroupStyles.size} " +
                    "groupIds=${finalGroupStyles.keys} groupedLinesPerPage=[$pageLineSummary] " +
                    "leftoverBoxStackDepth=${boxStack.size}",
            )
        } else {
            layoutLog.info(
                "BoxGroup/Summary",
                "ch=$chapterIndex title='$title' NO groups (boxStack empty=${boxStack.isEmpty()} - " +
                    "若有 div.装饰容器应该非空，可能 epub-compat onOpen 未走 ContainerScope)",
            )
        }

        return ScrollChapterLayout(
            chapterIndex = chapterIndex,
            title = title,
            pages = pagesWithGroupStyles,
            totalHeight = totalHeight,
            viewWidth = viewWidth,
            paddingLeft = paddingLeft,
            paddingTop = paddingTop,
            paddingBottom = paddingBottom,
            styleSignature = computeStyleSignature(),
            totalCharCount = chapterPositionCounter,
            chapterBgImageSrc = chapterBgSrc,
            boxGroupStyles = finalGroupStyles,
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
            layoutLog.warn("ScrollLayoutEngine", "stripChapterBgMarker: marker open but no close, content head='${content.take(60)}'")
            return content to null
        }
        val src = content.substring(marker.length, endIdx)
        val cleaned = content.substring(endIdx + end.length).removePrefix("\n")
        layoutLog.info("ScrollLayoutEngine/BG", "stripped chapter bg src='${src.take(80)}' cleanedLen=${cleaned.length} originalLen=${content.length}")
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
        append("cm=").append(contentMeasurer.cacheToken).append(';')
        append("tm0=").append(titleMeasurer.cacheToken).append(';')
        append("cnm=").append(chapterNumMeasurerSafe.cacheToken).append(';')
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
         * 被误判)。覆盖 某日轻 "书名" / "封面" / EPUB 通用 "目录" / "前言" / "后记" 等。
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
         * 同款表达式 扩展加 `<imgfp>` (full-page image) tag union；
         * group 1 = tag name (img / imgfp)，group 2 = src。
         * 直接定义为 Regex 而非引用 Pattern 转换，避免每次调用开销。
         *
         * fullpage 信号：epub-lib StructuredContent.flattenToString 对
         * [com.morealm.epub.compat.ChapterBlock.Image.isFullPage] = true 的 image 块写
         * `<imgfp src="...">`，由 [SvgImageRewriteVisitor] 把 `<svg><image/></svg>` 转
         * IMG event 时注入 `data-morealm-fullpage="1"` 触发。本 regex 用 `\b` boundary
         * 让 `<img\b` 不误匹配 `<imgfp` (gf 间无 word boundary)。
         */
        private val imgRegex = Regex(
            "<(imgfp|img)\\b[^>]*src=['\"]([^'\"]*)['\"][^>]*>",
            RegexOption.IGNORE_CASE,
        )

        /**
         * 从 img marker 提取声明宽度比例 `w="0.60"`（epub-lib StructuredContent flatten 对带
         * [com.morealm.epub.compat.ChapterBlock.Image.widthFraction] 的 image 写入）。命中 →
         * emitImage 按比例渲染装饰小图（云朵 / logo），不命中走满宽默认。
         */
        private val imgWidthRegex = Regex("""\bw=['"]([0-9.]+)['"]""")

        // **R1 (阶段 R1.2)**：TABLE_MARKER_* 常量已迁到 com.morealm.epub.layout.TableMarkers
        // (internal object)。主仓 contains 检测改用 hasTableMarker(text) entry point，不再
        // 暴露 marker 字面值。jadx 字节码分析主仓只能看到函数调用，不见 "__MOREALM_TBL__" 字面。
    }
}

/**
 * **Step 7 (2026-05-24)** —— layoutCellLines 返回的 cell 内单 glyph 数据。
 *
 * 替代之前 `Pair<String, Float>` 模型 — 携带 per-glyph color/imageSrc/sizeScale。
 *
 * @property text 字符（CJK / Latin / 标点 / U+FFFC 占位 for image）
 * @property width 该 glyph 渲染宽度 (px)，含 sizeScale 缩放后值
 * @property color ARGB 字符前景色；null = 用 cell.textColor 或默认
 * @property imageSrc 非 null 时该 glyph 是 inline image (text 是 U+FFFC 占位)，width 是 image 占位宽
 * @property sizeScale 字符 size 缩放（1f = 默认）；image 始终 1f
 */
internal data class CellGlyph(
    val text: String,
    val width: Float,
    val color: Int? = null,
    val imageSrc: String? = null,
    val sizeScale: Float = 1f,
)
