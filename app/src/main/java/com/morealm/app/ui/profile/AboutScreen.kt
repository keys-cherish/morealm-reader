package com.morealm.app.ui.profile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.morealm.app.BuildConfig
import com.morealm.app.ui.theme.LocalMoRealmColors

/**
 * QQ 交流群群号。**绝不**直接出现在源码 / commit 历史中。
 *
 * 实际值由 Gradle 在编译期注入到 [BuildConfig.QQ_GROUP_ID]，注入源（按优先级）：
 *   1. `-PqqGroupId=xxx` 命令行 / gradle.properties
 *   2. local.properties 的 `qqGroupId` 或 `qq.group.id`（local.properties 已 gitignore）
 *   3. 环境变量 `QQ_GROUP_ID`（CI/CD 用 GitHub Actions secrets 注入）
 *
 * 任何人 fork 公开仓库自己编译，看到的是空字符串 → UI 自动 fallback 到 "请联系作者获取"。
 * 维护者打 release 前在 local.properties 写一行 `qqGroupId=...` 即可。
 */
private val QQ_GROUP_ID: String = BuildConfig.QQ_GROUP_ID

/** UI 上是否显示具体群号；为空表示构建未配置群号。 */
private val hasGroupId: Boolean get() = QQ_GROUP_ID.isNotEmpty()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onNavigateChangelog: () -> Unit = {},
) {
    val moColors = LocalMoRealmColors.current
    val context = LocalContext.current
    // 复用同一个 lambda 避免每次重组重建（Card.onClick 接受稳定 lambda 体验更顺）。
    // 当群号未配置（开源 fork 编译场景）时点击仅 Toast 提示，不动剪贴板。
    val onCopyGroupId: () -> Unit = remember(context) {
        {
            if (hasGroupId) {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("QQ 群号", QQ_GROUP_ID))
                    Toast.makeText(context, "群号已复制，请打开 QQ 加群", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "复制失败，请手动记下群号 $QQ_GROUP_ID", Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(
                    context,
                    "本构建未配置群号，请联系作者或前往项目主页查看最新联系方式",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
    ) {
        TopAppBar(
            title = { Text("关于墨境", fontWeight = FontWeight.Bold) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
            ),
        )

        Spacer(Modifier.height(24.dp))

        // App icon + name
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // App icon placeholder
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("墨", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(16.dp))
            Text("墨境", fontSize = 24.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground)
            Text("MoRealm", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
            Spacer(Modifier.height(4.dp))
            Text("v${com.morealm.app.BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text(
                "作者：光坂镇",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
        }

        Spacer(Modifier.height(32.dp))

        // Description
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Text(
                "墨境是一款注重阅读体验的 Android 阅读器，支持 TXT、EPUB、PDF、MOBI 等多种格式。" +
                    "内置多套精心调校的主题配色，兼容 Legado 书源与主题格式，支持 WebDAV 云端同步。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                lineHeight = 24.sp,
                modifier = Modifier.padding(16.dp),
            )
        }

        Spacer(Modifier.height(16.dp))

        // Features
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("特性", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                FeatureRow(Icons.AutoMirrored.Filled.MenuBook, "多格式支持", "TXT / EPUB / PDF / MOBI")
                FeatureRow(Icons.Default.Palette, "主题系统", "6 套内置主题 + Legado 主题导入")
                FeatureRow(Icons.Default.Cloud, "WebDAV 同步", "进度 / 书架 / 书源 / 主题")
                FeatureRow(Icons.Default.RecordVoiceOver, "TTS 朗读", "系统 TTS + 书内控制面板")
                FeatureRow(Icons.Default.FolderOpen, "智能导入", "文件夹自动识别 + 批量导入")
                FeatureRow(Icons.Default.Extension, "Legado 兼容", "书源格式 + 一键搬家")
            }
        }

        Spacer(Modifier.height(16.dp))

        // Changelog entry
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            onClick = onNavigateChangelog,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.History,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "更新日志",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        "查看版本迭代记录",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Contact us entry —— QQ 交流群入口。
        // 整张 Card 可点击 = 复制群号到剪贴板。群号文本上故意"显示明文"是面向用户的，
        // 公开仓库的隐私防护点在源码层 (QQ_GROUP_ID 数组拼装) 而非运行时显示。
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            onClick = onCopyGroupId,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Forum,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "联系我们",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        if (hasGroupId) "QQ 交流群 $QQ_GROUP_ID · 点击复制群号"
                        else "请联系作者获取最新群号",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                }
                Icon(
                    if (hasGroupId) Icons.Default.ContentCopy else Icons.Default.Info,
                    null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        Text(
            "Made with ❤ for readers",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun FeatureRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    desc: String,
) {
    // 纯展示 ListItem —— containerColor=Transparent 因为外层卡片已经提供背景，
    // 不能再叠一层 surface 否则颜色会"过实"。
    ListItem(
        headlineContent = {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        },
        supportingContent = {
            Text(desc, style = MaterialTheme.typography.bodySmall)
        },
        leadingContent = {
            Icon(
                icon, null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
    )
}
