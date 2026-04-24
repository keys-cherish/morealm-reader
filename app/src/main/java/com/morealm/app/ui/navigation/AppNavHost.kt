package com.morealm.app.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
import com.morealm.app.ui.profile.ProfileScreen
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
import kotlinx.coroutines.launch

@Composable
fun MoRealmNavHost(
    windowSizeClass: WindowSizeClass,
    themeViewModel: ThemeViewModel,
    continueReading: Boolean = false,
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val moColors = LocalMoRealmColors.current
    val scope = rememberCoroutineScope()

    val isFullscreen = currentDestination?.route?.let { route ->
        route.startsWith("reader") || route == "webdav" || route == "about" || route == "source_manage" || route == "reading_settings" || route == "replace_rules" || route == "app_log" || route == "cache_book" || route.startsWith("theme_editor")
    } ?: false

    // Track whether we're on a main tab (pager) or a detail screen
    val isOnMainTab = currentDestination?.route == "main_tabs" || currentDestination == null

    val tabs = BottomTab.entries
    val pagerState = rememberPagerState(initialPage = 0, pageCount = { tabs.size })

    // Sync pager → bottom bar selection
    val selectedTab by remember { derivedStateOf { pagerState.currentPage } }

    val useNavRail = windowSizeClass.widthSizeClass >= WindowWidthSizeClass.Medium

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (!isFullscreen && isOnMainTab) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    tonalElevation = 0.dp,
                ) {
                    tabs.forEachIndexed { index, tab ->
                        NavigationBarItem(
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                            selected = selectedTab == index,
                            onClick = {
                                scope.launch { pagerState.animateScrollToPage(index) }
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
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
                val onSearchTab = remember(scope) { { scope.launch { pagerState.animateScrollToPage(1) } ; Unit } }
                val onToggleDayNight = remember(themeViewModel) { { themeViewModel.toggleDayNight() } }
                val onNavWebDav = remember { { navController.safeNavigate("webdav") } }
                val onNavAbout = remember { { navController.safeNavigate("about") } }
                val onNavSourceManage = remember { { navController.safeNavigate("source_manage") } }
                val onNavReadingSettings = remember { { navController.safeNavigate("reading_settings") } }
                val onNavReplaceRules = remember { { navController.safeNavigate("replace_rules") } }
                val onNavAppLog = remember { { navController.safeNavigate("app_log") } }
                val onNavCacheBook = remember { { navController.safeNavigate("cache_book") } }
                val onNavThemeEditor = remember { { navController.safeNavigate("theme_editor") } }
                val onSearchBack = remember(scope) { { scope.launch { pagerState.animateScrollToPage(0) } ; Unit } }

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    beyondViewportPageCount = tabs.size,
                ) { page ->
                    // 延迟 compose 非首屏 tab：冷启动首帧只 compose 当前页（Shelf），
                    // 其余 tab 先显示背景色占位，下一帧再真正 compose 内容。
                    // 效果等同于 Legado 的 Fragment BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT。
                    val isInitialPage = page == 0
                    var ready by remember { mutableStateOf(isInitialPage) }
                    if (!ready) {
                        LaunchedEffect(Unit) { ready = true }
                    }
                    if (!ready) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.background)
                        )
                        return@HorizontalPager
                    }

                    when (tabs[page]) {
                        BottomTab.Shelf -> {
                            // Read theme state inside ShelfScreen's scope so changes
                            // only recompose this branch, not the entire Pager
                            val activeTheme by themeViewModel.activeTheme.collectAsStateWithLifecycle()
                            val isNight = activeTheme?.isNightTheme ?: true
                            ShelfScreen(
                                onBookClick = onBookClick,
                                onBookLongClick = onBookLongClick,
                                onSearch = onSearchTab,
                                onToggleDayNight = onToggleDayNight,
                                isNightTheme = isNight,
                                columns = columns,
                                continueReading = continueReading,
                            )
                        }
                        BottomTab.Discover -> SearchScreen(
                            onBack = onSearchBack,
                            onNavigateReader = { bookId ->
                                navController.navigate("reader/$bookId")
                            },
                        )
                        BottomTab.Listen -> ListenScreen()
                        BottomTab.Profile -> ProfileScreen(
                            themeViewModel = themeViewModel,
                            onNavigateWebDav = onNavWebDav,
                            onNavigateAbout = onNavAbout,
                            onNavigateSourceManage = onNavSourceManage,
                            onNavigateReadingSettings = onNavReadingSettings,
                            onNavigateReplaceRules = onNavReplaceRules,
                            onNavigateAppLog = onNavAppLog,
                            onNavigateCacheBook = onNavCacheBook,
                            onNavigateThemeEditor = onNavThemeEditor,
                        )
                    }
                }
            }

            composable("webdav") {
                WebDavScreen(onBack = { navController.safePopBackStack() })
            }

            composable("about") {
                AboutScreen(onBack = { navController.safePopBackStack() })
            }

            composable("source_manage") {
                BookSourceManageScreen(onBack = { navController.safePopBackStack() })
            }

            composable("reading_settings") {
                ReadingSettingsScreen(onBack = { navController.safePopBackStack() })
            }

            composable("replace_rules") {
                ReplaceRuleScreen(onBack = { navController.safePopBackStack() })
            }

            composable("app_log") {
                AppLogScreen(onBack = { navController.safePopBackStack() })
            }

            composable("cache_book") {
                CacheBookScreen(onBack = { navController.safePopBackStack() })
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
                    onBack = { navController.safePopBackStack() },
                    onNavigateToBook = { targetBookId ->
                        navController.safeNavigate("reader/$targetBookId") {
                            popUpTo("reader/$bookId") { inclusive = true }
                        }
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
    }
}

/**
 * Safe navigation — guards against "Cannot transition entry that is not in the back stack"
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
