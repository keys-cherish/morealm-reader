package com.morealm.app.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

/**
 * 「替换」独立弹窗 —— 从阅读器选区菜单点「替换」后弹出。
 *
 * 与「新建替换规则」那套管理界面刻意分开：用户在正文里选中一个词点替换，心智是
 * **就地纠错**（错字 / 译名 / 转码乱码），不是"去规则库里加一条配置"。所以这里
 * 只问两件事——换什么、换成什么——外加一个作用范围开关，不暴露正则、排序、
 * 标题作用域等规则库概念。落库仍然复用 [com.morealm.app.domain.entity.ReplaceRule]
 * 管线（非正则字面量匹配），用户看不到"规则"这个词。
 *
 * 「替换为」留空 = 删除该词，与提示行一致。
 *
 * @param initialPattern 选区文字，预填到第一个输入框且允许改（用户可能想扩大/缩小范围）
 * @param onConfirm (原文, 替换为, 是否仅本章)
 */
@Composable
fun ReplaceTextDialog(
    initialPattern: String,
    onDismiss: () -> Unit,
    onConfirm: (pattern: String, replacement: String, chapterOnly: Boolean) -> Unit,
) {
    // 预填时把光标放到末尾而不是选中全文：用户多半是想在原词基础上微调，
    // 全选状态下随手一敲就把预填内容清了。
    var pattern by remember {
        mutableStateOf(TextFieldValue(initialPattern, TextRange(initialPattern.length)))
    }
    var replacement by remember { mutableStateOf("") }
    var chapterOnly by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "替换",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "替换为",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
                OutlinedTextField(
                    value = replacement,
                    onValueChange = { replacement = it },
                    singleLine = true,
                    placeholder = { Text("替换为") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ScopeOption("全书", selected = !chapterOnly) { chapterOnly = false }
                    ScopeOption("仅本章", selected = chapterOnly) { chapterOnly = true }
                }
                Text(
                    "* 替换输入框为空表示删除",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(pattern.text, replacement, chapterOnly) },
                // 原文为空的替换规则会命中任意位置，直接禁掉
                enabled = pattern.text.isNotEmpty(),
            ) { Text("替换") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun ScopeOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
        modifier = Modifier
            .selectable(selected = selected, onClick = onSelect)
            .padding(end = 16.dp),
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
