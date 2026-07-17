package com.morealm.app.ui.navigation

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.morealm.app.ui.detail.BookDetailScreen
import com.morealm.app.ui.listen.ListenScreen
import com.morealm.app.ui.profile.AboutScreen
import com.morealm.app.ui.profile.AppearanceScreen
import com.morealm.app.ui.profile.BackupExportScreen
import com.morealm.app.ui.profile.BackupImportScreen
import com.morealm.app.ui.profile.ChangelogScreen
import com.morealm.app.ui.profile.ContributorsScreen
import com.morealm.app.ui.profile.DonateScreen
import com.morealm.app.ui.profile.ProfileScreen
import com.morealm.app.ui.profile.RemoteBookScreen
import com.morealm.app.ui.profile.ReplaceRuleScreen
import com.morealm.app.ui.profile.ThemeEditorScreen
import com.morealm.app.ui.profile.WebDavScreen
import com.morealm.app.ui.reader.ReaderScreen
import com.morealm.app.ui.search.SearchScreen
import com.morealm.app.ui.settings.AppLogScreen
import com.morealm.app.ui.cache.CacheBookScreen
import com.morealm.app.ui.settings.ReadingSettingsScreen
import com.morealm.app.ui.settings.RuleColorScreen
import com.morealm.app.ui.shelf.ShelfScreen
import com.morealm.app.ui.source.BookSourceManageScreen
import com.morealm.app.ui.theme.LocalMoRealmColors
import com.morealm.app.presentation.theme.ThemeViewModel
import com.morealm.app.ui.common.GlobalBackgroundScaffold
import kotlinx.coroutines.launch

