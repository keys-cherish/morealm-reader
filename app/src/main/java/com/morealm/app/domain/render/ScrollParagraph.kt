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
     * 段总高度（含 [paddingTop] 与 [paragraphSpacingAfter] 的尾部段间距）。
     *
     * LazyColumn item 用此值作为固定高度（`Modifier.height(totalHeight.toDp())`），
     * 测量阶段 O(1)，不会成为快速滚动瓶颈。
     *
     * **关键**：包含 [paragraphSpacingAfter] —— 否则段与段在 LazyColumn 里紧贴，
     * 视觉上「段内多行被错画成多段」（段间无空白，段内 line-spacing-extra
     * 的 0.2H 间隙反而看起来像段落分隔）。Legado 老 [ScrollRenderer]
     * 把整页一次画下来时，page 内 durY 自带 paragraphSpacing 的 0.8H 间距，
     * 段级 LazyColumn 必须显式补回去。
     */
    val totalHeight: Float,
    /**
     * 段尾段间距（已计入 [totalHeight]，单独保留此字段供调试/进度精细计算用）。
     *
     * 来源（按优先级）：
     * 1. 下一段同页：等于 `next.firstLine.lineTop - this.lastLine.lineBottom`
     *    （即 [ChapterProvider] `durY` 累积出来的
     *    `(lineSpacingExtra-1+paragraphSpacing/10) × textHeight`）。Legado
     *    默认配置 lineSpacingExtra=1.2 + paragraphSpacing=8 → 段尾间距
     *    = 1.0 × textHeight，正是用户「连续阅读视觉节奏」的核心。
     * 2. 下一段跨页（同章）：page-local 坐标已断，用 `lastLineHeight ×
     *    CROSS_PAGE_PARAGRAPH_GAP_FACTOR` 兜底（典型 Legado 默认值）。
     * 3. 章末段（无下一段）：0f。下一章首段的 [paddingTop] = `firstPagePaddingTop`
     *    本身就提供了章间视觉留白；章末若再补 tail 会和下一章 paddingTop 叠加，
     *    肉眼看到一道异常长的空白。
     */
    val paragraphSpacingAfter: Float,
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

    // ── Pass 0：预扫所有同段同页相邻行差值，得到「整章典型行间距」 ──
    //
    // 用途：Pass 1 跨页相邻行（line N 在 page A 末，line N+1 在 page A+1 首）
    // 的 page break 间隙必须丢弃（page A+1 paddingTop 不能保留在段内），但**不能
    // 直接把两行拼在一起**——那样视觉上 page break 处的两行紧贴，比同段同页其它行
    // 的间距小一截，肉眼看到「这两行突然挤在一起」的违和感。
    //
    // 解决：跨页相邻行用本章首个 > 0 的 intraGap 兜底，让 page break 跨页的两行
    // 视觉上和段内其它相邻行行距一致。
    //
    // 为什么要全章扫而不是段内扫？因为有的段全部都跨页（每行都在不同页，比如
    // 长段卡在 page break 上），段内根本没有「同页相邻行」可参考，要从其它段借
    // 一份典型 intraGap。
    val chapterFallbackIntraGap: Float = run {
        for ((_, entries) in grouped) {
            var ppi = -1
            var plb = 0f
            for ((pi, ln) in entries) {
                if (pi == ppi) {
                    val g = (ln.lineTop - plb).coerceAtLeast(0f)
                    if (g > 0f) return@run g
                }
                ppi = pi
                plb = ln.lineBottom
            }
        }
        0f
    }

    // ── Pass 1：先把每段的「不带尾部间距」基础布局算好 ──
    //
    // 把 paragraphSpacingAfter 算进 totalHeight 需要看下一段的首行位置，所以这里
    // 先收集一份 raw 数据再到 Pass 2 统一计算尾间距。两次遍历都是纯计算，可以在
    // [kotlinx.coroutines.Dispatchers.Default] 跑（caller 已在后台调用）。
    class RawParagraph(
        val paragraphNum: Int,
        val paraLines: List<TextLine>,
        val firstLine: TextLine,
        val lastLine: TextLine,
        val firstPageIdx: Int,
        val lastPageIdx: Int,
        val paddingTop: Float,
        val linePositions: FloatArray,
        val baseHeight: Float, // 不含尾部段间距，覆盖到 lastLine.lineBottom
        val lastLineHeight: Float, // 跨页兜底用
        /**
         * 该段内首次出现的同页同段「相邻行间空隙」(line.lineTop - prevLineBottom)。
         *
         * 用途：Pass 2 计算段间距时做「最小段间距 = intraLineGap × MIN_RATIO」兜底，
         * 保证段间间距至少是段内行间距的 [PARAGRAPH_GAP_MIN_RATIO_TO_LINE_GAP] 倍，
         * 视觉上段与段才能真正拉开。
         *
         * 单行段（无法算出段内间距）：取 0f；Pass 2 会回落到「整章首个能算出的
         * intraLineGap」做兜底，不会因为单行段就丢失段间补偿。
         */
        val intraLineGap: Float,
        val contentType: ScrollParagraphType,
        val charSize: Int,
    )

    val raws = ArrayList<RawParagraph>(grouped.size)
    var seenChapterFirst = false

    for ((paragraphNum, entries) in grouped) {
        val paraLines = entries.map { it.second }
        val firstLine = paraLines.first()
        val lastLine = paraLines.last()
        val isChapterStart = !seenChapterFirst
        seenChapterFirst = true
        val paddingTop = if (isChapterStart) firstPagePaddingTop else 0f

        // 第 1.a 步：预计算 linePositions（段内绝对 top），同时累加 baseHeight。
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
        var firstIntraGap = 0f // 段内首次同页相邻行间隙；用于 Pass 2 兜底
        for ((i, entry) in entries.withIndex()) {
            val (pageIdx, line) = entry
            if (pageIdx == prevPageIdx) {
                // 同页：补回行间距（lineTop - prevLineBottom 可能是负值——
                // Legado 排版偶尔会用「lineTop = lineBottom」来表示零间距，
                // 这里 coerceAtLeast(0f) 防止段长被错误压缩）。
                val gap = (line.lineTop - prevLineBottom).coerceAtLeast(0f)
                cursor += gap
                if (firstIntraGap <= 0f && gap > 0f) firstIntraGap = gap
            } else if (prevPageIdx >= 0) {
                // 跨页相邻：丢弃 page break 间隙（page A+1 paddingTop 不能算进段内），
                // 但**用 [chapterFallbackIntraGap] 补回正常行间距**——否则 page break
                // 处的两行视觉上紧贴，比同段同页其它行的行距小，肉眼看到「某两行突然
                // 挤在一起」的违和感。Pass 0 已扫整章给出典型 intraGap 值。
                cursor += chapterFallbackIntraGap
            }
            // 段首行（prevPageIdx == -1）：无相邻参考，cursor 直接是 paddingTop。
            linePositions[i] = cursor
            cursor += (line.lineBottom - line.lineTop).coerceAtLeast(0f)
            prevPageIdx = pageIdx
            prevLineBottom = line.lineBottom
        }
        val baseHeight = cursor
        val lastLineHeight = (lastLine.lineBottom - lastLine.lineTop).coerceAtLeast(0f)

        // 第 1.b 步：判定 contentType。优先级：CHAPTER_TITLE > IMAGE > NORMAL。
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

        raws.add(
            RawParagraph(
                paragraphNum = paragraphNum,
                paraLines = paraLines,
                firstLine = firstLine,
                lastLine = lastLine,
                firstPageIdx = entries.first().first,
                lastPageIdx = entries.last().first,
                paddingTop = paddingTop,
                linePositions = linePositions,
                baseHeight = baseHeight,
                lastLineHeight = lastLineHeight,
                intraLineGap = firstIntraGap,
                contentType = contentType,
                charSize = charSize,
            ),
        )
    }

    // ── Pass 2：用「下一段首行 page-local lineTop」减「本段末行 page-local lineBottom」
    //   推出段尾间距（paragraphSpacingAfter），再补进 totalHeight ──
    //
    // 为什么不能在 Pass 1 直接算？因为 paragraphSpacingAfter 是「相邻两段之间」的
    // 关系，必须先把每段最后一行的 page-local lineBottom 收齐才能跨段查询。
    //
    // 同页相邻：差值就是 [ChapterProvider] 内 `durY += textHeight*lineSpacingExtra`
    //   后又 `+= textHeight*paragraphSpacing/10f` 累计出来的真实间距。Legado
    //   默认 lineSpacingExtra=1.2 + paragraphSpacing=8 → 间距 = 1.0×textHeight，
    //   正好是用户「段与段之间留一行」的视觉节奏。
    //
    // 跨页相邻（同章）：page 已经断了，page-local 坐标无法直接相减；用末行高度
    //   * [CROSS_PAGE_PARAGRAPH_GAP_FACTOR] 兜底，等价 Legado 默认配置下的目标值。
    //
    // 章末段（next == null）：tailSpacing = 0f。下一章的首段会用
    //   [ScrollParagraph.paddingTop] = firstPagePaddingTop 提供章首留白，章末再补
    //   tail 会和下一章 paddingTop 叠加，肉眼看到一道异常长的空白。
    //
    // ── 「最小段间距」兜底 ──
    //
    // ChapterProvider 在 lineSpacingExtra 较大时会让段间几何间距 = 段内行间距 +
    // paragraphSpacing/10×textHeight，比例只有 1.x 倍，肉眼难以区分「段内换行」
    // 和「段尾换段」。瀑布流模式段不再被 page break 隔开，必须显著拉开比例。
    //
    // 兜底规则：tailSpacing ≥ effectiveIntraGap × [PARAGRAPH_GAP_MIN_RATIO_TO_LINE_GAP]
    //   - 当前段算出 intraLineGap > 0：用本段值（精准）
    //   - 单行段（intraLineGap == 0）：回落到整章首个能算出的 intraLineGap
    //   - 整章都是单行段：跳过兜底（保持原算法值）
    //
    // 这条兜底只在「原算法的段间太小」时生效——若用户配置已经让段间足够明显
    // （如 Legado 默认 1.2 + 8 → 段间 1.0H 段内 0.2H = 5×），不会被改动。
    // 注：直接复用 Pass 0 算出的 [chapterFallbackIntraGap]——两个 Pass 都需要同一个
    // 「整章典型行间距」，只算一次共享。
    val result = ArrayList<ScrollParagraph>(raws.size)
    for (i in raws.indices) {
        val curr = raws[i]
        val next = raws.getOrNull(i + 1)
        val rawTail: Float = when {
            next == null -> 0f
            next.firstPageIdx == curr.lastPageIdx -> {
                (next.firstLine.lineTop - curr.lastLine.lineBottom).coerceAtLeast(0f)
            }
            else -> curr.lastLineHeight * CROSS_PAGE_PARAGRAPH_GAP_FACTOR
        }
        // 章末段（next==null）保留 0f 不兜底——避免和下一章 paddingTop 叠加造成
        // 异常长空白；其它段用 intraLineGap 兜底拉开段间比例。
        val effectiveIntraGap = if (curr.intraLineGap > 0f) curr.intraLineGap else chapterFallbackIntraGap
        val tailSpacing: Float = if (next == null || effectiveIntraGap <= 0f) {
            rawTail
        } else {
            maxOf(rawTail, effectiveIntraGap * PARAGRAPH_GAP_MIN_RATIO_TO_LINE_GAP)
        }

        result.add(
            ScrollParagraph(
                key = "$chapterIndex-${curr.paragraphNum}",
                contentType = curr.contentType,
                chapterIndex = chapterIndex,
                paragraphNum = curr.paragraphNum,
                lines = curr.paraLines,
                linePositions = curr.linePositions,
                paddingTop = curr.paddingTop,
                totalHeight = curr.baseHeight + tailSpacing,
                paragraphSpacingAfter = tailSpacing,
                firstChapterPosition = curr.firstLine.chapterPosition,
                charSize = curr.charSize,
            ),
        )
    }
    return result
}

