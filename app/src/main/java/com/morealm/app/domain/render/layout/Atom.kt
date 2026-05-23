package com.morealm.app.domain.render.layout

/**
 * 一行内的最小可定位单元 —— [TextRun]（一段同 styling 的字符）或 [InlineImage]
 * （行内图片）。
 *
 * ## CP 契约（A3-A6 推进时不可破坏）
 *
 * 这些 invariant 跟 DB 中存储的 Highlight / Bookmark / ReadProgress / TTS / Search
 * 锚点 1:1 对应，破坏任何一条都会让旧数据反查失败、用户保存的高亮和书签错位。
 * [com.morealm.app.domain.render.layout.AtomCpContractTest] 强制断言每条。
 *
 *  1. **cp 计数等价于旧 chapterPositionCounter**：一段 atoms 的 cpCount 之和必须
 *     等于旧 [ScrollLayoutEngine.layoutChapter] 对该段累计的 chapterPosition 增量。
 *     即 `atoms.sumOf { it.cpCount } == legacyChapterPosDelta`。
 *
 *  2. **[TextRun.cpCount] = `text.length`（UTF-16 char count，含 surrogate pair）**：
 *     跟 ZhLayout 的 `lineStart / lineEnd` char index 同口径；emoji surrogate pair
 *     算 2 cp（跟 `String.length` 严格对齐，跟 [ScrollColumn] 字符级 emit 一致）。
 *
 *  3. **[InlineImage.cpCount] = 1**：与现 [ScrollLayoutEngine.emitImage] `return
 *     startCp + 1` 严格对齐。A4 实现 inline image 时**必须**用 U+FFFC（OBJECT
 *     REPLACEMENT CHARACTER）做占位字符 —— BMP 内 1 char unit，跟「图片 1 cp」对齐，
 *     且 ZhLayout / libunibreak 不会跨它打断行（A4 引入时由 contract test 第 4 条
 *     验证）。
 *
 *  4. **DB 列名 `startChapterPos / endChapterPos / chapterPosition` 永久 stable**：
 *     不论 A6 清理多深，列名 / Entity 字段名不动（DB schema 不能跟代码命名一起改，
 *     破坏 = 全用户高亮书签丢）。
 *
 * ## 现状对比（P3-5b Phase 3 起步）
 *
 * 当前 [ScrollLine] 整行级承载图片（[ScrollLine.isImage] + 空 columns），同一行内
 * 不能 text + image 混排。SampleLN 封面那种 chibi 小图夹在标题字之间、章首大字旁
 * 配饰图等场景做不到。Atom 把"行内 token"抽出来：一行 = List<Atom>，TextRun 跟
 * InlineImage 可以**任意交错**。
 *
 * **与 [ScrollColumn] 的层级**：
 *  - ScrollColumn = **字符级**（1 char ↔ 1 column）—— 现行排版数据模型
 *  - Atom         = **token 级**（1 同 styling text run = 1 atom；1 image = 1 atom）
 *
 * 两者并存到 A6 完成才下线 ScrollColumn。Phase 3 A1（本文件）只定义类型；A2 加
 * Bridge；A3 起 ScrollLayoutEngine 真用 Atom emit 排版结果；A4 实现 inline image；
 * A5 Renderer 切到 Atom；A6 清理 ScrollColumn 路径。
 *
 * **[cpCount] 是显式字段而非派生属性**：
 *  - 让 host 端不需要 instanceof 也能算 cp 偏移
 *  - 跟 Unicode 标准对齐（U+FFFC ReplacementSpan / iOS NSAttachment / Web 选区都把
 *    图当 1 cp，TTS 跳图 +1，搜索"跨图"匹配自然失败）
 *  - 旧 highlight / bookmark 数据按 1 cp/图 索引，迁移到 Atom 模型时零偏移
 *  - cp ↔ atomIdx 双向映射可一次扫 `atoms.scan(0) { acc, a -> acc + a.cpCount }`
 *
 * **[baseline] 语义**：从 atom 顶部到文字基线的像素距离。Canvas `drawText` 接的是
 * baseline y 坐标，所以 atom 顶部 y + baseline = 调用 drawText 的 y。InlineImage 没
 * 文字基线，约定 baseline = height（图片底部对齐文字基线，最常见的视觉效果）。
 */