@Composable
fun MoRealmNavHost(
    windowSizeClass: WindowSizeClass,
    themeViewModel: ThemeViewModel,
    continueReadingRequest: Int = 0,
    pendingOpenBookId: String? = null,
    onPendingOpenConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val moColors = LocalMoRealmColors.current

    // Global one-shot toast collector for backup import/export results.
    // Lives at NavHost top-level so it stays subscribed regardless of which
    // screen triggered the operation — fixes the "import 成功 toast replays
    // when ProfileScreen recomposes after returning from BackupExportScreen"
    // bug. SharedFlow has replay=0, so re-subscriptions don't see stale events.
    //
    // 由原生 android.widget.Toast 改成全局 Snackbar：颜色随主题，且通过
    // ThemedSnackbarHost 浮在 PillNavigationBar 之上，避免被遮。
    val globalSnackbarHost = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        com.morealm.app.domain.sync.BackupStatusBus.events.collect { msg ->
            if (msg.isNotBlank()) {
                globalSnackbarHost.showSnackbar(msg)
            }
        }
    }

    // 外部「打开方式」用 MoRealm 打开文件：FileOpenActivity 导入完跳回 MainActivity 带 bookId，
    // MainActivity 置 pendingOpenBookId → 这里消费一次 → 导航到阅读器（去重命中已有书也走此路径）。
    LaunchedEffect(pendingOpenBookId) {
        val id = pendingOpenBookId ?: return@LaunchedEffect
        navController.navigateToReader(id)
        onPendingOpenConsumed()
    }

    val isFullscreen = currentDestination?.route?.let { route ->
        route.startsWith("reader") || route == "webdav" || route == "about" || route == "changelog" || route == "contributors" || route == "source_manage" || route == "reading_settings" || route == "rule_color" || route == "font_manager" || route == "bookmarks" || route == "replace_rules" || route == "auto_group_rules" || route == "app_log" || route == "cache_book" || route == "donate" || route == "remote_books" || route == "backup_export" || route == "backup_import" || route == "legado_import" || route == "appearance" || route.startsWith("theme_editor")
    } ?: false

    // Track whether we're on a main tab (pager) or a detail screen
    val isOnMainTab = currentDestination?.route == "main_tabs" || currentDestination == null

    val tabs = BottomTab.entries
    val scope = rememberCoroutineScope()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var targetTab by remember { mutableStateOf<Int?>(null) }
    var tabWidth by remember { mutableIntStateOf(0) }
    val tabOffset = remember { Animatable(0f) }
    val cachedTabs = remember { mutableStateListOf(0) }
    val switchTab: (Int) -> Unit = remember(selectedTab, scope, cachedTabs) {
        { index ->
            if (index == selectedTab) return@remember
            if (index !in cachedTabs) cachedTabs.add(index)
            selectedTab = index
            targetTab = null
            scope.launch { tabOffset.snapTo(0f) }
        }
    }


    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // No bottomBar — pill nav floats as overlay via Box
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = "main_tabs",
            modifier = Modifier.padding(innerPadding),
        ) {
            composable("main_tabs") {
                // ── 节日彩蛋：当天首次进主页弹一次 ──
                //
                // 进程级防重（HolidayPresenter.checkedThisSession） + DataStore lastShownDate
                // 双层去重，配置变化、tab 切换、横竖屏旋转都不会重弹。
                val holidayPresenter: com.morealm.app.presentation.holiday.HolidayPresenter =
                    hiltViewModel()
                val activeHoliday by holidayPresenter.activeHoliday.collectAsStateWithLifecycle()
                val holidayGreeting by holidayPresenter.greetingText.collectAsStateWithLifecycle()
                LaunchedEffect(Unit) { holidayPresenter.checkOnce() }
                activeHoliday?.let { h ->
                    com.morealm.app.ui.holiday.HolidayPopup(
                        holiday = h,
                        messageText = holidayGreeting,
                        onDismiss = { holidayPresenter.dismiss() },
                    )
                }

                val columns = when {
                    windowSizeClass.widthSizeClass >= WindowWidthSizeClass.Expanded -> 5
                    windowSizeClass.widthSizeClass >= WindowWidthSizeClass.Medium -> 4
                    else -> 3
                }
                // Stabilize lambdas to avoid recomposition of child screens
                val onBookClick = remember { { bookId: String -> navController.navigateToReader(bookId) } }
                val onBookLongClick = remember { { bookId: String -> navController.navigateToDetail(bookId) } }
                // Smart router: WEB books go to the detail page so the user can confirm
                // before reading (Legado-parity); local files open straight in the reader.
                val onBookOpen = remember {
                    { book: com.morealm.app.domain.entity.Book ->
                        if (book.format == com.morealm.app.domain.entity.BookFormat.WEB) {
                            navController.navigateToDetail(book.id)
                        } else {
                            navController.navigateToReader(book.id)
                        }
                    }
                }
                val onSearchTab = remember(switchTab) { { switchTab(1) } }
                val onToggleDayNight = remember(themeViewModel) { { themeViewModel.toggleDayNight() } }
                val selectedTabState = rememberUpdatedState(selectedTab)
                val navigateFromTab = remember(navController) {
                    { sourceTab: BottomTab, route: String ->
                        val sourceIndex = tabs.indexOf(sourceTab)
                        val currentRoute = navController.currentDestination?.route
                        if (canNavigateFromMainTab(selectedTabState.value, sourceIndex, currentRoute)) {
                            navController.safeNavigate(route)
                        } else {
                            com.morealm.app.core.log.AppLog.warn(
                                "Nav",
                                "blocked hidden-tab navigation source=$sourceTab" +
                                    " selected=${selectedTabState.value} currentRoute=$currentRoute target=$route",
                            )
                        }
                    }
                }
                val onNavWebDav = remember(navigateFromTab) { { navigateFromTab(BottomTab.Profile, "webdav") } }
                val onNavAbout = remember(navigateFromTab) { { navigateFromTab(BottomTab.Profile, "about") } }
                val onNavAppearance = remember(navigateFromTab) { { navigateFromTab(BottomTab.Profile, "appearance") } }
                val onNavSourceManage = remember(navigateFromTab) { { navigateFromTab(BottomTab.Profile, "source_manage") } }
                val onNavReadingSettings = remember(navigateFromTab) { { navigateFromTab(BottomTab.Profile, "reading_settings") } }
                val onNavSearchSettings = remember(navigateFromTab) { { navigateFromTab(BottomTab.Profile, "search_settings") } }
                val onNavReplaceRules = remember(navigateFromTab) { { navigateFromTab(BottomTab.Profile, "replace_rules") } }
                val onProfileAutoGroupRules = remember(navigateFromTab) { { navigateFromTab(BottomTab.Profile, "auto_group_rules") } }
                val onShelfAutoGroupRules = remember(navigateFromTab) { { navigateFromTab(BottomTab.Shelf, "auto_group_rules") } }
                val onNavAppLog = remember(navigateFromTab) { { navigateFromTab(BottomTab.Profile, "app_log") } }
                val onNavCacheBook = remember(navigateFromTab) { { navigateFromTab(BottomTab.Profile, "cache_book") } }
                val onNavThemeEditor = remember(navigateFromTab) { { navigateFromTab(BottomTab.Profile, "theme_editor") } }
                val onNavDonate = remember(navigateFromTab) { { navigateFromTab(BottomTab.Profile, "donate") } }
                val onNavBackupExport = remember(navigateFromTab) { { navigateFromTab(BottomTab.Profile, "backup_export") } }
                val onNavBackupImport = remember(navigateFromTab) { { navigateFromTab(BottomTab.Profile, "backup_import") } }
                val onNavLegadoImport = remember(navigateFromTab) { { navigateFromTab(BottomTab.Profile, "legado_import") } }
                val onNavBookmarks = remember(navigateFromTab) { { navigateFromTab(BottomTab.Profile, "bookmarks") } }
                val onNavHttpTtsManage = remember(navigateFromTab) { { navigateFromTab(BottomTab.Listen, "http_tts_manage") } }
                val onSearchBack = remember(switchTab) { { switchTab(0) } }

                var dragAmount by remember { mutableFloatStateOf(0f) }
                GlobalBackgroundScaffold {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { tabWidth = it.width }
                        .pointerInput(selectedTab) {
                            detectHorizontalDragGestures(
                                onDragStart = {
                                    dragAmount = 0f
                                    targetTab = null
                                    scope.launch { tabOffset.stop() }
                                },
                                onHorizontalDrag = { _, amount ->
                                    val width = size.width.toFloat().coerceAtLeast(1f)
                                    val nextOffset = (tabOffset.value + amount).coerceIn(-width, width)
                                    val nextTarget = when {
                                        nextOffset < 0f && selectedTab < tabs.lastIndex -> selectedTab + 1
                                        nextOffset > 0f && selectedTab > 0 -> selectedTab - 1
                                        else -> null
                                    }
                                    if (nextTarget != null && nextTarget !in cachedTabs) cachedTabs.add(nextTarget)
                                    targetTab = nextTarget
                                    dragAmount = nextOffset
                                    scope.launch { tabOffset.snapTo(nextOffset) }
                                },
                                onDragEnd = {
                                    val width = size.width.toFloat().coerceAtLeast(1f)
                                    val threshold = width * 0.22f
                                    val destination = targetTab
                                    scope.launch {
                                        if (destination != null && kotlin.math.abs(tabOffset.value) > threshold) {
                                            val settleOffset = if (tabOffset.value < 0f) -width else width
                                            tabOffset.animateTo(settleOffset, tween(140, easing = FastOutSlowInEasing))
                                            selectedTab = destination
                                        }
                                        tabOffset.snapTo(0f)
                                        targetTab = null
                                        dragAmount = 0f
                                    }
                                },
                                onDragCancel = {
                                    scope.launch {
                                        tabOffset.animateTo(0f, tween(120, easing = FastOutSlowInEasing))
                                        targetTab = null
                                        dragAmount = 0f
                                    }
                                },
                            )
                        },
                ) {
                    tabs.forEachIndexed { page, tab ->
                        if (page !in cachedTabs) return@forEachIndexed
                        val neighbor = targetTab
                        val visible = page == selectedTab || page == neighbor
                        val width = tabWidth.toFloat().coerceAtLeast(1f)
                        val offsetX = when {
                            page == selectedTab -> tabOffset.value
                            page == neighbor && neighbor > selectedTab -> width + tabOffset.value
                            page == neighbor && neighbor < selectedTab -> -width + tabOffset.value
                            else -> 0f
                        }
                        key(tab) {
                            // Bug 修复：原本 invisible 时切到 Modifier.size(0.dp)，会让 cached tab 的
                            // 父节点处于"未 placed"状态。OutlinedTextField 等内部使用 BringIntoViewRequester
                            // 的组件在 IME 收起 / focus 离开时排队的回调，会因父节点 size=0 抛
                            // IllegalStateException 并把 Compose 渲染管线打断，遗留 layer/绘制残影
                            // 在状态栏下方（即用户看到的橘色矩形 / 弧形）。
                            //
                            // 始终 fillMaxSize + alpha 保留 cached tab 的布局状态；但 alpha/zIndex
                            // 都不会自动禁止 Compose 命中测试。必须给每个全屏 tab 建独立输入边界，
                            // 否则当前书架空白处会穿透到下面 alpha=0 的「我的」设置项。
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .zIndex(if (page == selectedTab) 1f else 0f)
                                    .mainTabInputBoundary(active = page == selectedTab)
                                    .graphicsLayer {
                                        alpha = if (visible) 1f else 0f
                                        translationX = if (visible) offsetX else 0f
                                    }
                            ) {
                                when (tab) {
                        BottomTab.Shelf -> {
                            // Read theme state inside ShelfScreen's scope so changes
                            // only recompose this branch, not the entire Pager
                            val activeTheme by themeViewModel.activeTheme.collectAsStateWithLifecycle()
                            val isNight = activeTheme?.isNightTheme ?: true
                            ShelfScreen(
                                onBookClick = onBookClick,
                                onBookLongClick = onBookLongClick,
                                onBookOpen = onBookOpen,
                                onSearch = onSearchTab,
                                onToggleDayNight = onToggleDayNight,
                                isNightTheme = isNight,
                                columns = columns,
                                continueReadingRequest = continueReadingRequest,
                                onNavigateAutoGroupRules = onShelfAutoGroupRules,
                            )
                        }
                        BottomTab.Discover -> SearchScreen(
                            onBack = onSearchBack,
                            onNavigateReader = { bookId ->
                                navController.navigateToReader(bookId)
                            },
                            onNavigateDetail = { bookId ->
                                navController.navigateToDetail(bookId)
                            },
                        )
                        BottomTab.Listen -> ListenScreen(
                            onNavigateHttpTtsManage = onNavHttpTtsManage,
                        )
                        BottomTab.Profile -> ProfileScreen(
                            themeViewModel = themeViewModel,
                            onNavigateWebDav = onNavWebDav,
                            onNavigateAbout = onNavAbout,
                            onNavigateSourceManage = onNavSourceManage,
                            onNavigateReadingSettings = onNavReadingSettings,
                            onNavigateSearchSettings = onNavSearchSettings,
                            onNavigateReplaceRules = onNavReplaceRules,
                            onNavigateAutoGroupRules = onProfileAutoGroupRules,
                            onNavigateAppLog = onNavAppLog,
                            onNavigateCacheBook = onNavCacheBook,
                            onNavigateThemeEditor = onNavThemeEditor,
                            onNavigateDonate = onNavDonate,
                            onNavigateBackupExport = onNavBackupExport,
                            onNavigateBackupImport = onNavBackupImport,
                            onNavigateLegadoImport = onNavLegadoImport,
                            onNavigateBookmarks = onNavBookmarks,
                            onNavigateAppearance = onNavAppearance,
                        )
                                }
                            }
                        }
                    }
                }
                } // GlobalBackgroundScaffold
            }

            composable("webdav") {
                WebDavScreen(
                    onBack = { navController.safePopBackStack() },
                    onNavigateRemoteBooks = { navController.safeNavigate("remote_books") },
                )
            }

            composable("remote_books") {
                RemoteBookScreen(onBack = { navController.safePopBackStack() })
            }

            composable("about") {
                AboutScreen(
                    onBack = { navController.safePopBackStack() },
                    onNavigateChangelog = { navController.safeNavigate("changelog") },
                    onNavigateContributors = { navController.safeNavigate("contributors") },
                )
            }

            composable("appearance") {
                AppearanceScreen(onBack = { navController.safePopBackStack() })
            }

            composable("changelog") {
                ChangelogScreen(onBack = { navController.safePopBackStack() })
            }

            composable("contributors") {
                ContributorsScreen(onBack = { navController.safePopBackStack() })
            }

            composable("donate") {
                DonateScreen(onBack = { navController.safePopBackStack() })
            }

            composable("backup_export") {
                BackupExportScreen(onBack = { navController.safePopBackStack() })
            }

            composable("backup_import") {
                BackupImportScreen(onBack = { navController.safePopBackStack() })
            }

            composable("legado_import") {
                com.morealm.app.ui.profile.LegadoImportScreen(
                    onBack = { navController.safePopBackStack() },
                )
            }

            composable("source_manage") {
                BookSourceManageScreen(
                    onBack = { navController.safePopBackStack() },
                    onNavigateToLog = { navController.safeNavigate("app_log") },
                )
            }

            composable("reading_settings") {
                ReadingSettingsScreen(
                    onBack = { navController.safePopBackStack() },
                    onNavigateRuleColor = { navController.safeNavigate("rule_color") },
                )
            }

            composable("rule_color") {
                RuleColorScreen(onBack = { navController.safePopBackStack() })
            }

            composable("search_settings") {
                com.morealm.app.ui.settings.SearchSettingsScreen(
                    onBack = { navController.safePopBackStack() },
                )
            }

            composable("font_manager") {
                com.morealm.app.ui.settings.FontManagerScreen(
                    onBack = { navController.safePopBackStack() }
                )
            }

            composable("http_tts_manage") {
                com.morealm.app.ui.settings.HttpTtsManageScreen(
                    onBack = { navController.safePopBackStack() }
                )
            }

            composable("bookmarks") {
                com.morealm.app.ui.profile.BookmarksScreen(
                    onBack = { navController.safePopBackStack() },
                    onOpenBook = { bookId, _ ->
                        navController.navigateToReader(bookId)
                    },
                )
            }

            composable(
                "replace_rules?editId={editId}",
                arguments = listOf(
                    navArgument("editId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                val editId = entry.arguments?.getString("editId")
                ReplaceRuleScreen(
                    onBack = { navController.safePopBackStack() },
                    autoEditId = editId,
                )
            }

            composable("auto_group_rules") {
                com.morealm.app.ui.profile.AutoGroupRulesScreen(
                    onBack = { navController.safePopBackStack() },
                )
            }

            composable("app_log") {
                AppLogScreen(onBack = { navController.safePopBackStack() })
            }

            composable("cache_book") {
                CacheBookScreen(
                    onBack = { navController.safePopBackStack() },
                    onOpenReader = { bookId ->
                        navController.navigateToReader(bookId)
                    },
                )
            }

            composable("theme_editor") {
                ThemeEditorScreen(
                    themeViewModel = themeViewModel,
                    onBack = { navController.safePopBackStack() },
                )
            }

            composable(
                "reader/{bookId}",
                arguments = listOf(navArgument("bookId") { type = NavType.StringType }),
            ) { entry ->
                val bookId = entry.arguments?.getString("bookId") ?: return@composable
                // ── Reader 全屏化：抵消 Scaffold innerPadding ──
                //
                // AppNavHost.NavHost 给所有路由统一 Modifier.padding(innerPadding)，
                // innerPadding 包含 Scaffold 自动计算的 statusBars + navigationBars
                // inset（contentWindowInsets 默认 = systemBars）。普通页面要这块
                // 才不会被状态栏盖；但 reader 想要全屏沉浸式，自己用 enableEdgeToEdge
                // 已经把 status bar 设成透明 / 可隐藏，再被 NavHost padding 推一次
                // 就在顶部留下「与状态栏等高」的大空白（用户截图明显观感）。
                //
                // 用 Modifier.layout 做反向抵消：测量时把 constraints 顶部 / 底部
                // 都扩回原始 window 高度，再 place(0, -topPx) 把内容上移；对父级
                // 报告的 size 仍是父期望尺寸，不破坏 Scaffold 自身布局。
                //
                // ReaderTopBar 内部已用 windowInsetsPadding(displayCutout.only(Top))
                // 处理刘海；为了 showStatusBar=true 时不被状态栏盖文字，下面单独把
                // statusBars 也并进 ReaderTopBar 自己的 inset（见 ReaderComponents.kt）。
                Box(
                    modifier = Modifier.layout { measurable, constraints ->
                        val topPx = innerPadding.calculateTopPadding().roundToPx()
                        val bottomPx = innerPadding.calculateBottomPadding().roundToPx()
                        val expanded = Constraints(
                            minWidth = constraints.minWidth,
                            maxWidth = constraints.maxWidth,
                            minHeight = (constraints.minHeight + topPx + bottomPx)
                                .coerceAtLeast(0),
                            maxHeight = if (constraints.maxHeight == Constraints.Infinity) {
                                Constraints.Infinity
                            } else {
                                constraints.maxHeight + topPx + bottomPx
                            },
                        )
                        val placeable = measurable.measure(expanded)
                        val reportedWidth = placeable.width
                            .coerceIn(constraints.minWidth, constraints.maxWidth)
                        val reportedHeight = (placeable.height - topPx - bottomPx)
                            .coerceIn(
                                constraints.minHeight,
                                if (constraints.maxHeight == Constraints.Infinity) {
                                    Int.MAX_VALUE
                                } else {
                                    constraints.maxHeight
                                },
                            )
                        layout(reportedWidth, reportedHeight) {
                            placeable.place(0, -topPx)
                        }
                    }
                ) {
                    // ── 漫画 / 小说路由分流 ──
                    //
                    // 让 `reader/{bookId}` 路由内部根据 Book.isComic 决定渲染哪个屏，
                    // 调用方（书架 / 搜索 / 详情等）一律走 navigateToReader 不需要关心。
                    // [BookFormatProbeViewModel] 异步查 DB，加载期间显示空黑屏占位，
                    // 拿到结果后路由到 ComicReaderScreen 或 ReaderScreen。
                    val probe: com.morealm.app.presentation.reader.BookFormatProbeViewModel = androidx.hilt.navigation.compose.hiltViewModel()
                    val probeResult by probe.result.collectAsStateWithLifecycle()
                    when (val r = probeResult) {
                        com.morealm.app.presentation.reader.BookFormatProbeViewModel.Result.Comic -> {
                            com.morealm.app.ui.reader.comic.ComicReaderScreen(
                                onBack = { navController.safePopBackStackOrHome() },
                            )
                        }
                        com.morealm.app.presentation.reader.BookFormatProbeViewModel.Result.Novel,
                        com.morealm.app.presentation.reader.BookFormatProbeViewModel.Result.NotFound -> {
                            ReaderScreen(
                                bookId = bookId,
                                onBack = { navController.safePopBackStackOrHome() },
                                onNavigateToBook = { targetBookId ->
                                    navController.navigateToReader(targetBookId) {
                                        popUpTo("reader/${Uri.encode(bookId)}") { inclusive = true }
                                    }
                                },
                                onNavigateToReplaceRule = { ruleId ->
                                    navController.safeNavigate("replace_rules?editId=$ruleId")
                                },
                                onNavigateToFontManager = {
                                    navController.safeNavigate("font_manager")
                                },
                                themeViewModel = themeViewModel,
                            )
                        }
                        is com.morealm.app.presentation.reader.BookFormatProbeViewModel.Result.BadFile -> {
                            // 文件健康检查失败 —— 不进 reader 避免 OOM/黑屏，显示错误页
                            BadBookFileScreen(
                                bookTitle = r.bookTitle,
                                reason = r.reason,
                                onBack = { navController.safePopBackStackOrHome() },
                            )
                        }
                        null -> {
                            // 加载中：纯黑占位，几十毫秒内 probe 会出结果
                            androidx.compose.foundation.layout.Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(androidx.compose.ui.graphics.Color.Black),
                            )
                        }
                    }
                }
            }

            composable(
                "detail/{bookId}",
                arguments = listOf(navArgument("bookId") { type = NavType.StringType }),
            ) { entry ->
                val bookId = entry.arguments?.getString("bookId") ?: return@composable
                BookDetailScreen(
                    bookId = bookId,
                    onBack = { navController.safePopBackStack() },
                    onRead = { navController.navigateToReader(bookId) },
                )
            }
        }
        // Floating pill navigation — overlays content, not in Scaffold.bottomBar
        if (!isFullscreen && isOnMainTab) {
            // 长按"书架" tab 弹分组菜单（Legado-MD3 复刻）。
            //
            // 拿 ShelfViewModel 走 main_tabs 的 backstack entry —— 这样和 ShelfScreen
            // 内部 hiltViewModel() 是同一个 store / 同一份 instance，emit 的
            // navigateToFolder 事件 ShelfScreen 能直接收到。getBackStackEntry 在
            // startDestination 创建后立即可用，主屏可见时永远不会失败。
            val mainEntry = remember(navController) {
                runCatching { navController.getBackStackEntry("main_tabs") }.getOrNull()
            }
            val shelfViewModel: com.morealm.app.presentation.shelf.ShelfViewModel? = mainEntry?.let {
                hiltViewModel(it)
            }
            val groupsForMenu by (
                shelfViewModel?.allGroups
                    ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptyList<com.morealm.app.domain.entity.BookGroup>()) }
                ).collectAsStateWithLifecycle()
            var shelfMenuExpanded by remember { mutableStateOf(false) }

            PillNavigationBar(
                tabs = tabs,
                selectedIndex = selectedTab,
                onTabClick = { switchTab(it) },
                onTabLongClick = { idx ->
                    // 仅在"书架" tab 长按时弹菜单；其他 tab 长按目前 noop（保留扩展点
                    // 未来如果想给"听书"长按弹"最近朗读"等也走这里）。
                    if (idx in tabs.indices && tabs[idx] == BottomTab.Shelf) {
                        shelfMenuExpanded = true
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter),
                tabExtras = { idx ->
                    if (idx in tabs.indices && tabs[idx] == BottomTab.Shelf) {
                        DropdownMenu(
                            expanded = shelfMenuExpanded,
                            onDismissRequest = { shelfMenuExpanded = false },
                            // 默认 anchor 是 tab Box 左上角；offset 往上抬一点，避免菜单
                            // 第一项被 tab icon 盖住（DropdownMenu 在底部空间不够时会
                            // 自动 flip 向上展开，offset 仅影响起点位置）。
                            offset = DpOffset(x = 0.dp, y = (-8).dp),
                        ) {
                            DropdownMenuItem(
                                text = { Text("全部") },
                                onClick = {
                                    if (selectedTab != 0) switchTab(0)
                                    shelfViewModel?.requestNavigateToFolder(null)
                                    shelfMenuExpanded = false
                                },
                            )
                            if (groupsForMenu.isNotEmpty()) {
                                HorizontalDivider()
                            }
                            groupsForMenu.forEach { group ->
                                DropdownMenuItem(
                                    text = { Text(group.name) },
                                    onClick = {
                                        if (selectedTab != 0) switchTab(0)
                                        shelfViewModel?.requestNavigateToFolder(group.id)
                                        shelfMenuExpanded = false
                                    },
                                )
                            }
                        }
                    }
                },
            )
        }
        // 全局 Snackbar 覆盖层：承接 BackupStatusBus 等跨页消息。Z-order 高于 pill，
        // padding(bottom=96dp) 让它浮在药丸导航栏上方 ~16dp。
        com.morealm.app.ui.widget.ThemedSnackbarHost(
            hostState = globalSnackbarHost,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 96.dp),
        )
        } // Box
    }
}

