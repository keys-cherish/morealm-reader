package com.morealm.app.ui.reader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.view.KeyEvent
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.morealm.app.presentation.theme.ThemeViewModel
import com.morealm.app.domain.entity.BuiltinThemes
import com.morealm.app.ui.theme.LocalMoRealmColors
import com.morealm.app.ui.theme.toComposeColor
import com.morealm.app.presentation.reader.ReaderViewModel
import com.morealm.app.presentation.reader.PageTurnMode
import com.morealm.app.ui.reader.renderer.ReaderPageDirection
import com.morealm.app.ui.reader.page.animation.toPageAnimType
import com.morealm.app.ui.reader.TtsOverlayPanel
import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.entity.BookChapter
import com.morealm.app.domain.entity.Bookmark
import com.morealm.app.domain.entity.ReaderStyle
import com.morealm.app.domain.entity.displayTitle
import com.morealm.app.domain.entity.isAutoSplitChapter
import com.morealm.app.presentation.reader.ReaderSearchController
import androidx.compose.ui.graphics.Color
import com.morealm.app.core.log.AppLog
import com.morealm.app.ui.theme.MoRealmColors
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * 音量键长按"翻章"模式下，多少个 [android.view.KeyEvent.ACTION_DOWN] 重复事件后触发一次翻章。
 *
 * Android 默认按键重复频率约 12~15 Hz（首次重复延迟 ~500ms 后约每 50~80ms 一次），
 * 取 10 ≈ 持续按住 1~1.2 秒，平衡"误触"与"等待感"。第 11 次及之后忽略，避免
 * 一次长按连续翻多章。
 */
private const val LONG_PRESS_CHAPTER_THRESHOLD = 10

/** ReaderScreen 物理按键日志 tag（已注册到 LogTagCatalog 为 "Reader/Key"）。 */
private const val KEY_TAG = "ReaderKey"

