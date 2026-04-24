package com.morealm.app.ui.listen

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.morealm.app.presentation.profile.ListenViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ListenScreen(
    viewModel: ListenViewModel = hiltViewModel(),
) {
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()
    val selectedEngine by viewModel.selectedEngine.collectAsStateWithLifecycle()
    val selectedSpeed by viewModel.selectedSpeed.collectAsStateWithLifecycle()

    val isActive = playback.bookTitle.isNotBlank()
    val progress = if (playback.totalParagraphs > 0)
        playback.paragraphIndex.toFloat() / playback.totalParagraphs else 0f
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "ttsProgress",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(64.dp))

        // Gradient title
        Text(
            "听书",
            style = MaterialTheme.typography.headlineMedium.copy(
                brush = Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.onBackground,
                        MaterialTheme.colorScheme.primary,
                    )
                )
            ),
        )
        Text(
            "沉浸式聆听体验",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
        )

        Spacer(Modifier.height(28.dp))

        // Pulsing cover circle
        TtsCoverCircle(isPlaying = playback.isPlaying)

        Spacer(Modifier.height(28.dp))

        // Book title + chapter
        Text(
            if (isActive) playback.bookTitle else "未在播放",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        if (isActive) {
            Text(
                playback.chapterTitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                modifier = Modifier.padding(top = 3.dp),
            )
        }

        Spacer(Modifier.height(20.dp))

        // Progress bar
        Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp)) {
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier.fillMaxWidth().height(4.dp)
                    .clip(MaterialTheme.shapes.extraSmall),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Spacer(Modifier.height(5.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    if (isActive) "第 ${playback.paragraphIndex + 1} 段" else "--",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f),
                )
                Text(
                    if (isActive) "共 ${playback.totalParagraphs} 段" else "--",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f),
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        // Playback controls
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // Previous chapter
            IconButton(onClick = { viewModel.sendPrevChapter() }, Modifier.size(48.dp)) {
                Icon(Icons.Default.SkipPrevious, "上一章",
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onBackground)
            }
            // Rewind (prev paragraph — via PlayPause for now)
            IconButton(onClick = {}, Modifier.size(48.dp)) {
                Icon(Icons.Default.Replay10, "后退10秒",
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onBackground)
            }
            // Play / Pause
            FilledIconButton(
                onClick = { viewModel.sendPlayPause() },
                modifier = Modifier.size(60.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Icon(
                    if (playback.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (playback.isPlaying) "暂停" else "播放",
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }
            // Forward
            IconButton(onClick = {}, Modifier.size(48.dp)) {
                Icon(Icons.Default.Forward10, "快进10秒",
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onBackground)
            }
            // Next chapter
            IconButton(onClick = { viewModel.sendNextChapter() }, Modifier.size(48.dp)) {
                Icon(Icons.Default.SkipNext, "下一章",
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onBackground)
            }
        }

        Spacer(Modifier.height(28.dp))

        // Engine selection
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text("TTS 引擎", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                data class EngineOpt(val id: String, val label: String)
                val engines = listOf(
                    EngineOpt("edge", "Edge TTS"),
                    EngineOpt("system", "系统 TTS"),
                    EngineOpt("openai", "OpenAI"),
                    EngineOpt("custom", "自定义 API"),
                )
                engines.forEach { eng ->
                    val isSelected = selectedEngine == eng.id
                    Surface(
                        onClick = { viewModel.selectEngine(eng.id) },
                        shape = MaterialTheme.shapes.small,
                        color = if (isSelected)
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                        ),
                    ) {
                        Text(
                            eng.label,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // Speed selection
            Text("语速", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                val speeds = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
                speeds.forEach { spd ->
                    val isSelected = selectedSpeed == spd
                    Surface(
                        onClick = { viewModel.selectSpeed(spd) },
                        shape = MaterialTheme.shapes.small,
                        color = if (isSelected)
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) MaterialTheme.colorScheme.primary
                            else Color.Transparent,
                        ),
                    ) {
                        Text(
                            "${spd}x",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(120.dp))
    }
}

/** Large circular cover with pulse glow animation — matches HTML tts-cover */
@Composable
private fun TtsCoverCircle(isPlaying: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isPlaying) 1.06f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseScale",
    )
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.08f,
        targetValue = if (isPlaying) 0.04f else 0.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glowAlpha",
    )

    Box(contentAlignment = Alignment.Center) {
        // Outer glow ring
        Box(
            Modifier
                .size((180 * pulseScale + 16).dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = glowAlpha))
        )
        // Cover circle
        Box(
            modifier = Modifier
                .size((180 * pulseScale).dp)
                .shadow(24.dp, CircleShape, ambientColor = accent.copy(alpha = 0.35f))
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(
                            accent.copy(alpha = 0.3f),
                            accent.copy(alpha = 0.6f),
                        )
                    )
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text("📖", fontSize = 56.sp)
        }
    }
}
