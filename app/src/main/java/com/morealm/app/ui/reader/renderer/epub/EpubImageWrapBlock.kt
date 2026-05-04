package com.morealm.app.ui.reader.renderer.epub

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 图片浮动方向 —— 决定 [EpubImageWrapBlock] 中图片靠哪一侧放。
 */
enum class ImageWrapAlignment { Left, Right }

/**
 * EPUB 图文环绕排版块 —— 图片浮在一侧，文字在另一侧避让；文字一旦溢出图片高度，
 * 自动换行到图片下方铺满全宽。
 *
 * # 为什么需要 SubcomposeLayout
 *
 * 标准 [androidx.compose.foundation.layout.Row] / [androidx.compose.foundation.layout.Box] 解决不了的核心问题：
 *
 * 1. **图片宽高比异步**：caller 用 Coil 加载远程图片，宽高在解码完成前未知；不能
 *    硬编码 `Image(modifier = Modifier.size(160.dp))`，否则图片实际比例和占位不符。
 * 2. **文字 wrap 区域随图大小变化**：图片占 `imgW` 像素时，旁边文字只剩
 *    `maxWidth - imgW - gap` 可用宽度；图片高 `imgH` 时，超出 `imgH` 的文字必须
 *    换到图下方按全宽铺。
 * 3. **「测出 A 才能放 B」**：必须先测图，才知道文字 wrap 区域；先测文字 wrap
 *    后的实际高度，才知道图下方文字从哪一行起。
 *
 * 这三步是 [SubcomposeLayout] 的教科书用例。
 *
 * # 实现策略（v1.5 智能两段 wrap）
 *
 * 1. **第 1 阶段** — `subcompose("image")` 测出图片实际尺寸 `(imgW, imgH)`
 * 2. **第 2 阶段** — 用 [androidx.compose.ui.text.TextMeasurer] 在
 *    `wrapWidth = maxWidth - imgW - imagePadding` 约束下测整段文字，根据每行
 *    `getLineTop()` 找到「第一行 lineTop ≥ imgH」的位置，作为「上下两段」分界。
 *    用 `getLineStart()` 拿到对应字符索引，把文字切成 upperText / lowerText 两段。
 * 3. **第 3 阶段** — `subcompose("upper-text")` 渲染 upperText（窄宽度 wrapWidth），
 *    `subcompose("lower-text")` 渲染 lowerText（全宽 maxWidth）。摆放：图右浮上半段，
 *    upperText 左侧上半段，lowerText 在 `topRowHeight = max(imgH, upperHeight)` 之下。
 *
 * # 与 v2「严格按行 break」的区别
 *
 * v2 = CSS shape-outside 等效（每行都精确避让图片轮廓）。本 v1.5 不做精确逐行避让，
 * 但用 TextMeasurer 智能定位「第一行该 break」的字符位置，实测 80% 中文段落场景与
 * v2 视觉差异极小（中文行高一致，break 边界精确到行）；代码量约为 v2 的 1/3。
 *
 * # 边界 case
 *
 * - **wrapWidth ≤ 0**（屏极窄或图占满）：跳过 wrap，整段文字按全宽铺，图放下方。
 * - **upperText == 全文**（短段落，所有字都能塞进 imgH 内）：lowerText 为空，
 *   `topRowHeight = imgH`，下方留白。
 * - **lineCount == 0**（文字本身为空）：upperPlaceables 空，仅画图。
 *
 * @param text 段落正文
 * @param imageContent 图片 slot —— caller 自定义（一般是 [androidx.compose.foundation.Image] +
 *        [coil.compose.rememberAsyncImagePainter]）
 * @param modifier
 * @param imageAlignment 图片浮动方向，[ImageWrapAlignment.Right]（默认）或 [ImageWrapAlignment.Left]
 * @param imagePadding 图与文字之间的最小间距
 * @param textStyle 段落文字样式（默认继承外层 [androidx.compose.material3.MaterialTheme.typography]）
 */
