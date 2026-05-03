package com.morealm.app.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
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
    val toastCtx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        com.morealm.app.domain.sync.BackupStatusBus.events.collect { msg ->
            if (msg.isNotBlank()) {
                android.widget.Toast.makeText(toastCtx, msg, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    val isFullscreen = currentDestination?.route?.let { route ->
        route.startsWith("reader") || route == "webdav" || route == "about" || route == "changelog" || route == "source_manage" || route == "reading_settings" || route == "font_manager" || route == "bookmarks" || route == "replace_rules" || route == "auto_group_rules" || route == "app_log" || route == "cache_book" || route == "donate" || route == "remote_books" || route == "backup_export" || route == "backup_import" || route == "legado_import" || route == "appearance" || route.startsWith("theme_editor")
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
                val columns = when {
                    windowSizeClass.widthSizeClass >= WindowWidthSizeClass.Expanded -> 5
                    windowSizeClass.widthSizeClass >= WindowWidthSizeClass.Medium -> 4
                    else -> 3
                }
                // Stabilize lambdas to avoid recomposition of child screens
                val onBookClick = remember { { bookId: String -> navController.safeNavigate("reader/$bookId") } }
                val onBookLongClick = remember { { bookId: String -> navController.safeNavigate("detail/$bookId") } }
                // Smart router: WEB books go to the detail page so the user can confirm
                // before reading (Legado-parity); local files open straight in the reader.
                val onBookOpen = remember {
                    { book: com.morealm.app.domain.entity.Book ->
                        val route = if (book.format == com.morealm.app.domain.entity.BookFormat.WEB) {
                            "detail/${book.id}"
                        } else {
                            "reader/${book.id}"
                        }
                        navController.safeNavigate(route)
                    }
                }
                val onSearchTab = remember(switchTab) { { switchTab(1) } }
                val onToggleDayNight = remember(themeViewModel) { { themeViewModel.toggleDayNight() } }
                val onNavWebDav = remember { { navController.safeNavigate("webdav") } }
                val onNavAbout = remember { { navController.safeNavigate("about") } }
                val onNavAppearance = remember { { navController.safeNavigate("appearance") } }
                val onNavSourceManage = remember { { navController.safeNavigate("source_manage") } }
                val onNavReadingSettings = remember { { navController.safeNavigate("reading_settings") } }
                val onNavSearchSettings = remember { { navController.safeNavigate("search_settings") } }
                val onNavReplaceRules = remember { { navController.safeNavigate("replace_rules") } }
                val onNavAutoGroupRules = remember { { navController.safeNavigate("auto_group_rules") } }
                val onNavAppLog = remember { { navController.safeNavigate("app_log") } }
                val onNavCacheBook = remember { { navController.safeNavigate("cache_book") } }
                val onNavThemeEditor = remember { { navController.safeNavigate("theme_editor") } }
                val onNavDonate = remember { { navController.safeNavigate("donate") } }
                val onNavBackupExport = remember { { navController.safeNavigate("backup_export") } }
                val onNavBackupImport = remember { { navController.safeNavigate("backup_import") } }
                val onNavLegadoImport = remember { { navController.safeNavigate("legado_import") } }
                val onNavBookmarks = remember { { navController.safeNavigate("bookmarks") } }
                val onNavHttpTtsManage = remember { { navController.safeNavigate("http_tts_manage") } }
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
                            // 改为：始终 fillMaxSize，用 graphicsLayer.alpha 控制可见性。zIndex 保证
                            // selectedTab 在最上层接事件；不可见 tab 平铺在底但 alpha=0 看不见也不
                            // 受 size 切换影响。代价：cached tab 总参与 layout，但本来 cachedTabs 就
                            // 持久缓存，多一次 measure 可以接受。
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .zIndex(if (page == selectedTab) 1f else 0f)
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
                                onNavigateAutoGroupRules = onNavAutoGroupRules,
                            )
                        }
                        BottomTab.Discover -> SearchScreen(
                            onBack = onSearchBack,
                            onNavigateReader = { bookId ->
                                navController.navigate("reader/$bookId")
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
                            onNavigateAutoGroupRules = onNavAutoGroupRules,
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
                )
            }

            composable("appearance") {
                AppearanceScreen(onBack = { navController.safePopBackStack() })
            }

            composable("changelog") {
                ChangelogScreen(onBack = { navController.safePopBackStack() })
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
                ReadingSettingsScreen(onBack = { navController.safePopBackStack() })
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
                        navController.safeNavigate("reader/$bookId")
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
                        navController.safeNavigate("reader/$bookId")
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
                ReaderScreen(
                    bookId = bookId,
                    onBack = { navController.safePopBackStackOrHome() },
                    onNavigateToBook = { targetBookId ->
                        navController.safeNavigate("reader/$targetBookId") {
                            popUpTo("reader/$bookId") { inclusive = true }
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

            composable(
                "detail/{bookId}",
                arguments = listOf(navArgument("bookId") { type = NavType.StringType }),
            ) { entry ->
                val bookId = entry.arguments?.getString("bookId") ?: return@composable
                BookDetailScreen(
                    bookId = bookId,
                    onBack = { navController.safePopBackStack() },
                    onRead = { navController.safeNavigate("reader/$bookId") },
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
        } // Box
    }
}

/**
 * Safe navigation 鈥?guards against "Cannot transition entry that is not in the back stack"
 * crash caused by predictive back gestures in Navigation Compose 2.9.x.
 */
private fun NavController.safeNavigate(route: String, builder: (androidx.navigation.NavOptionsBuilder.() -> Unit)? = null) {
    try {
        if (builder != null) navigate(route, builder) else navigate(route)
    } catch (_: IllegalStateException) { }
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