@Composable
fun ReaderScreen(
    bookId: String,
    onBack: () -> Unit,
    onNavigateToBook: (String) -> Unit = {},
    /**
     * 跳转到替换规则编辑（EffectiveReplacesDialog 内点击规则名时触发）。
     * 接收 ruleId；导航到 replace_rules?editId=$ruleId 自动弹编辑框。
     */
    onNavigateToReplaceRule: (ruleId: String) -> Unit = {},
    /** 跳转到字体管理页（设置面板「字体管理…」按钮）。 */
    onNavigateToFontManager: () -> Unit = {},
    themeViewModel: ThemeViewModel? = null,
    viewModel: ReaderViewModel = hiltViewModel(),
) {
    val book by viewModel.book.collectAsStateWithLifecycle()
    val chapters by viewModel.chapters.collectAsStateWithLifecycle()
    // 是否本地 TXT 无目录自动分章本——决定是否跳过 CanvasRenderer 章首标题块、
    // 以及目录 chip 显示成 (1) 而不是 (249)。
    //
    // 命中条件：本地 TXT + 章节列表非空 + 全部章节 isAutoSplitChapter()
    // （标题命中 `^第\d+节$` 或纯"正文"，由 [LocalBookParser.parseWithoutToc] 硬切产生）。
    //
    // 由 [ChapterBookmarkPanel] 和 [com.morealm.app.ui.reader.renderer.CanvasRenderer]
    // 共用：前者据此渲染"《书名》整本书"单 entry + 计数 1；后者据此把
    // omitChapterTitleBlock 透传给 layoutChapter*/LazyScrollSection，让翻页/滚动
    // 模式都不画 isTitle 行（之前依赖 cur.title.looksLikeAutoSplitTitle() 嗅探，
    // 但 ReaderScreen 已用 displayTitle 把伪章名替换成书名，正则永远 miss）。
    val isAutoSplitTxt = remember(book, chapters) {
        book?.let { b ->
            !b.localPath.isNullOrEmpty() &&
                b.format == com.morealm.app.domain.entity.BookFormat.TXT &&
                chapters.isNotEmpty() &&
                chapters.all { it.isAutoSplitChapter() }
        } ?: false
    }
    val currentIndex by viewModel.currentChapterIndex.collectAsStateWithLifecycle()
    val content by viewModel.chapterContent.collectAsStateWithLifecycle()
    val renderedChapter by viewModel.renderedChapter.collectAsStateWithLifecycle()
    val visiblePage by viewModel.visiblePage.collectAsStateWithLifecycle()
    val nextPreloadedChapter by viewModel.nextPreloadedChapter.collectAsStateWithLifecycle()
    val prevPreloadedChapter by viewModel.prevPreloadedChapter.collectAsStateWithLifecycle()
    // Phase 2 MD3 同步腾挪源 — 由 ChapterController.commitChapterShiftNext/Prev 在主线程
    // 当帧重写。CanvasRenderer 的 syncPrev/NextTextChapter prop 优先于 prelayoutCache 派生。
    val syncPrevTextChapterValue by viewModel.chapter.prevTextChapter.collectAsStateWithLifecycle()
    val syncNextTextChapterValue by viewModel.chapter.nextTextChapter.collectAsStateWithLifecycle()
    val showControls by viewModel.showControls.collectAsStateWithLifecycle()
    val showTtsPanel by viewModel.showTtsPanel.collectAsStateWithLifecycle()
    val showSettings by viewModel.showSettingsPanel.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val pageTurnMode by viewModel.settings.pageTurnMode.collectAsStateWithLifecycle()
    val fontFamily by viewModel.settings.fontFamily.collectAsStateWithLifecycle()
    val fontSize by viewModel.settings.fontSize.collectAsStateWithLifecycle()
    val lineHeight by viewModel.settings.lineHeight.collectAsStateWithLifecycle()
    val scrollProgress by viewModel.scrollProgress.collectAsStateWithLifecycle()
    val linkedBooks by viewModel.linkedBooks.collectAsStateWithLifecycle()
    val nextBookPrompt by viewModel.nextBookPrompt.collectAsStateWithLifecycle()
    val navigateDirection by viewModel.navigateDirection.collectAsStateWithLifecycle()
    var pageTurnCommand by remember { mutableStateOf<ReaderPageDirection?>(null) }
    val customFontUri by viewModel.settings.customFontUri.collectAsStateWithLifecycle()
    val customFontName by viewModel.settings.customFontName.collectAsStateWithLifecycle()
    val readerTypeface by viewModel.settings.currentTypeface.collectAsStateWithLifecycle()
    val volumeKeyPage by viewModel.settings.volumeKeyPage.collectAsStateWithLifecycle()
    val volumeKeyReverse by viewModel.settings.volumeKeyReverse.collectAsStateWithLifecycle()
    val headsetButtonPage by viewModel.settings.headsetButtonPage.collectAsStateWithLifecycle()
    val volumeKeyLongPress by viewModel.settings.volumeKeyLongPress.collectAsStateWithLifecycle()
    val selectionMenuConfig by viewModel.settings.selectionMenuConfig.collectAsStateWithLifecycle()
    val screenTimeout by viewModel.settings.screenTimeout.collectAsStateWithLifecycle()
    val showChapterNameSetting by viewModel.settings.showChapterName.collectAsStateWithLifecycle()
    val showTimeBatterySetting by viewModel.settings.showTimeBattery.collectAsStateWithLifecycle()
    val tapLeftAction by viewModel.settings.tapLeftAction.collectAsStateWithLifecycle()
    val paragraphSpacing by viewModel.settings.paragraphSpacing.collectAsStateWithLifecycle()
    val marginHorizontal by viewModel.settings.marginHorizontal.collectAsStateWithLifecycle()
    val marginTopVal by viewModel.settings.marginTop.collectAsStateWithLifecycle()
    val marginBottomVal by viewModel.settings.marginBottom.collectAsStateWithLifecycle()
    /**
     * 页边距 preview state — 拖动滑块期间的临时值，仅在 Compose state 内流转，
     * 不写 Room、不触 StateFlow。`null` 表示该方向"未在拖动中"，回退到上面的 StateFlow 值。
     *
     * 拖动 onValueChange   → 写 preview，触发 CanvasRenderer 重组与重新分页
     * 拖动 onValueChangeFinished → 写 Room（持久化）+ 清空 preview（让单一来源回到 StateFlow）
     */
    var marginPreviewH by remember { mutableStateOf<Int?>(null) }
    var marginPreviewT by remember { mutableStateOf<Int?>(null) }
    var marginPreviewB by remember { mutableStateOf<Int?>(null) }
    val effectiveMarginH = marginPreviewH ?: marginHorizontal
    val effectiveMarginT = marginPreviewT ?: marginTopVal
    val effectiveMarginB = marginPreviewB ?: marginBottomVal
    val autoPageInterval by viewModel.autoPageInterval.collectAsStateWithLifecycle()
    // ttsChapterPosition 不在这里 collect —— 段切高频更新会触发整个 ReaderScreen
    // 重组（贴文里的「重组风暴」）。下沉到 [ReadAloudPositionScope] 这个 leaf
    // composable，仅它和 CanvasRenderer 那一层重组。
    val pendingSearchSelection by viewModel.pendingSearchSelection.collectAsStateWithLifecycle()
    val customCss by viewModel.settings.customCss.collectAsStateWithLifecycle()
    val customBgImage by viewModel.settings.customBgImage.collectAsStateWithLifecycle()
    val readerBgImageDay by viewModel.settings.readerBgImageDay.collectAsStateWithLifecycle()
    val readerBgImageNight by viewModel.settings.readerBgImageNight.collectAsStateWithLifecycle()
    val selectedText by viewModel.selectedText.collectAsStateWithLifecycle()
    val viewingImageSrc by viewModel.viewingImageSrc.collectAsStateWithLifecycle()
    val ttsScrollProgress by viewModel.tts.ttsScrollProgress.collectAsStateWithLifecycle()
    val pageAnim by viewModel.settings.pageAnim.collectAsStateWithLifecycle()
    // 章节标题对齐：透传给 CanvasRenderer，change 触发重新排版（layoutInputs.remember）。
    val titleAlign by viewModel.settings.titleAlign.collectAsStateWithLifecycle()
    val tapTL by viewModel.settings.tapActionTopLeft.collectAsStateWithLifecycle()
    val tapTR by viewModel.settings.tapActionTopRight.collectAsStateWithLifecycle()
    val tapBL by viewModel.settings.tapActionBottomLeft.collectAsStateWithLifecycle()
    val tapBR by viewModel.settings.tapActionBottomRight.collectAsStateWithLifecycle()
    val readerStyles by viewModel.settings.allStyles.collectAsStateWithLifecycle()
    val activeStyleId by viewModel.settings.activeStyleId.collectAsStateWithLifecycle()
    val activeStyle by viewModel.settings.activeStyle.collectAsStateWithLifecycle()
    val activeTheme by (themeViewModel?.activeTheme
        ?: flowOf<com.morealm.app.domain.entity.ThemeEntity?>(null))
        .collectAsStateWithLifecycle(null)
    val screenScope = rememberCoroutineScope()
    var exitingReader by remember { mutableStateOf(false) }
    var selectionWebPanel by remember { mutableStateOf<Pair<String, String>?>(null) }
    val allThemes by (themeViewModel?.allThemes ?: flowOf(BuiltinThemes.all()))
        .collectAsStateWithLifecycle(BuiltinThemes.all())
    val screenOrientation by viewModel.settings.screenOrientation.collectAsStateWithLifecycle()
    val hdrLeft by viewModel.settings.headerLeft.collectAsStateWithLifecycle()
    val hdrCenter by viewModel.settings.headerCenter.collectAsStateWithLifecycle()
    val hdrRight by viewModel.settings.headerRight.collectAsStateWithLifecycle()
    val ftrLeft by viewModel.settings.footerLeft.collectAsStateWithLifecycle()
    val ftrCenter by viewModel.settings.footerCenter.collectAsStateWithLifecycle()
    val ftrRight by viewModel.settings.footerRight.collectAsStateWithLifecycle()
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
    LaunchedEffect(viewModel.readAloudPageTurn) {
        viewModel.readAloudPageTurn.collect { direction ->
            pageTurnCommand = if (direction < 0) ReaderPageDirection.PREV else ReaderPageDirection.NEXT
        }
    }

    // Notification permission for TTS service (Android 13+)
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or not, TTS still works — notification just won't show */ }

    val context = LocalContext.current

    /**
     * 添加书签 + 立即弹 Toast 反馈。
     *
     * controller 走 fire-and-forget 协程插 DB（[ReaderBookmarkController.addBookmark]），
     * 在用户视角是"点了按钮屏幕没动静"——之前没有视觉反馈，用户会怀疑是否真的成功。
     * 用 Toast 而不是 Snackbar：Reader 大量浮层（toolbar / mini-menu / TTS bar）已经
     * 占着 Snackbar 默认位，再加一个会与现有浮层撞位；Toast 在系统层弹，零冲突。
     *
     * 没等 DB insert 回来：Repository 层基本不会因常规原因失败（无 unique 约束冲突，
     * id 用 timestamp），让用户立刻看到反馈比"等 IO 再 toast"更顺手。
     */
    fun addBookmarkWithToast() {
        viewModel.addBookmark()
        android.widget.Toast.makeText(context, "已添加书签", android.widget.Toast.LENGTH_SHORT).show()
    }

    fun openWebSearch(query: String) {
        if (query.isBlank()) return
        selectionWebPanel = "查词" to "https://www.google.com/search?q=${Uri.encode(query.trim())}"
    }

    fun openTranslate(text: String) {
        if (text.isBlank()) return
        selectionWebPanel = "翻译" to "https://translate.google.com/?sl=auto&tl=zh-CN&text=${Uri.encode(text.trim())}&op=translate"
    }

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

    fun exitReader() {
        if (exitingReader) return
        exitingReader = true
        restoreSystemBars()
        screenScope.launch {
            viewModel.saveProgressNowAndWait()
            onBack()
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
            viewingImageSrc != null -> viewModel.dismissImageViewer()
            autoPageInterval > 0 -> viewModel.stopAutoPage()
            showControls -> viewModel.hideControls()
            else -> exitReader()
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
    val showStatusBar by viewModel.settings.showStatusBar.collectAsStateWithLifecycle()
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
            .semantics { contentDescription = "阅读器" }
            .onKeyEvent { event ->
                // 物理按键翻页处理。两个开关分别管两组键：
                //   - 音量键 (VOLUME_UP/DOWN)：受 volumeKeyPage 管
                //   - 耳机/媒体/方向/翻页器键：受 headsetButtonPage 管
                // 都关 → 直接放行给系统。
                if (!volumeKeyPage && !headsetButtonPage) return@onKeyEvent false
                val nativeEvent = event.nativeKeyEvent
                val keyCode = nativeEvent.keyCode
                val isVolumeKey = keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                    keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                val isHeadsetKey = keyCode == KeyEvent.KEYCODE_HEADSETHOOK ||
                    keyCode == KeyEvent.KEYCODE_MEDIA_NEXT ||
                    keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS ||
                    keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                    keyCode == KeyEvent.KEYCODE_PAGE_UP ||
                    keyCode == KeyEvent.KEYCODE_PAGE_DOWN ||
                    keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                    keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                if (!isVolumeKey && !isHeadsetKey) return@onKeyEvent false
                if (isVolumeKey && !volumeKeyPage) return@onKeyEvent false
                if (isHeadsetKey && !headsetButtonPage) return@onKeyEvent false

                // 阅读菜单/设置面板/TTS 面板等显示时，把音量键还给系统（让用户能调音量）。
                // 媒体/方向键不让系统抢，因为蓝牙翻页器即便菜单弹出也希望继续翻。
                val anyMenuOpen = showControls || showSettings || showTtsPanel ||
                    showChapterList || showBookmarks || showFullSearch
                if (anyMenuOpen && isVolumeKey) return@onKeyEvent false

                if (nativeEvent.action != KeyEvent.ACTION_DOWN) return@onKeyEvent false
                val repeatCount = nativeEvent.repeatCount
                val isTtsActive = viewModel.tts.ttsPlaying.value

                // 解析"原始方向"：哪个方向是 NEXT，哪个是 PREV
                // KEYCODE_HEADSETHOOK 在 TTS 时单击切换播放，这里走特殊路径。
                if (keyCode == KeyEvent.KEYCODE_HEADSETHOOK ||
                    keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                ) {
                    if (repeatCount != 0) return@onKeyEvent true
                    if (isTtsActive) {
                        AppLog.debug(KEY_TAG, "headsetHook→ttsPlayPause (key=$keyCode)")
                        viewModel.tts.ttsPlayPause()
                    } else {
                        AppLog.debug(KEY_TAG, "headsetHook→nextPage (key=$keyCode, no TTS)")
                        pageTurnCommand = ReaderPageDirection.NEXT
                    }
                    return@onKeyEvent true
                }

                val nextKeys = setOf(
                    KeyEvent.KEYCODE_VOLUME_DOWN,
                    KeyEvent.KEYCODE_MEDIA_NEXT,
                    KeyEvent.KEYCODE_PAGE_DOWN,
                    KeyEvent.KEYCODE_DPAD_RIGHT,
                )
                val rawDir = if (keyCode in nextKeys) {
                    ReaderPageDirection.NEXT
                } else {
                    ReaderPageDirection.PREV
                }
                // 仅音量键参与方向反转（蓝牙翻页器自带左右标识，不该被反转）
                val dir = if (isVolumeKey && volumeKeyReverse) {
                    if (rawDir == ReaderPageDirection.NEXT) ReaderPageDirection.PREV
                    else ReaderPageDirection.NEXT
                } else rawDir

                // 单击：常规翻页 / TTS 段切换
                if (repeatCount == 0) {
                    AppLog.debug(
                        KEY_TAG,
                        "single key=$keyCode src=${if (isVolumeKey) "volume" else "headset"} " +
                            "raw=$rawDir dir=$dir reverse=$volumeKeyReverse tts=$isTtsActive",
                    )
                    if (isTtsActive) {
                        if (dir == ReaderPageDirection.NEXT) viewModel.tts.ttsNextParagraph()
                        else viewModel.tts.ttsPrevParagraph()
                    } else {
                        pageTurnCommand = dir
                    }
                    return@onKeyEvent true
                }

                // 长按：按 volumeKeyLongPress 模式分支（仅音量键参与"翻章"，避免媒体键长按误触）
                if (isVolumeKey) {
                    when (volumeKeyLongPress) {
                        "page" -> {
                            // 跟系统按键重复节奏连续翻页（每次 repeat 都翻 → 不打日志，避免刷屏）
                            if (isTtsActive) {
                                if (dir == ReaderPageDirection.NEXT) viewModel.tts.ttsNextParagraph()
                                else viewModel.tts.ttsPrevParagraph()
                            } else {
                                pageTurnCommand = dir
                            }
                        }
                        "chapter" -> {
                            // 长按 ~1s（约 10 个 repeat）后翻一次章，期间忽略后续重复
                            if (repeatCount == LONG_PRESS_CHAPTER_THRESHOLD) {
                                AppLog.info(
                                    KEY_TAG,
                                    "long-press chapter trigger: dir=$dir " +
                                        "(repeat=$repeatCount, threshold=$LONG_PRESS_CHAPTER_THRESHOLD)",
                                )
                                if (dir == ReaderPageDirection.NEXT) viewModel.nextChapter()
                                else viewModel.prevChapter()
                            }
                        }
                        else -> { /* off：长按不响应（吞键避免连续翻页） */ }
                    }
                }
                true
            }
    ) {
        // WebView reader content — handles all touch events internally via JS
        // Resolve reader colors: activeStyle overrides theme defaults
        val isNight = moColors.isNight
        // Reader bg/fg follow the THEME color, not the activeStyle.
        // The user expects: dark theme → dark bg, light theme → light bg.
        val readerBg = moColors.readerBackground
        val readerFg = moColors.readerText
        val readerFontSize = activeStyle?.textSize?.toFloat() ?: fontSize
        val readerLineHeight = activeStyle?.lineHeight ?: lineHeight
        val readerFontFamily = activeStyle?.fontFamily ?: fontFamily
        // Resolve background image priority:
        // 1. Per-style customBgImage (from reader bottom panel)
        // 2. Global day/night bg image (from Reading Settings)
        // 3. Per-style bgImageUri/bgImageUriNight
        val readerBgImage = customBgImage.ifEmpty {
            val globalBg = if (isNight) readerBgImageNight else readerBgImageDay
            globalBg.ifEmpty {
                (if (isNight) activeStyle?.bgImageUriNight else activeStyle?.bgImageUri) ?: ""
            }
        }
        val themeCss = activeTheme?.customCss.orEmpty()
        val currentStyle = activeStyle
        val effectiveReaderStyle = remember(currentStyle, themeCss) {
            when {
                themeCss.isBlank() -> currentStyle
                currentStyle == null -> ReaderStyle(
                    id = "theme_css",
                    name = "Theme CSS",
                    customCss = themeCss,
                )
                currentStyle.customCss.isBlank() -> currentStyle.copy(customCss = themeCss)
                else -> currentStyle.copy(customCss = "$themeCss\n${currentStyle.customCss}")
            }
        }
        val isTxtFormat = book?.format == com.morealm.app.domain.entity.BookFormat.TXT
        val displayContent = renderedChapter.content.ifEmpty { content }

        // Keep the last real reader surface on screen. During initial loading, avoid rendering
        // a synthetic 1/1 empty chapter that shows up as a visible white/loading flicker in LDPlayer.
        if (displayContent.isNotBlank()) {
            // 把 ttsChapterPosition 的 collect 收敛进这个 leaf composable —— 段切
            // 时只重组 [ReadAloudPositionScope] 自己 + 内部 lambda 里的 CanvasRenderer
            // 调用，不再让外层 ReaderScreen 整体 recompose。CanvasRenderer 自身的
            // stable check 会进一步把更新缩窄到 readAloudChapterPosition 一个参数。
            ReadAloudPositionScope(viewModel.tts.ttsChapterPosition) { ttsChapterPosition ->
            com.morealm.app.ui.reader.renderer.CanvasRenderer(
                content = displayContent,
                chapterTitle = chapters.getOrNull(currentIndex)?.displayTitle(book) ?: renderedChapter.title,
                chapterIndex = renderedChapter.index,
                nextChapterTitle = nextPreloadedChapter?.takeIf { it.index == currentIndex + 1 }?.let { ch -> chapters.getOrNull(ch.index)?.displayTitle(book) ?: ch.title } ?: "",
                nextChapterContent = nextPreloadedChapter?.takeIf { it.index == currentIndex + 1 }?.content ?: "",
                prevChapterTitle = prevPreloadedChapter?.takeIf { it.index == currentIndex - 1 }?.let { ch -> chapters.getOrNull(ch.index)?.displayTitle(book) ?: ch.title } ?: "",
                prevChapterContent = prevPreloadedChapter?.takeIf { it.index == currentIndex - 1 }?.content ?: "",
                backgroundColor = readerBg,
                textColor = readerFg,
                accentColor = MaterialTheme.colorScheme.primary,
                isNight = isNight,
                fontSize = readerFontSize,
                lineHeight = readerLineHeight,
                typeface = readerTypeface,
                paddingHorizontal = effectiveMarginH,
                paddingVertical = effectiveMarginT, // legacy fallback；下面 paddingTop/Bottom 优先
                paddingTop = effectiveMarginT,
                paddingBottom = effectiveMarginB,
                bgImageUri = readerBgImage,
                startFromLastPage = navigateDirection < 0,
                initialProgress = renderedChapter.initialProgress,
                initialChapterPosition = renderedChapter.initialChapterPosition,
                restoreToken = renderedChapter.restoreToken,
                onProgressRestored = { viewModel.clearNavigateDirection() },
                pageAnimType = pageAnim.toPageAnimType(),
                onTapCenter = { viewModel.toggleControls() },
                onProgress = { pct -> viewModel.updateScrollProgress(pct) },
                onVisiblePageChanged = { index, title, progress, chapterPosition ->
                    viewModel.onVisiblePageChanged(index, title, progress, chapterPosition)
                },
                onNextChapter = { viewModel.nextChapter() },
                onPrevChapter = { viewModel.prevChapter() },
                pageTurnCommand = pageTurnCommand,
                onPageTurnCommandConsumed = { pageTurnCommand = null },
                autoPageSeconds = autoPageInterval,
                readAloudChapterPosition = ttsChapterPosition,
                onScrollNearBottom = { if (!showControls) viewModel.onScrollNearBottom() },
                onScrollReachedBottom = { if (!showControls) viewModel.onScrollReachedBottom() },
                onCopyText = { text -> viewModel.copyTextToClipboard(text) },
                onSpeakFromHere = { chapterPosition -> viewModel.readAloudFromPosition(chapterPosition) },
                onTranslateText = { text -> openTranslate(text) },
                onLookupWord = { text -> openWebSearch(text) },
                onImageClick = { src -> viewModel.onImageClick(src) },
                chapterHighlights = viewModel.highlights.collectAsStateWithLifecycle().value,
                onAddHighlight = { start, end, content, argb ->
                    viewModel.highlight.add(
                        chapterIndex = renderedChapter.index,
                        startChapterPos = start,
                        endChapterPos = end,
                        content = content,
                        colorArgb = argb,
                    )
                },
                onDeleteHighlight = { id -> viewModel.highlight.delete(id) },
                // 橡皮：删除所有与当前选区有交集的高亮（覆盖删除语义）。
                // chapterIndex 用 renderedChapter.index 而不是 currentIndex —
                // 用户选区一定在已渲染的章节里，避免下载中切章导致错章删除。
                onEraseHighlight = { start, end ->
                    viewModel.highlight.eraseInRange(
                        chapterIndex = renderedChapter.index,
                        startChapterPos = start,
                        endChapterPos = end,
                    )
                },
                // 字体强调色：选区菜单 TEXT_COLOR 调色板某色 → 落库 kind=1。
                // chapterIndex 与背景高亮一致取 renderedChapter.index，避免切章错位。
                onAddTextColor = { start, end, content, argb ->
                    viewModel.highlight.add(
                        chapterIndex = renderedChapter.index,
                        startChapterPos = start,
                        endChapterPos = end,
                        content = content,
                        colorArgb = argb,
                        kind = com.morealm.app.domain.entity.Highlight.KIND_TEXT_COLOR,
                    )
                },
                onShareHighlight = { highlight ->
                    val ok = com.morealm.app.ui.reader.share.HighlightShareCard
                        .shareAsImage(context, highlight)
                    if (!ok) {
                        // 失败兜底：用纯文本 share，不让用户什么都拿不到
                        com.morealm.app.core.log.AppLog.warn("Highlight",
                            "share-card render failed; fall back to text-only share id=${highlight.id}")
                        runCatching {
                            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(
                                    android.content.Intent.EXTRA_TEXT,
                                    "${highlight.content}\n\n— 《${highlight.bookTitle}》· ${highlight.chapterTitle}",
                                )
                            }
                            context.startActivity(android.content.Intent.createChooser(intent, "分享高亮"))
                        }
                    }
                },
                onReadAloudParagraphPositions = { positions -> viewModel.updateReadAloudParagraphPositions(positions) },
                onVisibleReadAloudPosition = { index, position -> viewModel.updateVisibleReadAloudPosition(index, position) },
                pendingSearchSelection = pendingSearchSelection,
                onSearchSelectionConsumed = { viewModel.consumeSearchSelection() },
                onToggleTts = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    viewModel.toggleTtsPanel()
                },
                onAddBookmark = { addBookmarkWithToast() },
                bookTitle = book?.title ?: "",
                bookAuthor = book?.author ?: "",
                tapActionTopLeft = tapTL,
                tapActionTopRight = tapTR,
                tapActionBottomLeft = tapBL,
                tapActionBottomRight = tapBR,
                readerStyle = effectiveReaderStyle,
                chaptersSize = chapters.size,
                titleAlign = titleAlign,
                showChapterName = showChapterNameSetting,
                showTimeBattery = showTimeBatterySetting,
                headerLeft = hdrLeft,
                headerCenter = hdrCenter,
                headerRight = hdrRight,
                footerLeft = ftrLeft,
                footerCenter = ftrCenter,
                footerRight = ftrRight,
                selectionMenuConfig = selectionMenuConfig,
                // ── Phase 2 MD3 同步腾挪接通 ──
                // 三个 publish callback 让 CanvasRenderer 把 layoutChapterAsync 完成的
                // TextChapter 推回 ChapterController 的 _prev/_cur/_nextTextChapter，
                // sync 流让 ScrollRenderer 直接读真值（绕开 prelayoutCache 异步派生）。
                onCurTextChapterReady = { idx, ch -> viewModel.chapter.publishCurTextChapter(idx, ch) },
                onPrevTextChapterReady = { idx, ch -> viewModel.chapter.publishPrevTextChapter(idx, ch) },
                onNextTextChapterReady = { idx, ch -> viewModel.chapter.publishNextTextChapter(idx, ch) },
                syncPrevTextChapter = syncPrevTextChapterValue,
                syncNextTextChapter = syncNextTextChapterValue,
                // Phase 2e: 滚动模式跨章 commit 优先走同步腾挪——成功则 ChapterController
                // 当帧已更新所有相关 StateFlow，CanvasRenderer 跳过 onNextChapter()/onPrevChapter()
                // 避免老 loadChapter 异步路径覆盖。返回 false 时回退老路径。
                onChapterCommitShift = { direction ->
                    when (direction) {
                        com.morealm.app.ui.reader.renderer.ReaderPageDirection.NEXT ->
                            viewModel.commitChapterShiftNext()
                        com.morealm.app.ui.reader.renderer.ReaderPageDirection.PREV ->
                            viewModel.commitChapterShiftPrev()
                        else -> false
                    }
                },
                omitChapterTitleBlock = isAutoSplitTxt,
                modifier = Modifier.fillMaxSize(),
            )
            } // end ReadAloudPositionScope lambda
        }

        // Loading indicator
        if (loading && displayContent.isBlank()) {
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
                onBack = ::exitReader,
                onExport = {
                    val fileName = (book?.title ?: "book") + ".txt"
                    exportLauncher.launch(fileName)
                },
                onBookmark = { addBookmarkWithToast() },
                onEffectiveReplaces = { viewModel.showEffectiveReplacesDialog() },
                onSettings = {
                    viewModel.hideControls()
                    viewModel.toggleSettingsPanel()
                },
            )
        }

        // Bottom control bar
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(tween(300)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ReaderControlBar(
                currentChapter = currentIndex,
                totalChapters = chapters.size,
                chapterTitle = chapters.getOrNull(currentIndex)?.displayTitle(book) ?: visiblePage.title,
                readProgress = visiblePage.readProgress,
                scrollProgress = scrollProgress,
                onBack = ::exitReader,
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

        // Floating day/night toggle button (always visible, independent of control bar).
        // Anchored at BottomCenter so the toggle is reachable with either thumb regardless
        // of handedness — previously BottomEnd, which crowded right-handed page-turn taps.
        //
        // UX-1 (沉浸感): 阅读时常驻浮动按钮违背"沉浸式阅读"直觉. 不删除 (用户依赖该入口),
        //   但削弱 alpha (0.6→0.35 / 0.7→0.5) 让它"半隐", 并把 padding 从 16dp→22dp
        //   让按钮远离 BC 翻页区, 减少用户对误触的视觉担忧.
        if (!showControls && !showSettings && !showTtsPanel && !showChapterList && !showBookmarks && !showFullSearch) {
            androidx.compose.material3.FilledIconButton(
                onClick = { themeViewModel?.toggleDayNight() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 22.dp)
                    .size(36.dp),
                colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.35f),
                    contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                ),
            ) {
                androidx.compose.material3.Icon(
                    imageVector = if (isNight) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
                    contentDescription = if (isNight) "切换日间" else "切换夜间",
                    modifier = Modifier.size(18.dp),
                )
            }
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
                onModeChange = viewModel.settings::setPageTurnMode,
                pageAnim = pageAnim,
                onPageAnimChange = viewModel.settings::setPageAnim,
                currentFont = fontFamily,
                onFontChange = viewModel.settings::setFontFamily,
                currentFontSize = fontSize,
                onFontSizeChange = viewModel.settings::setFontSize,
                currentLineHeight = lineHeight,
                onLineHeightChange = viewModel.settings::setLineHeight,
                customFontName = customFontName,
                onImportFont = { uri, name -> viewModel.settings.importCustomFont(uri, name) },
                onClearCustomFont = viewModel.settings::clearCustomFont,
                onOpenFontManager = onNavigateToFontManager,
                allThemes = allThemes.ifEmpty { BuiltinThemes.all() },
                activeThemeId = activeTheme?.id ?: "",
                onThemeChange = { id -> themeViewModel?.switchTheme(id) },
                brightness = readerBrightness,
                onBrightnessChange = viewModel::setReaderBrightness,
                paragraphSpacing = paragraphSpacing,
                onParagraphSpacingChange = viewModel.settings::setParagraphSpacing,
                marginHorizontal = effectiveMarginH,
                onMarginHorizontalPreview = { marginPreviewH = it },
                onMarginHorizontalCommit = { v ->
                    viewModel.settings.setMarginHorizontal(v)
                    marginPreviewH = null
                },
                marginTop = effectiveMarginT,
                onMarginTopPreview = { marginPreviewT = it },
                onMarginTopCommit = { v ->
                    viewModel.settings.setMarginTop(v)
                    marginPreviewT = null
                },
                marginBottom = effectiveMarginB,
                onMarginBottomPreview = { marginPreviewB = it },
                onMarginBottomCommit = { v ->
                    viewModel.settings.setMarginBottom(v)
                    marginPreviewB = null
                },
                customCss = customCss,
                onCustomCssChange = viewModel.settings::setCustomCss,
                customBgImage = customBgImage,
                onCustomBgImageChange = viewModel.settings::setCustomBgImage,
                readerStyles = readerStyles,
                activeStyleId = activeStyleId,
                onStyleChange = viewModel.settings::switchStyle,
                screenOrientation = screenOrientation,
                onScreenOrientationChange = viewModel.settings::setScreenOrientation,
                textSelectable = viewModel.settings.textSelectable.collectAsStateWithLifecycle().value,
                onTextSelectableChange = viewModel.settings::setTextSelectable,
                chineseConvertMode = viewModel.settings.chineseConvertMode.collectAsStateWithLifecycle().value,
                onChineseConvertModeChange = viewModel::setChineseConvertMode,
                footerRight = ftrRight,
                onFooterRightChange = { viewModel.settings.setHeaderFooter("footerRight", it) },
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
            val ttsPlaying by viewModel.tts.ttsPlaying.collectAsStateWithLifecycle()
            val ttsSpeed by viewModel.tts.ttsSpeed.collectAsStateWithLifecycle()
            val ttsEngine by viewModel.tts.ttsEngine.collectAsStateWithLifecycle()
            val ttsParagraphIndex by viewModel.tts.ttsParagraphIndex.collectAsStateWithLifecycle()
            val ttsTotalParagraphs by viewModel.tts.ttsTotalParagraphs.collectAsStateWithLifecycle()
            val ttsSleepMinutes by viewModel.tts.ttsSleepMinutes.collectAsStateWithLifecycle()
            val ttsVoices by viewModel.tts.ttsVoices.collectAsStateWithLifecycle()
            val ttsVoiceName by viewModel.tts.ttsVoiceName.collectAsStateWithLifecycle()
            TtsOverlayPanel(
                bookTitle = book?.title ?: "",
                chapterTitle = chapters.getOrNull(currentIndex)?.displayTitle(book) ?: "",
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
                onPrevParagraph = viewModel.tts::ttsPrevParagraph,
                onNextParagraph = viewModel.tts::ttsNextParagraph,
                onSpeedChange = viewModel.tts::setTtsSpeed,
                onEngineChange = viewModel.tts::setTtsEngine,
                onSleepTimerSet = viewModel.tts::setTtsSleepTimer,
                voices = ttsVoices,
                selectedVoice = ttsVoiceName,
                onVoiceChange = viewModel.tts::setTtsVoice,
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
            book = book,
            isAutoSplitTxt = isAutoSplitTxt,
            moColors = moColors,
            onTabChange = { tab ->
                if (tab == 0) { showBookmarks = false; showChapterList = true }
                else { showChapterList = false; showBookmarks = true }
            },
            onChapterClick = { chapterIndex ->
                showChapterList = false
                viewModel.loadChapter(chapterIndex)
            },
            onAddBookmark = { addBookmarkWithToast() },
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
                viewModel.openSearchResult(result)
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

        selectionWebPanel?.let { (title, url) ->
            SelectionWebPanel(
                title = title,
                url = url,
                onDismiss = { selectionWebPanel = null },
            )
        }

        // Next book prompt dialog
        nextBookPrompt?.let { nextBook ->
            NextBookPromptDialog(
                nextBookTitle = nextBook.title,
                accentColor = MaterialTheme.colorScheme.primary,
                onConfirm = { viewModel.openNextLinkedBook() },
                onDismiss = { viewModel.dismissNextBookPrompt() },
            )
        }

        // ── #5 EffectiveReplacesDialog ──
        // 显示当前章「真命中」的替换规则 + 繁简转换占位。
        // dismiss 时若用户做了改动（禁用 / 编辑 / 改繁简），重渲染当前章让规则立即生效。
        val showEffectiveDialog by viewModel.showEffectiveReplacesDialog.collectAsStateWithLifecycle()
        if (showEffectiveDialog) {
            val hitContent by viewModel.hitContentRules.collectAsStateWithLifecycle()
            val hitTitle by viewModel.hitTitleRules.collectAsStateWithLifecycle()
            val cnMode by viewModel.settings.chineseConvertMode.collectAsStateWithLifecycle()
            EffectiveReplacesDialog(
                contentRules = hitContent,
                titleRules = hitTitle,
                chineseConvertMode = cnMode,
                onDismiss = { isEdit ->
                    viewModel.hideEffectiveReplacesDialog()
                    if (isEdit) viewModel.refreshAfterReplaceRulesChanged()
                },
                onEditRule = { ruleId ->
                    viewModel.hideEffectiveReplacesDialog()
                    onNavigateToReplaceRule(ruleId)
                },
                onDisableRule = { viewModel.disableReplaceRule(it) },
                onSetChineseConvertMode = { viewModel.setChineseConvertMode(it) },
                onDisableChineseConvert = { viewModel.disableChineseConvert() },
            )
        }

        // TTS engine error surface — must live inside the root Box so it can
        // align to the bottom edge above the system nav bar without disturbing
        // the reader's render layers stacked above it.
        com.morealm.app.ui.widget.TtsErrorSnackbarHost(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 8.dp)
        )
    }
}