/**
 * 为叠放缓存的主 Tab 隔离触摸、键盘焦点和无障碍语义。
 *
 * active 页的全屏 pointer node 只参与命中、不消费事件，因此子控件点击和外层横滑仍正常；
 * inactive 页在 Initial pass 消费所有变化，作为 zIndex/命中实现差异下的第二道防线。
 */
private fun Modifier.mainTabInputBoundary(active: Boolean): Modifier =
    this
        .focusProperties { canFocus = active }
        .pointerInput(active) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (!active) {
                        event.changes.forEach { it.consume() }
                    }
                }
            }
        }
        .then(if (active) Modifier else Modifier.clearAndSetSemantics { })

/** 导航层最终防线：只有当前 main_tabs 路由上的激活 Tab 能发起自己的子页导航。 */
internal fun canNavigateFromMainTab(
    selectedTab: Int,
    sourceTab: Int,
    currentRoute: String?,
): Boolean = selectedTab == sourceTab && currentRoute == "main_tabs"

/**
 * 走 reader/detail 路由前必须 Uri.encode bookId。
 *
 * Legado 搬家来的 Book.id 直接是完整 URL（如 https://m.qingrenyouxi.com/111/111173/）；
 * Navigation Compose 用 path 段匹配路由，未编码的 `/`、`?`、`,`、`{` 会让
 * `reader/{bookId}` 匹配失败并抛 IllegalArgumentException。统一在拼路由这一步
 * 编码；composable 接收侧 entry.arguments?.getString("bookId") 自带 URL 解码。
 */
