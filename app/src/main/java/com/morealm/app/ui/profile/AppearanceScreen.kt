package com.morealm.app.ui.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.morealm.app.presentation.appearance.GlobalBgViewModel

/**
 * 「外观」独立页面：全局背景图 + 背景图透明度 + 背景图模糊度。
 *
 * 从 ProfileScreen 抽出来，避免「我的」页面持续膨胀。
 *
 * 语义（v2）：
 *  - 透明度（0.3-1.0）和模糊度（0-25）都直接作用于"背景图本身"。
 *  - 卡片不参与；卡片仍然按主题不透明渲染。
 *  - 没有设置背景图时，整个 4 Tab 走主题 background（默认行为）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceScreen(
    onBack: () -> Unit,
    vm: GlobalBgViewModel = hiltViewModel(),
) {
    val bgUri by vm.globalBgImage.collectAsStateWithLifecycle()
    val bgAlpha by vm.globalBgCardAlpha.collectAsStateWithLifecycle()
    val bgBlur by vm.globalBgCardBlur.collectAsStateWithLifecycle()

    val pickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        // PickVisualMedia 返回的 content:// 自动持久化只读权限，无需 takePersistableUriPermission。
        uri?.let { vm.setGlobalBgImage(it.toString()) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()),
    ) {
        TopAppBar(
            title = { Text("外观", fontWeight = FontWeight.Bold) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                }
            },
            actions = {
                if (bgUri.isNotBlank()) {
                    TextButton(onClick = { vm.clearGlobalBg() }) {
                        Text("清除")
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
            ),
        )

        // ── 全局背景图卡片 ──
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Wallpaper,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "全局背景图",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "为 书架 / 发现 / 听书 / 我的 4 个 Tab 设置统一背景图。\n阅读器自成一套（在阅读设置中单独配置），不受此影响。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )

                Spacer(Modifier.height(16.dp))

                // 缩略图 + 选图说明
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                            .clickable {
                                pickerLauncher.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly
                                    )
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (bgUri.isBlank()) {
                            Icon(
                                Icons.Default.Add,
                                null,
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            )
                        } else {
                            // 用 Coil 而非 BgImageManager —— 缩略图 72dp 不需要走 LRU 大图缓存
                            AsyncImage(
                                model = bgUri,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (bgUri.isBlank()) "未设置背景图" else "已选择图片",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "点击缩略图从相册选择",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── 透明度 / 模糊度卡片 ──
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "效果调节",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "透明度和模糊度都直接作用于背景图本身，UI 卡片保持原样。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )

                Spacer(Modifier.height(16.dp))

                // 透明度 Slider
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "背景透明度",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.width(80.dp),
                    )
                    Slider(
                        value = bgAlpha,
                        onValueChange = { vm.setGlobalBgCardAlpha(it) },
                        valueRange = 0.3f..1.0f,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "%.2f".format(bgAlpha),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.width(40.dp),
                        textAlign = TextAlign.End,
                    )
                }

                Spacer(Modifier.height(8.dp))

                // 模糊度 Slider —— box-blur，所有 API 都生效
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "背景模糊",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.width(80.dp),
                    )
                    Slider(
                        value = bgBlur,
                        onValueChange = { vm.setGlobalBgCardBlur(it) },
                        valueRange = 0f..25f,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${bgBlur.toInt()}",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.width(40.dp),
                        textAlign = TextAlign.End,
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}
