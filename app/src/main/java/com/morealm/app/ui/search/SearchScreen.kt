package com.morealm.app.ui.search

import android.widget.Toast
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.morealm.app.domain.entity.Book
import com.morealm.app.presentation.search.SearchResult
import com.morealm.app.presentation.search.SearchViewModel
import com.morealm.app.presentation.search.SourceSearchProgress
import com.morealm.app.presentation.search.SourceStatus
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit = {},
    onNavigateReader: (String) -> Unit = {},
    viewModel: SearchViewModel = hiltViewModel(),
) {
    var query by remember { mutableStateOf("") }
    val results by viewModel.results.collectAsStateWithLifecycle()
    val localResults by viewModel.localResults.collectAsStateWithLifecycle()
    val sourceProgress by viewModel.sourceProgress.collectAsStateWithLifecycle()
    val isSearching by viewModel.isSearching.collectAsStateWithLifecycle()
    val disclaimerAccepted by viewModel.disclaimerAccepted.collectAsStateWithLifecycle()
    val sourceCount by viewModel.sourceCount.collectAsStateWithLifecycle()

    // UX-1: Snackbar host 用于「清空历史」的撤销窗口。SearchScreen 之前没有 Scaffold，
    // 这里直接在 Box 里手动放 SnackbarHost，避免大改原有 Column 布局。
    val snackbarHost = remember { SnackbarHostState() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
    ) {
        // Compact top bar with back + title inline
        TopAppBar(
            title = {
                Text(
                    "发现",
                    style = MaterialTheme.typography.titleLarge.copy(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.onBackground,
                                MaterialTheme.colorScheme.primary,
                            )
                        )
                    ),
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = androidx.compose.ui.graphics.Color.Transparent,
            ),
        )

        Spacer(Modifier.height(4.dp))

        // Search box: input + button
        // UX-3: 进入搜索页自动 focus + 弹出软键盘，省一次「点输入框」交互。
        val focusRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f).height(48.dp).focusRequester(focusRequester),
                placeholder = { Text("搜索书名、作者…", style = MaterialTheme.typography.bodySmall) },
                shape = MaterialTheme.shapes.medium,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    cursorColor = MaterialTheme.colorScheme.primary,
                ),
                keyboardActions = KeyboardActions(
                    onSearch = { viewModel.search(query) }
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )
            Button(
                onClick = { viewModel.search(query) },
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.height(48.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Text("搜索", style = MaterialTheme.typography.labelLarge)
            }
        }

        // Disclaimer
        if (!disclaimerAccepted) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f),
            ) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "搜索结果来自用户导入的书源，MoRealm 不提供任何内容。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "知道了",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { viewModel.acceptDisclaimer() }
                            .padding(start = 8.dp),
                    )
                }
            }
        }

        // Source count (when idle)
        if (sourceCount > 0 && sourceProgress.isEmpty()) {
            Text(
                "已加载 ${sourceCount} 个文本书源",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }

        // Progress card (ring + source tags)
        if (sourceProgress.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            SearchProgressCard(
                progress = sourceProgress,
                resultCount = results.size + localResults.size,
            )
        }

        Spacer(Modifier.height(4.dp))

        // Results
        if (results.isEmpty() && localResults.isEmpty() && !isSearching) {
            // 空态优先级：
            //  - 还有正在进行 / 已结束搜索（sourceProgress 非空）→ 显示"暂无结果"
            //  - 用户当前 query 是空 + 没搜过 → 显示历史区（若历史也空，回退到 placeholder 文案）
            //  - 用户输入了 query 但没回车 → 不动（让他继续输）
            val history by viewModel.searchHistory.collectAsStateWithLifecycle()
            val showHistory = sourceProgress.isEmpty() && query.isEmpty() && history.isNotEmpty()
            if (showHistory) {
                SearchHistorySection(
                    history = history,
                    onSelect = { word ->
                        query = word
                        viewModel.search(word)
                    },
                    onDelete = { viewModel.deleteHistory(it) },
                    onClear = {
                        // UX-1: 快删 + Snackbar 撤销取代 AlertDialog 二次确认。
                        // 先 snapshot 当前 history，再清空 DB；用户 5s 内点撤销 → 走
                        // restoreHistory 把整批 upsert 回去（保留原 usage / lastUseTime）。
                        val snapshot = history.toList()
                        viewModel.clearHistory()
                        scope.launch {
                            val r = snackbarHost.showSnackbar(
                                message = "已清空 ${snapshot.size} 条搜索历史",
                                actionLabel = "撤销",
                                duration = SnackbarDuration.Short,
                                withDismissAction = true,
                            )
                            if (r == SnackbarResult.ActionPerformed) {
                                viewModel.restoreHistory(snapshot)
                            }
                        }
                    },
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (sourceProgress.isEmpty()) "输入关键词搜索" else "暂无结果",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Online results first: Legado search keeps remote results as the primary result stream.
                if (results.isNotEmpty()) {
                    item {
                        Text(
                            "在线 (${results.size})",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                        )
                    }
                    items(results, key = { "${it.sourceUrl}_${it.bookUrl}" }) { result ->
                        val ctx = LocalContext.current
                        OnlineResultItem(
                            result = result,
                            onClick = {
                                if (result.sourceType == 0) {
                                    viewModel.addToShelfAndRead(result) { bookId ->
                                        onNavigateReader(bookId)
                                    }
                                } else {
                                    Toast.makeText(ctx, "非文本书源暂不能阅读", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onDownload = {
                                if (result.sourceType == 0) {
                                    viewModel.addToShelfAndDownload(result)
                                    Toast.makeText(ctx, "开始缓存: ${result.title}", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(ctx, "非文本书源暂不能缓存", Toast.LENGTH_SHORT).show()
                                }
                            },
                        )
                    }
                }

                // Local shelf results
                if (localResults.isNotEmpty()) {
                    item {
                        Text(
                            "书架 (${localResults.size})",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                            modifier = Modifier.padding(top = if (results.isNotEmpty()) 12.dp else 4.dp, bottom = 4.dp),
                        )
                    }
                    items(localResults, key = { it.id }) { book ->
                        LocalBookItem(book, onClick = { onNavigateReader(book.id) })
                    }
                }

                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }
        SnackbarHost(snackbarHost, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

/** Ring progress + source tag chips — matches HTML prototype se-prog */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchProgressCard(
    progress: List<SourceSearchProgress>,
    resultCount: Int,
) {
    val total = progress.size
    val done = remember(progress) {
        progress.count { it.status == SourceStatus.DONE || it.status == SourceStatus.FAILED }
    }
    val fraction = if (total > 0) done.toFloat() / total else 0f
    val isComplete = done >= total && total > 0
    val failed = remember(progress) { progress.count { it.status == SourceStatus.FAILED } }
    val failedDetails = remember(progress) {
        progress.filter { it.status == SourceStatus.FAILED && !it.errorMessage.isNullOrBlank() }
    }
    var expanded by remember { mutableStateOf(false) }
    val previewCount = 12
    val compactCompleteWithResults = isComplete && resultCount > 0 && !expanded
    val visibleProgress = when {
        expanded -> progress
        compactCompleteWithResults -> progress.filter { it.status == SourceStatus.SEARCHING }.take(previewCount)
        else -> progress.take(previewCount)
    }
    val hiddenCount = (progress.size - visibleProgress.size).coerceAtLeast(0)

    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (isComplete) "搜索完成" else "正在检索文本书源…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                    Text(
                        "找到 $resultCount 条 · 失败 $failed 个",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.32f),
                    )
                }
                Text(
                    "$done/$total",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    if (expanded) "收起" else "展开",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.extraSmall)
                        .clickable { expanded = !expanded }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }

            if (expanded || resultCount == 0) {
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    RingProgress(fraction)
                }
            }

            if (visibleProgress.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (expanded) {
                                Modifier
                                    .heightIn(max = if (resultCount > 0) 120.dp else 220.dp)
                                    .verticalScroll(rememberScrollState())
                            } else {
                                Modifier
                            }
                        )
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        visibleProgress.forEach { src ->
                            SourceTag(src)
                        }
                        if (hiddenCount > 0) {
                            MoreSourceTag(hiddenCount)
                        }
                    }
                }
            } else if (compactCompleteWithResults && failed > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "失败详情已折叠，展开后查看",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f),
                )
            }
            if (expanded && failedDetails.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 96.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    failedDetails.take(12).forEach { detail ->
                        Text(
                            "${detail.sourceName}: ${detail.errorMessage}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (failedDetails.size > 12) {
                        Text(
                            "另有 ${failedDetails.size - 12} 个失败来源",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.68f),
                        )
                    }
                }
            }
        }
    }
}

