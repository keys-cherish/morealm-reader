package com.morealm.app.ui.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.morealm.app.domain.entity.TagDefinition
import com.morealm.app.domain.entity.TagType
import com.morealm.app.presentation.profile.AutoGroupRulesViewModel
import com.morealm.app.presentation.profile.MergeOptions
import com.morealm.app.presentation.profile.PendingImport
import com.morealm.app.presentation.profile.TagMergeStrategy

/**
 * "自动分组规则" — the only place users tune how books get auto-foldered.
 *
 * Layout, top to bottom:
 *   1. **Threshold slider** — how many books before a folder appears.
 *   2. **Tag list** — every GENRE / USER tag with its keyword list inline-editable.
 *      Built-in tags show 🔒 next to the name; their *keywords* are still editable
 *      (so users can teach 玄幻 their preferred sub-genres) but the name is locked
 *      to keep upgrade-time matching stable.
 *   3. **Ignored tags banner** — surfaces tags whose auto-folder the user
 *      previously deleted, with a one-tap "重新启用" button.
 *
 * Top-app-bar action: 📤 share the whole rule set as JSON. The share intent
 * triggers Android's native sheet, so it works for save-to-files, send via
 * email, post to chat — without us writing per-app integrations.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoGroupRulesScreen(
    onBack: () -> Unit,
    viewModel: AutoGroupRulesViewModel = hiltViewModel(),
) {
    val tags by viewModel.tags.collectAsStateWithLifecycle()
    val threshold by viewModel.threshold.collectAsStateWithLifecycle()
    val ignored by viewModel.ignored.collectAsStateWithLifecycle()
    val toast by viewModel.exportToast.collectAsStateWithLifecycle()
    val pendingImport by viewModel.pendingImport.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(toast) {
        toast?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeToast()
        }
    }

    // SAF picker for the import flow.  We accept JSON; some Android pickers
    // also need octet-stream / text/plain to surface .json files saved by
    // chat apps that mis-detect the MIME, so we whitelist all three.
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.importPreview(it) }
    }

    // ── Local UI state for "create new tag" sheet ──
    var showNewTagDialog by rememberSaveable { mutableStateOf(false) }
    if (showNewTagDialog) {
        NewUserTagDialog(
            onDismiss = { showNewTagDialog = false },
            onConfirm = { name, emoji, color, keywords ->
                viewModel.createUserTag(name, emoji, color, keywords)
                showNewTagDialog = false
            },
        )
    }

    // ── Pending import dialog (preview + merge options) ──
    pendingImport?.let { pending ->
        ImportMergeDialog(
            pending = pending,
            onDismiss = viewModel::cancelPendingImport,
            onConfirm = viewModel::importApply,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("自动分组规则", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    // Order matters: 导入 → 导出 → 新增. Reading left-to-right
                    // matches the "bring rules in / send rules out / make my own" flow.
                    IconButton(onClick = {
                        importLauncher.launch(
                            arrayOf("application/json", "application/octet-stream", "text/plain")
                        )
                    }) {
                        Icon(Icons.Default.Download, "导入规则",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                    }
                    IconButton(onClick = { viewModel.exportAndShare() }) {
                        Icon(Icons.Default.IosShare, "导出规则",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                    }
                    IconButton(onClick = { showNewTagDialog = true }) {
                        Icon(Icons.Default.Add, "新建标签",
                            tint = MaterialTheme.colorScheme.primary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        // 内置 GENRE 整段默认折叠 —— 大多数用户不会修改预设关键词，
        // 进屏先看自己创建的 USER 标签更直观。点击 section 头展开。
        var genreSectionExpanded by rememberSaveable { mutableStateOf(false) }
        val genreTags = tags.filter { it.type == TagType.GENRE }
        val userTags = tags.filter { it.type == TagType.USER }
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
        ) {
            item("intro") {
                IntroCard()
            }
            item("threshold") {
                ThresholdCard(
                    value = threshold,
                    onChange = viewModel::setThreshold,
                )
            }
            if (ignored.isNotEmpty()) {
                item("ignored") {
                    IgnoredCard(
                        ignoredIds = ignored,
                        tagLookup = tags,
                        onRestore = viewModel::unignoreTag,
                    )
                }
            }
            // ── 我的标签 (USER) ── 默认始终展开，用户的自定义最重要
            if (userTags.isNotEmpty()) {
                item("section_user") {
                    SectionTitle("我的标签 (${userTags.size})", "你创建的标签同样参与自动归类")
                }
                items(userTags, key = { it.id }) { tag ->
                    TagCard(
                        tag = tag,
                        onKeywordsChange = { viewModel.updateKeywords(tag, it) },
                        onRename = { viewModel.renameTag(tag, it) },
                        onDelete = { viewModel.deleteUserTag(tag) },
                    )
                }
            }
            // ── 题材标签 (GENRE 内置) ── 折叠 section，点击 header 展开
            item("section_genre") {
                CollapsibleSectionHeader(
                    title = "内置题材标签",
                    count = genreTags.size,
                    expanded = genreSectionExpanded,
                    onToggle = { genreSectionExpanded = !genreSectionExpanded },
                )
            }
            if (genreSectionExpanded) {
                items(genreTags, key = { it.id }) { tag ->
                    TagCard(
                        tag = tag,
                        onKeywordsChange = { viewModel.updateKeywords(tag, it) },
                        onRename = { viewModel.renameTag(tag, it) },
                        onDelete = null,  // 内置标签不可删
                    )
                }
            }
        }
    }
}

@Composable
private fun IntroCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("自动分组怎么工作？", fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            Text(
                "加入书架的书会按下方关键词命中题材标签。当某题材积累到阈值数量，会自动建立同名文件夹并把命中的书归入。\n手建文件夹永远不会被自动改动；删除自动文件夹后该题材会进入忽略列表。",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            )
        }
    }
}

@Composable
private fun ThresholdCard(value: Int, onChange: (Int) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("自动建组阈值", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text("$value 本", fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height(8.dp))
            Slider(
                value = value.toFloat(),
                onValueChange = { onChange(it.toInt()) },
                valueRange = 2f..10f,
                steps = 7,
            )
            Text(
                "命中同一题材的书数量达到该阈值时，自动创建文件夹",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
        }
    }
}

@Composable
private fun IgnoredCard(
    ignoredIds: Set<String>,
    tagLookup: List<TagDefinition>,
    onRestore: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("已忽略的题材", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "下列题材的自动文件夹已被你删除，不会再次创建",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            )
            Spacer(Modifier.height(8.dp))
            ignoredIds.forEach { id ->
                val tag = tagLookup.firstOrNull { it.id == id }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(tag?.icon ?: "🚫", fontSize = 16.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(tag?.name ?: id, modifier = Modifier.weight(1f), fontSize = 13.sp)
                    TextButton(onClick = { onRestore(id) }) { Text("重新启用") }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column(modifier = Modifier.padding(top = 8.dp, bottom = 2.dp, start = 4.dp)) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(subtitle, fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
    }
}

/**
 * 可折叠 section 标题：内置题材标签默认折叠，避免 30+ 卡片淹没主列表。
 * 整行可点 toggle expanded，右侧 chevron 直观提示状态。
 */