/**
 * 跨页 / 章末段的「段尾间距」兜底因子（× lastLineHeight）。
 *
 * 等价于 Legado 默认 `lineSpacingExtra=1.2 + paragraphSpacing=8` 配置下，
 * 段尾视觉间距 = `(lineSpacingExtra - 1 + paragraphSpacing/10) × textHeight`
 *            = `(0.2 + 0.8) × textHeight`
 *            = `1.0 × textHeight`。
 *
 * 用户改了排版偏好（如缩小行距）时，同页相邻段会按真实差值算（更准确），
 * 这个兜底只在 page-break 处生效，误差可接受。
 */
private const val CROSS_PAGE_PARAGRAPH_GAP_FACTOR = 1.0f

/**
 * 段间距「相对于段内行间距」的最小倍率。
 *
 * ── 为什么需要这个常量 ──
 *
 * MoRealm 默认排版 (lineSpacingExtra=2.0, paragraphSpacing=8) 下，
 * ChapterProvider 给出的几何间距：
 *   - 段内行间距 = (lineSpacingExtra - 1) × textHeight = 1.0 × textHeight
 *   - 段间间距 = (lineSpacingExtra - 1 + paragraphSpacing/10) × textHeight
 *             = 1.8 × textHeight
 * 比例只有 **1.8×** —— 在瀑布流连续滚动场景，肉眼很难区分「段内换行」和
 * 「段尾换段」，每行都像独立段。
 *
 * 解决办法：让段间至少是段内行间距的 [PARAGRAPH_GAP_MIN_RATIO_TO_LINE_GAP] 倍。
 *
 * ── 为什么取 2.0f ──
 *
 * 中文阅读体验研究：段间留白 ≥ 行间空白 × 2 时，段落分界感清晰，但又不至于
 * 让长文章看起来「碎片化」。取整数 2.0 与排版界一般共识对齐
 * （CSS 默认 `<p>` margin-block-start ≈ 1em，相当于行间距的 ≈2x）。
 *
 * ── 对其他配置的影响 ──
 *
 * - Legado 默认 (1.2, 8)：段内 0.2H 段间 1.0H = **5×** → 已远超 2×，**不触发**兜底
 * - MoRealm 默认 (2.0, 8)：段内 1.0H 段间 1.8H = 1.8× → 兜底拉到 2.0H = **2×**
 * - 用户调小段距 (1.2, 0)：段内 0.2H 段间 0.2H = 1× → 兜底拉到 0.4H = 2×
 *
 * 兜底只在「原配置段间不够分明」时生效，对偏好已经良好的用户零干扰。
 */
private const val PARAGRAPH_GAP_MIN_RATIO_TO_LINE_GAP = 2.0f

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
    // LOADING 段是占位 —— 不需要再补段尾间距（占位本身就是「占位」），totalHeight
    // 全部是占位高度，0 留给真实段替换占位时由各自的 paragraphSpacingAfter 接管。
    paragraphSpacingAfter = 0f,
    firstChapterPosition = 0,
    charSize = 0,
)
