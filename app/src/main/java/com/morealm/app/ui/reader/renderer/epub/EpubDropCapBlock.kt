package com.morealm.app.ui.reader.renderer.epub

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.takeOrElse

/**
 * EPUB 首字下沉块 —— 段首字符以 `capScale × textStyle.fontSize` 的尺寸渲染并占
 * `dropLines` 行高度，正文剩余文字在首字右侧 wrap，溢出 dropLines 后回到全宽。
 *
 * # 为什么需要 SubcomposeLayout
 *
 * 1. **首字实际尺寸字体相关**：同一字号下不同字体（默认 / 衬线 / 装饰体）的字宽
 *    差异 30%+；硬编码 width 会让 body 第一行起始位置错位。
 * 2. **body 前 N 行宽度依赖首字宽度**：必须先测出 capW 才知道 body 前 dropLines
 *    行 wrap 宽度 = `maxWidth - capW - capPadding`。
 * 3. **N 行字符切分依赖测量结果**：要在「窄宽度」下测 body 前 N 行能容纳多少字符，
 *    切出 narrowText（避让首字部分）和 wideText（之后全宽部分）。
 *
 * # 实现策略（Subcompose 2 阶段）
 *
 * 1. **第 1 阶段**：`subcompose("cap")` 用大字号渲染首字 → 实测 `capW × capH`
 * 2. **第 2 阶段**：用 [androidx.compose.ui.text.TextMeasurer] 在
 *    `narrowWidth = maxWidth - capW - capPadding` 约束下测 body，限定 `maxLines = dropLines`，
 *    根据 `getLineEnd(dropLines - 1)` 找到「窄区结束字符」。把 body 切成
 *    narrowText（避让段）+ wideText（铺满段）。
 * 3. **第 3 阶段**：`subcompose("body-narrow")` 渲染 narrowText 在窄宽度，
 *    `subcompose("body-wide")` 渲染 wideText 在全宽。摆放：
 *    - cap 在 (0, 0)
 *    - narrowText 在 (capW + gap, 0)，每行高度由 BasicText 自己排
 *    - wideText 在 (0, capRowHeight)，全宽继续
 *
 * # 与 textIndent 简化版的取舍
 *
 * 简化版 = 用 `Modifier.drawBehind` 把首字画背景上 + body 用 `paragraphStyle.textIndent`
 * 让首行避让。问题：textIndent 只对**首行**生效，drop cap 占多行时第二、三行依然
 * 撞首字。本 SubcomposeLayout 版方法精确避让多行，效果对得起 EPUB 「真正的 drop cap」。
 *
 * # 边界 case
 *
 * - **firstChar 实际为空白字符 / 标点**：仍按字符渲染，capW 可能很小；body 几乎不
 *   避让，效果接近无 drop cap，可接受。
 * - **body 短到 narrowText 就装下全文**：wideText 为空，仅画 cap + narrowText 一段。
 * - **narrowWidth ≤ 0**（屏极窄或 cap 占满宽）：跳过 narrowText，body 直接走全宽
 *   wideText（无避让效果但不报错）。
 *
 * @param firstChar 首字字符（caller 自行从段首取）
 * @param body 段落剩余文字
 * @param modifier
 * @param dropLines 首字占几行（一般 2-3 行；EPUB 习惯 3 行）
 * @param capScale 首字字号倍数（基于 [textStyle].fontSize）
 * @param capColor 首字单独着色；[Color.Unspecified] 时随 [textStyle].color
 * @param capPadding 首字与正文之间的水平间距
 * @param textStyle 正文样式
 */
