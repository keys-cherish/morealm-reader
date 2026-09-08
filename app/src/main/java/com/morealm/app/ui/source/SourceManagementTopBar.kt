package com.morealm.app.ui.source

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.morealm.app.presentation.source.SourceSortKey

/** Two permanent actions leave space for the title at narrow widths and large font scales. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SourceManagementTopBar(
    sortKey: SourceSortKey,
    sortAscending: Boolean,
    sourceCount: Int,
    isChecking: Boolean,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onSelect: () -> Unit,
    onCheck: () -> Unit,
    onSort: (SourceSortKey) -> Unit,
    onPaste: () -> Unit,
    onExport: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var sorting by remember { mutableStateOf(false) }
    TopAppBar(
        title = {
            Text("书源管理", fontWeight = FontWeight.Bold, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        },
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        },
        actions = {
            IconButton(onClick = onAdd) { Icon(Icons.Default.Add, "添加书源") }
            Box {
                IconButton(onClick = { sorting = false; menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, "更多操作")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (sorting) {
                        SourceSortKey.entries.forEach { key ->
                            DropdownMenuItem(
                                text = {
                                    Text(if (key == sortKey) {
                                        "${key.label} · ${if (sortAscending) "升序" else "降序"}"
                                    } else key.label)
                                },
                                onClick = { onSort(key); menuOpen = false },
                            )
                        }
                    } else {
                        DropdownMenuItem(text = { Text("多选管理") }, enabled = sourceCount > 0,
                            onClick = { menuOpen = false; onSelect() })
                        DropdownMenuItem(text = { Text("排序") }, onClick = { sorting = true })
                        DropdownMenuItem(text = { Text(if (isChecking) "停止校验" else "校验书源") },
                            onClick = { menuOpen = false; onCheck() })
                        DropdownMenuItem(text = { Text("粘贴导入") },
                            onClick = { menuOpen = false; onPaste() })
                        DropdownMenuItem(text = { Text("导出全部 ($sourceCount)") }, enabled = sourceCount > 0,
                            onClick = { menuOpen = false; onExport() })
                    }
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}
