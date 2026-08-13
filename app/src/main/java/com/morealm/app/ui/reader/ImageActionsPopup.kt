package com.morealm.app.ui.reader

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/**
 * EPUB 图片长按弹层 —— 对齐参照阅读器的图片操作菜单形态：贴长按点的一条扁平操作条。
 *
 * 动作：查看大图 / 保存图片 / 复制图片源（书内资源名，供识别与反馈）/ 屏蔽此图
 * （加入本书屏蔽集，立即重排版消失；设置里可恢复）。
 *
 * 定位：优先长按点上方（gap 12dp），放不下翻到下方；水平方向按内容宽居中并
 * clamp 进屏（与 SelectionToolbar 同一套语义，尺寸由框架实测不做 dp 估算）。
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
            modifier = modifier,
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
            shadowElevation = 6.dp,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(Modifier.padding(horizontal = 4.dp)) {
                PopupAction("查看大图", onView)
                PopupAction("保存图片", onSave)
                PopupAction("复制图片源", onCopySource)
                PopupAction("屏蔽此图", onBlock)
            }
        }
    }
}

@Composable
private fun PopupAction(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

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
