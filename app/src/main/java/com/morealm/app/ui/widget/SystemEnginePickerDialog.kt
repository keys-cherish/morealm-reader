package com.morealm.app.ui.widget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.morealm.app.presentation.tts.SystemEnginePickerState

/** 听书页与阅读页共用的系统 TTS 引擎选择弹窗。 */
@Composable
fun SystemEnginePickerDialog(
    state: SystemEnginePickerState,
    onRefresh: () -> Unit,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择系统 TTS 引擎") },
        text = {
            Column {
                Text(
                    "改动后即时生效，无需重启。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                Spacer(Modifier.height(12.dp))
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                ) {
                    item(key = "system-default") {
                        EngineRow(
                            label = "跟随系统默认",
                            pkg = "",
                            selected = state.selectedPackage.isBlank(),
                            onClick = { onSelect("") },
                        )
                    }
                    when {
                        state.isLoading -> item(key = "loading") {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(10.dp))
                                Text("正在读取已安装引擎", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        state.errorMessage != null -> item(key = "error") {
                            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                                Text(
                                    state.errorMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                TextButton(onClick = onRefresh) {
                                    Icon(Icons.Default.Refresh, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("重试")
                                }
                            }
                        }
                        state.engines.isEmpty() -> item(key = "empty") {
                            Text(
                                "未检测到已安装的 TTS 引擎",
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        else -> items(state.engines, key = { it.name }) { engine ->
                            EngineRow(
                                label = engine.label.ifBlank { engine.name },
                                pkg = engine.name,
                                selected = state.selectedPackage == engine.name,
                                onClick = { onSelect(engine.name) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun EngineRow(
    label: String,
    pkg: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
            )
        },
        supportingContent = pkg.takeIf { it.isNotBlank() }?.let {
            { Text(it, style = MaterialTheme.typography.labelSmall) }
        },
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        colors = ListItemDefaults.colors(
            containerColor = if (selected)
                MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
            else
                Color.Transparent,
        ),
    )
}