/**
 * Reader 导航限流状态：(bookId, 最近一次 navigate 时间)。
 *
 * 用户快速连点书架同一本书 N 次会让 Nav stack 累积 N 个 reader entry，
 * 用户需按返回 N 次才回到主页。500ms throttle 已经足够覆盖手指连点 + 触发延迟
 * （Compose ripple 反馈通常 100-200ms），同时不阻塞用户「真的想多次进入不同
 * 章节」的场景（点 A → 退出 → 点 B 这种 500ms 间隔足够大）。
 */
private var lastReaderNavBookId: String? = null
private var lastReaderNavTime: Long = 0L
private const val READER_NAV_THROTTLE_MS = 500L

private fun NavController.navigateToReader(
    bookId: String,
    builder: (androidx.navigation.NavOptionsBuilder.() -> Unit)? = null,
) {
    val now = System.currentTimeMillis()
    if (bookId == lastReaderNavBookId && now - lastReaderNavTime < READER_NAV_THROTTLE_MS) {
        com.morealm.app.core.log.AppLog.info(
            "Nav",
            "navigateToReader throttled bookId=$bookId (Δ=${now - lastReaderNavTime}ms)",
        )
        return
    }
    lastReaderNavBookId = bookId
    lastReaderNavTime = now
    com.morealm.app.core.log.AppLog.info("Nav", "navigateToReader bookId=$bookId")
    safeNavigate("reader/${Uri.encode(bookId)}", builder)
}

