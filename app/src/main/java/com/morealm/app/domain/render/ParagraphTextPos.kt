package com.morealm.app.domain.render

/**
 * 「章 + 段编号 + 段内字符偏移」三元坐标 —— Phase 4 引入的选区/TTS/书签统一位置表示。
 *
 * ── 设计动机 ──
 *
 * 旧坐标系是 (chapterIndex, chapterPosition)：章内字符绝对偏移。
 * 优点：DB 序列化天然支持、跨段比较直接、向后兼容书签数据。
 * 缺点：在 LazyColumn 段落级渲染下，每次定位都要做一次「字符偏移 → 段编号」的
 *       线性扫描；TTS 高亮、选区拖把手、书签 jump 都频繁要这步。
 *
 * 三元坐标的优点：直接对齐 [com.morealm.app.domain.render.ScrollParagraph.paragraphNum]，
 * `findAnchorIndex(paragraphs, anchor)` 一次扫描就得到 LazyColumn item idx。
 *
 * ── 持久化策略 ──
 *
 * **DB / 书签 / 进度仍存 chapterPosition，不动**。运行时在内存里用本类传递坐标，
 * 与 chapterPosition 通过 [chapterPositionToParagraphPos] / [toChapterPosition] 双向
 * 转换。这样：
 *   - 旧书签数据零迁移、零兼容代码
 *   - LazyColumn 路径性能最优
 *   - 选区/TTS 可以用更细粒度（具体到段内字符）
 *
 * @param chapterIndex 章 idx（[com.morealm.app.domain.entity.BookChapter.index]）
 * @param paragraphNum 段编号，**1-based**，对齐 [com.morealm.app.domain.render.ScrollParagraph.paragraphNum]
 * @param charOffset 段内字符偏移（0-based）。0 表示段首字符。
 */
data class ParagraphTextPos(
    val chapterIndex: Int,
    val paragraphNum: Int,
    val charOffset: Int,
) {
    init {
        require(paragraphNum >= 1) { "paragraphNum 必须 >= 1，得到 $paragraphNum" }
        require(charOffset >= 0) { "charOffset 不能为负，得到 $charOffset" }
    }
}

/**
 * 在已扁平的段落窗口 [paragraphs] 里把 `(chapterIndex, chapterPosition)` 转成
 * [ParagraphTextPos]。
 *
 * 算法：线性扫描 paragraphs，在目标章里找 firstChapterPosition 不超过 chapterPosition
 * 的**最末**段；段内 offset = chapterPosition - firstChapterPosition。
 *
 * 与 [com.morealm.app.domain.render.bookmarkToAnchor] 的区别：本函数返回段内
 * **字符偏移**（精确到字符），而 bookmarkToAnchor 返回**行偏移**（精确到行内首字符）。
 * 选区/TTS 高亮需要字符级精度，bookmark 跳转用行级即可。
 *
 * @return null 如果 paragraphs 不含目标章 —— 调用方应等窗口加载完再重试，或退化到
 *         (chapterIndex, paragraphNum=1, charOffset=0)。
 */
fun chapterPositionToParagraphPos(
    paragraphs: List<ScrollParagraph>,
    chapterIndex: Int,
    chapterPosition: Int,
): ParagraphTextPos? {
    require(chapterPosition >= 0) { "chapterPosition 不能为负，得到 $chapterPosition" }
    var hit: ScrollParagraph? = null
    for (p in paragraphs) {
        if (p.chapterIndex != chapterIndex) {
            // 已扫过目标章后又遇到不同章 → 截断
            if (hit != null) break
            continue
        }
        if (p.firstChapterPosition <= chapterPosition) {
            hit = p
        } else {
            break
        }
    }
    val target = hit ?: return null
    val offset = (chapterPosition - target.firstChapterPosition).coerceAtLeast(0)
    return ParagraphTextPos(chapterIndex, target.paragraphNum, offset)
}

/**
 * [ParagraphTextPos] 反向转 chapterPosition。
 *
 * @return null 如果 paragraphs 不含 (chapterIndex, paragraphNum) 段；
 *         非 null 时为 firstChapterPosition + charOffset，可直接写回 DB / 书签。
 */
fun ParagraphTextPos.toChapterPosition(paragraphs: List<ScrollParagraph>): Int? {
    val target = paragraphs.firstOrNull {
        it.chapterIndex == chapterIndex && it.paragraphNum == paragraphNum
    } ?: return null
    return target.firstChapterPosition + charOffset
}

/**
 * 把 [ParagraphTextPos] 转成 [ScrollAnchor]（段首行）—— 用于 LazyColumn `scrollToItem` 跳转。
 *
 * charOffset 在转换中**被丢弃**（行级精度对齐 LazyColumn item 即可，段内 charOffset 主要
 * 给选区/TTS 高亮用）；如需保留段内字符位置，用 [toChapterPosition] 走旧路径。
 */
fun ParagraphTextPos.toScrollAnchor(): ScrollAnchor =
    ScrollAnchor(chapterIndex = chapterIndex, paragraphNum = paragraphNum)

/**
 * 在 paragraphs 里查找 [ParagraphTextPos] 对应的 LazyColumn item idx。
 * 找不到返回 -1。等价于 `findAnchorIndex(paragraphs, this.toScrollAnchor())`，
 * 这里直接走 paragraph 字段比对，避免构造临时对象。
 */
fun ParagraphTextPos.findItemIndex(paragraphs: List<ScrollParagraph>): Int {
    for (i in paragraphs.indices) {
        val p = paragraphs[i]
        if (p.chapterIndex == chapterIndex && p.paragraphNum == paragraphNum) {
            return i
        }
    }
    return -1
}
