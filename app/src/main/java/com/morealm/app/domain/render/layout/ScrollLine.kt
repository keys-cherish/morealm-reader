package com.morealm.app.domain.render.layout

import com.morealm.epub.compat.BlockStyle

/**
 * 单行排版结果 —— [ScrollPage] 的子结构。
 *
 * 一行 = N 个 [ScrollColumn]（横排时按 x 升序，整体 y 范围 [lineTop, lineBottom]）。
 *
 * 字段语义严格区分行内绘制坐标（lineTop/lineBottom）与逻辑归属（paragraphNum / isTitle）。
 * 高亮反查时按 chapterPosition 找到 column → 拿 line 的 lineTop/lineBottom 作为 rect 的 y 边界。
 */
data class ScrollLine(
    /** 行内字符列表，按 [ScrollColumn.start] 升序。空行（空段 / 图片段）为空 list。 */
    val columns: List<ScrollColumn>,
    /** 行顶 y 像素（相对所在 [ScrollPage]）。 */
    val lineTop: Float,
    /** 行底 y 像素。`height = lineBottom - lineTop`。含 lineSpacingExtra 影响。 */
    val lineBottom: Float,
    /**
     * 段编号（1-based，对齐 [com.morealm.app.domain.render.ScrollParagraph.paragraphNum]
     * 语义保持一致）。同一段内多行共享相同 paragraphNum；章首块独占段号 1。
     */
    val paragraphNum: Int,
    /** true=章节标题行（含章首块的章序号 / 主标题）；false=正文行。绘制 paint 选择依据。 */
    val isTitle: Boolean,
    /**
     * true=章序号小字行（"第N章" 用 [ScrollLayoutEngine.chapterNumPaint]）；
     * false=主标题 / 正文（用 titlePaint / contentPaint）。
     *
     * 仅当 [isTitle] = true 时此字段才有意义。渲染层 paint 选择规则：
     *   - isTitle && isChapterNum → chapterNumPaint（橙色小字）
     *   - isTitle && !isChapterNum → titlePaint（主色大字）
     *   - !isTitle → contentPaint（正文）
     */
    val isChapterNum: Boolean = false,
    /**
     * true=章首块最后一行 —— 渲染层据此画装饰横条（accent bar），视觉区分章首块与正文。
     * 仅在章首块末行（title 末行；无 title 时是 chapter-num 末行）为 true。
     */
    val isTitleEnd: Boolean = false,
    /**
     * true=图片占位行 —— 渲染层据此从 [imageSrc] 异步加载 bitmap，按 lineTop / lineBottom
     * 给定的像素范围绘制。column 列表为空。
     *
     * cp 占用规则（与旧 [com.morealm.app.domain.render.ChapterProvider.setTypeImage] 严格对齐）：
     * 每个图片占 1 cp（对应旧引擎 stringBuilder.append(" ") 占位字符），
     * firstChapterPos = lastChapterPos = 该图占的那 1 cp。
     */
    val isImage: Boolean = false,
    /**
     * 图片资源标识 —— 协议形式（如 `file:///...`、`mobi-img://...`、`http(s)://...`），
     * 由上层 contentProcessor 嵌入 `<img src="...">` 占位标记内，本引擎仅原样保存供渲染层
     * 异步加载使用。null = 非图片行。
     */
    val imageSrc: String? = null,
    /**
     * 行内整文本（含全角空格 / 标点，由 [ScrollLayoutEngine] 决定是否含末尾对齐填充）。
     * 跟 `columns.map { it.charData }.joinToString("")` 在大多数行下等价，但章首块 /
     * 图片段例外 —— 文本以 `text` 为权威，columns 为坐标权威。
     */
    val text: String,
    /**
     * 行覆盖的 chapterPosition 范围（含起含止）—— 反查工具
     * [ScrollLayoutEngine.findColumnAt] / [ScrollLayoutEngine.findColumnByPixel] 用。
     *
     * 设计动机：空段 / 图片段 line 的 [columns] 为空，但**该行仍占据 chapterPosition**
     * （空段占 1 cp、图片段占 1 cp）。仅靠 columns 无法反推 line 的 cp 归属——必须
     * 在 emit 阶段由 [ScrollLayoutEngine] 显式记录范围。
     *
     * 三种 line 类型的 firstChapterPos / lastChapterPos 约定：
     * - **非空文本行**：firstChapterPos = columns.first().chapterPosition,
     *   lastChapterPos = columns.last().chapterPosition
     * - **空段 line**：firstChapterPos = lastChapterPos = 该空段占的那 1 cp
     * - **图片段 line**：firstChapterPos = lastChapterPos = 该图片占的那 1 cp
     */
    val firstChapterPos: Int,
    /** 见 [firstChapterPos]。 */
    val lastChapterPos: Int,
    /**
     * **P3-5b Phase 3**：所在 paragraph 的 CSS box 装饰（圆角背景 / 边框）。
     *
     * 由 [ScrollLayoutEngine] 解析 EPUB content 里的 `__MOREALM_BLOCK_STYLE__`
     * inline marker 后从段落透传到所有 emit 出的 line。同一 paragraph 内每条 line
     * 共享同一份 BlockStyle。EMPTY = 无装饰（默认）。
     *
     * 渲染时由 ChapterPaneCanvas 在该行 columns 包围盒前画 `drawRoundRect`（背景
     * fill + 边框 stroke）。
     */
    val blockStyle: BlockStyle = BlockStyle.EMPTY,
    /**
     * **A5 atoms 骨架**（前进性双轨）：行级 [Atom] 列表，承载新排版路径的渲染数据
     * （含 sizeScale / color / inlineImageSrc 等富 styling）。
     *
     * **互斥分发**：
     *  - `atoms != null` → 新路径：Renderer 走 `drawByAtoms` 按 atom 序绘制
     *  - `atoms == null`（默认） → 旧路径：Renderer 走 column 字符级绘制（现行行为）
     *
     * **不**做「双填」：同一 ScrollLine 要么走 atoms 要么走 columns，无数据同步成本。
     * 后续 emit 入口逐步切到 atoms 路径，columns 字段使用面收缩，最终（A6）整体下线
     * [ScrollColumn] 时直接删除 columns + atoms 改成非空字段。
     *
     * **当前阶段（A5 骨架）**：所有 emit 仍走 columns 路径，本字段永远 null。Renderer
     * 已 ready 分发逻辑 + drawByAtoms 实现，等 A4c 起 emit 时填 atoms 触发新路径。
     */
    val atoms: List<Atom>? = null,
    /**
     * **H1+H2**：本行所在 paragraph 的 heading 级别（1..6，0 = 非 heading 正文）。
     *
     * 来源：epub-compat 端 `<h1>..<h6>` emit `ChapterBlock.Heading(level=...)` →
     * flattenToString 编码 BEL+digit+BS 前缀 marker → ScrollLayoutEngine.parseInlineMarkers
     * 检测段首 marker 剥掉 + 提取 level 数字 → emit line 时透传到本字段。
     *
     * H3 渲染时用：
     *   level > 0 → 选 titlePaint（大字号）+ 加 heading 段间距
     *   level == 0 → contentPaint（正文）
     *
     * **当前阶段（H1+H2 数据通路）**：字段就位但渲染端暂不区分（仍按普通段处理），等
     * H3 commit 接入 titlePaint 选择 + 段间距。
     */
    val headingLevel: Int = 0,
    /**
     * **D2.b 方案 F per-cell stride** —— layoutTable emit 表格行时每 cell 字符独立 stride
     * （avoid cell[1] 0.9em 小字号字符在 cell[0] 1.4em row 节奏内空隙过大）。
     *
     * 格式：每个 atom 对应一个 cellIdx（atom.cellIdx 通过 [Atom.cellIdx] 字段携带），
     * 本字段提供 cellIdx → vertical stride (px) 查找表。drawByAtoms 根据 atom.cellIdx 查
     * stride 算独立 baseline = lineTop + cellOffset + ascent。
     *
     * **null = non-table line**（普通 paragraph / heading / image）—— drawByAtoms 走原路径
     * 不读 cellLineHeights，全 atom 共用 line.lineTop 节奏。
     *
     * TODO(D-future)：vertical-align middle/bottom 或 cell padding/border/rowspan 需求出现
     * 时，把 cellLineHeights[] 升级为 [ScrollLineCell]（含 contentTop/height/padding/cellIdx）
     * 子对象数组。atom.cellIdx 字段保持兼容。
     */
    val cellLineHeights: FloatArray? = null,
) {
    val height: Float get() = lineBottom - lineTop

    /** 该行是否承载某 chapterPosition（含起含止）。反查时常用。 */
    fun containsChapterPos(cp: Int): Boolean = cp in firstChapterPos..lastChapterPos
}