private fun NavController.navigateToDetail(
    bookId: String,
    builder: (androidx.navigation.NavOptionsBuilder.() -> Unit)? = null,
) {
    safeNavigate("detail/${Uri.encode(bookId)}", builder)
}

/**
 * Safe navigation 鈥?guards against "Cannot transition entry that is not in the back stack"
 * crash caused by predictive back gestures in Navigation Compose 2.9.x.
 */
private fun NavController.safeNavigate(route: String, builder: (androidx.navigation.NavOptionsBuilder.() -> Unit)? = null) {
    try {
        if (builder != null) navigate(route, builder) else navigate(route)
    } catch (e: IllegalStateException) {
        // 不再静默吞 —— predictive back 触发的 ISE 是预期的，但其他 ISE（如 nav graph 错配）
        // 之前被一并埋没，导致「点击没响应 + 无日志」类问题排查不出。改 warn 暴露。
        com.morealm.app.core.log.AppLog.warn("Nav", "safeNavigate swallowed ISE route=$route msg=${e.message}")
    }
}

private fun NavController.safePopBackStack(): Boolean {
    return try {
        popBackStack()
    } catch (_: IllegalStateException) { false }
}

private fun NavController.safePopBackStackOrHome(): Boolean {
    return try {
        if (previousBackStackEntry != null && popBackStack()) {
            true
        } else {
            navigate("main_tabs") {
                launchSingleTop = true
                popUpTo("main_tabs") { inclusive = false }
            }
            true
        }
    } catch (_: IllegalStateException) { false }
}

/**
 * 书籍文件健康检查失败时显示的占位错误页。给用户清晰错误 + 返回按钮，避免
 * 进 reader 后触发 OOM 或黑屏卡死。常见触发场景：0 字节占位 EPUB、下载残缺、
 * 文件被系统清理但书架条目还在。
 */
@Composable
private fun BadBookFileScreen(
    bookTitle: String,
    reason: String,
    onBack: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "⚠",
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            Text(
                text = "无法打开《${bookTitle}》",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Text(
                text = reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Text(
                text = "建议长按书架上的这本书删除，确认源文件无误后重新导入。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            OutlinedButton(onClick = onBack) {
                Text("返回书架")
            }
        }
    }
}
