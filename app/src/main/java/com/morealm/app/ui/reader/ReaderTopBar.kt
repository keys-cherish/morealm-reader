package com.morealm.app.ui.reader

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.filled.*
// ── 阅读控制栏底部按钮线性矢量图标 (Image 16 设计) ────────────────────
// 全部走 Outlined 系列让底栏视觉更轻、更现代；TTS 按钮从「人 + 声波」
// (RecordVoiceOver) 换成传统麦克风形状 (Mic)，和大多数音频/朗读类 App 对齐。
// 注意：Icons.Outlined.* 与 Icons.Default.* (= Icons.Filled.*) 是扩展属性，
// 不能用 `import ... as Alias` 重命名当独立标识符用——只能用完整路径
// `Icons.Outlined.Xxx`。这里只 import 包让属性可被解析；引用时写完整路径。
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.pointer.pointerInput
import com.morealm.app.domain.entity.ReaderTool
import com.morealm.app.domain.entity.ReaderToolLayout
import com.morealm.app.domain.entity.ReaderToolZone
import com.morealm.app.ui.reader.toolbar.icon
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.documentfile.provider.DocumentFile
import com.morealm.app.domain.entity.ThemeEntity
import com.morealm.app.ui.theme.LocalMoRealmColors
import com.morealm.app.ui.theme.toComposeColor
import com.morealm.app.presentation.reader.PageTurnMode
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.snapshotFlow
import coil.compose.AsyncImage

// ── Top Bar ─────────────────────────────────────────────

@Composable
fun ReaderTopBar(
    bookTitle: String,
    onBack: () -> Unit,
    onExport: () -> Unit = {},
    onBookmark: () -> Unit = {},
    /** 顶栏「书签列表」按钮 — 打开书签面板（与「添加书签」相邻，#2 反馈）。 */
    onBookmarkList: () -> Unit = {},
    /**
     * #5：「当前章生效规则」按钮 — 反馈认为属于低频操作，已收纳到右侧 ⋮ 溢出菜单，
     * 不再占顶栏主行图标位。保留 callback 以兼容现有调用方。
     */
    onEffectiveReplaces: () -> Unit = {},
    /** 顶栏更多菜单中的全文搜索入口。 */
    onSearch: () -> Unit = {},
    /** 顶栏更多菜单中的快速替换/净化入口。 */
    onReplace: () -> Unit = {},
    onMenuVisibilityChanged: (Boolean) -> Unit = {},
    onHandwriting: () -> Unit = {},
    onHandwritingLibrary: () -> Unit = {},
    /** 顶栏「阅读设置」— 打开底部设置面板，方便用户从右上角快速进入。 */
    onSettings: () -> Unit = {},
) {
    val moColors = LocalMoRealmColors.current
    var showMenu by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.95f),
    ) {
        Row(
            modifier = Modifier
                // ── 顶部 inset 合并：statusBars + displayCutout ──
                //
                // 之前只 only(displayCutout) → showStatusBar=true 时状态栏会盖住第一行
                // 文字（"返回总纲"被电池图标压一截）。现在并进 statusBars 让 ReaderTopBar
                // 自动避让状态栏；showStatusBar=false（沉浸模式）时 statusBars inset
                // 系统会自动归零，TopBar 自然贴到屏幕极顶——与「让 reader 路由抵消
                // Scaffold innerPadding」配合，组成完整的「reader 全屏沉浸」体验。
                .windowInsetsPadding(
                    WindowInsets.statusBars
                        .union(WindowInsets.displayCutout)
                        .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                )
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.semantics {
                    contentDescription = "返回书架"
                    role = Role.Button
                },
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回",
                    tint = MaterialTheme.colorScheme.onSurface)
            }
            Text(
                bookTitle,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // #2：添加书签 + 书签列表 相邻放置，避免「添加在顶栏，查看在底部章节面板」
            // 两端跑的体感问题。
            IconButton(
                onClick = onBookmark,
                modifier = Modifier.semantics {
                    contentDescription = "添加书签"
                    role = Role.Button
                },
            ) {
                Icon(Icons.Default.BookmarkAdd, "添加书签",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp))
            }
            IconButton(
                onClick = onBookmarkList,
                modifier = Modifier.semantics {
                    contentDescription = "书签列表"
                    role = Role.Button
                },
            ) {
                Icon(Icons.Default.Bookmarks, "书签列表",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp))
            }
            IconButton(
                onClick = onSettings,
                modifier = Modifier.semantics {
                    contentDescription = "阅读设置"
                    role = Role.Button
                },
            ) {
                Icon(Icons.Default.Settings, "设置",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp))
            }
            Box {
                IconButton(
                    onClick = {
                        showMenu = true
                        onMenuVisibilityChanged(true)
                    },
                    modifier = Modifier.semantics {
                        contentDescription = "更多操作"
                        role = Role.Button
                    },
                ) {
                    Icon(Icons.Default.MoreVert, "更多",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp))
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = {
                    showMenu = false
                    onMenuVisibilityChanged(false)
                }) {
                    DropdownMenuItem(
                        text = { Text("搜索") },
                        leadingIcon = { Icon(Icons.Outlined.Search, null) },
                        onClick = { showMenu = false; onMenuVisibilityChanged(false); onSearch() },
                    )
                    DropdownMenuItem(
                        text = { Text("替换/净化") },
                        leadingIcon = { Icon(Icons.Default.FindReplace, null) },
                        onClick = { showMenu = false; onMenuVisibilityChanged(false); onReplace() },
                    )
                    // #5：低频「当前生效规则」收纳到溢出菜单
                    DropdownMenuItem(
                        text = { Text("当前生效规则") },
                        leadingIcon = { Icon(Icons.Default.FilterAlt, null) },
                        onClick = { showMenu = false; onMenuVisibilityChanged(false); onEffectiveReplaces() },
                    )
                    DropdownMenuItem(
                        text = { Text("手写批注") },
                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                        onClick = { showMenu = false; onMenuVisibilityChanged(false); onHandwriting() },
                    )
                    DropdownMenuItem(
                        text = { Text("批注列表") },
                        leadingIcon = { Icon(Icons.Default.List, null) },
                        onClick = { showMenu = false; onMenuVisibilityChanged(false); onHandwritingLibrary() },
                    )
                    DropdownMenuItem(
                        text = { Text("导出为 TXT") },
                        leadingIcon = { Icon(Icons.Default.FileDownload, null) },
                        onClick = { showMenu = false; onMenuVisibilityChanged(false); onExport() },
                    )
                }
            }
        }
    }
}