@Composable
private fun CollapsibleSectionHeader(
    title: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onToggle() }
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(
                if (expanded) "命中关键词时自动归入对应文件夹" else "默认折叠 — 点击展开后可调整内置关键词",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Text(
                "$count",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.width(6.dp))
        Icon(
            if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = if (expanded) "收起" else "展开",
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun TagCard(
    tag: TagDefinition,
    onKeywordsChange: (String) -> Unit,
    onRename: (String) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var keywords by remember(tag.id, tag.keywords) { mutableStateOf(tag.keywords) }
    var name by remember(tag.id, tag.name) { mutableStateOf(tag.name) }
    val keyboard = LocalSoftwareKeyboardController.current
    // 卡片默认折叠：只露 emoji + name + 关键词数量。点击行展开后才显示输入框，
    // 把 30+ 内置标签的视觉密度从「整屏输入框」降到「整屏小行」，需要修
    // 改时再单点展开。展开状态用 rememberSaveable 跟随 tagId 持久化，列表
    // 重组不会重置。
    var expanded by rememberSaveable(tag.id) { mutableStateOf(false) }
    var showDeleteConfirm by rememberSaveable(tag.id) { mutableStateOf(false) }
    val keywordCount = remember(keywords) {
        keywords.split(',', '，', ';', '；', '\n', '|', '/', '、', ' ')
            .count { it.isNotBlank() }
    }
    val hasUnsaved = keywords != tag.keywords || (!tag.builtin && name != tag.name)

    if (showDeleteConfirm && onDelete != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除标签 \"${tag.name}\"？") },
            text = {
                Text(
                    "删除后该标签的关键词与命中关系都会清空，已经被该标签自动归类的书会回到根目录。",
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete()
                        showDeleteConfirm = false
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            },
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            // ── Header（始终可见）── 点击切换展开
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!tag.icon.isNullOrBlank()) {
                    Text(tag.icon, fontSize = 18.sp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    tag.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f),
                )
                // 关键词数量徽章 —— 一眼判断该标签当前命中能力
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        "$keywordCount 个",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.width(6.dp))
                if (hasUnsaved) {
                    // 未保存提示点 —— 折叠状态也能看到「这张卡有改动」
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.error),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.size(20.dp),
                )
            }

            // ── 展开后的编辑区 ──
            if (expanded) {
                Spacer(Modifier.height(10.dp))
                if (!tag.builtin) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        singleLine = true,
                        label = { Text("标签名", fontSize = 11.sp) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    )
                    Spacer(Modifier.height(8.dp))
                } else {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    ) {
                        Text(
                            "内置标签 · 名称不可修改",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = keywords,
                    onValueChange = { keywords = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("关键词（用逗号 / 顿号 / 空格分隔）") },
                    placeholder = { Text("如：玄幻,魔法,异界,斗气", fontSize = 12.sp) },
                    textStyle = MaterialTheme.typography.bodySmall,
                    minLines = 2,
                    maxLines = 4,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 仅 USER 标签暴露删除入口（onDelete 非 null）。放最左，与右侧
                    // 操作按钮拉开距离 —— 误触代价大的操作不应该跟「保存」相邻。
                    if (onDelete != null) {
                        TextButton(
                            onClick = { showDeleteConfirm = true },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("删除")
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    if (!tag.builtin && name != tag.name) {
                        TextButton(onClick = {
                            onRename(name)
                            keyboard?.hide()
                        }) { Text("重命名") }
                        Spacer(Modifier.width(8.dp))
                    }
                    Button(
                        onClick = {
                            onKeywordsChange(keywords)
                            keyboard?.hide()
                        },
                        enabled = keywords != tag.keywords,
                    ) { Text("保存关键词") }
                }
            }
        }
    }
}

