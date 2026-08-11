package com.morealm.app.ui.reader.toolbar

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector
import com.morealm.app.domain.entity.ReaderTool

fun ReaderTool.icon(): ImageVector = when (this) {
    ReaderTool.Catalog -> Icons.AutoMirrored.Outlined.FormatListBulleted
    ReaderTool.Search -> Icons.Outlined.Search
    ReaderTool.Audio -> Icons.Outlined.Mic
    ReaderTool.AutoPage -> Icons.Outlined.Timer
    ReaderTool.Settings -> Icons.Outlined.TextFields
    // 与详情页换源按钮同图标，保持视觉语言一致
    ReaderTool.ChangeSource -> Icons.Outlined.SwapHoriz
}