@Composable
fun EpubDropCapBlock(
    firstChar: Char,
    body: String,
    modifier: Modifier = Modifier,
    dropLines: Int = 3,
    capScale: Float = 2.5f,
    capColor: Color = Color.Unspecified,
    capPadding: Dp = 4.dp,
    textStyle: TextStyle = TextStyle.Default,
) {
    val measurer = rememberTextMeasurer()

    // 首字单独样式：放大字号 + 可选独立色（默认沿用 textStyle 的颜色）
    val capStyle = textStyle.copy(
        fontSize = textStyle.fontSize.takeOrElse { 14.sp } * capScale,
        color = if (capColor.isSpecified) capColor else textStyle.color,
    )

    SubcomposeLayout(modifier = modifier.fillMaxWidth()) { constraints ->
        // ── 阶段 1: 测首字 ────────────────────────────────────────────────
        val capPlaceables = subcompose("cap") {
            BasicText(firstChar.toString(), style = capStyle)
        }.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val capW = capPlaceables.maxOfOrNull { it.width } ?: 0
        val capH = capPlaceables.maxOfOrNull { it.height } ?: 0
        val gap = capPadding.roundToPx()

        val narrowWidth = (constraints.maxWidth - capW - gap).coerceAtLeast(0)
        val hasNarrow = narrowWidth > 0 && capW > 0 && body.isNotEmpty()

        // ── 阶段 2: 切 body 为 narrow / wide ──────────────────────────────
        val splitChar = if (!hasNarrow) {
            0  // 没空间避让 → body 全部走 wideText 全宽
        } else {
            val measured = measurer.measure(
                text = AnnotatedString(body),
                style = textStyle,
                constraints = Constraints(maxWidth = narrowWidth),
                maxLines = dropLines,
            )
            // measured.lineCount 反映在 maxLines 限制下实际占了几行
            // 如果 lineCount < dropLines，说明 body 全装进窄区，没溢出
            if (measured.lineCount < dropLines || measured.lineCount == 0) {
                body.length
            } else {
                // 取第 dropLines 行结束位置作为切分点
                val end = measured.getLineEnd(dropLines - 1, visibleEnd = true)
                end.coerceIn(0, body.length)
            }
        }
        val narrowText = body.substring(0, splitChar)
        val wideText = body.substring(splitChar)

        // ── 阶段 3: 渲染两段 ─────────────────────────────────────────────
        val narrowPlaceables = if (narrowText.isNotEmpty()) {
            subcompose("body-narrow") {
                BasicText(
                    narrowText,
                    style = textStyle,
                    maxLines = dropLines,
                    overflow = TextOverflow.Visible,
                )
            }.map { it.measure(Constraints(maxWidth = narrowWidth)) }
        } else emptyList()

        val widePlaceables = if (wideText.isNotEmpty()) {
            subcompose("body-wide") {
                BasicText(wideText, style = textStyle)
            }.map { it.measure(Constraints(maxWidth = constraints.maxWidth)) }
        } else emptyList()

        // ── 摆放 ─────────────────────────────────────────────────────────
        val narrowHeight = narrowPlaceables.sumOf { it.height }
        // 首字所占行高度 = max(capH, narrowHeight)；保证 wideText 起始 y 不会撞首字底
        val capRowHeight = maxOf(capH, narrowHeight)
        val wideHeight = widePlaceables.sumOf { it.height }
        val totalHeight = capRowHeight + wideHeight

        layout(constraints.maxWidth, totalHeight) {
            capPlaceables.forEach { it.place(0, 0) }
            var y = 0
            narrowPlaceables.forEach { it.place(capW + gap, y); y += it.height }
            // wideText 从 capRowHeight 起，全宽
            y = capRowHeight
            widePlaceables.forEach { it.place(0, y); y += it.height }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// @Preview —— IDE 直接预览
// ──────────────────────────────────────────────────────────────────────────────

@Preview(name = "Drop cap (3 lines, default)", widthDp = 320, heightDp = 280)
@Composable
private fun PreviewEpubDropCapBlock_3Lines() {
    EpubDropCapBlock(
        firstChar = '初',
        body = "次见到她时，正是去年的春天。她坐在窗前，手里拿着一本旧书，目光却落在窗外飘落的樱花上。我从她身边走过，没有打扰她。她似乎完全沉浸在自己的世界里，那一刻，时间仿佛停止了。我站在不远处，静静地看着她。",
        dropLines = 3,
        capScale = 2.5f,
        textStyle = TextStyle(fontSize = 15.sp, color = Color(0xFF222222)),
    )
}

@Preview(name = "Drop cap (2 lines, accented)", widthDp = 320, heightDp = 280)
@Composable
private fun PreviewEpubDropCapBlock_Accented() {
    EpubDropCapBlock(
        firstChar = 'O',
        body = "nce upon a time, in a land far away, there lived a wise old man who had a magical book. The book contained the secrets of the universe, but only those pure of heart could read it. Many came to seek the book, but few succeeded.",
        dropLines = 2,
        capScale = 3.0f,
        capColor = Color(0xFFB85C38),
        textStyle = TextStyle(fontSize = 14.sp, color = Color(0xFF222222)),
    )
}

@Preview(name = "Drop cap (short body, fits in narrow region)", widthDp = 320, heightDp = 200)
@Composable
private fun PreviewEpubDropCapBlock_ShortBody() {
    EpubDropCapBlock(
        firstChar = '春',
        body = "天来了。",
        dropLines = 3,
        capScale = 2.5f,
        textStyle = TextStyle(fontSize = 16.sp, color = Color(0xFF222222)),
    )
}
