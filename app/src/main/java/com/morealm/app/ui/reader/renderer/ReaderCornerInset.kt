package com.morealm.app.ui.reader.renderer

import android.os.Build
import android.view.RoundedCorner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * 底部屏幕圆角（R 角）让位量。全面屏底部两角是圆弧，贴边绘制的 InfoBar 角落内容
 * （时间 / 电量）会被圆角裁掉。返回 InfoBar 左右**额外**需要内缩的 dp（叠加在已有
 * 水平边距之上）。
 *
 * - Android 12+（API 31）：读硬件底部圆角真实半径（取左右较大者）。内缩量 =
 *   半径 × 0.8 − 已有水平边距；有圆角时下限补到 6dp（半径大 / 边距大时也至少留一点）。
 * - 旧系统无该 API：有底部系统栏 inset（多为全面屏手势导航）→ 按经验 20dp 圆角兜底；
 *   直角屏 / 实体导航键 → 0（不内缩）。
 *
 * @param existingHorizontalDp InfoBar 已有的水平边距（用户阅读边距）
 * @param bottomSystemInsetDp  底部系统栏 inset（旧系统判断是否全面屏的启发依据）
 */
@Composable
fun rememberBottomCornerInsetDp(
    existingHorizontalDp: Dp,
    bottomSystemInsetDp: Dp,
): Dp {
    val density = LocalDensity.current
    val view = LocalView.current
    return remember(existingHorizontalDp, bottomSystemInsetDp, view) {
        val radiusPx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val insets = view.rootWindowInsets
            val bl = insets?.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius ?: 0
            val br = insets?.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)?.radius ?: 0
            max(bl, br)
        } else 0
        when {
            radiusPx > 0 -> {
                val radiusDp = with(density) { radiusPx.toDp() }
                (radiusDp * 0.8f - existingHorizontalDp).coerceAtLeast(6.dp)
            }
            // 旧系统无 RoundedCorner API：有底部系统栏 inset → 多为全面屏，按经验圆角兜底
            bottomSystemInsetDp > 0.dp -> (20.dp * 0.8f - existingHorizontalDp).coerceAtLeast(6.dp)
            else -> 0.dp
        }
    }
}
