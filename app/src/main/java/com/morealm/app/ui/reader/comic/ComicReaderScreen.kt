package com.morealm.app.ui.reader.comic

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.morealm.app.domain.parser.ComicResourceRegistry
import com.morealm.app.presentation.reader.comic.ComicReaderViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ComicReaderScreen(
    onBack: () -> Unit,
    viewModel: ComicReaderViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var controlsVisible by remember { mutableStateOf(false) }
    var pageMode by remember { mutableIntStateOf(0) } // 0=条漫滚动, 1=左右翻页

    // 音量键翻页：与小说阅读器共用 volumeKeyPage 偏好。Box 需持键盘焦点才能收到 onKeyEvent，
    // viewportHeightPx 用于条漫模式按「约一屏」滚动（翻页模式直接 pager ±1）。
    val keyFocus = remember { FocusRequester() }
    val volumeKeyPage by viewModel.volumeKeyPage.collectAsStateWithLifecycle()
    val volumeKeyReverse by viewModel.volumeKeyReverse.collectAsStateWithLifecycle()
    var viewportHeightPx by remember { mutableIntStateOf(0) }

    // ── 条漫滚动 state ──
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = state.startIndex)

    // ── 左右翻页 state ──
    val pagerState = rememberPagerState(
        initialPage = state.startIndex,
        pageCount = { state.totalImages.coerceAtLeast(1) },
    )

    // ── 监听当前页同步到 ViewModel ──
    //
    // 关键：只监听**当前活跃模式**的 state，避免另一个 state（停在旧位置）
    // 把 currentIndex 错误地拉回。例如：用户在条漫滚到第 50 页 → currentIndex=50
    // 此时如果同时监听 pagerState.currentPage（=0，没动过），会把 currentIndex
    // 倒灌回 0。所以必须按 pageMode 二选一收集。
    LaunchedEffect(listState, pagerState, pageMode, state.totalImages) {
        if (state.totalImages == 0) return@LaunchedEffect
        if (pageMode == 0) {
            snapshotFlow { listState.firstVisibleItemIndex }
                .distinctUntilChanged()
                .collect { viewModel.updateCurrentIndex(it) }
        } else {
            snapshotFlow { pagerState.currentPage }
                .distinctUntilChanged()
                .collect { viewModel.updateCurrentIndex(it) }
        }
    }

    // ── 切换 pageMode 时把目标 state 同步到 currentIndex ──
    //
    // 用户在条漫模式滚到第 50 页（currentIndex=50, pagerState 还在 0）→ 切到翻页模式
    // 此时 HorizontalPager 显示 pagerState.currentPage=0，与用户预期不符。
    // 这里在 pageMode 切换瞬间把目标 state 拨到 currentIndex。
    LaunchedEffect(pageMode, state.totalImages) {
        if (state.totalImages == 0) return@LaunchedEffect
        val target = state.currentIndex.coerceIn(0, state.totalImages - 1)
        if (pageMode == 0) {
            if (listState.firstVisibleItemIndex != target) {
                listState.scrollToItem(target)
            }
        } else {
            if (pagerState.currentPage != target) {
                pagerState.scrollToPage(target)
            }
        }
    }

    // 初始定位 —— rememberPagerState 的 initialPage 只在创建时用，但 state.startIndex
    // 来自 DB 异步加载，VM 出值时 pagerState 早就构造好了，必须主动拨过去。
    LaunchedEffect(state.totalImages, state.startIndex) {
        if (state.totalImages > 0 && state.startIndex > 0) {
            listState.scrollToItem(state.startIndex)
            pagerState.scrollToPage(state.startIndex)
        }
    }

    // 图片就绪后请求键盘焦点，让音量键 onKeyEvent 生效（仿小说 ReaderScreen 的 keyFocus）。
    LaunchedEffect(state.totalImages) {
        if (state.totalImages > 0) {
            runCatching { keyFocus.requestFocus() }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { viewportHeightPx = it.height }
            .focusRequester(keyFocus)
            .focusable()
            .onKeyEvent { event ->
                // 音量键翻页（受 volumeKeyPage 管）。控制栏显示时把音量键还给系统调音量。
                if (!volumeKeyPage) return@onKeyEvent false
                val ne = event.nativeKeyEvent
                val kc = ne.keyCode
                if (kc != KeyEvent.KEYCODE_VOLUME_UP && kc != KeyEvent.KEYCODE_VOLUME_DOWN) {
                    return@onKeyEvent false
                }
                if (controlsVisible) return@onKeyEvent false
                if (ne.action == KeyEvent.ACTION_DOWN && state.totalImages > 0) {
                    // VOLUME_DOWN = 下一页/下一屏；volumeKeyReverse 反转（XOR）
                    val forward = (kc == KeyEvent.KEYCODE_VOLUME_DOWN) != volumeKeyReverse
                    scope.launch {
                        if (pageMode == 1) {
                            val target = (pagerState.currentPage + if (forward) 1 else -1)
                                .coerceIn(0, state.totalImages - 1)
                            pagerState.animateScrollToPage(target)
                        } else {
                            val oneScreen = (if (viewportHeightPx > 0) viewportHeightPx.toFloat() else 2400f) * 0.9f
                            listState.animateScrollBy(if (forward) oneScreen else -oneScreen)
                        }
                    }
                }
                true // 消费音量键 DOWN+UP，阻止系统弹音量条
            },
    ) {
        when {
            state.loading -> CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White,
            )
            state.error != null -> Text(
                state.error!!,
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                textAlign = TextAlign.Center,
            )
            state.totalImages == 0 -> Text(
                "该漫画文件无可显示图片",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )
            else -> {
                if (pageMode == 0) {
                    // 条漫滚动
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures(onTap = { controlsVisible = !controlsVisible })
                            },
                        contentPadding = PaddingValues(0.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                        items(count = state.totalImages, key = { "${state.hash}:$it" }) { idx ->
                            ComicPage(hash = state.hash, imageIndex = idx + 1)
                        }
                    }
                } else {
                    // 左右翻页
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectTapGestures(onTap = { offset ->
                                    val third = size.width / 3f
                                    when {
                                        offset.x < third -> scope.launch {
                                            val prev = (pagerState.currentPage - 1).coerceAtLeast(0)
                                            pagerState.animateScrollToPage(prev)
                                        }
                                        offset.x > third * 2 -> scope.launch {
                                            val next = (pagerState.currentPage + 1).coerceAtMost(state.totalImages - 1)
                                            pagerState.animateScrollToPage(next)
                                        }
                                        else -> controlsVisible = !controlsVisible
                                    }
                                })
                            },
                        beyondViewportPageCount = 1,
                    ) { page ->
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            ComicPage(hash = state.hash, imageIndex = page + 1, fitPage = true)
                        }
                    }
                }
            }
        }

        // ── 顶栏 ──
        AnimatedVisibility(
            visible = controlsVisible,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.7f))
                    .systemBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = Color.White,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    state.book?.title ?: "",
                    color = Color.White,
                    fontSize = 16.sp,
                    maxLines = 1,
                )
            }
        }

        // ── 底栏：胶囊进度条 + 翻页模式 Tab ──
        AnimatedVisibility(
            visible = controlsVisible,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.7f))
                    .systemBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                // 胶囊进度条（复用小说阅读器风格）
                val fraction = if (state.totalImages <= 1) 0f
                    else state.currentIndex.toFloat() / (state.totalImages - 1)
                val barColor = MaterialTheme.colorScheme.primary
                val trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)

                Slider(
                    value = state.currentIndex.toFloat(),
                    onValueChange = { v ->
                        val idx = v.toInt().coerceIn(0, state.totalImages - 1)
                        viewModel.updateCurrentIndex(idx)
                        scope.launch {
                            if (pageMode == 0) listState.scrollToItem(idx)
                            else pagerState.scrollToPage(idx)
                        }
                    },
                    valueRange = 0f..(state.totalImages - 1).coerceAtLeast(1).toFloat(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp),
                    thumb = { Box(modifier = Modifier.size(0.dp)) },
                    track = {
                        ComicProgressTrack(
                            fraction = fraction,
                            text = "${state.currentIndex + 1} / ${state.totalImages}",
                            barColor = barColor,
                            trackColor = trackColor,
                        )
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = barColor,
                        activeTrackColor = barColor,
                        inactiveTrackColor = trackColor,
                    ),
                )

                Spacer(Modifier.height(8.dp))

                // 翻页模式 Tab
                val tabs = listOf("条漫", "翻页")
                TabRow(
                    selectedTabIndex = pageMode,
                    containerColor = Color.Transparent,
                    contentColor = Color.White,
                    indicator = { tabPositions ->
                        if (pageMode < tabPositions.size) {
                            TabRowDefaults.SecondaryIndicator(
                                modifier = Modifier.tabIndicatorOffset(tabPositions[pageMode]),
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                    divider = {},
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = pageMode == index,
                            onClick = { pageMode = index },
                            text = {
                                Text(
                                    title,
                                    fontSize = 13.sp,
                                    fontWeight = if (pageMode == index) FontWeight.Bold else FontWeight.Normal,
                                )
                            },
                            selectedContentColor = Color.White,
                            unselectedContentColor = Color.White.copy(alpha = 0.5f),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 漫画进度条 —— 复用小说阅读器的胶囊风格（独立胶囊填充 + 渐变 + 嵌入文字反差色）。
 */
@Composable
private fun ComicProgressTrack(
    fraction: Float,
    text: String,
    barColor: Color,
    trackColor: Color,
) {
    val barHeight = 24.dp
    val shape = RoundedCornerShape(barHeight / 2)
    val gradientStart = lerp(barColor, Color.White, 0.22f)
    val fillBrush = remember(barColor, gradientStart) {
        Brush.horizontalGradient(colors = listOf(gradientStart, barColor))
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(barHeight)
            .clip(shape)
            .background(trackColor),
        contentAlignment = Alignment.Center,
    ) {
        if (fraction > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .align(Alignment.CenterStart)
                    .clip(shape)
                    .background(fillBrush),
            )
        }
        // 底层文字（空白段上显色）
        Text(
            text = text,
            color = barColor.copy(alpha = 0.7f),
            style = MaterialTheme.typography.labelMedium.copy(
                fontFeatureSettings = "tnum",
                fontWeight = FontWeight.SemiBold,
            ),
        )
        // 上层文字（填充段上显色）
        Box(
            modifier = Modifier
                .matchParentSize()
                .drawWithContent {
                    val clipWidth = size.width * fraction.coerceIn(0f, 1f)
                    clipRect(0f, 0f, clipWidth, size.height) {
                        this@drawWithContent.drawContent()
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                color = Color.White,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFeatureSettings = "tnum",
                    fontWeight = FontWeight.SemiBold,
                ),
            )
        }
    }
}

@Composable
private fun ComicPage(hash: String, imageIndex: Int, fitPage: Boolean = false) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, hash, imageIndex) {
        value = withContext(Dispatchers.IO) {
            val bytes = ComicResourceRegistry.readBytes(context, hash, imageIndex) ?: return@withContext null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }
    val bmp = bitmap
    if (bmp != null) {
        val aspect by remember(bmp) {
            derivedStateOf { if (bmp.height == 0) 1f else bmp.width.toFloat() / bmp.height.toFloat() }
        }
        if (fitPage) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "page $imageIndex",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } else {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "page $imageIndex",
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspect.coerceAtLeast(0.05f)),
                contentScale = ContentScale.FillWidth,
            )
        }
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (fitPage) Modifier.fillMaxSize() else Modifier.aspectRatio(0.7f))
                .background(Color(0xFF111111)),
            contentAlignment = Alignment.Center,
        ) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(0.4f),
                color = Color.White.copy(alpha = 0.5f),
                trackColor = Color.Transparent,
            )
        }
    }
}