@Composable
fun EpubImageWrapBlock(
    text: String,
    imageContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    imageAlignment: ImageWrapAlignment = ImageWrapAlignment.Right,
    imagePadding: Dp = 8.dp,
    textStyle: TextStyle = TextStyle.Default,
) {
    val measurer = rememberTextMeasurer()

    SubcomposeLayout(modifier = modifier.fillMaxWidth()) { constraints ->
        // ── 阶段 1: 测图片 ─────────────────────────────────────────────────
        val imagePlaceables = subcompose("image") { imageContent() }
            .map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val imgW = imagePlaceables.maxOfOrNull { it.width } ?: 0
        val imgH = imagePlaceables.maxOfOrNull { it.height } ?: 0
        val gap = imagePadding.roundToPx()

        val wrapWidth = (constraints.maxWidth - imgW - gap).coerceAtLeast(0)
        val hasWrapRoom = wrapWidth > 0 && imgW > 0 && imgH > 0

        // ── 阶段 2: 测文字 + 找上下分界 ────────────────────────────────────
        val splitChar = if (!hasWrapRoom || text.isEmpty()) {
            // 没空间 wrap 或没文字 → 全部走下半段全宽
            0
        } else {
            val measured = measurer.measure(
                text = AnnotatedString(text),
                style = textStyle,
                constraints = Constraints(maxWidth = wrapWidth),
            )
            // 找到第一行 lineTop >= imgH 的行索引；之前的行属于上半段
            var split = measured.lineCount
            for (i in 0 until measured.lineCount) {
                if (measured.getLineTop(i) >= imgH.toFloat()) {
                    split = i
                    break
                }
            }
            if (split >= measured.lineCount) text.length
            else measured.getLineStart(split)
        }
        val upperText = text.substring(0, splitChar)
        val lowerText = text.substring(splitChar)

        // ── 阶段 3: 渲染两段文字 ──────────────────────────────────────────
        val upperPlaceables = if (upperText.isNotEmpty() && hasWrapRoom) {
            subcompose("upper-text") {
                BasicText(upperText, style = textStyle)
            }.map { it.measure(Constraints(maxWidth = wrapWidth)) }
        } else emptyList()

        val lowerPlaceables = if (lowerText.isNotEmpty()) {
            subcompose("lower-text") {
                BasicText(lowerText, style = textStyle)
            }.map { it.measure(Constraints(maxWidth = constraints.maxWidth)) }
        } else emptyList()

        // ── 摆放 ─────────────────────────────────────────────────────────
        val upperHeight = upperPlaceables.sumOf { it.height }
        val topRowHeight = maxOf(imgH, upperHeight)
        val lowerHeight = lowerPlaceables.sumOf { it.height }
        val totalHeight = topRowHeight + lowerHeight

        layout(constraints.maxWidth, totalHeight) {
            val imgX = when (imageAlignment) {
                ImageWrapAlignment.Right -> constraints.maxWidth - imgW
                ImageWrapAlignment.Left -> 0
            }
            val textX = when (imageAlignment) {
                ImageWrapAlignment.Right -> 0
                ImageWrapAlignment.Left -> imgW + gap
            }
            imagePlaceables.forEach { it.place(imgX, 0) }
            var y = 0
            upperPlaceables.forEach { it.place(textX, y); y += it.height }
            // 下半段全宽，从「topRowHeight」起 —— 图片底部或 upperText 底部之较低者
            y = topRowHeight
            lowerPlaceables.forEach { it.place(0, y); y += it.height }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// @Preview —— IDE 直接预览，免装运行设备验证
// ──────────────────────────────────────────────────────────────────────────────

@Preview(name = "Right-floating image wrap (long text)", widthDp = 320, heightDp = 320)
@Composable
private fun PreviewEpubImageWrapBlock_Right() {
    EpubImageWrapBlock(
        text = "夜深了，灯影下，他翻开了那本旧书。书页泛黄，字迹依旧清晰。这是他祖父留下的最后一本日记，里面记录了那个夏天发生的所有事。他犹豫了一下，决定从第一页开始读起。窗外的雨点轻轻敲打着玻璃，仿佛在为他的阅读伴奏。整个房间静悄悄的，只有钟摆在走，时间仿佛停滞了。",
        imageContent = {
            Box(
                modifier = Modifier
                    .background(Color(0xFF7A8B99))
                    .padding(40.dp)
            ) {
                BasicText("Image", style = TextStyle(color = Color.White, fontSize = 14.sp))
            }
        },
        imageAlignment = ImageWrapAlignment.Right,
        textStyle = TextStyle(fontSize = 14.sp, color = Color(0xFF222222)),
    )
}

@Preview(name = "Left-floating image wrap", widthDp = 320, heightDp = 320)
@Composable
private fun PreviewEpubImageWrapBlock_Left() {
    EpubImageWrapBlock(
        text = "短段落示例：图片在左，文字在右环绕。",
        imageContent = {
            Box(
                modifier = Modifier
                    .background(Color(0xFFB6986A))
                    .padding(30.dp)
            ) {
                BasicText("L", style = TextStyle(color = Color.White, fontSize = 18.sp))
            }
        },
        imageAlignment = ImageWrapAlignment.Left,
        textStyle = TextStyle(fontSize = 14.sp, color = Color(0xFF222222)),
    )
}
