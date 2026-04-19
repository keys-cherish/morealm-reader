package com.morealm.app.ui.reader

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.morealm.app.presentation.theme.ThemeViewModel
import com.morealm.app.ui.theme.BuiltinThemes
import com.morealm.app.ui.theme.LocalMoRealmColors
import com.morealm.app.ui.theme.toComposeColor
import com.morealm.app.presentation.reader.ReaderViewModel
import com.morealm.app.presentation.reader.PageTurnMode
import com.morealm.app.ui.reader.TtsOverlayPanel

@Composable
fun ReaderScreen(
    bookId: String,
    onBack: () -> Unit,
    onNavigateToBook: (String) -> Unit = {},
    themeViewModel: ThemeViewModel? = null,
    viewModel: ReaderViewModel = hiltViewModel(),
) {
    val book by viewModel.book.collectAsState()
    val chapters by viewModel.chapters.collectAsState()
    val currentIndex by viewModel.currentChapterIndex.collectAsState()
    val content by viewModel.chapterContent.collectAsState()
    val showControls by viewModel.showControls.collectAsState()
    val showTtsPanel by viewModel.showTtsPanel.collectAsState()
    val showSettings by viewModel.showSettingsPanel.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val pageTurnMode by viewModel.pageTurnMode.collectAsState()
    val fontFamily by viewModel.fontFamily.collectAsState()
    val fontSize by viewModel.fontSize.collectAsState()
    val lineHeight by viewModel.lineHeight.collectAsState()
    val scrollProgress by viewModel.scrollProgress.collectAsState()
    val linkedBooks by viewModel.linkedBooks.collectAsState()
    val nextBookPrompt by viewModel.nextBookPrompt.collectAsState()
    val navigateDirection by viewModel.navigateDirection.collectAsState()
    val customFontUri by viewModel.customFontUri.collectAsState()
    val customFontName by viewModel.customFontName.collectAsState()
    val volumeKeyPage by viewModel.volumeKeyPage.collectAsState()
    val screenTimeout by viewModel.screenTimeout.collectAsState()
    val showChapterNameSetting by viewModel.showChapterName.collectAsState()
    val showTimeBatterySetting by viewModel.showTimeBattery.collectAsState()
    val tapLeftAction by viewModel.tapLeftAction.collectAsState()
    val paragraphSpacing by viewModel.paragraphSpacing.collectAsState()
    val marginHorizontal by viewModel.marginHorizontal.collectAsState()
    val marginTopVal by viewModel.marginTop.collectAsState()
    val marginBottomVal by viewModel.marginBottom.collectAsState()
    val autoPageInterval by viewModel.autoPageInterval.collectAsState()
    val customCss by viewModel.customCss.collectAsState()
    val customBgImage by viewModel.customBgImage.collectAsState()
    val selectedText by viewModel.selectedText.collectAsState()
    val viewingImageSrc by viewModel.viewingImageSrc.collectAsState()
    val ttsScrollProgress by viewModel.ttsScrollProgress.collectAsState()
    val pageAnim by viewModel.pageAnim.collectAsState()
    val readerStyles by viewModel.allStyles.collectAsState()
    val activeStyleId by viewModel.activeStyleId.collectAsState()
    val activeStyle by viewModel.activeStyle.collectAsState()
    val screenOrientation by viewModel.screenOrientation.collectAsState()
    val moColors = LocalMoRealmColors.current

    // Apply screen orientation
    val activity = LocalContext.current as? android.app.Activity
    LaunchedEffect(screenOrientation) {
        activity?.requestedOrientation = when (screenOrientation) {
            0 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            1 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    // Reset orientation when leaving reader
    DisposableEffect(Unit) {
        onDispose {
            activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    // Save progress on pause (like Legado's onPause → ReadBook.saveRead())
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) {
                viewModel.saveProgressNow()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var showChapterList by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var showFullSearch by remember { mutableStateOf(false) }
    // Texture from theme (e.g., "texture:paper")
    val bgTexture = moColors.backgroundImageUri?.takeIf { it.startsWith("texture:") }
    val readerBrightness by viewModel.readerBrightness.collectAsState()

    // Set auto-navigate callback for seamless multi-TXT reading
    LaunchedEffect(Unit) {
        viewModel.setNavigateToBookCallback { targetBookId ->
            onNavigateToBook(targetBookId)
        }
    }

    // Notification permission for TTS service (Android 13+)
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or not, TTS still works — notification just won't show */ }

    val context = LocalContext.current

    // Export file picker
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri -> uri?.let { viewModel.exportAsTxt(it) } }

    // Back button: dismiss overlays first, then exit
    BackHandler(enabled = true) {
        when {
            showFullSearch -> showFullSearch = false
            showBookmarks -> showBookmarks = false
            showChapterList -> showChapterList = false
            showTtsPanel -> viewModel.hideTtsPanel()
            showSettings -> viewModel.hideSettingsPanel()
            showControls -> viewModel.hideControls()
            else -> onBack()
        }
    }

    // Keep screen on based on setting
    DisposableEffect(screenTimeout) {
        val activity = context as? android.app.Activity
        if (screenTimeout == -1) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Reader brightness control (independent of system brightness)
    DisposableEffect(readerBrightness) {
        val activity = context as? android.app.Activity
        val lp = activity?.window?.attributes
        if (lp != null) {
            lp.screenBrightness = if (readerBrightness < 0f) {
                WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            } else {
                readerBrightness.coerceIn(0.01f, 1f)
            }
            activity.window.attributes = lp
        }
        onDispose {
            val a = context as? android.app.Activity
            val p = a?.window?.attributes
            if (p != null) {
                p.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                a.window.attributes = p
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(moColors.readerBackground)
            .onKeyEvent { event ->
                if (!volumeKeyPage) return@onKeyEvent false
                if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onKeyEvent false
                val isTtsActive = viewModel.ttsPlaying.value
                when (event.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_VOLUME_DOWN -> {
                        if (isTtsActive) viewModel.ttsNextParagraph() else viewModel.nextChapter()
                        true
                    }
                    KeyEvent.KEYCODE_VOLUME_UP -> {
                        if (isTtsActive) viewModel.ttsPrevParagraph() else viewModel.prevChapter()
                        true
                    }
                    else -> false
                }
            }
    ) {
        // WebView reader content — handles all touch events internally via JS
        // Resolve reader colors: activeStyle overrides theme defaults
        val isNight = moColors.readerBackground.luminance() < 0.5f
        val readerBg = activeStyle?.let {
            (if (isNight) it.bgColorNight else it.bgColor).toComposeColor()
        } ?: moColors.readerBackground
        val readerFg = activeStyle?.let {
            (if (isNight) it.textColorNight else it.textColor).toComposeColor()
        } ?: moColors.readerText
        val readerFontSize = activeStyle?.textSize?.toFloat() ?: fontSize
        val readerLineHeight = activeStyle?.lineHeight ?: lineHeight
        val readerFontFamily = activeStyle?.fontFamily ?: fontFamily
        val isTxtFormat = book?.format == com.morealm.app.domain.entity.BookFormat.TXT
        val readerEngine by viewModel.readerEngine.collectAsState()
        val isPdfFormat = book?.format == com.morealm.app.domain.entity.BookFormat.PDF
        val isCbzFormat = book?.format == com.morealm.app.domain.entity.BookFormat.CBZ
        // Canvas works for TXT and EPUB; PDF/CBZ need WebView for image rendering
        val useWebView = readerEngine == "webview" || isPdfFormat || isCbzFormat

        if (!useWebView) {
            // Canvas renderer — Compose Canvas + HorizontalPager, works for TXT and EPUB
            com.morealm.app.ui.reader.renderer.CanvasRenderer(
                content = content,
                chapterTitle = chapters.getOrNull(currentIndex)?.title ?: "",
                chapterIndex = currentIndex,
                backgroundColor = readerBg,
                textColor = readerFg,
                accentColor = moColors.accent,
                fontSize = readerFontSize,
                lineHeight = readerLineHeight,
                paddingHorizontal = marginHorizontal,
                paddingVertical = marginTopVal,
                startFromLastPage = navigateDirection < 0,
                onTapCenter = { viewModel.toggleControls() },
                onProgress = { pct -> viewModel.updateScrollProgress(pct) },
                onNextChapter = { viewModel.nextChapter() },
                onPrevChapter = { viewModel.prevChapter() },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
        // WebView renderer — fallback for PDF/CBZ or user preference
        ReaderWebView(
            content = content,
            chapterTitle = chapters.getOrNull(currentIndex)?.title ?: "",
            backgroundColor = readerBg.toArgb(),
            textColor = readerFg.toArgb(),
            accentColor = moColors.accent.toArgb(),
            pageTurnMode = pageTurnMode,
            fontFamily = readerFontFamily,
            fontSize = readerFontSize,
            lineHeight = readerLineHeight,
            backgroundTexture = bgTexture,
            customFontUri = customFontUri,
            startFromLastPage = navigateDirection < 0,
            showChapterName = showChapterNameSetting,
            showTimeBattery = showTimeBatterySetting,
            tapLeftAction = tapLeftAction,
            paragraphSpacing = paragraphSpacing,
            marginHorizontal = marginHorizontal,
            marginTop = marginTopVal,
            marginBottom = marginBottomVal,
            customCss = customCss,
            customBgImage = customBgImage,
            onTapZone = { zone ->
                when (zone) {
                    "prev" -> viewModel.prevChapter()
                    "next" -> viewModel.nextChapter()
                    "center" -> viewModel.toggleControls()
                }
            },
            onLongPress = {
                // Long press always shows controls (escape hatch for fullscreen mode)
                viewModel.toggleControls()
            },
            onSwipeBack = onBack,
            onProgress = { pct -> viewModel.updateScrollProgress(pct) },
            onScrollNearBottom = { viewModel.onScrollNearBottom() },
            onVisibleChapterChanged = { idx -> viewModel.onVisibleChapterChanged(idx) },
            onTextSelected = { text -> viewModel.onTextSelected(text) },
            onImageClick = { src -> viewModel.onImageClick(src) },
            onSpeakSelected = { viewModel.onTextSelected(it); viewModel.speakSelectedText() },
            ttsScrollProgress = ttsScrollProgress,
            pageAnim = pageAnim,
            dualPage = LocalContext.current.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                && LocalContext.current.resources.configuration.screenWidthDp >= 600,
            modifier = Modifier.fillMaxSize(),
        )
        } // end else (WebView)

        // Loading indicator
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).size(32.dp),
                color = moColors.accent,
                strokeWidth = 2.dp,
            )
        }

        // Top bar overlay (back button + chapter info)
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            ReaderTopBar(
                bookTitle = book?.title ?: "",
                onBack = onBack,
                onExport = {
                    val fileName = (book?.title ?: "book") + ".txt"
                    exportLauncher.launch(fileName)
                },
                onBookmark = { viewModel.addBookmark() },
            )
        }

        // Bottom control bar
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ReaderControlBar(
                currentChapter = currentIndex,
                totalChapters = chapters.size,
                chapterTitle = chapters.getOrNull(currentIndex)?.title ?: "",
                scrollProgress = scrollProgress,
                onBack = onBack,
                onPrevChapter = viewModel::prevChapter,
                onNextChapter = viewModel::nextChapter,
                onTts = {
                    // Request notification permission on Android 13+ (for TTS notification)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                            != PackageManager.PERMISSION_GRANTED) {
                            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                    viewModel.hideControls()
                    viewModel.toggleTtsPanel()
                },
                onSettings = {
                    viewModel.hideControls()
                    viewModel.toggleSettingsPanel()
                },
                onChapterSelect = {
                    viewModel.hideControls()
                    showChapterList = true
                },
                onSearch = {
                    viewModel.hideControls()
                    showFullSearch = true
                },
                onAutoPage = {
                    viewModel.hideControls()
                    // Cycle through intervals: off -> 5s -> 10s -> 15s -> 30s -> off
                    val current = autoPageInterval
                    val next = when (current) {
                        0 -> 5; 5 -> 10; 10 -> 15; 15 -> 30; else -> 0
                    }
                    viewModel.setAutoPageInterval(next)
                },
            )
        }

        // Scrim overlay for panels (tap to dismiss)
        if (showSettings || showTtsPanel || showChapterList || showBookmarks || showFullSearch) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.4f))
                    .clickable(
                        indication = null,
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    ) {
                        when {
                            showFullSearch -> showFullSearch = false
                            showSettings -> viewModel.hideSettingsPanel()
                            showTtsPanel -> viewModel.hideTtsPanel()
                            showBookmarks -> showBookmarks = false
                            showChapterList -> showChapterList = false
                        }
                    }
            )
        }

        // Reader settings panel (font, page turn mode, etc.)
        AnimatedVisibility(
            visible = showSettings,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ReaderSettingsPanel(
                currentMode = pageTurnMode,
                onModeChange = viewModel::setPageTurnMode,
                currentFont = fontFamily,
                onFontChange = viewModel::setFontFamily,
                currentFontSize = fontSize,
                onFontSizeChange = viewModel::setFontSize,
                currentLineHeight = lineHeight,
                onLineHeightChange = viewModel::setLineHeight,
                customFontName = customFontName,
                onImportFont = { uri, name -> viewModel.importCustomFont(uri, name) },
                onClearCustomFont = viewModel::clearCustomFont,
                allThemes = themeViewModel?.allThemes?.collectAsState()?.value ?: BuiltinThemes.all(),
                activeThemeId = themeViewModel?.activeTheme?.collectAsState()?.value?.id ?: "",
                onThemeChange = { id -> themeViewModel?.switchTheme(id) },
                brightness = readerBrightness,
                onBrightnessChange = viewModel::setReaderBrightness,
                paragraphSpacing = paragraphSpacing,
                onParagraphSpacingChange = viewModel::setParagraphSpacing,
                marginHorizontal = marginHorizontal,
                onMarginHorizontalChange = viewModel::setMarginHorizontal,
                marginTop = marginTopVal,
                onMarginTopChange = viewModel::setMarginTop,
                marginBottom = marginBottomVal,
                onMarginBottomChange = viewModel::setMarginBottom,
                customCss = customCss,
                onCustomCssChange = viewModel::setCustomCss,
                customBgImage = customBgImage,
                onCustomBgImageChange = viewModel::setCustomBgImage,
                readerStyles = readerStyles,
                activeStyleId = activeStyleId,
                onStyleChange = viewModel::switchStyle,
                screenOrientation = screenOrientation,
                onScreenOrientationChange = viewModel::setScreenOrientation,
                textSelectable = viewModel.textSelectable.collectAsState().value,
                onTextSelectableChange = viewModel::setTextSelectable,
                chineseConvertMode = viewModel.chineseConvertMode.collectAsState().value,
                onChineseConvertModeChange = viewModel::setChineseConvertMode,
                readerEngine = viewModel.readerEngine.collectAsState().value,
                onReaderEngineChange = viewModel::setReaderEngine,
                onDismiss = viewModel::hideSettingsPanel,
            )
        }

        // TTS overlay panel — collect TTS state only when panel is visible
        AnimatedVisibility(
            visible = showTtsPanel,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            val ttsPlaying by viewModel.ttsPlaying.collectAsState()
            val ttsSpeed by viewModel.ttsSpeed.collectAsState()
            val ttsEngine by viewModel.ttsEngine.collectAsState()
            val ttsParagraphIndex by viewModel.ttsParagraphIndex.collectAsState()
            val ttsTotalParagraphs by viewModel.ttsTotalParagraphs.collectAsState()
            val ttsSleepMinutes by viewModel.ttsSleepMinutes.collectAsState()
            val ttsVoices by viewModel.ttsVoices.collectAsState()
            val ttsVoiceName by viewModel.ttsVoiceName.collectAsState()
            TtsOverlayPanel(
                bookTitle = book?.title ?: "",
                chapterTitle = chapters.getOrNull(currentIndex)?.title ?: "",
                isPlaying = ttsPlaying,
                speed = ttsSpeed,
                currentParagraph = ttsParagraphIndex,
                totalParagraphs = ttsTotalParagraphs,
                selectedEngine = ttsEngine,
                sleepMinutes = ttsSleepMinutes,
                onPlayPause = viewModel::ttsPlayPause,
                onStop = viewModel::ttsStop,
                onPrevChapter = viewModel::prevChapter,
                onNextChapter = viewModel::nextChapter,
                onPrevParagraph = viewModel::ttsPrevParagraph,
                onNextParagraph = viewModel::ttsNextParagraph,
                onSpeedChange = viewModel::setTtsSpeed,
                onEngineChange = viewModel::setTtsEngine,
                onSleepTimerSet = viewModel::setTtsSleepTimer,
                voices = ttsVoices,
                selectedVoice = ttsVoiceName,
                onVoiceChange = viewModel::setTtsVoice,
                onDismiss = viewModel::hideTtsPanel,
            )
        }

        // Chapter list panel (with search + bookmark tab)
        AnimatedVisibility(
            visible = showChapterList || showBookmarks,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            val isBookmarkTab = showBookmarks
            Surface(
                modifier = Modifier.fillMaxWidth().fillMaxHeight(0.6f),
                color = moColors.bottomBar.copy(alpha = 0.97f),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                tonalElevation = 12.dp,
            ) {
                Column(modifier = Modifier.navigationBarsPadding().padding(top = 16.dp)) {
                    // Drag handle
                    Box(
                        Modifier
                            .width(40.dp).height(4.dp)
                            .background(
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                                RoundedCornerShape(2.dp)
                            )
                            .align(Alignment.CenterHorizontally)
                    )
                    Spacer(Modifier.height(8.dp))
                    // Tab row: 目录 | 书签
                    Row(modifier = Modifier.padding(horizontal = 16.dp)) {
                        FilterChip(
                            selected = !isBookmarkTab,
                            onClick = { showBookmarks = false; showChapterList = true },
                            label = { Text("目录 (${chapters.size})") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = moColors.accent.copy(alpha = 0.15f),
                                selectedLabelColor = moColors.accent),
                        )
                        Spacer(Modifier.width(8.dp))
                        FilterChip(
                            selected = isBookmarkTab,
                            onClick = { showChapterList = false; showBookmarks = true },
                            label = {
                                val bmCount = viewModel.bookmarks.collectAsState().value.size
                                Text("书签 ($bmCount)")
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = moColors.accent.copy(alpha = 0.15f),
                                selectedLabelColor = moColors.accent),
                        )
                        Spacer(Modifier.weight(1f))
                        if (!isBookmarkTab) {
                            // Add bookmark button
                            IconButton(
                                onClick = { viewModel.addBookmark() },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    Icons.Default.BookmarkAdd, "添加书签",
                                    tint = moColors.accent, modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))

                    if (!isBookmarkTab) {
                        // Chapter search
                        var chapterSearch by remember { mutableStateOf("") }
                        val filteredChapters = remember(chapters, chapterSearch) {
                            if (chapterSearch.isBlank()) chapters
                            else chapters.filter { it.title.contains(chapterSearch, ignoreCase = true) }
                        }
                        OutlinedTextField(
                            value = chapterSearch,
                            onValueChange = { chapterSearch = it },
                            placeholder = { Text("搜索章节", style = MaterialTheme.typography.bodySmall) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(44.dp),
                            textStyle = MaterialTheme.typography.bodySmall,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = moColors.accent,
                                cursorColor = moColors.accent,
                            ),
                        )
                        Spacer(Modifier.height(4.dp))
                        val listState = rememberLazyListState(
                            initialFirstVisibleItemIndex = if (chapterSearch.isBlank()) (currentIndex - 2).coerceAtLeast(0) else 0
                        )
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        ) {
                            itemsIndexed(filteredChapters, key = { _, ch -> ch.id }) { _, ch ->
                                val isCurrent = ch.index == currentIndex
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            showChapterList = false
                                            viewModel.loadChapter(ch.index)
                                        }
                                        .background(
                                            if (isCurrent) moColors.accent.copy(alpha = 0.08f)
                                            else androidx.compose.ui.graphics.Color.Transparent,
                                            RoundedCornerShape(10.dp)
                                        )
                                        .padding(horizontal = 12.dp, vertical = 11.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        ch.title,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isCurrent) moColors.accent
                                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (isCurrent) {
                                        Text("▶", style = MaterialTheme.typography.labelSmall, color = moColors.accent)
                                    }
                                }
                            }
                            // Linked books
                            linkedBooks.forEach { linkedBook ->
                                item(key = "linked_header_${linkedBook.id}") {
                                    Text(
                                        linkedBook.title,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = moColors.accent.copy(alpha = 0.7f),
                                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 4.dp),
                                    )
                                }
                                item(key = "linked_tap_${linkedBook.id}") {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                showChapterList = false
                                                onNavigateToBook(linkedBook.id)
                                            }
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                    ) {
                                        Text("打开阅读 →", style = MaterialTheme.typography.bodySmall,
                                            color = moColors.accent.copy(alpha = 0.5f))
                                    }
                                }
                            }
                        }
                    } else {
                        // Bookmarks tab
                        val bookmarkList by viewModel.bookmarks.collectAsState()
                        if (bookmarkList.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("暂无书签", style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                            ) {
                                items(bookmarkList, key = { it.id }) { bm ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                showBookmarks = false
                                                viewModel.jumpToBookmark(bm)
                                            }
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(bm.chapterTitle, style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onSurface, maxLines = 1,
                                                overflow = TextOverflow.Ellipsis)
                                            if (bm.content.isNotEmpty()) {
                                                Text(bm.content, style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                        }
                                        IconButton(
                                            onClick = { viewModel.deleteBookmark(bm.id) },
                                            modifier = Modifier.size(28.dp),
                                        ) {
                                            Icon(Icons.Default.Close, "删除",
                                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                                                modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Full text search panel
        AnimatedVisibility(
            visible = showFullSearch,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            val searchResults by viewModel.searchResults.collectAsState()
            val searching by viewModel.searching.collectAsState()
            var searchQuery by remember { mutableStateOf("") }
            Surface(
                modifier = Modifier.fillMaxWidth().fillMaxHeight(0.6f),
                color = moColors.bottomBar.copy(alpha = 0.97f),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                tonalElevation = 12.dp,
            ) {
                Column(modifier = Modifier.navigationBarsPadding().padding(top = 16.dp)) {
                    Box(
                        Modifier.width(40.dp).height(4.dp)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f), RoundedCornerShape(2.dp))
                            .align(Alignment.CenterHorizontally)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("全文搜索", style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 16.dp))
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("输入关键词搜索全书", style = MaterialTheme.typography.bodySmall) },
                        singleLine = true,
                        trailingIcon = {
                            IconButton(onClick = { viewModel.searchFullText(searchQuery) }) {
                                if (searching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = moColors.accent)
                                else Icon(Icons.Default.Search, "搜索", tint = moColors.accent, modifier = Modifier.size(18.dp))
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(48.dp),
                        textStyle = MaterialTheme.typography.bodySmall,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = moColors.accent, cursorColor = moColors.accent),
                    )
                    Spacer(Modifier.height(4.dp))
                    if (searchResults.isEmpty() && !searching && searchQuery.isNotEmpty()) {
                        Text("未找到结果", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                            modifier = Modifier.padding(16.dp))
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        items(searchResults, key = { "sr_${it.chapterIndex}_${it.snippet.hashCode()}" }) { result ->
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .clickable {
                                        showFullSearch = false
                                        viewModel.clearSearchResults()
                                        viewModel.loadChapter(result.chapterIndex)
                                    }
                                    .padding(horizontal = 4.dp, vertical = 8.dp),
                            ) {
                                Column {
                                    Text(result.chapterTitle, style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = moColors.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(result.snippet, style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Auto page turn indicator
        if (autoPageInterval > 0) {
            Surface(
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 48.dp, end = 12.dp),
                color = moColors.accent.copy(alpha = 0.85f),
                shape = RoundedCornerShape(16.dp),
            ) {
                Row(
                    modifier = Modifier.clickable { viewModel.stopAutoPage() }.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("自动 ${autoPageInterval}s", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.surface)
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Default.Close, "停止", tint = MaterialTheme.colorScheme.surface, modifier = Modifier.size(14.dp))
                }
            }
        }

        // Image viewer dialog (full-screen zoomable)
        viewingImageSrc?.let { src ->
            ImageViewerDialog(
                imageSrc = src,
                onDismiss = { viewModel.dismissImageViewer() },
            )
        }

        // Next book prompt dialog
        nextBookPrompt?.let { nextBook ->
            AlertDialog(
                onDismissRequest = { viewModel.dismissNextBookPrompt() },
                title = { Text("本书已读完") },
                text = { Text("是否继续阅读《${nextBook.title}》？") },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.dismissNextBookPrompt()
                        onNavigateToBook(nextBook.id)
                    }) {
                        Text("继续阅读", color = moColors.accent)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissNextBookPrompt() }) {
                        Text("留在这里")
                    }
                },
            )
        }
    }
}
