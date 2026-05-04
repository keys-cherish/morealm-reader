package com.morealm.app.ui.widget

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 与主题对齐的 Snackbar Host —— 用 [MaterialTheme.colorScheme] 的 surfaceVariant
 * 当容器、onSurfaceVariant 当文字、primary 当 action，避免 M3 默认 inverseSurface
 * 把 Snackbar 渲成"反色独立块"导致与整屏主题脱节。
 *
 * 用法：替代裸 [SnackbarHost]，行为完全兼容（snackbarData 仍由 [SnackbarHostState] 控制）。
 *
 * 设计选择：
 *  - 容器 `surfaceVariant` 比 `surface` 略深，跟主题同 tone 又有可分辨层级；
 *  - 文字 `onSurfaceVariant` 与之自动配对；
 *  - action / dismiss 用 `primary`，让用户能一眼看到可点；
 *  - 不强制 elevation/shape，沿用 M3 默认（让 Material You 主题色可继续生效）。
 */
@Composable
fun ThemedSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    SnackbarHost(hostState = hostState, modifier = modifier) { data ->
        Snackbar(
            snackbarData = data,
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            actionColor = MaterialTheme.colorScheme.primary,
            actionContentColor = MaterialTheme.colorScheme.primary,
            dismissActionContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
