package com.morealm.app.domain.render

/*
 * 排版轴抽象 (Inline-axis / Block-axis)
 * =====================================
 *
 * # 为什么引入抽象
 *
 * 当前横排 ([layoutInternal]) 与竖排 ([layoutInternalVertical]) 是两条独立实现，
 * 字段 (TextLine.lineTop/Bottom, TextColumn.start/end) 在两种方向下被赋予**不同
 * 物理含义**——横排里 lineTop=Y, columns.start=X；竖排里 lineTop 仍是 Y 但表示
 * 「整列的 Y 范围」, columns.start=Y 表示「字符在列内的 Y 上边界」, 列的 X 范围
 * 单独存在 columnLeftX/columnRightX。drawer 必须根据 `textChapter.readingDirection`
 * 才能解读字段。
 *
 * 这种「字段语义跟方向走」的设计在两个方向各自独立时还能撑住，但要扩展第三种
 * 方向（VERTICAL_LR / 横排逆向 RTL...）就会爆炸。CSS/Web/HarfBuzz/CoreText 等
 * 现代排版引擎统一走的设计是 **逻辑轴 (logical axis)** 抽象：
 *
 *   - **Inline-axis**：文本延伸的方向（横排 = X 向右；竖排 = Y 向下）
 *   - **Block-axis**：换行/换列的方向（横排 = Y 向下；竖排 = X 向左）
 *
 * 排版算法（measure / line break / pagination）只用 inline/block 测算，不碰 X/Y。
 * 绘制层最后通过一个固定的 *direction → screen mapper* 把 (inlineOffset, blockOffset)
 * 翻译成屏幕 (x, y)。同一套算法跑两种方向，CPU 指令 / 分支预测 / 缓存命中都更友好。
 *
 * # 为什么不立刻全量重构
 *
 *   - 横排 [layoutInternal] 是 ~700 行精排代码（justify / 标点挤压 / ZhLayout
 *     / setTypeImage / accent bar），全部按 X/Y 写，一次性切到 inline/block 的
 *     破坏面太大，回归风险与测试成本远超 Phase 2 范围。
 *   - 竖排 Phase 2 用独立 [layoutInternalVertical] + 独立 [VerticalReaderView]
 *     已经把动画风险隔离，Phase 2 的成果不依赖 axis 抽象就能跑。
 *
 * 所以本文件**只提供基础类型与 mapper**，作为 Phase 3 / 长期演进的脚手架：
 *
 *   - 任何新写的、需要支持双向的 layout / drawer / hit-test 代码，可以直接
 *     基于本文件的 [InlineSize] / [BlockSize] / [LogicalRect] / [AxisMapper] 写
 *   - 老的横排 / 竖排实现暂时不动；等 Phase 3 真正需要扩展第三种方向时，再统一
 *     按本文件 mapper 接口收敛到一份算法
 *
 * 收益：未来 N 种方向只需要新增 N 个 [AxisMapper]，layout 算法零改动。
 *
 * # 命名约定（与 CSS Logical Properties / 主流引擎对齐）
 *
 *   - `inline*` = 沿 inline-axis 的量（size / offset / start / end）
 *   - `block*`  = 沿 block-axis 的量
 *   - `logical*` 前缀 = 表示「逻辑」坐标系（与具体方向无关）
 *   - `screen*` 前缀 = 表示「屏幕」坐标系（X/Y, 像素）
 *
 * 对应关系：
 *
 * | direction      | inline-axis 物理 | block-axis 物理 |
 * |----------------|------------------|------------------|
 * | HORIZONTAL     | X (从左到右)      | Y (从上到下)      |
 * | VERTICAL_RL    | Y (从上到下)      | X (从右到左)      |
 *
 * （未来扩展 VERTICAL_LR / RTL 横排时各自定义新 mapper）
 */

// ── 基础尺度类型 ──────────────────────────────────────────────────────
//
// 不用 value class（kotlin inline class）：跨模块 / Kotlin 反射场景下 inline class
// 在 JVM bytecode 里仍是底层 Float，可读性收益不抵 mocking / 测试时的代价。直接
// typealias 既零开销又允许 String 形式区分意图（IDE 补全时能看到 InlineSize 而非
// 裸 Float）。

/**
 * 沿 inline-axis 的尺寸 / 距离 / 偏移。横排里物理对应 X 像素，竖排 RL 里对应 Y 像素。
 */
typealias InlineSize = Float

/**
 * 沿 block-axis 的尺寸 / 距离 / 偏移。横排里物理对应 Y 像素，竖排 RL 里对应 X 像素。
 */
typealias BlockSize = Float

