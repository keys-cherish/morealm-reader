package com.morealm.app.ui.source

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.morealm.app.core.error.ErrorMessages
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.entity.BookSource
import com.morealm.app.domain.webbook.CheckSource
import com.morealm.app.presentation.source.BookSourceManageViewModel
import com.morealm.app.presentation.source.SourceSortKey
import com.morealm.app.presentation.source.sortedBySourceKey
import com.morealm.app.ui.theme.LocalMoRealmColors
import com.morealm.app.ui.widget.swipeBackEdge

@Composable
internal fun SourceItem(
    source: BookSource,
    /**
     * 单独提取的 enabled boolean 参数 —— 修用户反馈"toggle 不实时生效"。
     *
     * 根因（log 21:31:50 实锤）：source: BookSource 是 data class with 几十个字段，
     * Compose strong skipping 把 SourceItem 看作 stable function。后续 source 参数变化
     * 时（仅 enabled 字段从 true→false），Compose 比较 source.equals(prevSource)，
     * data class equals 比较所有字段后返 false，本应 recompose。
     *
     * 但日志显示 SourceItem render log 只触发 1 次，后续 toggle 多次都没再触发 ——
     * Compose 实际跳过了 SourceItem。可能与 LazyColumn item slot 复用 + Compose
     * skipping policy 内部对"嵌套 data class 字段"的稳定性判断有关。
     *
     * 修：把 source.enabled 单独提取为 Boolean 参数（primitive stable），Compose 100%
     * 识别参数变化触发重组。SourceItem 内 Switch.checked 用此参数而非 source.enabled。
     */
    enabled: Boolean,
    checkResult: CheckSource.CheckResult? = null,
    isLoggedIn: Boolean = false,
    selected: Boolean = false,
    selectionMode: Boolean = false,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onLogin: () -> Unit = {},
    onLogout: () -> Unit = {},
    onLongPress: (() -> Unit)? = null,
) {
    val moColors = LocalMoRealmColors.current
    var showMenu by remember { mutableStateOf(false) }
    // 进入多选态时立即关闭已经打开的菜单，避免"点菜单弹出后紧跟长按进多选"残留
    LaunchedEffect(selectionMode) { if (selectionMode) showMenu = false }
    // source.enabled 已经是 ViewModel 层 combine(rawSources, _toggleOverlay) 派生的
    // **原子合并**值——overlay 写入和真值更新在同一 emission 内一起到达 UI，无需 UI 端
    // 再做 enabledOverride fallback（之前的 UI fallback 因 onEach 改 overlay 与 stateIn
    // emit 跨 transaction 引发 Compose snapshot race，日志 191200 实锤）。
    //
    // [DIAG SourceToggle 2026-05-18] composable 函数体顶部 immediate log（每次 recompose
    // 都跑，跟之前 LaunchedEffect(source.enabled) 不一样—— LaunchedEffect 只在 enabled
    // 变化时触发，掩盖了"Compose 根本没重 invoke SourceItem"的情况）。
    // 同时打 enabled 参数实际值 + source.enabled 字段值 + 是否相等，定位是哪一层 stale。
    com.morealm.app.core.log.AppLog.info(
        "SourceToggleDiag",
        "SourceItem CALL url=...${source.bookSourceUrl.takeLast(20)} param.enabled=$enabled" +
            " source.enabled=${source.enabled} same=${enabled == source.enabled}",
    )
    val enabledColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val disabledColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    val bgColorAnim = animateColorAsState(
        targetValue = if (source.enabled) enabledColor else disabledColor,
        label = "sourceBg"
    )
    val shape = MaterialTheme.shapes.medium
    val primary = MaterialTheme.colorScheme.primary

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .drawBehind { drawRect(bgColorAnim.value) }
            .then(
                if (selected) Modifier.border(2.dp, primary, shape) else Modifier
            ),
    ) {
        Row(
            modifier = Modifier
                .combinedClickable(
                    onClick = {
                        if (selectionMode) onLongPress?.invoke()
                        else showMenu = true
                    },
                    onLongClick = { onLongPress?.invoke() },
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectionMode) {
                Checkbox(checked = selected, onCheckedChange = { onLongPress?.invoke() })
                Spacer(Modifier.width(8.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        source.bookSourceName.ifBlank { source.bookSourceUrl },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (source.enabled) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (checkResult != null) {
                        Spacer(Modifier.width(6.dp))
                        val scoreColor = when {
                            checkResult.score >= 4 -> MaterialTheme.colorScheme.tertiary
                            checkResult.score >= 2 -> MaterialTheme.colorScheme.secondary
                            else -> MaterialTheme.colorScheme.error
                        }
                        Text(
                            "${checkResult.score}/4",
                            style = MaterialTheme.typography.labelSmall,
                            color = scoreColor,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Row {
                    if (!source.bookSourceGroup.isNullOrBlank()) {
                        Text(
                            source.bookSourceGroup!!,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        source.bookSourceUrl,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // Show check error if present
                if (checkResult?.error != null) {
                    Text(
                        checkResult.error!!,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // [DIAG SourceToggle 2026-05-18] Switch checked= 处 .also 日志。
            // 这是「UI 真假」最后一公里：composable 顶层日志可能因 stale closure / 参数
            // 没传到这里，所以在 Switch checked 实际取值时再打一次最终 boolean。
            // 若 SourceItem CALL 出新 enabled 但 Switch CHECKED= 还是旧值 → 参数传递断了；
            // 若 Switch CHECKED= 出新值但视觉仍旧 → Compose Switch 内部 stale。
            Switch(
                checked = enabled.also {
                    com.morealm.app.core.log.AppLog.info(
                        "SourceToggleDiag",
                        "Switch CHECKED= url=...${source.bookSourceUrl.takeLast(20)} actual=$it",
                    )
                },
                onCheckedChange = if (selectionMode) null else { { onToggle() } },
                enabled = !selectionMode,
                colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.padding(start = 8.dp),
            )
            // 登录状态 chip —— 仅当源配了 loginUrl 才显示。
            //
            // 为什么从 IconButton 改为 AssistChip：
            //  - 原实现是一把 Lock/LockOpen 纯图标按钮，挤在 "开关 + 编辑 + 删除" 中间，
            //    图形对比度低、用户认不出这是"登录"入口（设计走查里用户反馈："看不出小
            //    锁的作用是登录"）。
            //  - AssistChip 带文字标签"登录"/"已登录"，语义直白；未登录时 outlined 高对比，
            //    已登录时带 ✓ + primary tint 次要化（对齐 M3 状态 chip 用法）。
            //  - selectionMode 下整个 chip 禁用，避免多选中误触发登录流程。
            if (!source.loginUrl.isNullOrBlank()) {
                Spacer(Modifier.width(6.dp))
                if (isLoggedIn) {
                    AssistChip(
                        onClick = { if (!selectionMode) onLogout() },
                        enabled = !selectionMode,
                        label = { Text("已登录", style = MaterialTheme.typography.labelSmall) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(AssistChipDefaults.IconSize),
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            leadingIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                        border = null,
                    )
                } else {
                    AssistChip(
                        onClick = { if (!selectionMode) onLogin() },
                        enabled = !selectionMode,
                        label = { Text("登录", style = MaterialTheme.typography.labelSmall) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.LockOpen,
                                contentDescription = null,
                                modifier = Modifier.size(AssistChipDefaults.IconSize),
                            )
                        },
                    )
                }
            }
            // 编辑 / 删除收进 overflow —— 之前它们各自占一个 32dp IconButton，和"登录"
            // 并排在行尾，视觉密度过高且和低频场景错配（删除是很低频操作）。改为
            // MoreVert + DropdownMenu：行点击也展开同款菜单，长按进多选，语义稳定。
            Spacer(Modifier.width(4.dp))
            Box {
                IconButton(
                    onClick = { if (!selectionMode) showMenu = true },
                    enabled = !selectionMode,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "更多",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.size(18.dp),
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("编辑") },
                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                        onClick = {
                            showMenu = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete,
                                null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            showMenu = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}
// ─── 分组功能 ────────────────────────────────────────────────────────────
//
// 这一段在文件尾部独立成块：
//   1. SourceGroupMode 与 BookSourceManageScreen 强耦合（key 字符串和 AppPreferences
//      约定一一对应），不值得抽到 domain 层；
//   2. 与现有 SourceItem 一样属于"内部组件"，对外不暴露；
//   3. 顺序上把 enum/helpers 放在 composable 之前，让阅读时先看到状态模型。
