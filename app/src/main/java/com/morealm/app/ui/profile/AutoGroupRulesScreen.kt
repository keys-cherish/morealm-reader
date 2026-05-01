package com.morealm.app.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(toast) {
        toast?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeToast()
        }
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
                    IconButton(onClick = { viewModel.exportAndShare() }) {
                        Icon(Icons.Default.IosShare, "导出规则")
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
            item("section_genre") {
                SectionTitle("题材标签", "命中关键词时自动归入对应文件夹")
            }
            items(tags.filter { it.type == TagType.GENRE }, key = { it.id }) { tag ->
                TagCard(
                    tag = tag,
                    onKeywordsChange = { viewModel.updateKeywords(tag, it) },
                    onRename = { viewModel.renameTag(tag, it) },
                )
            }
            val userTags = tags.filter { it.type == TagType.USER }
            if (userTags.isNotEmpty()) {
                item("section_user") {
                    SectionTitle("我的标签", "你创建的标签同样参与自动归类")
                }
                items(userTags, key = { it.id }) { tag ->
                    TagCard(
                        tag = tag,
                        onKeywordsChange = { viewModel.updateKeywords(tag, it) },
                        onRename = { viewModel.renameTag(tag, it) },
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

@Composable
private fun TagCard(
    tag: TagDefinition,
    onKeywordsChange: (String) -> Unit,
    onRename: (String) -> Unit,
) {
    var keywords by remember(tag.id, tag.keywords) { mutableStateOf(tag.keywords) }
    var name by remember(tag.id, tag.name) { mutableStateOf(tag.name) }
    val keyboard = LocalSoftwareKeyboardController.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header: emoji + name (editable iff USER) + builtin lock badge
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!tag.icon.isNullOrBlank()) {
                    Text(tag.icon, fontSize = 18.sp)
                    Spacer(Modifier.width(8.dp))
                }
                if (tag.builtin) {
                    Text(
                        tag.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    ) {
                        Text("内置", fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                    }
                } else {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        singleLine = true,
                        label = { Text("标签名", fontSize = 11.sp) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = keywords,
                onValueChange = { keywords = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("关键词（用逗号 / 顿号 / 空格分隔）") },
                placeholder = { Text("如：玄幻,魔法,异界,斗气", fontSize = 12.sp) },
                textStyle = MaterialTheme.typography.bodySmall,
                minLines = 2,
                maxLines = 4,
                supportingText = {
                    val n = keywords.split(',', '，', ';', '；', '\n', '|', '/', '、', ' ')
                        .count { it.isNotBlank() }
                    Text("$n 个关键词", fontSize = 10.sp)
                },
            )
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
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
