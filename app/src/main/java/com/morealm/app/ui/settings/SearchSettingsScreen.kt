package com.morealm.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.morealm.app.presentation.settings.SearchSettingsViewModel
import kotlin.math.roundToInt

/**
 * 「搜索设置」页 — 控制全网搜索（搜索 tab + 换源对话框共用）的两个网络层参数：
 *   1. 并发搜索数量（Semaphore 上限）
 *   2. 单源超时（withTimeout 时长）
 *
 * 这两个参数先前是硬编码常量 (8 / 30s)，提到 prefs 是为了让用户能根据自己的
 * 网络/设备情况调节 —— 搜索路径的体感优化方向已经在 SearchViewModel 注释里。
 *
 * 设计说明：
 *  - 不用 dialog，直接在卡片里嵌 Slider。Slider 拖动过程实时落库，与
 *    阅读设置页的字号 Slider 风格一致。
 *  - 范围与默认值的 single source of truth 在 [com.morealm.app.domain.preference.AppPreferences]，
 *    页面只展示 + 写回；coerceIn 在 prefs 那一层兜底。
 *  - 「恢复默认」按钮放在底部，与其他设置页的「危险操作」位置约定一致。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchSettingsScreen(
    onBack: () -> Unit = {},
    viewModel: SearchSettingsViewModel = hiltViewModel(),
) {
    val parallelism by viewModel.parallelism.collectAsStateWithLifecycle()
    val timeoutSec by viewModel.timeoutSec.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("搜索设置") },
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
            SectionHeaderInline("搜索性能")

            // ── 并发搜索数量 ────────────────────────────────────────────
            SliderSettingRow(
                title = "并发搜索数量",
                value = parallelism,
                valueLabel = "$parallelism 个源",
                range = 1f..32f,
                steps = 30, // 1..32 含端点共 32 档，steps = 30
                hint = buildString {
                    append("同一时刻最多并行向多少个书源发起搜索请求。")
                    append("默认 16；弱网或低端设备可调小至 4–8 缓解卡顿；")
                    append("被反爬频繁拦截的书源也建议调小。")
                },
                onValueChange = { viewModel.setParallelism(it) },
            )

            SettingsDividerInline()

            // ── 单源超时 ────────────────────────────────────────────────
            SliderSettingRow(
                title = "单源搜索超时",
                value = timeoutSec,
                valueLabel = "${timeoutSec}s",
                range = 5f..120f,
                steps = 22, // 5..120 step 5 共 24 档，steps = 22
                hint = "单个书源响应超过此时长视为失败，跳过不影响其他源。" +
                    "默认 30 秒；网络抖动严重时可调到 60–90 秒。",
                onValueChange = { viewModel.setTimeoutSec(it) },
            )

            Spacer(Modifier.height(16.dp))

            // ── 恢复默认 ────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { viewModel.restoreDefaults() }) {
                    Icon(
                        Icons.Default.RestartAlt,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("恢复默认值")
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

// ── 局部 helpers ──────────────────────────────────────────────────────────
//
// 与 ReadingSettingsScreen 那边的同名 private 函数语义相同，但一两个 helper
// 各文件自带一份的代价比抽到 common 包要小（避免新增公共耦合点）。命名加
// `Inline` 后缀避免后续如果有人把它们 promote 到 common 时 import 撞名。

@Composable
private fun SectionHeaderInline(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun SettingsDividerInline() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/**
 * 整数 Slider 设置行。把当前值显示到右上角，主滑块下挂一段说明文案。
 *
 * @param value     当前值，主滑块的位置由它驱动
 * @param valueLabel 右上角显示的格式化文案（"16 个源" / "30s"）
 * @param range     Slider 的浮点范围；Slider 内部用 Float，外部传 Int 时记得 round
 * @param steps     除两端外的中间步数 = (max - min) / step - 1
 *                  例：1..32 step 1 → steps=30；5..120 step 5 → steps=22
 * @param hint      副文，bodySmall + 50% alpha 显示，承担说明义务
 * @param onValueChange 拖动期间立刻回调，由 ViewModel 写回 prefs（DataStore 节流）
 */
@Composable
private fun SliderSettingRow(
    title: String,
    value: Int,
    valueLabel: String,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    hint: String,
    onValueChange: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                valueLabel,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(4.dp))
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.roundToInt()) },
            valueRange = range,
            steps = steps,
        )
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
    }
}
