package com.morealm.app.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.morealm.app.domain.entity.ReplaceRule

/**
 * EffectiveReplacesDialog — Legado-parity（任务 #5）。
 *
 * 展示「在当前章被实际命中（result≠input）」的替换规则 + 繁简转换占位条目。
 *
 * 用户操作：
 *  - 点击规则名 → 通过 [onEditRule] 跳到 ReplaceRuleScreen 并自动弹该规则的编辑框（用 navArg `editId`）。
 *  - 点击 ✕ → 该规则 enabled=false 持久化（[onDisableRule]），列表立刻移除。
 *  - 点击「繁简转换」占位条目 → 弹模式选择 BottomSheet（off/简→繁/繁→简）。
 *  - 点击 ✕（繁简） → chineseConvertMode=0。
 *  - 任意修改 → isEdit=true；dialog dismiss 时由调用方根据 isEdit 触发当前章重渲染。
 *
 * 真命中跟踪由 ReaderChapterController 内 hitContentRulesSet/hitTitleRulesSet 维护，
 * 切章时 clear。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EffectiveReplacesDialog(
    contentRules: List<ReplaceRule>,
    titleRules: List<ReplaceRule>,
    chineseConvertMode: Int,
    onDismiss: (isEdit: Boolean) -> Unit,
    onEditRule: (ruleId: String) -> Unit,
    onDisableRule: (ruleId: String) -> Unit,
    onSetChineseConvertMode: (mode: Int) -> Unit,
    onDisableChineseConvert: () -> Unit,
) {
    /** 用户做了任意改动（禁用规则 / 改繁简模式）→ true，dismiss 时调用方据此重渲染。 */
    var isEdit by remember { mutableStateOf(false) }
    /** 局部隐藏（不等 db 回写）— UI 立即响应。再次打开 dialog 走真命中重新计算。 */
    val locallyHiddenIds = remember { mutableStateListOf<String>() }
    var showChineseModeSheet by remember { mutableStateOf(false) }

    // 合并 title + content 命中（去重 by id），供展示
    val mergedRules = remember(contentRules, titleRules, locallyHiddenIds.toList()) {
        val seen = HashSet<String>()
        buildList {
            for (r in contentRules + titleRules) {
                if (r.id in locallyHiddenIds) continue
                if (seen.add(r.id)) add(r)
            }
        }
    }
    val hasChinese = chineseConvertMode > 0

    AlertDialog(
        onDismissRequest = { onDismiss(isEdit) },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.FilterAlt, null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("当前章生效规则", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            if (mergedRules.isEmpty() && !hasChinese) {
                // 空态
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 80.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "当前章未命中任何规则",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(mergedRules, key = { it.id }) { rule ->
                        EffectiveRuleRow(
                            rule = rule,
                            isTitleScope = rule in titleRules && rule !in contentRules,
                            onClick = {
                                onEditRule(rule.id)
                                isEdit = true
                                onDismiss(true)  // 跳转后立即关闭，避免回来还显示陈旧 dialog
                            },
                            onClose = {
                                locallyHiddenIds.add(rule.id)
                                onDisableRule(rule.id)
                                isEdit = true
                            },
                        )
                    }
                    if (hasChinese) {
                        item {
                            ChineseConvertRow(
                                mode = chineseConvertMode,
                                onClick = { showChineseModeSheet = true },
                                onClose = {
                                    onDisableChineseConvert()
                                    isEdit = true
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(isEdit) }) {
                Text("关闭", color = MaterialTheme.colorScheme.primary)
            }
        },
    )

    if (showChineseModeSheet) {
        ChineseConvertModeSheet(
            current = chineseConvertMode,
            onSelect = { mode ->
                onSetChineseConvertMode(mode)
                isEdit = true
                showChineseModeSheet = false
            },
            onDismiss = { showChineseModeSheet = false },
        )
    }
}

@Composable
private fun EffectiveRuleRow(
    rule: ReplaceRule,
    isTitleScope: Boolean,
    onClick: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        rule.name.ifBlank { rule.pattern },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(6.dp))
                    ScopeTag(if (isTitleScope) "标题" else if (rule.isRegex) "正则" else "普通")
                    if (rule.bookId != null) {
                        Spacer(Modifier.width(4.dp))
                        ScopeTag("本书")
                    }
                }
                if (rule.pattern.isNotBlank() && rule.name.isNotBlank()) {
                    Text(
                        buildString {
                            append(rule.pattern)
                            if (rule.replacement.isNotEmpty()) append(" → ${rule.replacement}")
                        },
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    Icons.Default.Close,
                    "禁用",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun ChineseConvertRow(
    mode: Int,
    onClick: () -> Unit,
    onClose: () -> Unit,
) {
    val label = when (mode) {
        1 -> "简→繁"
        2 -> "繁→简"
        else -> "关闭"
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "繁简转换 (系统)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            ScopeTag(label)
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Default.Close,
                    "关闭繁简",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun ScopeTag(label: String) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChineseConvertModeSheet(
    current: Int,
    onSelect: (mode: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "繁简转换模式",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(12.dp))
            for ((mode, label) in listOf(0 to "关闭", 1 to "简→繁", 2 to "繁→简")) {
                Surface(
                    onClick = { onSelect(mode) },
                    shape = RoundedCornerShape(8.dp),
                    color = if (mode == current)
                        MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (mode == current)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