// ── Extracted composables ──────────────────────────────────────────────────────

/**
 * 把 TTS 段切高频更新的 [kotlinx.coroutines.flow.StateFlow] 收敛在自己内部 collect，
 * 避免外层 [ReaderScreen] 因为这一个 State 的变化整体 recompose。
 *
 * 用法（取代原先在 ReaderScreen 顶层 `val ttsChapterPosition by ...collectAsStateWithLifecycle()`）：
 * ```
 * ReadAloudPositionScope(viewModel.tts.ttsChapterPosition) { pos ->
 *     CanvasRenderer(readAloudChapterPosition = pos, ... )
 * }
 * ```
 *
 * **为什么有效**：Compose 重组的最小单元是 `@Composable` 函数。在 ReaderScreen
 * 函数体里 `by collectAsStateWithLifecycle()` 等价于让该 State 的变化重组
 * 整个 ReaderScreen；把 collect 挪到这个 leaf 函数里后，State 变化只重组本函数和
 * 它内部 lambda 调到的 CanvasRenderer 一个节点，外层结构稳定的所有兄弟组件全部
 * 跳过 recomposition。
 *
 * 配合 CanvasRenderer 内部的 stable 检测 + Canvas drawWithContent 直绘高亮，
 * TTS 段切 → 仅高亮那一小块矩形重画，外层零成本。
 */
