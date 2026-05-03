package com.morealm.app.ui.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.morealm.app.ui.theme.LocalMoRealmColors

/**
 * Floating pill-shaped bottom navigation bar.
 *
 * Design spec: docs/ui-design-spec.md
 * - Pill shape (100dp corner radius) with blur background
 * - Floats above content with 20dp bottom margin, 24dp horizontal margin
 * - Selected item uses accent color + bottom dot indicator
 * - Unselected items use muted text color
 * - Shadow for depth/floating feel
 *
 * Long-press 支持：[onTabLongClick] 在用户长按某个 tab 时回调（带 tab 索引）。
 * 调用方一般用它来"长按书架→弹分组菜单 / 长按听书→弹最近书"等捷径交互。
 *
 * 菜单渲染：长按通常配合 DropdownMenu 显示。由于 DropdownMenu 是 Popup，需要
 * anchor 在被长按的 tab item 上才能贴近显示，[tabExtras] 让调用方往每个 tab
 * 的 Box 内塞自定义 Composable（典型用法：根据 expanded state 渲染 DropdownMenu）。
 * 默认空 lambda，不影响现有调用方。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PillNavigationBar(
    tabs: List<BottomTab>,
    selectedIndex: Int,
    onTabClick: (Int) -> Unit,
    onTabLongClick: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
    tabExtras: @Composable BoxScope.(tabIndex: Int) -> Unit = {},
) {
    val moColors = LocalMoRealmColors.current
    val accent = moColors.accent
    val unselected = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
    val pillShape = RoundedCornerShape(100.dp)
    val navBg = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.1f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                // shadow BEFORE clip+background to avoid black rectangle
                .shadow(16.dp, pillShape)
                .background(navBg, pillShape)
                .border(1.dp, borderColor, pillShape),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, tab ->
                val selected = index == selectedIndex
                val color by animateColorAsState(
                    targetValue = if (selected) accent else unselected,
                    animationSpec = tween(200),
                    label = "nav_color_$index",
                )

                // 把每个 tab 的内容包在外层 Box 里：Box 既承担长按回调，又给 [tabExtras]
                // （比如 DropdownMenu）一个 anchor scope。Column 里只画 icon/label/dot，
                // 让点击/长按的命中区域跟 Column 等大、和 tabExtras 完全分离。
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(64.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .combinedClickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { onTabClick(index) },
                                onLongClick = { onTabLongClick(index) },
                            ),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = tab.label,
                            tint = color,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = tab.label,
                            color = color,
                            fontSize = 9.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            lineHeight = 12.sp,
                            modifier = Modifier.padding(top = 1.dp),
                        )
                        // Indicator dot (always present for stable layout, invisible when not selected)
                        Box(
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .size(4.dp)
                                .then(
                                    if (selected) Modifier
                                        .clip(CircleShape)
                                        .background(accent.copy(alpha = 0.8f))
                                    else Modifier
                                ),
                        )
                    }
                    // 让调用方在这个 tab 的 Box 里塞 DropdownMenu / Tooltip 等。
                    // BoxScope 提供 align 修饰符；DropdownMenu 自己是 Popup 与 layout
                    // 解耦，offset 只影响 anchor 位置（Box 的左上角）。
                    tabExtras(index)
                }
            }
        }
    }
}
