package com.morealm.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.morealm.app.domain.render.color.RuleColorCategory
import com.morealm.app.domain.render.color.RuleColorPalette
import com.morealm.app.presentation.settings.RuleColorViewModel
import com.morealm.app.ui.theme.LocalMoRealmColors

/**
 * 「文字上色」设置子页。Phase 1：总开关骨架；Phase 3：调色板（6 类别色，明 / 暗各一套，
 * 单色 hex / 预设网格覆盖 + 恢复默认）。Phase 2 用户高亮词管理后续填入下方占位块。
 *
 * 视觉沿用阅读设置页（SectionHeader + 卡片）。颜色选择行参考 ThemeEditorScreen 的色块 +
 * hex 输入 + 预设网格模式（不跨文件复用其 private helper，本文件自带精简版）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleColorScreen(
    onBack: () -> Unit = {},
    viewModel: RuleColorViewModel = hiltViewModel(),
) {
    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
    val lightOverrides by viewModel.lightOverrides.collectAsStateWithLifecycle()
    val darkOverrides by viewModel.darkOverrides.collectAsStateWithLifecycle()

    // 用户调的是「当前生效主题」下的色：明主题改明色、夜主题改夜色（与阅读时所见一致）。
    val isNight = LocalMoRealmColors.current.isNight
    val overrides = if (isNight) darkOverrides else lightOverrides
    var expandedCat by remember { mutableStateOf<RuleColorCategory?>(null) }
    val highlightWords by viewModel.highlightWords.collectAsStateWithLifecycle()
    val hlColors = RuleColorPalette.highlightColors(isNight)
    var newWord by remember { mutableStateOf("") }
    var newColorIdx by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("文字上色") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                "按规则给正文自动着色：标点、数字、字母、特殊符号，以及引号内、括号内的文字，" +
                    "提升阅读时的视觉层次。纯本地规则，无需联网。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp),
            )

            SectionHeader("总开关")
            SettingsCard {
                ToggleRow(
                    title = "正文规则上色",
                    subtitle = "关闭后正文按主题默认色显示",
                    checked = enabled,
                    onCheckedChange = { viewModel.setEnabled(it) },
                )
            }

            SectionHeader("调色板（${if (isNight) "夜间" else "日间"}主题）")
            SettingsCard {
                RuleColorPalette.EDITABLE_CATEGORIES.forEach { cat ->
                    val argb = overrides[cat] ?: RuleColorPalette.defaultColor(cat, isNight)
                    ColorPickRow(
                        label = categoryLabel(cat),
                        argb = argb,
                        overridden = overrides[cat] != null,
                        expanded = expandedCat == cat,
                        onToggle = { expandedCat = if (expandedCat == cat) null else cat },
                        onPick = { viewModel.setColor(cat, isNight, it) },
                        onReset = { viewModel.resetColor(cat, isNight) },
                    )
                }
            }
            TextButton(
                onClick = { viewModel.resetAll(isNight) },
                modifier = Modifier.padding(start = 16.dp, top = 4.dp),
            ) { Text("全部恢复默认（${if (isNight) "夜间" else "日间"}）") }

            SectionHeader("高亮词")
            SettingsCard {
                if (highlightWords.isEmpty()) {
                    Text(
                        "还没有高亮词。添加后，正文每次出现该词都会自动着色（长词优先）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                } else {
                    highlightWords.forEach { hw ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .size(20.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(hlColors[hw.colorIndex % hlColors.size])),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                hw.word,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { viewModel.deleteHighlightWord(hw.id) }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    "删除",
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                )
                            }
                        }
                    }
                }
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    OutlinedTextField(
                        value = newWord,
                        onValueChange = { newWord = it },
                        label = { Text("新高亮词") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "颜色",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        hlColors.forEachIndexed { idx, c ->
                            Box(
                                Modifier
                                    .size(28.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(c))
                                    .then(
                                        if (idx == newColorIdx) {
                                            Modifier.border(
                                                2.dp,
                                                MaterialTheme.colorScheme.onSurface,
                                                RoundedCornerShape(8.dp),
                                            )
                                        } else {
                                            Modifier
                                        },
                                    )
                                    .clickable { newColorIdx = idx },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(
                        onClick = { viewModel.addHighlightWord(newWord, newColorIdx); newWord = "" },
                        enabled = newWord.isNotBlank(),
                    ) { Text("添加高亮词") }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

/** 类别中文名（NONE 不展示，不在 EDITABLE_CATEGORIES）。 */
private fun categoryLabel(c: RuleColorCategory): String = when (c) {
    RuleColorCategory.PUNCTUATION -> "标点"
    RuleColorCategory.NUMBER -> "数字"
    RuleColorCategory.LATIN -> "字母"
    RuleColorCategory.SPECIAL -> "特殊符号"
    RuleColorCategory.QUOTE_INNER -> "引号内"
    RuleColorCategory.BRACKET_INNER -> "括号内"
    RuleColorCategory.NONE -> ""
}

/** 调色板预设色（ARGB）；6/行，覆盖 6 类别明暗默认 + 常用阅读色。 */
private val PRESET_COLORS: List<Int> = listOf(
    0xFF267F99, 0xFF4EC9B0, 0xFFA31515, 0xFFCE9178, 0xFF001080, 0xFF9CDCFE,
    0xFF795E26, 0xFFDCDCAA, 0xFFAF00DB, 0xFFC586C0, 0xFFF56C6C, 0xFF569CD6,
    0xFFCC0000, 0xFF009933, 0xFF0033CC, 0xFFCC6600, 0xFF333333, 0xFFBBBBBB,
).map { it.toInt() }

private fun hex6(argb: Int): String = "%06X".format(argb and 0xFFFFFF)

@Composable
private fun ColorPickRow(
    label: String,
    argb: Int,
    overridden: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onPick: (Int) -> Unit,
    onReset: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(argb)),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (overridden) {
                Text(
                    "已自定义",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                "#${hex6(argb)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }
        if (expanded) {
            // Hex 输入
            var hexInput by remember(argb) { mutableStateOf(hex6(argb)) }
            OutlinedTextField(
                value = hexInput,
                onValueChange = { v ->
                    val clean = v.replace("#", "").take(6)
                    hexInput = clean
                    if (clean.length == 6 && clean.all { it in "0123456789abcdefABCDEF" }) {
                        ("FF$clean".toLongOrNull(16))?.let { onPick(it.toInt()) }
                    }
                },
                label = { Text("Hex 色值") },
                prefix = { Text("#") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
            // 预设网格 6/行
            PRESET_COLORS.chunked(6).forEach { rowColors ->
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    rowColors.forEach { c ->
                        Box(
                            Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(c))
                                .clickable { onPick(c); hexInput = hex6(c) },
                        )
                    }
                }
            }
            if (overridden) {
                TextButton(onClick = onReset, modifier = Modifier.padding(bottom = 4.dp)) {
                    Text("恢复默认", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        modifier = Modifier.padding(start = 24.dp, end = 16.dp, top = 18.dp, bottom = 8.dp),
    )
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 4.dp),
            content = content,
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    checked: Boolean,
    subtitle: String? = null,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                checkedThumbColor = Color.White,
            ),
        )
    }
}

/** 后续版本功能的占位卡片：灰字说明 + 禁用的「敬请期待」chip。 */
@Composable
private fun PlaceholderCard(text: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            AssistChip(
                onClick = {},
                enabled = false,
                label = { Text("敬请期待") },
            )
        }
    }
}
