package com.morealm.app.ui.reader.renderer.epub

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 章末卡片相对正文的位置。
 * - [Bottom]：先排正文，卡片靠章节底部
 * - [Top]：先排卡片，正文从卡片下方开始（用于「上一章预告」之类的引子）
 */
enum class CardAlignment { Top, Bottom }

/**
 * EPUB 章节末尾推荐卡（或开篇引子卡） —— 卡片高度异步加载（含网络图、AI 推荐等
 * 不可预知尺寸内容），段落正文必须**测出卡片高后**才能决定自身可用的最大高度。
 *
 * # 为什么需要 SubcomposeLayout
 *
 * 1. **卡片含异步内容**：网络图加载完成才知道实际高度；卡片可能含 `Box.wrapContentSize`
 *    动态展开。caller 不能传死高度。
 * 2. **正文须避让卡片**：若卡片靠底部，正文可用最大高度 = `屏剩余 - cardH - minGap`；
 *    必须先测卡片才知道这个值。
 * 3. **卡片宽度对齐正文**：卡片随 `constraints.maxWidth` 适配 EPUB 内边距，不能
 *    用绝对 dp。
 *
 * # 实现策略
 *
 * 简单两阶段：
 *
 * 1. **第 1 阶段**：`subcompose("card") { card() }` 测卡片实际高度 `cardH`。
 *    用 `constraints` 原 `maxWidth` 限制宽度，让卡片随屏宽自适应。
 * 2. **第 2 阶段**：`subcompose("paragraph")` 用 `constraints.copy(maxHeight = maxHeight - cardH - gap)`
 *    限制正文可用高度，正文 BasicText 在限定高度内自然 wrap。
 * 3. **摆放**：按 [cardAlignment] 决定先后顺序。
 *
 * # 与「正文 + 卡片放 Column」的区别
 *
 * 直接 `Column { paragraph(); Spacer(...); card() }` 写法的痛点：正文不知道自己
 * 应该排到哪里停（卡片在 Column 里靠 wrapContentHeight，正文也是 wrap，会出现
 * 「正文铺满屏 + 卡片被挤到下一页」）。SubcomposeLayout 让正文先看到「卡片要占
 * 多少」再决定自己能铺多高，避免溢出到下一章首页。
 *
 * # 边界 case
 *
 * - **constraints.maxHeight == Constraints.Infinity**（在 LazyColumn 里挂载，无高度
 *   限制）：跳过高度切割，正文按内容自然铺；卡片仍在底部摆放。这是预期行为：
 *   LazyColumn item 内不需要章节级别高度切割。
 * - **cardH > maxHeight - gap**（卡片本身就超屏高）：正文可用高度被裁到 0，仅画
 *   卡片；用户需要滚才能看到下面（如果在 LazyColumn 里）。
 * - **paragraph 内容超出可用高度**：BasicText 自然换行；超出部分被裁（这正是
 *   「告诉 caller 该换页」的信号）。
 *
 * @param paragraph 正文 slot —— caller 自定义文字段或更复杂 layout
 * @param card 卡片 slot —— caller 自定义图文混合卡片
 * @param modifier
 * @param minGap 正文与卡片之间的最小间距
 * @param cardAlignment [CardAlignment.Bottom]（默认章末）或 [CardAlignment.Top]（章首引子）
 */
@Composable
fun EpubChapterEndCard(
    paragraph: @Composable () -> Unit,
    card: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    minGap: Dp = 24.dp,
    cardAlignment: CardAlignment = CardAlignment.Bottom,
) {
    SubcomposeLayout(modifier = modifier.fillMaxWidth()) { constraints ->
        // ── 阶段 1: 测卡片 ────────────────────────────────────────────────
        val cardPlaceables = subcompose("card") { card() }
            .map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val cardH = cardPlaceables.maxOfOrNull { it.height } ?: 0
        val gap = if (cardH > 0) minGap.roundToPx() else 0

        // ── 阶段 2: 限定正文高度后渲染 ────────────────────────────────────
        val maxParaHeight = if (constraints.maxHeight == Constraints.Infinity) {
            Constraints.Infinity
        } else {
            (constraints.maxHeight - cardH - gap).coerceAtLeast(0)
        }
        val paraConstraints = if (maxParaHeight == Constraints.Infinity) {
            constraints.copy(minWidth = 0, minHeight = 0, maxHeight = Constraints.Infinity)
        } else {
            Constraints(
                minWidth = 0,
                maxWidth = constraints.maxWidth,
                minHeight = 0,
                maxHeight = maxParaHeight,
            )
        }
        val paraPlaceables = subcompose("paragraph") { paragraph() }
            .map { it.measure(paraConstraints) }
        val paraH = paraPlaceables.sumOf { it.height }

        val totalH = paraH + (if (cardH > 0) gap + cardH else 0)

        layout(constraints.maxWidth, totalH) {
            when (cardAlignment) {
                CardAlignment.Bottom -> {
                    // 先正文，再卡片
                    var y = 0
                    paraPlaceables.forEach { it.place(0, y); y += it.height }
                    if (cardH > 0) {
                        cardPlaceables.forEach { it.place(0, paraH + gap) }
                    }
                }
                CardAlignment.Top -> {
                    // 先卡片，再正文
                    if (cardH > 0) {
                        cardPlaceables.forEach { it.place(0, 0) }
                    }
                    var y = if (cardH > 0) cardH + gap else 0
                    paraPlaceables.forEach { it.place(0, y); y += it.height }
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// @Preview —— IDE 直接预览
// ──────────────────────────────────────────────────────────────────────────────

@Preview(name = "Chapter end card (bottom)", widthDp = 320, heightDp = 480)
@Composable
private fun PreviewEpubChapterEndCard_Bottom() {
    EpubChapterEndCard(
        paragraph = {
            BasicText(
                "他合上书页，长长地舒了一口气。窗外的雨还在下，仿佛永远不会停。这一夜，他第一次明白了祖父留下的那句话的含义。明天，他将踏上一段未知的旅程。",
                style = TextStyle(fontSize = 14.sp, color = Color(0xFF222222)),
            )
        },
        card = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF0EAD6))
                    .padding(16.dp),
            ) {
                BasicText(
                    "下一章预告：第 12 章 · 老宅深处\n\n那扇紧闭的门后，藏着祖父最不愿让外人知道的秘密……",
                    style = TextStyle(fontSize = 13.sp, color = Color(0xFF333333)),
                )
            }
        },
        cardAlignment = CardAlignment.Bottom,
    )
}

@Preview(name = "Chapter intro card (top)", widthDp = 320, heightDp = 480)
@Composable
private fun PreviewEpubChapterEndCard_Top() {
    EpubChapterEndCard(
        paragraph = {
            BasicText(
                "新章节的正文从卡片下方开始……",
                style = TextStyle(fontSize = 14.sp, color = Color(0xFF222222)),
            )
        },
        card = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFE0E8F0))
                    .padding(12.dp),
            ) {
                BasicText(
                    "上一章回顾：他终于打开了那本书。",
                    style = TextStyle(fontSize = 12.sp, color = Color(0xFF555555)),
                )
            }
        },
        cardAlignment = CardAlignment.Top,
    )
}
