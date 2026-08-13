package com.morealm.app.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/**
 * EPUB 图片长按弹层 —— 与 [com.morealm.app.ui.reader.renderer.SelectionToolbar]
 * 同一套视觉语言（4dp 圆角扁平条 + surfaceContainerHigh + 3dp 投影 + 竖排小图标项），
 * 让阅读器内「长按文字」与「长按图片」两种弹层手感一致；配合宿主的震动确认与
 * 图片选中态（压暗 + 描边）对齐参照阅读器的图片选中交互。
 *
 * 动作：查看大图 / 保存图片 / 复制图片源 / 屏蔽此图。
 * 定位：优先长按点上方（gap 12dp），放不下翻到下方；水平居中并 clamp 进屏。
 */
@Composable
fun ImageActionsPopup(
    offset: Offset,
    onView: () -> Unit,
    onSave: () -> Unit,
    onCopySource: () -> Unit,
    onBlock: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val gapPx = with(density) { 12.dp.toPx() }.toInt()
    val edgePx = with(density) { 8.dp.toPx() }.toInt()
    val anchor = IntOffset(offset.x.toInt(), offset.y.toInt())
    val provider = remember(anchor, gapPx, edgePx) {
        ImagePopupPositionProvider(anchor, gapPx, edgePx)
    }
    Popup(
        popupPositionProvider = provider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            modifier = modifier.width(BAR_WIDTH),
            shape = RoundedCornerShape(BAR_CORNER),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 3.dp,
            tonalElevation = 2.dp,
        ) {
            Row(
                Modifier.padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ImageMenuBtn(Icons.Default.ZoomOutMap, "查看大图", onView, Modifier.weight(1f))
                ImageMenuBtn(Icons.Default.SaveAlt, "保存图片", onSave, Modifier.weight(1f))
                ImageMenuBtn(Icons.Default.ContentCopy, "复制图源", onCopySource, Modifier.weight(1f))
                ImageMenuBtn(Icons.Default.Block, "屏蔽此图", onBlock, Modifier.weight(1f))
            }
        }
    }
}

/** 与 SelectionToolbar.MenuBtn 同款（14dp 主色 icon + 9sp label + 4dp 圆角水波）。 */
@Composable
private fun ImageMenuBtn(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(BAR_CORNER))
            .clickable(onClick = onClick)
            .padding(top = 9.dp, bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            icon, label,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(14.dp).height(14.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
            fontSize = 9.sp,
            lineHeight = 10.sp,
            maxLines = 1,
            fontWeight = FontWeight.Medium,
        )
    }
}

private val BAR_CORNER = 4.dp
private val BAR_WIDTH = 232.dp

private class ImagePopupPositionProvider(
    private val anchor: IntOffset,
    private val gapPx: Int,
    private val edgePx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width - edgePx).coerceAtLeast(edgePx)
        val x = (anchor.x - popupContentSize.width / 2).coerceIn(edgePx, maxX)
        val above = anchor.y - gapPx - popupContentSize.height
        val maxY = (windowSize.height - popupContentSize.height - edgePx).coerceAtLeast(edgePx)
        val y = if (above >= edgePx) above else (anchor.y + gapPx).coerceIn(edgePx, maxY)
        return IntOffset(x, y)
    }
}
