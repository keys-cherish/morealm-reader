package com.morealm.app.ui.reader

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.morealm.app.domain.entity.BuiltinThemes
import com.morealm.app.ui.theme.LocalMoRealmColors
import com.morealm.app.ui.theme.toComposeColor
import com.morealm.app.presentation.reader.ReaderViewModel
import com.morealm.app.presentation.reader.PageTurnMode
import com.morealm.app.ui.reader.renderer.toPageAnimType
import com.morealm.app.ui.reader.TtsOverlayPanel
import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.entity.BookChapter
import com.morealm.app.domain.entity.Bookmark
import com.morealm.app.presentation.reader.ReaderViewModel.SearchResult
import androidx.compose.ui.graphics.Color
import com.morealm.app.ui.theme.MoRealmColors

@Composable
fun ReaderScreen(
    bookId: String,
    onBack: () -> Unit,
    onNavigateToBook: (String) -> Unit = {},
    themeViewModel: ThemeViewModel? = null,
    viewModel: ReaderViewModel = hiltViewModel(),
) {
    val book by viewModel.book.collectAsStateWithLifecycle()
    val chapters by viewModel.chapters.collectAsStateWithLifecycle()
    val currentIndex by viewModel.currentChapterIndex.collectAsStateWithLifecycle()
    val content by viewModel.chapterContent.collectAsStateWithLifecycle()
    val showControls by viewModel.showControls.collectAsStateWithLifecycle()
    val showTtsPanel by viewModel.showTtsPanel.collectAsStateWithLifecycle()
    val showSettings by viewModel.showSettingsPanel.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val pageTurnMode by viewModel.pageTurnMode.collectAsStateWithLifecycle()
    val fontFamily by viewModel.fontFamily.collectAsStateWithLifecycle()
    val fontSize by viewModel.fontSize.collectAsStateWithLifecycle()
    val lineHeight by viewModel.lineHeight.collectAsStateWithLifecycle()
    val scrollProgress by viewModel.scrollProgress.collectAsStateWithLifecycle()
    val linkedBooks by viewModel.linkedBooks.collectAsStateWithLifecycle()
    val nextBookPrompt by viewModel.nextBookPrompt.collectAsStateWithLifecycle()
    val navigateDirection by viewModel.navigateDirection.collectAsStateWithLifecycle()
    val customFontUri by viewModel.customFontUri.collectAsStateWithLifecycle()
    val customFontName by viewModel.customFontName.collectAsStateWithLifecycle()
    val volumeKeyPage by viewModel.volumeKeyPage.collectAsStateWithLifecycle()
    val screenTimeout by viewModel.screenTimeout.collectAsStateWithLifecycle()
    val showChapterNameSetting by viewModel.showChapterName.collectAsStateWithLifecycle()
    val showTimeBatterySetting by viewModel.showTimeBattery.collectAsStateWithLifecycle()
    val tapLeftAction by viewModel.tapLeftAction.collectAsStateWithLifecycle()
    val paragraphSpacing by viewModel.paragraphSpacing.collectAsStateWithLifecycle()
    val marginHorizontal by viewModel.marginHorizontal.collectAsStateWithLifecycle()
    val marginTopVal by viewModel.marginTop.collectAsStateWithLifecycle()
    val marginBottomVal by viewModel.marginBottom.collectAsStateWithLifecycle()
    val autoPageInterval by viewModel.autoPageInterval.collectAsStateWithLifecycle()
    val customCss by viewModel.customCss.collectAsStateWithLifecycle()
    val customBgImage by viewModel.customBgImage.collectAsStateWithLifecycle()
    val selectedText by viewModel.selectedText.collectAsStateWithLifecycle()
    val viewingImageSrc by viewModel.viewingImageSrc.collectAsStateWithLifecycle()
    val ttsScrollProgress by viewModel.ttsScrollProgress.collectAsStateWithLifecycle()
    val pageAnim by viewModel.pageAnim.collectAsStateWithLifecycle()
    val tapTL by viewModel.tapActionTopLeft.collectAsStateWithLifecycle()
    val tapTR by viewModel.tapActionTopRight.collectAsStateWithLifecycle()
    val tapBL by viewModel.tapActionBottomLeft.collectAsStateWithLifecycle()
    val tapBR by viewModel.tapActionBottomRight.collectAsStateWithLifecycle()
    val readerStyles by viewModel.allStyles.collectAsStateWithLifecycle()
    val activeStyleId by viewModel.activeStyleId.collectAsStateWithLifecycle()
    val activeStyle by viewModel.activeStyle.collectAsStateWithLifecycle()
    val screenOrientation by viewModel.screenOrientation.collectAsStateWithLifecycle()
    val hdrLeft by viewModel.headerLeft.collectAsStateWithLifecycle()
    val hdrCenter by viewModel.headerCenter.collectAsStateWithLifecycle()
    val hdrRight by viewModel.headerRight.collectAsStateWithLifecycle()
    val ftrLeft by viewModel.footerLeft.collectAsStateWithLifecycle()
    val ftrCenter by viewModel.footerCenter.collectAsStateWithLifecycle()
    val ftrRight by viewModel.footerRight.collectAsStateWithLifecycle()
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
    // Save progress on pause
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
    val readerBrightness by viewModel.readerBrightness.collectAsStateWithLifecycle()

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

    // Restore system bars before navigating away to avoid flash
    val restoreSystemBars = {
        val act = context as? android.app.Activity
        if (act != null) {
            val ctrl = WindowCompat.getInsetsController(act.window, act.window.decorView)
            ctrl.show(WindowInsetsCompat.Type.statusBars())
        }
    }

    // Back button: dismiss overlays first, then exit
    BackHandler(enabled = true) {
        when {
            showFullSearch -> showFullSearch = false
            showBookmarks -> showBookmarks = false
            showChapterList -> showChapterList = false
            showTtsPanel -> viewModel.hideTtsPanel()
            showSettings -> viewModel.hideSettingsPanel()
            showControls -> viewModel.hideControls()
            else -> {
                restoreSystemBars()
                onBack()
            }
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

    // ── Immersive mode: hide status bar in reader (ported from Legado BaseReadBookActivity) ──
    val showStatusBar by viewModel.showStatusBar.collectAsStateWithLifecycle()
    DisposableEffect(showStatusBar) {
        val act = context as? android.app.Activity ?: return@DisposableEffect onDispose {}
        val window = act.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (showStatusBar) {
            controller.show(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.statusBars())
        }
        onDispose {
            val a = context as? android.app.Activity ?: return@onDispose
            WindowCompat.getInsetsController(a.window, a.window.decorView)
                .show(WindowInsetsCompat.Type.statusBars())
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
        // Resolve background image: customBgImage (global pref) takes priority,
        // then activeStyle's per-style bg image (day/night aware)
        val readerBgImage = customBgImage.ifEmpty {
            (if (isNight) activeStyle?.bgImageUriNight else activeStyle?.bgImageUri) ?: ""
        }
        val isTxtFormat = book?.format == com.morealm.app.domain.entity.BookFormat.TXT

        // Always use Canvas renderer
        com.morealm.app.ui.reader.renderer.CanvasRenderer(
                content = content,
                chapterTitle = chapters.getOrNull(currentIndex)?.title ?: "",
                chapterIndex = currentIndex,
                backgroundColor = readerBg,
                textColor = readerFg,
                accentColor = MaterialTheme.colorScheme.primary,
                fontSize = readerFontSize,
                lineHeight = readerLineHeight,
                paddingHorizontal = marginHorizontal,
                paddingVertical = marginTopVal,
                bgImageUri = readerBgImage,
                startFromLastPage = navigateDirection < 0,
                initialProgress = scrollProgress,
                pageAnimType = pageAnim.toPageAnimType(),
                onTapCenter = { viewModel.toggleControls() },
                onProgress = { pct -> viewModel.updateScrollProgress(pct) },
                onNextChapter = { viewModel.nextChapter() },
                onPrevChapter = { viewModel.prevChapter() },
                onCopyText = { text -> viewModel.copyTextToClipboard(text) },
                onSpeakFromHere = { text -> viewModel.onTextSelected(text); viewModel.speakSelectedText() },
                onLookupWord = { text -> viewModel.onTextSelected(text) },
                onImageClick = { src -> viewModel.onImageClick(src) },
                bookTitle = book?.title ?: "",
                bookAuthor = book?.author ?: "",
                tapActionTopLeft = tapTL,
                tapActionTopRight = tapTR,
                tapActionBottomLeft = tapBL,
                tapActionBottomRight = tapBR,
                readerStyle = activeStyle,
                chaptersSize = chapters.size,
                headerLeft = hdrLeft,
                headerCenter = hdrCenter,
                headerRight = hdrRight,
                footerLeft = ftrLeft,
                footerCenter = ftrCenter,
                footerRight = ftrRight,
                modifier = Modifier.fillMaxSize(),
            )

        // Loading indicator
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).size(32.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 2.dp,
            )
        }

        // Top bar overlay (back button + chapter info)
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(tween(400, easing = androidx.compose.animation.core.CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f))) + slideInVertically(tween(400, easing = androidx.compose.animation.core.CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f))) { -it },
            exit = fadeOut(tween(200, easing = androidx.compose.animation.core.CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f))) + slideOutVertically(tween(200, easing = androidx.compose.animation.core.CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f))) { -it },
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
            enter = fadeIn(tween(400, easing = androidx.compose.animation.core.CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f))) + slideInVertically(tween(400, easing = androidx.compose.animation.core.CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f))) { it },
            exit = fadeOut(tween(200, easing = androidx.compose.animation.core.CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f))) + slideOutVertically(tween(200, easing = androidx.compose.animation.core.CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f))) { it },
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
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f))
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
            enter = slideInVertically(tween(400, easing = androidx.compose.animation.core.CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f))) { it },
            exit = slideOutVertically(tween(200, easing = androidx.compose.animation.core.CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f))) { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ReaderSettingsPanel(
                currentMode = pageTurnMode,
                onModeChange = viewModel::setPageTurnMode,
                pageAnim = pageAnim,
                onPageAnimChange = viewModel::setPageAnim,
                currentFont = fontFamily,
                onFontChange = viewModel::setFontFamily,
                currentFontSize = fontSize,
                onFontSizeChange = viewModel::setFontSize,
                currentLineHeight = lineHeight,
                onLineHeightChange = viewModel::setLineHeight,
                customFontName = customFontName,
                onImportFont = { uri, name -> viewModel.importCustomFont(uri, name) },
                onClearCustomFont = viewModel::clearCustomFont,
                allThemes = themeViewModel?.allThemes?.collectAsStateWithLifecycle()?.value ?: BuiltinThemes.all(),
                activeThemeId = themeViewModel?.activeTheme?.collectAsStateWithLifecycle()?.value?.id ?: "",
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
                textSelectable = viewModel.textSelectable.collectAsStateWithLifecycle().value,
                onTextSelectableChange = viewModel::setTextSelectable,
                chineseConvertMode = viewModel.chineseConvertMode.collectAsStateWithLifecycle().value,
                onChineseConvertModeChange = viewModel::setChineseConvertMode,
                onDismiss = viewModel::hideSettingsPanel,
            )
        }

        // TTS overlay panel — collect TTS state only when panel is visible
        AnimatedVisibility(
            visible = showTtsPanel,
            enter = slideInVertically(tween(400, easing = androidx.compose.animation.core.CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f))) { it },
            exit = slideOutVertically(tween(200, easing = androidx.compose.animation.core.CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f))) { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            val ttsPlaying by viewModel.ttsPlaying.collectAsStateWithLifecycle()
            val ttsSpeed by viewModel.ttsSpeed.collectAsStateWithLifecycle()
            val ttsEngine by viewModel.ttsEngine.collectAsStateWithLifecycle()
            val ttsParagraphIndex by viewModel.ttsParagraphIndex.collectAsStateWithLifecycle()
            val ttsTotalParagraphs by viewModel.ttsTotalParagraphs.collectAsStateWithLifecycle()
            val ttsSleepMinutes by viewModel.ttsSleepMinutes.collectAsStateWithLifecycle()
            val ttsVoices by viewModel.ttsVoices.collectAsStateWithLifecycle()
            val ttsVoiceName by viewModel.ttsVoiceName.collectAsStateWithLifecycle()
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
        ChapterBookmarkPanel(
            visible = showChapterList || showBookmarks,
            chapters = chapters,
            bookmarks = viewModel.bookmarks.collectAsStateWithLifecycle().value,
            currentChapter = currentIndex,
            selectedSideTab = if (showBookmarks) 1 else 0,
            linkedBooks = linkedBooks,
            moColors = moColors,
            onTabChange = { tab ->
                if (tab == 0) { showBookmarks = false; showChapterList = true }
                else { showChapterList = false; showBookmarks = true }
            },
            onChapterClick = { chapterIndex ->
                showChapterList = false
                viewModel.loadChapter(chapterIndex)
            },
            onAddBookmark = { viewModel.addBookmark() },
            onDeleteBookmark = { id -> viewModel.deleteBookmark(id) },
            onJumpToBookmark = { bm ->
                showBookmarks = false
                viewModel.jumpToBookmark(bm)
            },
            onLinkedBookClick = { bookId ->
                showChapterList = false
                onNavigateToBook(bookId)
            },
            onDismiss = { showChapterList = false; showBookmarks = false },
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        // Full text search panel
        FullTextSearchPanel(
            visible = showFullSearch,
            searchResults = viewModel.searchResults.collectAsStateWithLifecycle().value,
            isSearching = viewModel.searching.collectAsStateWithLifecycle().value,
            moColors = moColors,
            onSearch = { query -> viewModel.searchFullText(query) },
            onResultClick = { result ->
                showFullSearch = false
                viewModel.clearSearchResults()
                viewModel.loadChapter(result.chapterIndex)
            },
            onDismiss = { showFullSearch = false },
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        // Auto page turn indicator
        AutoPageIndicator(
            visible = autoPageInterval > 0,
            intervalSeconds = autoPageInterval,
            accentColor = MaterialTheme.colorScheme.primary,
            onStop = { viewModel.stopAutoPage() },
            modifier = Modifier.align(Alignment.TopEnd),
        )

        // Image viewer dialog (full-screen zoomable)
        viewingImageSrc?.let { src ->
            ImageViewerDialog(
                imageSrc = src,
                onDismiss = { viewModel.dismissImageViewer() },
            )
        }

        // Next book prompt dialog
        nextBookPrompt?.let { nextBook ->
            NextBookPromptDialog(
                nextBookTitle = nextBook.title,
                accentColor = MaterialTheme.colorScheme.primary,
                onConfirm = {
                    viewModel.dismissNextBookPrompt()
                    onNavigateToBook(nextBook.id)
                },
                onDismiss = { viewModel.dismissNextBookPrompt() },
            )
        }
    }
}

// ── Extracted composables ──────────────────────────────────────────────────────

@Composable
private fun ChapterBookmarkPanel(
    visible: Boolean,
    chapters: List<BookChapter>,
    bookmarks: List<Bookmark>,
    currentChapter: Int,
    selectedSideTab: Int,
    linkedBooks: List<Book>,
    moColors: MoRealmColors,
    onTabChange: (Int) -> Unit,
    onChapterClick: (Int) -> Unit,
    onAddBookmark: () -> Unit,
    onDeleteBookmark: (String) -> Unit,
    onJumpToBookmark: (Bookmark) -> Unit,
    onLinkedBookClick: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(tween(400, easing = androidx.compose.animation.core.CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f))) { it },
        exit = slideOutVertically(tween(200, easing = androidx.compose.animation.core.CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f))) { it },
        modifier = modifier,
    ) {
        val isBookmarkTab = selectedSideTab == 1
        Surface(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.6f),
            color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.97f),
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
                            MaterialTheme.shapes.extraSmall
                        )
                        .align(Alignment.CenterHorizontally)
                )
                Spacer(Modifier.height(8.dp))
                // Tab row: 目录 | 书签
                Row(modifier = Modifier.padding(horizontal = 16.dp)) {
                    FilterChip(
                        selected = !isBookmarkTab,
                        onClick = { onTabChange(0) },
                        label = { Text("目录 (${chapters.size})") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            selectedLabelColor = MaterialTheme.colorScheme.primary),
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        selected = isBookmarkTab,
                        onClick = { onTabChange(1) },
                        label = { Text("书签 (${bookmarks.size})") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            selectedLabelColor = MaterialTheme.colorScheme.primary),
                    )
                    Spacer(Modifier.weight(1f))
                    if (!isBookmarkTab) {
                        // Add bookmark button
                        IconButton(
                            onClick = onAddBookmark,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                Icons.Default.BookmarkAdd, "添加书签",
                                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp),
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
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            cursorColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                    Spacer(Modifier.height(4.dp))
                    val listState = rememberLazyListState(
                        initialFirstVisibleItemIndex = if (chapterSearch.isBlank()) (currentChapter - 2).coerceAtLeast(0) else 0
                    )
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        itemsIndexed(filteredChapters, key = { _, ch -> ch.id }) { _, ch ->
                            val isCurrent = ch.index == currentChapter
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onChapterClick(ch.index) }
                                    .background(
                                        if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                                        else Color.Transparent,
                                        MaterialTheme.shapes.small
                                    )
                                    .padding(horizontal = 12.dp, vertical = 11.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    ch.title,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isCurrent) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                if (isCurrent) {
                                    Text("▶", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
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
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 4.dp),
                                )
                            }
                            item(key = "linked_tap_${linkedBook.id}") {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onLinkedBookClick(linkedBook.id) }
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                ) {
                                    Text("打开阅读 →", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                                }
                            }
                        }
                    }
                } else {
                    // Bookmarks tab
                    if (bookmarks.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("暂无书签", style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        ) {
                            items(bookmarks, key = { it.id }) { bm ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onJumpToBookmark(bm) }
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
                                        onClick = { onDeleteBookmark(bm.id) },
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
}

@Composable
private fun FullTextSearchPanel(
    visible: Boolean,
    searchResults: List<ReaderViewModel.SearchResult>,
    isSearching: Boolean,
    moColors: MoRealmColors,
    onSearch: (String) -> Unit,
    onResultClick: (ReaderViewModel.SearchResult) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(tween(400, easing = androidx.compose.animation.core.CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f))) { it },
        exit = slideOutVertically(tween(200, easing = androidx.compose.animation.core.CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f))) { it },
        modifier = modifier,
    ) {
        var searchQuery by remember { mutableStateOf("") }
        Surface(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.6f),
            color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.97f),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            tonalElevation = 12.dp,
        ) {
            Column(modifier = Modifier.navigationBarsPadding().padding(top = 16.dp)) {
                Box(
                    Modifier.width(40.dp).height(4.dp)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f), MaterialTheme.shapes.extraSmall)
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
                        IconButton(onClick = { onSearch(searchQuery) }) {
                            if (isSearching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                            else Icon(Icons.Default.Search, "搜索", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(48.dp),
                    textStyle = MaterialTheme.typography.bodySmall,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary, cursorColor = MaterialTheme.colorScheme.primary),
                )
                Spacer(Modifier.height(4.dp))
                if (searchResults.isEmpty() && !isSearching && searchQuery.isNotEmpty()) {
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
                                .clickable { onResultClick(result) }
                                .padding(horizontal = 4.dp, vertical = 8.dp),
                        ) {
                            Column {
                                Text(result.chapterTitle, style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
}

@Composable
private fun NextBookPromptDialog(
    nextBookTitle: String,
    accentColor: Color,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("本书已读完") },
        text = { Text("是否继续阅读《${nextBookTitle}》？") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("继续阅读", color = accentColor)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("留在这里")
            }
        },
    )
}

@Composable
private fun AutoPageIndicator(
    visible: Boolean,
    intervalSeconds: Int,
    accentColor: Color,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (visible) {
        Surface(
            modifier = modifier.padding(top = 48.dp, end = 12.dp),
            color = accentColor.copy(alpha = 0.85f),
            shape = MaterialTheme.shapes.large,
        ) {
            Row(
                modifier = Modifier.clickable(onClick = onStop).padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("自动 ${intervalSeconds}s", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.surface)
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Default.Close, "停止", tint = MaterialTheme.colorScheme.surface, modifier = Modifier.size(14.dp))
            }
        }
    }
}