@Composable
private fun ReadAloudPositionScope(
    flow: kotlinx.coroutines.flow.StateFlow<Int>,
    content: @Composable (Int) -> Unit,
) {
    val pos by flow.collectAsStateWithLifecycle()
    content(pos)
}

@Composable
private fun ChapterBookmarkPanel(
    visible: Boolean,
    chapters: List<BookChapter>,
    bookmarks: List<Bookmark>,
    currentChapter: Int,
    selectedSideTab: Int,
    linkedBooks: List<Book>,
    /**
     * 当前书。仅用于章节标题显示（[BookChapter.displayTitle]）—— TXT 自动分章场景
     * 把"伪章名"替换成书名时需要。null 时退回原标题。
     */
    book: Book?,
    /**
     * 是否本地 TXT 无目录自动分章本（由 [ReaderScreen] 已经算好直接传入，避免本地
     * 重算 + 与翻页/滚动路径口径漂移）。命中时目录 chip 计数显示 1（"整本书"单 entry），
     * 否则按 chapters.size 显示真实章节数。
     */
    isAutoSplitTxt: Boolean,
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
                        // 自动分章 TXT 场景计数显示 1（一条「整本书」入口），
                        // 否则按真实章节数。避免出现 "目录 (249)" 但下面只有
                        // 一条 entry 的视觉违和。
                        label = { Text("目录 (${if (isAutoSplitTxt) 1 else chapters.size})") },
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
                    // OutlinedTextField 默认 minHeight 56dp（M3 规范），原写法强制 .height(44.dp)
                    // 会把 placeholder 压扁（视觉上"塌陷"成一条线）。改用 heightIn(min = 48.dp)：
                    // 既给 M3 留出最小可读空间，又不会显得过于松散；singleLine=true 保证不会被
                    // 多行内容撑高。
                    OutlinedTextField(
                        value = chapterSearch,
                        onValueChange = { chapterSearch = it },
                        placeholder = { Text("搜索章节", style = MaterialTheme.typography.bodySmall) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .heightIn(min = 48.dp),
                        textStyle = MaterialTheme.typography.bodySmall,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            cursorColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                    Spacer(Modifier.height(4.dp))
                    // ── 本地 TXT 自动分章合并显示 ──
                    //
                    // [LocalBookParser.parseWithoutToc] 会把无目录 TXT 按 MAX_LENGTH_NO_TOC 切成
                    // `第1节` / `第2节` / ... 这些伪章名。底层切分仍需保留（渲染缓存 / 续读 /
                    // ScrollAnchor 都按段做），但目录里再展示成多行只会让用户看到一串相同的
                    // "《书名》"（[BookChapter.displayTitle] 已把伪章名换成书名）—— 完全没区分度。
                    //
                    // 解决方案：检测到这种"全是自动分章"的本地 TXT 时，目录里只展示一个虚拟
                    // "整本书"项；点击 = 跳到 chapter 0 + chapterPosition 0。chapters 数据本身
                    // 不动，所有渲染层 / 续读 / 进度上报全部按原样跑。
                    //
                    // 搜索状态（chapterSearch 非空）下退化回原逐章列表 —— 用户可能在查 "第 N 节"。
                    // [isAutoSplitTxt] 由外层 [ReaderScreen] 计算后通过参数传入，与
                    // [com.morealm.app.ui.reader.renderer.CanvasRenderer.omitChapterTitleBlock]
                    // 同一口径，避免本地重算漂移。
                    val showMergedWholeBook = isAutoSplitTxt && chapterSearch.isBlank()
                    val listState = rememberLazyListState(
                        initialFirstVisibleItemIndex = when {
                            showMergedWholeBook -> 0
                            chapterSearch.isBlank() -> (currentChapter - 2).coerceAtLeast(0)
                            else -> 0
                        }
                    )
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        if (showMergedWholeBook) {
                            // 单 item — 整本书入口
                            item(key = "merged_whole_book") {
                                val totalChapters = chapters.size
                                val readPercent = if (totalChapters > 0)
                                    ((currentChapter + 1).toFloat() / totalChapters * 100f).toInt().coerceIn(0, 100)
                                else 0
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onChapterClick(0) }
                                        .background(
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                                            MaterialTheme.shapes.small,
                                        )
                                        .padding(horizontal = 12.dp, vertical = 14.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "《${book!!.title}》整本书",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        "已读 $readPercent%",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                    )
                                }
                            }
                        } else {
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
                                        ch.displayTitle(book),
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
    searchResults: List<ReaderSearchController.SearchResult>,
    isSearching: Boolean,
    moColors: MoRealmColors,
    onSearch: (String) -> Unit,
    onResultClick: (ReaderSearchController.SearchResult) -> Unit,
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
private fun SelectionWebPanel(
    title: String,
    url: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "关闭")
                }
            }
        },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(520.dp),
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            webViewClient = WebViewClient()
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true
                            loadUrl(url)
                        }
                    },
                    update = { webView ->
                        if (webView.url != url) webView.loadUrl(url)
                    },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("返回阅读")
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
