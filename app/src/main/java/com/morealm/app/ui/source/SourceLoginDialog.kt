package com.morealm.app.ui.source

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.morealm.app.domain.entity.BookSource
import com.morealm.app.presentation.source.RowUi

/**
 * 书源登录表单对话框（升级版，支持 RowUi 全 5 类）。
 *
 *   - text / password ：OutlinedTextField，password 走视觉遮罩
 *   - button          ：FilledTonalButton，点击触发 [onActionJs]
 *   - toggle          ：自循环切换 chars 列表，切换后触发 action JS（如有）
 *   - select          ：ExposedDropdownMenuBox，选中后触发 action JS（如有）
 *
 * 与 Legado SourceLoginDialog 行为对齐的部分：
 *   - 点击外部 / 取消按钮关闭对话框时，**自动**把当前输入回写 putLoginInfo
 *     （避免误触丢失输入），等价 Legado onDismiss 自动持久化。
 *   - 默认值优先级：已存登录信息 > [RowUi.default]
 *
 * 暂未实现的 Legado 行为（task D 范围）：
 *   - upUiData / reUiView 反向 UI 通道（JS 期间刷新表单）
 *   - 文本输入 600ms 防抖触发 action（目前需用户显式点 button）
 *   - 长按 button 区分 isLongClick（统一按 false）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceLoginDialog(
    source: BookSource,
    fields: List<RowUi>,
    onDismiss: () -> Unit,
    onLogin: (Map<String, String>) -> Unit,
    onActionJs: (actionJs: String, currentValues: Map<String, String>) -> Unit = { _, _ -> },
    /** 跳转到 App 日志页（顶栏菜单"查看日志"项触发）。null 时菜单项隐藏。 */
    onNavigateToLog: (() -> Unit)? = null,
    /**
     * JS 反向通道：脚本端调 `loginExt.upUiData(map)` 时通过此 Flow 推送增量更新。
     * 收到的 map 直接合并到 [fieldValues]（key 不存在则忽略 —— 保护 UI 不被随意添加）。
     */
    uiPatchFlow: kotlinx.coroutines.flow.SharedFlow<Map<String, String>>? = null,
    /**
     * JS 反向通道：脚本端调 `loginExt.reUiView()` 时触发；当前实现简单 —— 仅清空
     * 缓存的字段值并重新从 `getLoginInfoMap()` 拉一遍。后续可扩展为重新解析 loginUi。
     */
    uiRebuildFlow: kotlinx.coroutines.flow.SharedFlow<Boolean>? = null,
) {
    val fieldValues = remember { mutableStateMapOf<String, String>() }
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current

    // 顶栏菜单状态
    var menuExpanded by remember { mutableStateOf(false) }
    var loginHeaderText by remember { mutableStateOf<String?>(null) }

    // 反向通道 — JS 期间增量更新表单
    LaunchedEffect(uiPatchFlow) {
        uiPatchFlow?.collect { patch ->
            // 仅写入表单已知字段，未知 key 忽略防止 JS 注入伪字段
            for ((key, value) in patch) {
                if (fields.any { it.name == key }) {
                    fieldValues[key] = value
                }
            }
        }
    }
    LaunchedEffect(uiRebuildFlow) {
        uiRebuildFlow?.collect {
            // 简单粗暴：清空当前内存值 + 从持久化拉一遍。完整重新解析 loginUi 留给后续。
            fieldValues.clear()
            val saved = source.getLoginInfoMap().orEmpty()
            fields.forEach { row ->
                fieldValues[row.name] = saved[row.name] ?: row.default ?: row.chars?.firstOrNull() ?: ""
            }
        }
    }

    // 预填：已存 loginInfo > rowUi.default
    LaunchedEffect(source) {
        val saved = source.getLoginInfoMap().orEmpty()
        fields.forEach { row ->
            val initial = saved[row.name] ?: row.default ?: row.chars?.firstOrNull() ?: ""
            fieldValues[row.name] = initial
        }
    }

    AlertDialog(
        onDismissRequest = {
            // 关闭时自动回写当前输入（与 Legado SourceLoginDialog.onDismiss 行为对齐）
            persistOnDismiss(source, fields, fieldValues.toMap())
            onDismiss()
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "登录 ${source.bookSourceName}",
                    modifier = Modifier.weight(1f),
                )
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, "更多")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("查看登录头") },
                            onClick = {
                                menuExpanded = false
                                loginHeaderText = source.getLoginHeader() ?: "（未保存登录头）"
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("删除登录头") },
                            onClick = {
                                menuExpanded = false
                                source.removeLoginHeader()
                                Toast.makeText(context, "已删除登录头", Toast.LENGTH_SHORT).show()
                            },
                        )
                        if (onNavigateToLog != null) {
                            DropdownMenuItem(
                                text = { Text("查看日志") },
                                onClick = {
                                    menuExpanded = false
                                    onNavigateToLog()
                                },
                            )
                        }
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                fields.forEach { row ->
                    val isLastInput = row == fields.lastOrNull {
                        it.type == RowUi.Type.TEXT || it.type == RowUi.Type.PASSWORD
                    }
                    when (row.type) {
                        RowUi.Type.PASSWORD,
                        RowUi.Type.TEXT -> {
                            val isPwd = row.type == RowUi.Type.PASSWORD
                            OutlinedTextField(
                                value = fieldValues[row.name] ?: "",
                                onValueChange = { fieldValues[row.name] = it },
                                label = { Text(row.displayLabel()) },
                                visualTransformation = if (isPwd) PasswordVisualTransformation() else VisualTransformation.None,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = if (isPwd) KeyboardType.Password else KeyboardType.Text,
                                    imeAction = if (isLastInput) ImeAction.Done else ImeAction.Next,
                                ),
                                keyboardActions = KeyboardActions(
                                    onNext = { focusManager.moveFocus(FocusDirection.Down) },
                                    onDone = {
                                        focusManager.clearFocus()
                                        onLogin(fieldValues.toMap())
                                    },
                                ),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        RowUi.Type.SELECT -> {
                            SelectRow(
                                row = row,
                                value = fieldValues[row.name] ?: row.default ?: row.chars?.firstOrNull() ?: "",
                                onValueChange = { newValue ->
                                    fieldValues[row.name] = newValue
                                    row.action?.takeIf { it.isNotBlank() }?.let {
                                        onActionJs(it, fieldValues.toMap())
                                    }
                                },
                            )
                        }

                        RowUi.Type.TOGGLE -> {
                            ToggleRow(
                                row = row,
                                value = fieldValues[row.name] ?: row.default ?: row.chars?.firstOrNull() ?: "",
                                onValueChange = { newValue ->
                                    fieldValues[row.name] = newValue
                                    row.action?.takeIf { it.isNotBlank() }?.let {
                                        onActionJs(it, fieldValues.toMap())
                                    }
                                },
                            )
                        }

                        RowUi.Type.BUTTON -> {
                            FilledTonalButton(
                                onClick = {
                                    val action = row.action.orEmpty()
                                    if (action.isNotBlank()) {
                                        onActionJs(action, fieldValues.toMap())
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(row.displayLabel())
                            }
                        }

                        else -> {
                            // 未知类型：当作只读文本展示，避免漏渲染
                            Text(
                                "未知类型 ${row.type}: ${row.name}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onLogin(fieldValues.toMap()) },
                // 仅校验 text/password 类必填，button/toggle/select 不影响可登录
                enabled = fields.filter { it.type == RowUi.Type.TEXT || it.type == RowUi.Type.PASSWORD }
                    .all { fieldValues[it.name]?.isNotBlank() == true },
            ) {
                Text("登录")
            }
        },
        dismissButton = {
            TextButton(onClick = {
                persistOnDismiss(source, fields, fieldValues.toMap())
                onDismiss()
            }) {
                Text("取消")
            }
        },
    )

    // 子 dialog：显示当前登录头（菜单"查看登录头"触发）
    loginHeaderText?.let { headerStr ->
        AlertDialog(
            onDismissRequest = { loginHeaderText = null },
            title = { Text("登录头") },
            text = {
                Text(
                    headerStr,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("loginHeader", headerStr))
                    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                    loginHeaderText = null
                }) { Text("复制") }
            },
            dismissButton = {
                TextButton(onClick = { loginHeaderText = null }) { Text("关闭") }
            },
        )
    }
}

/** 关闭对话框时把表单当前值回写为 loginInfo，与 Legado onDismiss 行为对齐。 */
private fun persistOnDismiss(source: BookSource, fields: List<RowUi>, values: Map<String, String>) {
    if (values.isEmpty()) return
    // 仅持久化用户实际可输入的字段，过滤掉 button（无值）
    val toPersist = values.filterKeys { key ->
        fields.find { it.name == key }?.type != RowUi.Type.BUTTON
    }
    if (toPersist.isEmpty()) return
    runCatching {
        val jsonStr = kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.builtins.MapSerializer(
                kotlinx.serialization.serializer<String>(),
                kotlinx.serialization.serializer<String>(),
            ),
            toPersist,
        )
        source.putLoginInfo(jsonStr)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectRow(
    row: RowUi,
    value: String,
    onValueChange: (String) -> Unit,
) {
    val options = row.chars.orEmpty()
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(row.displayLabel()) },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onValueChange(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(
    row: RowUi,
    value: String,
    onValueChange: (String) -> Unit,
) {
    val options = row.chars.orEmpty().ifEmpty { listOf(value) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable {
                val curIdx = options.indexOf(value).coerceAtLeast(0)
                val next = options[(curIdx + 1) % options.size]
                onValueChange(next)
            },
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                row.displayLabel(),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                value,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