sealed interface Atom {

    /** 占用章内字符位置数。TextRun = text.length；InlineImage = 1（U+FFFC 惯例）。 */
    val cpCount: Int

    /** 像素宽度（layout 后确定）。 */
    val width: Float

    /** 像素高度。 */
    val height: Float

    /** Atom 顶部到文字基线的像素距离。Canvas drawText 需要的 baseline y 偏移。 */
    val baseline: Float
}

/**
 * 一段连续同 styling 的字符。多字符压缩到一个 Atom 减少排版迭代次数和绘制时
 * paint 切换开销。
 *
 * **为什么不存 advances 数组（每字符宽度）**：现行 ScrollColumn 已经精算每字符
 * `start/end`；Atom 抽到 token 级后，hit-test 走 `paint.measureText` 二分定位
 * （只在用户长按选字时才算，热路径绘制不需要逐字 advance）。等 A3 起实测确定需要
 * 才补字段。
 *
 * @property text 该 run 的字符内容（不可为空，空文本不应生成 atom）
 * @property colorArgb 前景色 ARGB；null = 用 line/paint 默认色
 */
data class TextRun(
    val text: String,
    val colorArgb: Int? = null,
    /**
     * **A4c**：字号缩放倍率（相对 paint 默认 textSize）。1f = 跟段落默认字号一致，
     * 2.5f = `em25` (font-size:250%)，3f = `em30`，等等。
     *
     * 来源：epub-compat 端 cascade CSS `font-size` 解析后存于 TextSpan.sizeScale，
     * flattenToString 编码为 inline marker → ScrollLayoutEngine.parseInlineMarkers
     * 解析 → emit 到 TextRun.sizeScale。
     *
     * Renderer 用法：`paint.textSize = basePaint.textSize * sizeScale`，1f 时零开销
     * 跳过 paint 修改。
     */
    val sizeScale: Float = 1f,
    override val width: Float,
    override val height: Float,
    override val baseline: Float,
    /**
     * **D2.b 方案 F per-cell stride** —— layoutTable emit table 字符时携带 cell 索引，
     * drawByAtoms 根据本字段查 [ScrollLine.cellLineHeights] 算独立 vertical baseline，
     * 让两 cell 字号差大时各自字符 row stride 紧凑（cell[1] 0.9em 小字号字符之间不跟
     * cell[0] 1.4em 大字号 row 节奏联动）。
     *
     * 默认 -1 = non-table atom（普通 paragraph / heading），drawByAtoms 走原路径不查
     * cell stride table。
     *
     * TODO(D-future)：升级到 [ScrollLineCell] 子对象时本字段仍有效（cellIdx 索引到 cells[]）。
     */
    val cellIdx: Int = -1,
) : Atom {
    override val cpCount: Int get() = text.length
}

/**
 * 行内图片 —— `src` 是已 resolve 的 file/uri，width/height 是 layout 时确定的呈现
 * 尺寸（原图可能更大；排版层负责按行高 + 视区宽 fit）。
 *
 * **cpCount = 1**：跟 Android ReplacementSpan / iOS NSAttachment 一致。
 * **baseline = height**：图片底部对齐文字基线，与文字行混排时视觉自然。
 *
 * @property src 图片资源协议 URL（`file:///` / `mobi-img://` / `http(s)://` 等），
 *   由 contentProcessor 嵌入到 atom emit 前 resolve 完
 */
data class InlineImage(
    val src: String,
    override val width: Float,
    override val height: Float,
) : Atom {
    override val cpCount: Int get() = 1
    override val baseline: Float get() = height
}
