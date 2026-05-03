package com.morealm.app.domain.render

/**
 * 滚动模式（LazyColumn 瀑布流）专用的段落数据类。
 *
 * 与 [TextParagraph]（Legado 移植，给 TTS / 搜索使用）的区别：
 * - [ScrollParagraph] 专为渲染层 LazyColumn 设计，每个实例对应一个 LazyColumn item
 * - 携带 [contentType]、[totalHeight]、[linePositions] 等渲染必需的预计算字段
 * - 对 [TextLine] 共享引用，不复制，避免段量大时内存翻倍
 *
 * ── 性能契约（针对快速滚动场景）──
 *
 * 1. **contentType**：提供给 LazyColumn `contentType` 参数。同 [ScrollParagraphType]
 *    的 item 在滑动复用时走 view 结构层复用，跳过整段重组评估。结构差异大的段
 *    （CHAPTER_TITLE vs NORMAL vs IMAGE）必须分类型，否则会触发 fallback 全量重组，
 *    用户疯狂上下滑动时 UI 线程瞬时尖峰，肉眼可见掉帧/白屏。
 *
 * 2. **稳定的 key**：[key] 字符串全局唯一（跨章窗口拼接后也唯一），方便
 *    [androidx.compose.foundation.lazy.LazyListState.scrollToItem] 在 prepend 章节
 *    时锚定原位置不抖动。
 *
 * 3. **immutable**：所有字段不可变（class 改为 immutable list、FloatArray 不可变契约）。
 *    主线程零拷贝读取；构建可在 [kotlinx.coroutines.Dispatchers.Default] 完成后整体
 *    交付主线程，不阻塞滑动动画。
 *
 * 4. **不内置 CanvasRecorder**：录制缓存放在 Renderer 层 LRU（限制条数），
 *    远端段释放 recorder。如果把 recorder 放进数据类，跨章窗口里成百上千段
 *    全部持有 Picture，内存压力 + GC 抖动会反噬滑动性能。
 *
 * 5. **预计算 totalHeight / linePositions**：LazyColumn item 测量阶段直接拿固定高度，
 *    不依赖 [TextLine.lineTop/lineBottom] 的运行时累加。Compose 1.3+ 的 prefetch
 *    机制能利用主线程空闲帧提前测量，前提是测量足够便宜——我们在数据层一次性
 *    算好，测量阶段 O(1)。
 */
class ScrollParagraph(
    /**
     * LazyColumn item key —— `"$chapterIndex-$paragraphNum"` 形式。
     *
     * 用 String 而非 [Long] 哈希组合，因为：
     * - LOADING 占位段无 paragraphNum，要走 `"loading-$chapterIndex"` 这种特殊 key
     * - 跨章窗口拼接后唯一性靠 chapterIndex 前缀保证，调试时直接看 key 就知道归属
     */
    val key: String,
    /** 见 [ScrollParagraphType] —— LazyColumn `contentType` 参数。 */
    val contentType: ScrollParagraphType,
    /** 段所属章 idx —— 跨章窗口里识别归属，进度/标题栏切换时使用。 */
    val chapterIndex: Int,
    /**
     * 章内段编号（1-based，对齐 [TextLine.paragraphNum]）。
     *
     * LOADING 占位段此值为 0（章内无对应排版段）。
     */
    val paragraphNum: Int,
    /**
     * 段内所有行（共享 [TextPage.lines] 里的 [TextLine] 实例，不复制）。
     * Immutable [List] —— 调用方禁止修改返回引用，否则会破坏 Compose stability 推断。
     *
     * 跨页段：本字段会把同一 paragraphNum 在多个 [TextPage] 中的 line 合并到一起。
     * line 自身 [TextLine.lineTop]/[TextLine.lineBottom] 保持 page-local 坐标
     * 不修改（其它模块——选中/搜索/TTS——可能仍依赖原坐标），段内绝对 top
     * 用 [linePositions] 拿。
     */
    val lines: List<TextLine>,
    /**
     * 每行在**段内**的 top Y（已合并跨页 page-break 空隙）。长度 == [lines].size。
     *
     * 绘制公式（Phase 2 的 LazyScrollRenderer 实现要严格遵守）：
     * ```
     * for ((i, line) in lines.withIndex()) {
     *     val topInParagraph = linePositions[i]
     *     val lineHeight = line.lineBottom - line.lineTop
     *     canvas.save()
     *     canvas.translate(0f, topInParagraph - line.lineTop)
     *     drawTextLine(canvas, line, ...)  // line 内部仍按自己的 lineTop/lineBase 绘制
     *     canvas.restore()
     * }
     * ```
     *
     * 用 [FloatArray] 而非 [List]<Float>：避免拆/装箱，紧凑连续内存便于 cache。
     */
    val linePositions: FloatArray,
    /**
     * 段顶 padding —— 章首段保留 [TextPage.paddingTop]（章号上方留白），其它段为 0f。
     *
     * 已经计入 [linePositions]（首行 top == [paddingTop]），单独保留是为了让绘制层
     * 在 [paddingTop] 区域绘制章号装饰条（accent bar）等附加元素。
     */
    val paddingTop: Float,
    /**
     * 段总高度（含 [paddingTop]）。
     *
     * LazyColumn item 用此值作为固定高度（`Modifier.height(totalHeight.toDp())`），
     * 测量阶段 O(1)，不会成为快速滚动瓶颈。
     */
    val totalHeight: Float,
    /** 段首字符在章内位置（[TextLine.chapterPosition]）。进度/书签换算用。 */
    val firstChapterPosition: Int,
    /** 段总字符数（含段末换行符）。 */
    val charSize: Int,
) {
    /** 该段是否已是某章的最后一段。计算需要 [chapterTotalParagraphs] 上下文，故未存字段。 */
    fun isChapterLast(chapterTotalParagraphs: Int): Boolean = paragraphNum == chapterTotalParagraphs

    /** 该段是否是某章的第一段。 */
    val isChapterFirst: Boolean get() = paragraphNum == 1

    /** 是否为 LOADING 占位段。 */
    val isLoadingPlaceholder: Boolean get() = contentType == ScrollParagraphType.LOADING
}