/**
 * inline-axis 上的位置（相对于 viewport 的 inline-start）。语义同 [InlineSize]，
 * 但分开命名提示「这是位置不是长度」。
 */
typealias InlineOffset = Float

/** block-axis 上的位置（相对于 viewport 的 block-start）。 */
typealias BlockOffset = Float

// ── 逻辑矩形 ──────────────────────────────────────────────────────────

/**
 * 在逻辑坐标系下表达的矩形——绝大多数排版几何（一行字的范围 / 一段的范围 /
 * 一页正文区） 都是这种「沿两条轴各划一段」的矩形。
 *
 * 转屏幕坐标走 [AxisMapper.toScreenRect]。
 *
 * @property inlineStart 沿 inline-axis 的起点（相对 viewport.inline-start）
 * @property inlineEnd   沿 inline-axis 的终点（必须 ≥ [inlineStart]）
 * @property blockStart  沿 block-axis 的起点
 * @property blockEnd    沿 block-axis 的终点（必须 ≥ [blockStart]）
 */
data class LogicalRect(
    val inlineStart: InlineOffset,
    val inlineEnd: InlineOffset,
    val blockStart: BlockOffset,
    val blockEnd: BlockOffset,
) {
    val inlineSize: InlineSize get() = inlineEnd - inlineStart
    val blockSize: BlockSize get() = blockEnd - blockStart

    init {
        // 排版数据通常不带「负面积」语义；调用方传错时早 fail 比静默生 0 面积矩形
        // 让后续 hit-test 静默失败强。仅 debug build 校验避免影响性能（throw 路径
        // 通常不被 inline）。
        require(inlineEnd >= inlineStart) {
            "LogicalRect inlineEnd ($inlineEnd) < inlineStart ($inlineStart)"
        }
        require(blockEnd >= blockStart) {
            "LogicalRect blockEnd ($blockEnd) < blockStart ($blockStart)"
        }
    }
}

/**
 * 在屏幕坐标系下表达的矩形——drawer / hit-test 直接用。等价于
 * [androidx.compose.ui.geometry.Rect]，但不引 Compose 依赖让 domain 层保持纯净。
 */
data class ScreenRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

// ── Axis Mapper ───────────────────────────────────────────────────────

/**
 * 把逻辑坐标 (inline, block) 映射到屏幕坐标 (x, y) 的方向感知映射器。
 *
 * 映射器是**无状态的**——只依赖固定的 viewport 几何（宽 / 高 / paddings），同一
 * 排版方向的所有 page / line / column 共用一个实例。每次方向变更或 viewport
 * 变更时构造新实例。
 *
 * 实现需保证以下不变量：
 *
 *   - **同方向不变性**：连续两次同输入应返回同输出（无 randomness / 无内部 mutation）
 *   - **顺序保持**：inline-axis 与 block-axis 的"start → end"在屏幕坐标上落到一致
 *     的物理方向（横排里 inlineStart < inlineEnd ⇒ leftX < rightX；竖排 RL 里
 *     blockStart < blockEnd ⇒ rightX > leftX 因为 block-axis 物理上是 X 反向）
 *
 * @see HorizontalAxisMapper
 * @see VerticalRlAxisMapper
 */
interface AxisMapper {
    /** 当前映射器服务的排版方向，便于 drawer 做最后一步分支 / 字体 vert 特性开关。 */
    val direction: ReadingDirection

    /** Viewport 宽度（屏幕坐标，px）。 */
    val viewportWidth: Float

    /** Viewport 高度（屏幕坐标，px）。 */
    val viewportHeight: Float

    /**
     * 把逻辑点 (inline, block) 映射到屏幕 (x, y)。inline/block = 0 对应 viewport
     * 的 inline-start / block-start。
     */
    fun toScreenPoint(inline: InlineOffset, block: BlockOffset): Pair<Float, Float>

    /**
     * 把逻辑矩形映射到屏幕矩形。注意映射可能会让 [ScreenRect.left] / [ScreenRect.right]
     * 翻转（如竖排 RL 里 block-axis 物理上是 X 反向，所以 logical blockStart 对应
     * screen.right，blockEnd 对应 screen.left），实现需保证返回 [ScreenRect] 仍然
     * `left ≤ right` / `top ≤ bottom`，避免下游 drawer 做反复校正。
     */
    fun toScreenRect(rect: LogicalRect): ScreenRect

