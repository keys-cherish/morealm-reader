package com.morealm.app.ui.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import com.morealm.app.presentation.settings.ReplaceRuleViewModel
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.morealm.app.ui.theme.LocalMoRealmColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReplaceRuleScreen(
    onBack: () -> Unit,
    /**
     * 可选的「打开后自动弹该规则编辑框」入口 — EffectiveReplacesDialog 跳来时使用。
     * null = 普通进入；非空 = 列表加载后等到匹配 id 出现时自动打开 ReplaceRuleDialog。
     * 该效果只触发一次（用 LaunchedEffect 的 key 是 editId 自身）。
     */
    autoEditId: String? = null,
    viewModel: ReplaceRuleViewModel = hiltViewModel(),
) {
    val moColors = LocalMoRealmColors.current
    val rules by viewModel.allRules.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }
    var editingRuleId by remember { mutableStateOf<String?>(null) }
    /** Guard: autoEditId 触发只走一次，否则旋转屏 / recomposition 会反复弹窗。 */
    var autoEditConsumed by rememberSaveable { mutableStateOf(false) }

    // 导入：兼容 MoRealm bundle / Legado 新格式 / Yuedu 老格式 / 单条 / 数组。
    // 检测/解析逻辑都封在 ViewModel.parseRules 里，这里只管 SAF。
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.importRulesFromUri(it) }
    }
    // 导出：单文件 JSON，扩展名走默认（.json）。文件名给个有意义的默认值，
    // 用户可在 SAF picker 里再改。
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { viewModel.exportRules(it) }
    }

    // 从 EffectiveReplacesDialog 跳来：等 rules 加载好后自动弹编辑框。
    LaunchedEffect(autoEditId, rules) {
        val target = autoEditId ?: return@LaunchedEffect
        if (autoEditConsumed) return@LaunchedEffect
        if (rules.isEmpty()) return@LaunchedEffect  // 还在加载
        if (rules.any { it.id == target }) {
            editingRuleId = target
            showAddDialog = true
            autoEditConsumed = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("正文替换净化") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        importLauncher.launch(
                            arrayOf("application/json", "application/octet-stream", "text/plain")
                        )
                    }) {
                        Icon(Icons.Default.Download, "导入规则",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                    }
                    IconButton(onClick = {
                        // 文件名带规则数让用户一眼看出导出范围。
                        exportLauncher.launch("morealm-replace-${rules.size}.json")
                    }, enabled = rules.isNotEmpty()) {
                        Icon(Icons.Default.Upload, "导出规则",
                            tint = if (rules.isNotEmpty())
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
                    }
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, "添加规则", tint = MaterialTheme.colorScheme.primary)
                    }
                },
            )
        },
    ) { padding ->
        if (rules.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("暂无替换规则", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    Spacer(Modifier.height(8.dp))
                    Text("添加规则可去除广告、净化正文内容", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
                    Spacer(Modifier.height(16.dp))
                    FilledTonalButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("添加规则")
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(rules, key = { it.id }) { rule ->
                    ReplaceRuleItem(
                        rule = rule,
                        onToggle = { viewModel.toggleRule(rule) },
                        onEdit = { editingRuleId = rule.id; showAddDialog = true },
                        onDelete = { viewModel.deleteRule(rule.id) },
                    )
                }
            }
        }
    }

    // Add/Edit dialog
    if (showAddDialog) {
        val editRule = editingRuleId?.let { id -> rules.find { it.id == id } }
        ReplaceRuleDialog(
            initial = editRule,
            onDismiss = { showAddDialog = false; editingRuleId = null },
            onSave = { name, pattern, replacement, isRegex, scope ->
                viewModel.saveRule(editingRuleId, name, pattern, replacement, isRegex, scope)
                showAddDialog = false
                editingRuleId = null
            },
        )
    }
}

@Composable
private fun ReplaceRuleItem(
    rule: com.morealm.app.domain.entity.ReplaceRule,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val moColors = LocalMoRealmColors.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Switch(
                checked = rule.enabled,
                onCheckedChange = { onToggle() },
                colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.size(36.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    rule.name.ifEmpty { rule.pattern },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Text(
                    buildString {
                        if (rule.isRegex) append("[正则] ")
                        append(rule.pattern)
                        if (rule.replacement.isNotEmpty()) append(" → ${rule.replacement}")
                    },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    maxLines = 1,
                )
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Edit, "编辑",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Delete, "删除",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
                    modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun ReplaceRuleDialog(
    initial: com.morealm.app.domain.entity.ReplaceRule?,
    onDismiss: () -> Unit,
    onSave: (name: String, pattern: String, replacement: String, isRegex: Boolean, scope: String) -> Unit,
) {
    val moColors = LocalMoRealmColors.current
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var pattern by remember { mutableStateOf(initial?.pattern ?: "") }
    var replacement by remember { mutableStateOf(initial?.replacement ?: "") }
    var isRegex by remember { mutableStateOf(initial?.isRegex ?: false) }
    var scope by remember { mutableStateOf(initial?.scope ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial != null) "编辑规则" else "添加替换规则") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("规则名称") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary, cursorColor = MaterialTheme.colorScheme.primary),
                )
                OutlinedTextField(
                    value = pattern, onValueChange = { pattern = it },
                    label = { Text("匹配内容 *") },
                    placeholder = { Text("输入要替换的文字或正则") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary, cursorColor = MaterialTheme.colorScheme.primary),
                )
                OutlinedTextField(
                    value = replacement, onValueChange = { replacement = it },
                    label = { Text("替换为（留空则删除匹配内容）") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary, cursorColor = MaterialTheme.colorScheme.primary),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = isRegex, onCheckedChange = { isRegex = it },
                        colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary),
                    )
                    Text("使用正则表达式", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (pattern.isNotBlank()) onSave(name, pattern, replacement, isRegex, scope) },
                enabled = pattern.isNotBlank(),
            ) {
                Text("保存", color = if (pattern.isNotBlank()) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
