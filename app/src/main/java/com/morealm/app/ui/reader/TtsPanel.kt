package com.morealm.app.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.morealm.app.domain.entity.TtsVoice
import com.morealm.app.ui.theme.LocalMoRealmColors

/**
 * In-reader TTS overlay panel with full playback controls.
 * Connects to the reader's TTS state for real paragraph-by-paragraph reading.
 */
@Composable
fun TtsOverlayPanel(
    bookTitle: String,
    chapterTitle: String,
    isPlaying: Boolean = false,
    speed: Float = 1.0f,
    currentParagraph: Int = 0,
    totalParagraphs: Int = 0,
    selectedEngine: String = "system",
    sleepMinutes: Int = 0,
    onPlayPause: () -> Unit = {},
    onStop: () -> Unit = {},
    onPrevChapter: () -> Unit = {},
    onNextChapter: () -> Unit = {},
    onPrevParagraph: () -> Unit = {},
    onNextParagraph: () -> Unit = {},
    onSpeedChange: (Float) -> Unit = {},
    onEngineChange: (String) -> Unit = {},
    onSleepTimerSet: (Int) -> Unit = {},
    voices: List<TtsVoice> = emptyList(),
    selectedVoice: String = "",
    onVoiceChange: (String) -> Unit = {},
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val moColors = LocalMoRealmColors.current
    var showSleepMenu by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.97f),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        tonalElevation = 12.dp,
    ) {
        Column(
            modifier = Modifier.navigationBarsPadding().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Drag handle
            Box(
                modifier = Modifier
                    .width(40.dp).height(4.dp)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))
            )

            Spacer(Modifier.height(16.dp))

            // Book & chapter info
            Text(bookTitle, style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(chapterTitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)

            // Paragraph progress
            if (totalParagraphs > 0) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { (currentParagraph + 1).toFloat() / totalParagraphs },
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(MaterialTheme.shapes.extraSmall),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                )
                Text("${currentParagraph + 1} / $totalParagraphs 段",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
            }

            Spacer(Modifier.height(16.dp))

            // Main playback controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Prev chapter
                IconButton(onClick = onPrevChapter) {
                    Icon(Icons.Default.SkipPrevious, "上一章",
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.onSurface)
                }
                // Prev paragraph
                IconButton(onClick = onPrevParagraph) {
                    Icon(Icons.Default.FastRewind, "上一段",
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                }

                Spacer(Modifier.width(12.dp))

                // Play/Pause
                FilledIconButton(
                    onClick = onPlayPause,
                    modifier = Modifier.size(56.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary),
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        modifier = Modifier.size(28.dp))
                }

                Spacer(Modifier.width(12.dp))

                // Next paragraph
                IconButton(onClick = onNextParagraph) {
                    Icon(Icons.Default.FastForward, "下一段",
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                }
                // Next chapter
                IconButton(onClick = onNextChapter) {
                    Icon(Icons.Default.SkipNext, "下一章",
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.onSurface)
                }
            }

            Spacer(Modifier.height(12.dp))

            // Speed control
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("语速", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                Slider(
                    value = speed,
                    onValueChange = onSpeedChange,
                    valueRange = 0.5f..3.0f,
                    steps = 9,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary),
                )
                Text("${String.format("%.1f", speed)}x",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
            }

            Spacer(Modifier.height(8.dp))

            // Voice selection
            if (voices.isNotEmpty()) {
                var showVoiceMenu by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("语音", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                    Spacer(Modifier.width(8.dp))
                    Box {
                        OutlinedButton(
                            onClick = { showVoiceMenu = true },
                            modifier = Modifier.height(32.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        ) {
                            val displayName = if (selectedVoice.isBlank()) "默认"
                                else voices.find { it.id == selectedVoice }?.name?.take(20)
                                    ?: selectedVoice.substringAfterLast("#").take(20)
                            Text(
                                displayName,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Default.ArrowDropDown, null, modifier = Modifier.size(16.dp))
                        }
                        DropdownMenu(
                            expanded = showVoiceMenu,
                            onDismissRequest = { showVoiceMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text("默认", fontWeight = if (selectedVoice.isBlank()) FontWeight.Bold else FontWeight.Normal)
                                },
                                onClick = { onVoiceChange(""); showVoiceMenu = false },
                            )
                            voices.take(30).forEach { voice ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(
                                                voice.name.substringAfterLast("#").take(30),
                                                fontWeight = if (selectedVoice == voice.id) FontWeight.Bold else FontWeight.Normal,
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                            Text(
                                                voice.language,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                            )
                                        }
                                    },
                                    onClick = { onVoiceChange(voice.id); showVoiceMenu = false },
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            // Bottom row: engine selector + sleep timer + stop
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Engine chips
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("system" to "系统", "edge" to "Edge").forEach { (id, label) ->
                        FilterChip(
                            selected = selectedEngine == id,
                            onClick = { onEngineChange(id) },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                selectedLabelColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.height(28.dp),
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Sleep timer
                    Box {
                        IconButton(onClick = { showSleepMenu = !showSleepMenu },
                            modifier = Modifier.size(32.dp)) {
                            Icon(
                                if (sleepMinutes > 0) Icons.Default.Timer else Icons.Default.TimerOff,
                                "定时关闭",
                                tint = if (sleepMinutes > 0) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                modifier = Modifier.size(20.dp))
                        }
                        DropdownMenu(expanded = showSleepMenu,
                            onDismissRequest = { showSleepMenu = false }) {
                            listOf(0 to "不定时", 15 to "15分钟", 30 to "30分钟",
                                   60 to "1小时", 90 to "1.5小时").forEach { (min, label) ->
                                DropdownMenuItem(
                                    text = {
                                        Text(label, fontWeight = if (sleepMinutes == min) FontWeight.Bold else FontWeight.Normal)
                                    },
                                    onClick = { onSleepTimerSet(min); showSleepMenu = false },
                                )
                            }
                        }
                    }
                    if (sleepMinutes > 0) {
                        Text("${sleepMinutes}分后停",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                            modifier = Modifier.align(Alignment.CenterVertically))
                    }

                    // Stop button
                    IconButton(onClick = onStop, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Stop, "停止",
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                            modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}
