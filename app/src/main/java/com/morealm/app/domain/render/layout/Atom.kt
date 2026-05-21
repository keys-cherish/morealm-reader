package com.morealm.app.domain.render.layout

/**
 * 一行内的最小可定位单元 —— [TextRun]（一段同 styling 的字符）或 [InlineImage]
 * （行内图片）。
 *
 * **现状对比（P3-5b Phase 3 起步）**：当前 [ScrollLine] 整行级承载图片
 * （[ScrollLine.isImage] + 空 columns），同一行内不能 text + image 混排。SampleLN
 * 封面那种 chibi 小图夹在标题字之间、章首大字旁配饰图等场景做不到。Atom 把"行内
 * token"抽出来：一行 = List<Atom>，TextRun 跟 InlineImage 可以**任意交错**。
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
    override val width: Float,
    override val height: Float,
    override val baseline: Float,
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