    companion object {
        /**
         * 工厂——按方向选具体实现。Viewport 几何按 [direction] 解读：横排里
         * (viewportInlineSize=width, viewportBlockSize=height)，竖排里
         * (viewportInlineSize=height, viewportBlockSize=width)。
         */
        fun forDirection(
            direction: ReadingDirection,
            viewportWidth: Float,
            viewportHeight: Float,
            paddingLeft: Float = 0f,
            paddingRight: Float = 0f,
            paddingTop: Float = 0f,
            paddingBottom: Float = 0f,
        ): AxisMapper = when (direction) {
            ReadingDirection.HORIZONTAL -> HorizontalAxisMapper(
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
                paddingLeft = paddingLeft,
                paddingTop = paddingTop,
            )
            ReadingDirection.VERTICAL_RL -> VerticalRlAxisMapper(
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
                paddingRight = paddingRight,
                paddingTop = paddingTop,
            )
            ReadingDirection.VERTICAL_LR -> VerticalLrAxisMapper(
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
                paddingLeft = paddingLeft,
                paddingTop = paddingTop,
            )
        }
    }
}

/**
 * 横排 (HORIZONTAL) 映射：
 *   - inline-axis = X（屏幕 X = paddingLeft + inline）
 *   - block-axis  = Y（屏幕 Y = paddingTop  + block）
 *
 * 这是最简单的 1:1 映射，几乎零开销——大多数横排几何代码已经按这个对应关系
 * 在写，引入 mapper 不会改变性能。
 */
class HorizontalAxisMapper(
    override val viewportWidth: Float,
    override val viewportHeight: Float,
    private val paddingLeft: Float,
    private val paddingTop: Float,
) : AxisMapper {
    override val direction = ReadingDirection.HORIZONTAL

    override fun toScreenPoint(inline: InlineOffset, block: BlockOffset): Pair<Float, Float> =
        (paddingLeft + inline) to (paddingTop + block)

    override fun toScreenRect(rect: LogicalRect): ScreenRect = ScreenRect(
        left = paddingLeft + rect.inlineStart,
        right = paddingLeft + rect.inlineEnd,
        top = paddingTop + rect.blockStart,
        bottom = paddingTop + rect.blockEnd,
    )
}

/**
 * 竖排右起 (VERTICAL_RL) 映射：
 *   - inline-axis = Y（屏幕 Y = paddingTop + inline）  — 字符在列内从上到下
 *   - block-axis  = X 反向（屏幕 X = viewportWidth - paddingRight - block）  — 列从右向左
 *
 * 因此 `blockStart=0` 对应屏幕最右侧（含 paddingRight 留白），`blockEnd > 0`
 * 时屏幕 X 反而更小。[toScreenRect] 内部把 left/right 取 min/max 确保返回值
 * 仍然 `left ≤ right`，下游不需要做反复校正。
 */
class VerticalRlAxisMapper(
    override val viewportWidth: Float,
    override val viewportHeight: Float,
    private val paddingRight: Float,
    private val paddingTop: Float,
) : AxisMapper {
    override val direction = ReadingDirection.VERTICAL_RL

    override fun toScreenPoint(inline: InlineOffset, block: BlockOffset): Pair<Float, Float> {
        val y = paddingTop + inline
        val x = viewportWidth - paddingRight - block
        return x to y
    }

    override fun toScreenRect(rect: LogicalRect): ScreenRect {
        val xStart = viewportWidth - paddingRight - rect.blockStart
        val xEnd = viewportWidth - paddingRight - rect.blockEnd
        return ScreenRect(
            left = minOf(xStart, xEnd),
            right = maxOf(xStart, xEnd),
            top = paddingTop + rect.inlineStart,
            bottom = paddingTop + rect.inlineEnd,
        )
    }
}

/**
 * 竖排左起 (VERTICAL_LR) 映射：
 *   - inline-axis = Y（屏幕 Y = paddingTop + inline）  — 字符在列内从上到下
 *   - block-axis  = X 正向（屏幕 X = paddingLeft + block）  — 列从左到右
 *
 * 与 VERTICAL_RL 的唯一差别：block-axis 在屏幕 X 上**不反向**——第 0 列贴左边界。
 * 章节标题（chapterPosition 最小、最先排版）出现在屏幕**左侧**，正文向右铺开。
 */
class VerticalLrAxisMapper(
    override val viewportWidth: Float,
    override val viewportHeight: Float,
    private val paddingLeft: Float,
    private val paddingTop: Float,
) : AxisMapper {
    override val direction = ReadingDirection.VERTICAL_LR

    override fun toScreenPoint(inline: InlineOffset, block: BlockOffset): Pair<Float, Float> =
        (paddingLeft + block) to (paddingTop + inline)

    override fun toScreenRect(rect: LogicalRect): ScreenRect = ScreenRect(
        left = paddingLeft + rect.blockStart,
        right = paddingLeft + rect.blockEnd,
        top = paddingTop + rect.inlineStart,
        bottom = paddingTop + rect.inlineEnd,
    )
}
