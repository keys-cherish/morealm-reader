package com.morealm.app.ui.profile.remote

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * 远程书架面包屑导航 —— 用户在嵌套远程目录里浏览时显示当前路径栈，**点任一段
 * 跳回那层**（pathStack pop 到对应 index），最右一段绿色高亮显示当前层。
 *
 * 用 [horizontalScroll] 而不是 LazyRow：路径段数通常 ≤ 6，全量 measure 没成本；
 * LazyRow 在测量阶段需要确切尺寸导致跨段对齐 baseline 麻烦。Scroll state 用
 * remember(segments) key 让换路径后自动回到滚动起点。
 *
 * 截图比对：左 "/" → 中间各段 "夸克网盘 > MoRealm > books > 2025精品电子" →
 * 右侧 ← 返回箭头。返回箭头与"goBack" 等价（pop 一层），但视觉上独立于顶栏 nav。
 *
 * @param segments 路径段，例如 `["mount", "books", "subdir"]`；空 = 当前在根
 * @param onSegmentClick 点某段 → 跳到那段为终点的层（index 0 = 跳第一段）
 * @param onBackClick 点最右 ← 箭头 → goBack 一层
 */
@Composable
fun RemoteBreadcrumb(
    segments: List<String>,
    onSegmentClick: (Int) -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState: ScrollState = rememberScrollState()
    Row(
        modifier = modifier
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(scrollState),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 根目录占位 "/"
            Text(
                text = "/",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            segments.forEachIndexed { index, seg ->
                Text(
                    text = ">",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                )
                val isLast = index == segments.lastIndex
                Surface(
                    color = androidx.compose.ui.graphics.Color.Transparent,
                    onClick = { onSegmentClick(index) },
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = seg,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isLast) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isLast) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
            }
        }
        // 右侧返回箭头：等价于 goBack，但 UX 上更醒目（截图中明示）
        IconButton(onClick = onBackClick, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回上层",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun RemoteBreadcrumbPreview() {
    MaterialTheme {
        RemoteBreadcrumb(
            segments = listOf("示例网盘", "MoRealm", "books", "示例文件夹 A"),
            onSegmentClick = {},
            onBackClick = {},
        )
    }
}

@Preview(showBackground = true, name = "Root level")
@Composable
private fun RemoteBreadcrumbRootPreview() {
    MaterialTheme {
        RemoteBreadcrumb(
            segments = emptyList(),
            onSegmentClick = {},
            onBackClick = {},
        )
    }
}