/**
 * "新建标签" 对话框 —— 收集 name (必填) / emoji / color / keywords (可选)，
 * 提交给 ViewModel.createUserTag。
 *
 * 设计要点：
 *  - **name 必填**：trim 后为空则禁用确认按钮，配合 supportingText 给即时反馈。
 *  - **emoji 单字符场景多**：用 OutlinedTextField + KeyboardType.Text，
 *    不强约束输入长度（用户可能粘 "📚🐉" 这种组合 emoji，强行裁会破坏 ZWJ 序列）。
 *  - **color 用 hex 文本输入**：暂不带取色器（依赖外部库），placeholder 给样例。
 *    输入后 ViewModel 会保存原值；显示时由 [TagDefinition.color] 走主题降级路径。
 *  - **keywords 选填**：用户可以创建空标签后回主屏，再用 TagCard 填关键词；
 *    把"创建"和"调教"分开，避免一开始就给一堆字段。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewUserTagDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, emoji: String?, color: String?, keywords: String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var emoji by rememberSaveable { mutableStateOf("") }
    var color by rememberSaveable { mutableStateOf("") }
    var keywords by rememberSaveable { mutableStateOf("") }
    val canConfirm = name.trim().isNotEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建标签") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("标签名") },
                    placeholder = { Text("例如：修真 / 都市种田", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = {
                        if (name.trim().isEmpty()) Text("必填", fontSize = 11.sp)
                    },
                    isError = name.isNotEmpty() && name.trim().isEmpty(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = emoji,
                        onValueChange = { emoji = it },
                        label = { Text("Emoji") },
                        placeholder = { Text("🐉", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = color,
                        onValueChange = { color = it },
                        label = { Text("颜色 #") },
                        placeholder = { Text("#FF6B6B", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1.4f),
                    )
                }
                OutlinedTextField(
                    value = keywords,
                    onValueChange = { keywords = it },
                    label = { Text("关键词（可选）") },
                    placeholder = { Text("玄幻,魔法,异界", fontSize = 12.sp) },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        name,
                        emoji.takeIf { it.isNotBlank() },
                        color.takeIf { it.isNotBlank() },
                        keywords,
                    )
                },
                enabled = canConfirm,
            ) { Text("创建") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * 导入合并策略对话框 —— 在文件解析成功后弹出，让用户在落库前选择策略。
 *
 * 三栏布局，从上到下：
 *  1. **预览头**：metadata.name / author / 描述 + 标签数。让用户清楚 to-be-imported
 *     是什么内容，避免"看到一大堆勾选项不知道在选啥"。
 *  2. **标签合并策略 RadioGroup**：MERGE_KEYWORDS（默认） / OVERWRITE / APPEND_NEW_ONLY。
 *  3. **同步开关 Checkbox**：阈值 / 忽略列表 —— 这俩是「全局状态」，默认不勾。
 *
 * 关闭对话框两条路径：cancel（保留 pendingImport=null 让 VM 清状态）/ confirm（应用）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportMergeDialog(
    pending: PendingImport,
    onDismiss: () -> Unit,
    onConfirm: (MergeOptions) -> Unit,
) {
    var strategy by rememberSaveable { mutableStateOf(TagMergeStrategy.MERGE_KEYWORDS) }
    var syncThreshold by rememberSaveable { mutableStateOf(false) }
    var syncIgnored by rememberSaveable { mutableStateOf(false) }
    val snap = pending.snapshot
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("导入规则", fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, "关闭", modifier = Modifier.size(18.dp))
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // 预览头：metadata 优先，缺字段时显示 fallback。
                Card(
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                    ),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            snap.metadata?.name ?: "未命名规则集",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                        )
                        snap.metadata?.author?.let {
                            Text("作者：$it", fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                        }
                        snap.metadata?.description?.let {
                            Spacer(Modifier.height(4.dp))
                            Text(it, fontSize = 12.sp, lineHeight = 16.sp)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "${snap.tags.size} 个标签 · 阈值 ${snap.threshold} · 忽略 ${snap.ignoredTags.size}" +
                                (snap.metadata?.appVersion?.let { " · 来自 $it" } ?: ""),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                }
                // 策略选择
                Text("标签冲突如何处理", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                StrategyOption(
                    label = "合并关键词（推荐）",
                    desc = "保留你已有的关键词，把规则集的关键词追加进来",
                    selected = strategy == TagMergeStrategy.MERGE_KEYWORDS,
                    onSelect = { strategy = TagMergeStrategy.MERGE_KEYWORDS },
                )
                StrategyOption(
                    label = "覆盖同 id 标签",
                    desc = "用规则集的内容替换你本地的同 id 标签",
                    selected = strategy == TagMergeStrategy.OVERWRITE,
                    onSelect = { strategy = TagMergeStrategy.OVERWRITE },
                )
                StrategyOption(
                    label = "仅追加新标签",
                    desc = "已存在的标签一字不动，只插入本地没有的",
                    selected = strategy == TagMergeStrategy.APPEND_NEW_ONLY,
                    onSelect = { strategy = TagMergeStrategy.APPEND_NEW_ONLY },
                )
                // 全局状态同步开关（默认关闭，提示性放置在最后）
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { syncThreshold = !syncThreshold }) {
                    Checkbox(checked = syncThreshold, onCheckedChange = { syncThreshold = it })
                    Column {
                        Text("同步阈值（${snap.threshold}）", fontSize = 13.sp)
                        Text("覆盖你当前的自动建组阈值",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { syncIgnored = !syncIgnored }) {
                    Checkbox(checked = syncIgnored, onCheckedChange = { syncIgnored = it })
                    Column {
                        Text("同步忽略列表", fontSize = 13.sp)
                        Text("用规则集的忽略集替换本地（${snap.ignoredTags.size} 项）",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(
                    MergeOptions(
                        tagStrategy = strategy,
                        syncThreshold = syncThreshold,
                        syncIgnoredTags = syncIgnored,
                    )
                )
            }) { Text("确认导入") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun StrategyOption(
    label: String,
    desc: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onSelect() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(
                desc,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}
