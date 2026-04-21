package com.morealm.app.ui.listen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.morealm.app.presentation.profile.ListenViewModel
import com.morealm.app.ui.theme.LocalMoRealmColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListenScreen(
    viewModel: ListenViewModel = hiltViewModel(),
) {
    val moColors = LocalMoRealmColors.current
    val engines by viewModel.availableEngines.collectAsState()
    val selectedEngine by viewModel.selectedEngine.collectAsState()
    val recentSessions by viewModel.recentSessions.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
    ) {
        TopAppBar(
            title = { Text("听书", fontWeight = FontWeight.Bold) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )

        // TTS engine selector
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = moColors.surfaceGlass),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("语音引擎", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("在阅读页点击 TTS 按钮即可朗读当前章节",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    data class EngineOption(val id: String, val label: String, val desc: String)
                    val options = listOf(
                        EngineOption("system", "系统 TTS", "Android 内置"),
                        EngineOption("edge", "Edge TTS", "微软神经语音"),
                    )
                    options.forEach { opt ->
                        FilterChip(
                            selected = selectedEngine == opt.id,
                            onClick = { viewModel.selectEngine(opt.id) },
                            label = {
                                Column {
                                    Text(opt.label, style = MaterialTheme.typography.labelMedium)
                                    Text(opt.desc, style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                                }
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = moColors.accent.copy(alpha = 0.15f),
                                selectedLabelColor = moColors.accent),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Usage tips
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = moColors.accent.copy(alpha = 0.06f)),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("使用方法", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                TipRow(icon = Icons.Default.MenuBook, text = "打开任意书籍进入阅读页")
                TipRow(icon = Icons.Default.RecordVoiceOver, text = "点击底部控制栏的「朗读」按钮")
                TipRow(icon = Icons.Default.Speed, text = "支持调节语速、切换音色、定时停止")
                TipRow(icon = Icons.Default.VolumeUp, text = "音量键可切换上/下一段")
            }
        }

        Spacer(Modifier.height(16.dp))

        // Recent sessions
        if (recentSessions.isNotEmpty()) {
            Text("最近听书", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(recentSessions, key = { it.timestamp }) { session ->
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = moColors.surfaceGlass),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Headphones, null,
                                tint = moColors.accent, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(session.bookTitle, style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(session.chapterTitle, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TipRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    val moColors = LocalMoRealmColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 3.dp),
    ) {
        Icon(icon, null, tint = moColors.accent.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
    }
}