/**
 * LazyColumn `contentType` 用的段落类型枚举。同 type 间 view 结构复用率最高。
 *
 * 扩展规则：未来加新类型（如脚注卡片、图文混排）时，必须同时在 LazyScrollRenderer 的
 * item composable 里增加分支并保持每个分支结构稳定，不能笼统归到 [NORMAL]——否则会
 * 破坏 contentType 复用，快速滚动时撞 fallback 重组路径。
 */
enum class ScrollParagraphType {
    /**
     * 章首标题段（含 [TextLine.isChapterNum] 或 [TextLine.isTitle] 行） ——
     * 通常带装饰 accent bar、章号大字体、标题居中等结构与正文段差异较大。
     */
    CHAPTER_TITLE,
    /** 普通文字段。绝大多数段属于此类型，复用收益最大。 */
    NORMAL,
    /**
     * 含 [ImageColumn] 的段 —— item 高度通常远大于文字段，drawable 解码也走单独路径，
     * 与文字段隔离避免污染缓存。
     */
    IMAGE,
    /**
     * 章节加载中的占位段 —— 配合 ViewModel 异步 append 流程使用。
     *
     * **快速滚动护栏**：当用户疯狂下滑、下一章数据还在 IO 线程切段时，
     * 这条占位段填补视觉空缺，避免「空气墙」/ 白屏。给一个固定高度（如 1 屏高度的 30%）
     * 让 LazyColumn 测量稳定，等数据到位后再用同 key 替换为真实段——LazyColumn
     * 会做 item 高度过渡而非整体重排。
     */
    LOADING,
}

/**
 * 把一章的排版结果（[TextChapter.snapshotPages]）转换为 LazyColumn 可直接消费的
 * [ScrollParagraph] 列表。
 *
 * **线程安全**：本函数纯计算（[TextChapter.snapshotPages] 内部已加锁取快照），
 * 全程不修改 [TextLine] / [TextPage] 状态，可在 [kotlinx.coroutines.Dispatchers.Default]
 * 调用，结果用 immutable [List] 交付主线程，零拷贝。
 *
 * **跨页段处理**：同一 [TextLine.paragraphNum] 在多个 [TextPage] 中的 line 会合并到
 * 一个 [ScrollParagraph]。合并时丢弃 page break 间隙（page N 末行底 → page N+1 首行顶
 * 的 paddingBottom + paddingTop），保证视觉连续——这正是「瀑布流无缝衔接」的核心。
 *
 * **章首 paddingTop**：仅保留 [pages][0].paddingTop（章号上方留白），其余段为 0f。
 * 章末 paddingBottom 不在段内体现（LazyColumn item 紧挨即可）。
 *
 * @return immutable [List]<[ScrollParagraph]>。空章返回 emptyList()。
 */
