package com.morealm.app.ui.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
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
 */
@Composable
fun PillNavigationBar(
    tabs: List<BottomTab>,
    selectedIndex: Int,
    onTabClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val moColors = LocalMoRealmColors.current
    val accent = moColors.accent
    val unselected = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
    val pillShape = RoundedCornerShape(100.dp)
    val navBg = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, bottom = 20.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .shadow(12.dp, pillShape)
                .clip(pillShape)
                .background(navBg)
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

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onTabClick(index) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tab.label,
                        tint = color,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        text = tab.label,
                        color = color,
                        fontSize = 10.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    // Selected indicator dot
                    if (selected) {
                        Box(
                            modifier = Modifier
                                .padding(top = 3.dp)
                                .size(4.dp)
                                .clip(CircleShape)
                                .background(accent.copy(alpha = 0.8f)),
                        )
                    } else {
                        // Spacer to keep consistent height
                        Box(modifier = Modifier.padding(top = 3.dp).size(4.dp))
                    }
                }
            }
        }
    }
}
