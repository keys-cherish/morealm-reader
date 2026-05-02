package com.morealm.app.ui.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.clip
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
    /**
     * 分类筛选：null = 全部；KIND_GENERAL / KIND_PURIFY = 单类。
     * 用 rememberSaveable 让旋屏 / 暂离回来还在原分类，符合"我刚才在看哪一类"心智。
     */
    var kindFilter by rememberSaveable { mutableStateOf<Int?>(null) }
    /** Guard: autoEditId 触发只走一次，否则旋转屏 / recomposition 会反复弹窗。 */
    var autoEditConsumed by rememberSaveable { mutableStateOf(false) }

    val visibleRules = remember(rules, kindFilter) {
        if (kindFilter == null) rules else rules.filter { it.kind == kindFilter }
    }

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
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                // 分类筛选条 —— 与 BookSourceManageScreen 的 group filter 一致风格，
                // FilterChip 单选语义足够；选中态走 selectedContainerColor 区分而非用整套
                // 配色重写。计数挂在 label 上而不是单独 badge，避免行高跳动。
                ReplaceRuleKindFilterRow(
                    selected = kindFilter,
                    onSelect = { kindFilter = it },
                    countAll = rules.size,
                    countGeneral = rules.count { it.kind == com.morealm.app.domain.entity.ReplaceRule.KIND_GENERAL },
                    countPurify = rules.count { it.kind == com.morealm.app.domain.entity.ReplaceRule.KIND_PURIFY },
                )
                if (visibleRules.isEmpty()) {
                    Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "该分类下暂无规则",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(visibleRules, key = { it.id }) { rule ->
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
        }
    }

    // Add/Edit dialog
    if (showAddDialog) {
        val editRule = editingRuleId?.let { id -> rules.find { it.id == id } }
        ReplaceRuleDialog(
            initial = editRule,
            // 新建时用当前筛选的 kind 作为默认值（"在净化分类下点 +"自然得到净化规则）。
            // null 筛选回退到 GENERAL。
            defaultKind = kindFilter ?: com.morealm.app.domain.entity.ReplaceRule.KIND_GENERAL,
            onDismiss = { showAddDialog = false; editingRuleId = null },
            onSave = { name, pattern, replacement, isRegex, scope, kind ->
                viewModel.saveRule(editingRuleId, name, pattern, replacement, isRegex, scope, kind)
                showAddDialog = false
                editingRuleId = null
            },
        )
    }
}

/**
 * 分类筛选条：[全部] [替换 N] [净化 M]。计数硬挂 label 上避免引入 BadgedBox。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReplaceRuleKindFilterRow(
    selected: Int?,
    onSelect: (Int?) -> Unit,
    countAll: Int,
    countGeneral: Int,
    countPurify: Int,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            label = { Text("全部 $countAll") },
        )
        FilterChip(
            selected = selected == com.morealm.app.domain.entity.ReplaceRule.KIND_GENERAL,
            onClick = { onSelect(com.morealm.app.domain.entity.ReplaceRule.KIND_GENERAL) },
            label = { Text("替换 $countGeneral") },
        )
        FilterChip(
            selected = selected == com.morealm.app.domain.entity.ReplaceRule.KIND_PURIFY,
            onClick = { onSelect(com.morealm.app.domain.entity.ReplaceRule.KIND_PURIFY) },
            label = { Text("净化 $countPurify") },
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // kind 角标紧贴名字，让用户在长列表里也能扫一眼分清两类。
                    // 净化用 errorContainer 色（红橘）暗示"删除性"操作；替换用 secondary。
                    val isPurify = rule.kind == com.morealm.app.domain.entity.ReplaceRule.KIND_PURIFY
                    val tagBg = if (isPurify) MaterialTheme.colorScheme.errorContainer
                                else MaterialTheme.colorScheme.secondaryContainer
                    val tagFg = if (isPurify) MaterialTheme.colorScheme.onErrorContainer
                                else MaterialTheme.colorScheme.onSecondaryContainer
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                            .background(tagBg)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            if (isPurify) "净化" else "替换",
                            style = MaterialTheme.typography.labelSmall,
                            color = tagFg,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        rule.name.ifEmpty { rule.pattern },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReplaceRuleDialog(
    initial: com.morealm.app.domain.entity.ReplaceRule?,
    /**
     * 新建规则时的默认 kind —— 由调用方根据当前筛选传入。编辑时该参数被
     * `initial.kind` 覆盖（用 ?: 接），避免「编辑净化规则却显示默认替换」的反直觉。
     */
    defaultKind: Int = com.morealm.app.domain.entity.ReplaceRule.KIND_GENERAL,
    onDismiss: () -> Unit,
    onSave: (
        name: String,
        pattern: String,
        replacement: String,
        isRegex: Boolean,
        scope: String,
        kind: Int,
    ) -> Unit,
) {
    val moColors = LocalMoRealmColors.current
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var pattern by remember { mutableStateOf(initial?.pattern ?: "") }
    var replacement by remember { mutableStateOf(initial?.replacement ?: "") }
    var isRegex by remember { mutableStateOf(initial?.isRegex ?: false) }
    var scope by remember { mutableStateOf(initial?.scope ?: "") }
    var kind by remember { mutableStateOf(initial?.kind ?: defaultKind) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial != null) "编辑规则" else "添加替换规则") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // 分类切换 — SegmentedButton 比 Radio 占空间小且语义自洽。
                // 选中"净化"时下方 replacement 字段还可填，但语义上意味着"替换为该字符串后再清干净"，
                // 不强制清空，给高级用户保留灵活度。
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = kind == com.morealm.app.domain.entity.ReplaceRule.KIND_GENERAL,
                        onClick = { kind = com.morealm.app.domain.entity.ReplaceRule.KIND_GENERAL },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) { Text("替换") }
                    SegmentedButton(
                        selected = kind == com.morealm.app.domain.entity.ReplaceRule.KIND_PURIFY,
                        onClick = { kind = com.morealm.app.domain.entity.ReplaceRule.KIND_PURIFY },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) { Text("净化") }
                }
                Text(
                    if (kind == com.morealm.app.domain.entity.ReplaceRule.KIND_PURIFY)
                        "净化规则会先于替换执行，适合清除广告 / 版权声明 / 推广文字"
                    else
                        "替换规则在净化之后执行，适合错别字、专名修正",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
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
                    label = { Text(
                        if (kind == com.morealm.app.domain.entity.ReplaceRule.KIND_PURIFY)
                            "替换为（净化常用：留空 = 直接删除）"
                        else
                            "替换为（留空则删除匹配内容）"
                    ) },
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
                onClick = { if (pattern.isNotBlank()) onSave(name, pattern, replacement, isRegex, scope, kind) },
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