fun TextChapter.toScrollParagraphs(): List<ScrollParagraph> {
    val pageSnapshot = snapshotPages()
    if (pageSnapshot.isEmpty()) return emptyList()

    // 第 1 步：按 paragraphNum 分组收集 (pageIndex, line) 对。
    // 用 LinkedHashMap 保留章内段顺序（paragraphNum 单调递增，但用 HashMap 不保证
    // entries 迭代顺序与插入顺序一致）。
    val grouped = LinkedHashMap<Int, MutableList<Pair<Int, TextLine>>>()
    for ((pageIdx, page) in pageSnapshot.withIndex()) {
        for (line in page.lines) {
            // paragraphNum <= 0 是占位 / 错误状态，跳过避免污染段编号。
            if (line.paragraphNum <= 0) continue
            grouped.getOrPut(line.paragraphNum) { mutableListOf() }.add(pageIdx to line)
        }
    }
    if (grouped.isEmpty()) return emptyList()

    val firstPagePaddingTop = pageSnapshot.first().paddingTop.toFloat()
    val result = ArrayList<ScrollParagraph>(grouped.size)
    var seenChapterFirst = false

    for ((paragraphNum, entries) in grouped) {
        val paraLines = entries.map { it.second }
        val firstLine = paraLines.first()
        val isChapterStart = !seenChapterFirst
        seenChapterFirst = true
        val paddingTop = if (isChapterStart) firstPagePaddingTop else 0f

        // 第 2 步：预计算 linePositions（段内绝对 top），同时累加 totalHeight。
        //
        // 算法：以 paddingTop 为起点游标 cursor。
        //   - 同页相邻行：保留 page 内自然行间距 = (line.lineTop - prevLineBottom)
        //     这里的 line.lineTop / prevLineBottom 都是 page-local Y。
        //   - 跨页相邻行：丢弃 page break 间隙——直接接在上一行底部，形成视觉连续。
        //   - 行高 = line.lineBottom - line.lineTop（保留 paint 行距 + 字号编码的高度）。
        val linePositions = FloatArray(paraLines.size)
        var cursor = paddingTop
        var prevPageIdx = -1
        var prevLineBottom = 0f
        for ((i, entry) in entries.withIndex()) {
            val (pageIdx, line) = entry
            if (pageIdx == prevPageIdx) {
                // 同页：补回行间距（lineTop - prevLineBottom 可能是负值——
                // Legado 排版偶尔会用「lineTop = lineBottom」来表示零间距，
                // 这里 coerceAtLeast(0f) 防止段长被错误压缩）。
                cursor += (line.lineTop - prevLineBottom).coerceAtLeast(0f)
            }
            // 跨页：cursor 不补间距，直接接在上一行底部。
            linePositions[i] = cursor
            cursor += (line.lineBottom - line.lineTop).coerceAtLeast(0f)
            prevPageIdx = pageIdx
            prevLineBottom = line.lineBottom
        }
        val totalHeight = cursor

        // 第 3 步：判定 contentType。优先级：CHAPTER_TITLE > IMAGE > NORMAL。
        // 章首段 + 含标题/章号 line → CHAPTER_TITLE；
        // 任意 line 含 ImageColumn → IMAGE；
        // 否则 NORMAL。
        // 注意 ButtonColumn / ReviewColumn 不单独分类——它们在内容里出现频率极低，
        // 单独分类反而拖累复用率。
        val contentType = when {
            isChapterStart && paraLines.any { it.isChapterNum || it.isTitle } -> ScrollParagraphType.CHAPTER_TITLE
            paraLines.any { line -> line.columns.any { it is ImageColumn } } -> ScrollParagraphType.IMAGE
            else -> ScrollParagraphType.NORMAL
        }

        // charSize：含段末换行（lastLine.isParagraphEnd ? +1 : +0）。
        // Legado 习惯 [TextChapter.getNeedReadAloud] 等地方都按这个口径累计字符。
        var charSize = 0
        for (line in paraLines) {
            charSize += line.charSize
            if (line.isParagraphEnd) charSize += 1
        }

        result.add(
            ScrollParagraph(
                key = "$chapterIndex-$paragraphNum",
                contentType = contentType,
                chapterIndex = chapterIndex,
                paragraphNum = paragraphNum,
                lines = paraLines,
                linePositions = linePositions,
                paddingTop = paddingTop,
                totalHeight = totalHeight,
                firstChapterPosition = firstLine.chapterPosition,
                charSize = charSize,
            ),
        )
    }
    return result
}

/**
 * 构造一个 LOADING 占位段 —— 用户快速下滑撞到章末、下一章还在加载时，
 * ViewModel 把这个 placeholder 接到窗口尾部，避免视觉空缺。
 *
 * 数据到位后 ViewModel 用真实段（同 chapterIndex）替换 placeholder，LazyColumn 会按
 * key 平滑过渡 item 高度，不会触发整体 relayout。
 *
 * @param chapterIndex 占位段对应的章 idx
 * @param placeholderHeight 占位高度，建议 viewport 高度的 30%~50%。默认 600px ≈ 1080p
 *        屏 30%。
 */
fun loadingScrollParagraph(
    chapterIndex: Int,
    placeholderHeight: Float = 600f,
): ScrollParagraph = ScrollParagraph(
    key = "loading-$chapterIndex",
    contentType = ScrollParagraphType.LOADING,
    chapterIndex = chapterIndex,
    paragraphNum = 0,
    lines = emptyList(),
    linePositions = FloatArray(0),
    paddingTop = 0f,
    totalHeight = placeholderHeight,
    firstChapterPosition = 0,
    charSize = 0,
)