/** Animated ring progress indicator — matches HTML se-ring */
@Composable
private fun RingProgress(progress: Float) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(400, easing = FastOutSlowInEasing),
        label = "ring",
    )
    val accent = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant

    Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val strokeWidth = 3.dp.toPx()
            val pad = strokeWidth / 2
            drawArc(
                color = track, startAngle = 0f, sweepAngle = 360f, useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(pad, pad),
                size = androidx.compose.ui.geometry.Size(size.width - strokeWidth, size.height - strokeWidth),
                style = Stroke(strokeWidth),
            )
            drawArc(
                color = accent, startAngle = -90f, sweepAngle = 360f * animatedProgress,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(pad, pad),
                size = androidx.compose.ui.geometry.Size(size.width - strokeWidth, size.height - strokeWidth),
                style = Stroke(strokeWidth, cap = StrokeCap.Round),
            )
        }
        Text(
            "${(animatedProgress * 100).toInt()}%",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** Source tag chip — changes color based on status */
@Composable
private fun SourceTag(source: SourceSearchProgress) {
    val (bgColor, textColor) = when (source.status) {
        SourceStatus.DONE -> Pair(
            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            MaterialTheme.colorScheme.primary,
        )
        SourceStatus.FAILED -> Pair(
            MaterialTheme.colorScheme.error.copy(alpha = 0.1f),
            MaterialTheme.colorScheme.error,
        )
        else -> Pair(
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
        )
    }
    Surface(shape = MaterialTheme.shapes.extraSmall, color = bgColor) {
        Text(
            source.sourceName,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MoreSourceTag(count: Int) {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
    ) {
        Text(
            "+$count",
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * 在线书源搜索结果卡片 —— 规范化布局，5 槽位固定。
 *
 * 设计目的：解决不同书源 SearchBook 字段填得不一致导致每张卡显得"乱"的问题。
 * 即使某些槽（最新章节 / 字数 / 简介）为空，也保留位置不重排，让列表所有卡片
 * 高度一致 / 视线对齐。
 *
 * 布局：90×128dp 全高封面 ┃ 信息列：
 *   1. 标题（titleSmall）
 *   2. 作者 · 分类（labelMedium，灰色）
 *   3. 最新章节（labelSmall，primary 色，前缀"最新："；为空时显示 "—"）
 *   4. 简介（bodySmall × 2 行；为空时显示 "暂无简介"）
 *   5. 底栏：来源（labelSmall primary）· 字数（labelSmall 灰）· 下载按钮
 *
 * 非文本源（音频/图片等）：禁用点击，底栏显示红色"非文本源"标记。
 */
@Composable
private fun OnlineResultItem(
    result: SearchResult,
    onClick: () -> Unit,
    onDownload: () -> Unit,
) {
    val isTextSource = result.sourceType == 0
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = isTextSource, onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (isTextSource) 0.3f else 0.16f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // 全高封面 90×128dp（与右侧信息列同高）
            Box(
                modifier = Modifier
                    .size(width = 90.dp, height = 128.dp)
                    .clip(MaterialTheme.shapes.small),
            ) {
                if (!result.coverUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = result.coverUrl,
                        contentDescription = result.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("📖", fontSize = 28.sp)
                    }
                }
            }

            Spacer(Modifier.width(12.dp))

            // 信息列：5 个固定槽位
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(128.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                // 1. 标题
                Text(
                    result.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                // 2. 作者 · 分类
                val authorAndKind = remember(result) {
                    val parts = mutableListOf<String>()
                    if (result.author.isNotBlank()) parts.add(result.author)
                    result.searchBook?.kind?.takeIf { it.isNotBlank() }?.let {
                        parts.add(it.replace(",", " ").replace("，", " ").take(20))
                    }
                    parts.joinToString(" · ").ifBlank { "—" }
                }
                Text(
                    authorAndKind,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                // 3. 最新章节
                val latestText = result.searchBook?.latestChapterTitle?.takeIf { it.isNotBlank() }
                    ?.let { "最新: ${it.take(24)}" }
                    ?: "最新: —"
                Text(
                    latestText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                // 4. 简介（2 行）
                Text(
                    result.intro.ifBlank { "暂无简介" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                // 5. 底栏：来源 · 字数 · 下载
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val sourceLabel = if (isTextSource) result.sourceName
                                      else "${result.sourceName} · ${sourceTypeLabel(result.sourceType)}"
                    Text(
                        sourceLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isTextSource) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    val wordCount = result.searchBook?.wordCount?.takeIf { it.isNotBlank() }
                    if (wordCount != null) {
                        Text(
                            wordCount,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                    IconButton(
                        onClick = onDownload,
                        enabled = isTextSource,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Default.CloudDownload,
                            contentDescription = "缓存下载",
                            tint = MaterialTheme.colorScheme.primary.copy(
                                alpha = if (isTextSource) 0.7f else 0.18f
                            ),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun sourceTypeLabel(type: Int): String = when (type) {
    1 -> "音频书源"
    2 -> "图片书源"
    3 -> "下载书源"
    4 -> "视频书源"
    else -> "非文本书源"
}

/** Local book result item */
@Composable
private fun LocalBookItem(book: Book, onClick: () -> Unit = {}) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    book.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (book.author.isNotBlank()) {
                    Text(
                        book.author,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Text(
                book.format.name,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
            )
        }
    }
}

/**
 * 搜索历史区。两段式布局：
 *
 *   - 顶部一行: 标题 "搜索历史" + 右侧"清空"文字按钮（点击二次确认）
 *   - 下方 FlowRow: 每个历史词一个 [SuggestionChip]，点 = 填入并触发搜索；
 *     右上 X 小按钮 = 删除单条。SuggestionChip 比 AssistChip 更贴"列表项"语义。
 *
 * 滚动用 [verticalScroll] 而非 LazyColumn，因为：
 *   - 历史已 trim 到 200 行以内，整渲染开销可忽略；
 *   - 嵌入到 Column 父布局里，LazyColumn 套 Column 会触发 nested scroll 灾难。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun SearchHistorySection(
    history: List<com.morealm.app.domain.entity.SearchKeyword>,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onClear: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        ) {
            Text(
                "搜索历史",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                modifier = Modifier.weight(1f),
            )
            // 直接清空 — 顶层 SearchScreen 会用 Snackbar 提供 5s 撤销窗口；
            // 不再弹 AlertDialog 二次确认。
            TextButton(onClick = onClear) {
                Text(
                    "清空",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                )
            }
        }
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            history.forEach { kw ->
                AssistChip(
                    onClick = { onSelect(kw.word) },
                    label = {
                        Text(
                            kw.word,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 200.dp),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    },
                    trailingIcon = {
                        // 单条删除：用 close 图标做 trailingIcon，让 chip 既可点搜也可删；
                        // 不引入长按手势是因为 chip 本身没 onLongClick 接口，且 trailing X
                        // 在密集列表里更易点中。
                        // UX-7: 触摸热区 ≥40dp，图标视觉仍是 12dp，外层 Box 拓展可点区域。
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clickable { onDelete(kw.word) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.Close,
                                "删除",
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            )
                        }
                    },
                )
            }
        }
    }

    // 二次确认对话框已移除：清空动作改为顶层 SearchScreen 的 Snackbar 撤销模式
    // (UX-1)。SearchHistorySection 只发出"清空"信号，撤销窗口期由 5s Snackbar 提供。
}
