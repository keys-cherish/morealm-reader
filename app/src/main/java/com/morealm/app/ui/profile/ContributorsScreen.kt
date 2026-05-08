package com.morealm.app.ui.profile

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.morealm.app.domain.contributor.Contributor
import com.morealm.app.domain.contributor.ContributorLink
import com.morealm.app.domain.contributor.ContributorTag
import com.morealm.app.presentation.profile.ContributorsViewModel
import com.morealm.app.ui.widget.ThemedSnackbarHost
import kotlinx.coroutines.launch

/**
 * 贡献墙。展示 `assets/contributors.json` 解析出的全部贡献者。
 *
 * 设计原则：
 *  - 不绑死 GitHub —— 一个人可以同时挂 GitHub / 酷安 / QQ / TG / 邮箱多平台
 *  - 头像缺失走「首字母彩色圆」fallback，避免强迫人提供头像
 *  - QQ 等无 URL 的平台点击仅 Snackbar 提示 handle，绝不直接显示 QQ 号
 *  - 排序「按加入时间」而非字母 —— 中英文混排做拼音排序代价太高，时间序自然
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContributorsScreen(
    onBack: () -> Unit,
    viewModel: ContributorsViewModel = hiltViewModel(),
) {
    val contributors = viewModel.contributors.collectAsStateWithLifecycle().value
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    val onLinkClick: (ContributorLink) -> Unit = remember(context) {
        { link ->
            val url = link.url
            if (url.isNullOrBlank()) {
                // QQ 群名片这类没法跳转的，弹 Snackbar 显示 handle
                scope.launch {
                    snackbarHost.showSnackbar(
                        "${platformLabel(link.platform)}：${link.handle}",
                    )
                }
            } else {
                openExternal(context, url) { msg ->
                    scope.launch { snackbarHost.showSnackbar(msg) }
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
        ) {
            TopAppBar(
                title = { Text("贡献者", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                scrollBehavior = scrollBehavior,
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "header") { HeaderCard() }

                if (contributors.isEmpty()) {
                    item(key = "empty") { EmptyPlaceholder() }
                } else {
                    items(contributors, key = { it.id }) { c ->
                        ContributorCard(c, onLinkClick)
                    }
                }

                item(key = "footer-spacer") {
                    Spacer(Modifier.height(24.dp))
                }
            }
        }

        ThemedSnackbarHost(
            hostState = snackbarHost,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp),
        )
    }
}

@Composable
private fun HeaderCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "感谢每一位让墨境更好的人",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "按加入时间排序，不分先后。无论你是写代码、提 Issue、做设计，还是在 QQ 群和酷安里耐心反馈，都同样重要。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                lineHeight = 18.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "想加入？查看仓库根目录的 CONTRIBUTING.md",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }
    }
}

@Composable
private fun EmptyPlaceholder() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Text(
            "还没有贡献者记录。也许你会是第一个？",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(24.dp),
        )
    }
}

@Composable
private fun ContributorCard(
    c: Contributor,
    onLinkClick: (ContributorLink) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            ContributorAvatar(c, size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.fillMaxWidth()) {
                Text(
                    c.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (c.tags.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        c.tags.forEach { TagChip(it) }
                    }
                }
                if (c.contribution.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        c.contribution,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                        lineHeight = 18.sp,
                    )
                }
                if (c.links.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        c.links.forEach { link ->
                            IconButton(
                                onClick = { onLinkClick(link) },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    platformIcon(link.platform),
                                    contentDescription = "${platformLabel(link.platform)}：${link.handle}",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContributorAvatar(c: Contributor, size: Dp) {
    val initial = remember(c.name) {
        c.name.firstOrNull { !it.isWhitespace() }?.toString() ?: "?"
    }
    val containerColor = pickContainerColor(c.id)
    val onContainerColor = pickOnContainerColor(c.id)

    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(containerColor),
        contentAlignment = Alignment.Center,
    ) {
        if (!c.avatar.isNullOrBlank()) {
            AsyncImage(
                model = c.avatar,
                contentDescription = c.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                initial,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = onContainerColor,
            )
        }
    }
}

@Composable
private fun TagChip(tag: ContributorTag) {
    val accent = tagAccentColor(tag)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(accent.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            tag.name,
            style = MaterialTheme.typography.labelSmall,
            color = accent,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

// ── 配色与图标映射 ───────────────────────────────────────────────────────

@Composable
private fun pickContainerColor(id: String): Color = when (id.hashCode().mod(3)) {
    0 -> MaterialTheme.colorScheme.primaryContainer
    1 -> MaterialTheme.colorScheme.secondaryContainer
    else -> MaterialTheme.colorScheme.tertiaryContainer
}

@Composable
private fun pickOnContainerColor(id: String): Color = when (id.hashCode().mod(3)) {
    0 -> MaterialTheme.colorScheme.onPrimaryContainer
    1 -> MaterialTheme.colorScheme.onSecondaryContainer
    else -> MaterialTheme.colorScheme.onTertiaryContainer
}

@Composable
private fun tagAccentColor(tag: ContributorTag): Color = when (tag) {
    ContributorTag.Code -> MaterialTheme.colorScheme.primary
    ContributorTag.Design -> MaterialTheme.colorScheme.secondary
    ContributorTag.Issues -> MaterialTheme.colorScheme.tertiary
    ContributorTag.Community -> MaterialTheme.colorScheme.tertiary
    ContributorTag.Localization -> MaterialTheme.colorScheme.secondary
}

private fun platformIcon(platform: String): ImageVector = when (platform.lowercase()) {
    "github" -> Icons.Default.Code
    "coolapk" -> Icons.Default.Apps
    "qq" -> Icons.Default.Forum
    "telegram", "tg" -> Icons.AutoMirrored.Filled.Send
    "email", "mail" -> Icons.Default.Email
    else -> Icons.Default.Link
}

private fun platformLabel(platform: String): String = when (platform.lowercase()) {
    "github" -> "GitHub"
    "coolapk" -> "酷安"
    "qq" -> "QQ"
    "telegram", "tg" -> "Telegram"
    "email", "mail" -> "邮箱"
    else -> platform
}

private fun openExternal(context: Context, url: String, onError: (String) -> Unit) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        onError("未找到可打开此链接的应用")
    } catch (_: Exception) {
        onError("打开链接失败")
    }
}
