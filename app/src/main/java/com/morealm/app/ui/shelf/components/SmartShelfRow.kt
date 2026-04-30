package com.morealm.app.ui.shelf.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.morealm.app.presentation.shelf.SystemView
import com.morealm.app.presentation.shelf.SystemViewCount

/**
 * The "smart shelf" zero-config row — shows above the user's own folders,
 * each chip jumps to a per-view detail screen.
 *
 * Empty views render with count = 0 instead of being hidden so users always
 * see the same six chips (BY_SOURCE is filtered out when there are no web
 * books — its count is always 0 by construction). Stable layout matters: a
 * row that shrinks/grows as books are added looks unstable.
 *
 * Visual design:
 *   - Filled chip with subtle accent tint per category — emoji carries the
 *     personality, count badge is the call-to-action.
 *   - Active count > 0 highlights the chip; 0 dims it but keeps it tappable
 *     ("show me what's empty so I notice").
 */
@Composable
fun SmartShelfRow(
    counts: List<SystemViewCount>,
    onSelect: (SystemView) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (counts.isEmpty()) return
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(counts.filter { it.view != SystemView.BY_SOURCE || it.count > 0 }, key = { it.view.name }) { item ->
            SmartShelfChip(item = item, onClick = { onSelect(item.view) })
        }
    }
}

@Composable
private fun SmartShelfChip(item: SystemViewCount, onClick: () -> Unit) {
    val active = item.count > 0
    val container = if (active)
        MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    else
        MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.4f)
    val onContainer = if (active)
        MaterialTheme.colorScheme.onSurface
    else
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(container)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(item.view.emoji, fontSize = 14.sp)
        Spacer(Modifier.width(6.dp))
        Text(
            item.view.displayName,
            color = onContainer,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
        if (active) {
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                Text(
                    item.count.toString(),
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
