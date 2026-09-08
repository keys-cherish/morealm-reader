package com.morealm.app.ui.reader

import android.Manifest
import android.content.ClipData
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
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
import com.morealm.app.domain.reader.scroll.ScrollChapterContent
import com.morealm.app.domain.storage.ImageSaver
import com.morealm.app.presentation.reader.PageTurnMode
import com.morealm.app.ui.reader.renderer.scroll.ScrollCanvasInfoBarConfig
import com.morealm.app.ui.reader.renderer.scroll.ScrollCanvasReaderHost
import com.morealm.app.ui.reader.renderer.scroll.LinkTargetResolvability
import com.morealm.epub.render.LinkRange
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
import com.morealm.app.presentation.reader.TxtReplaceState
import com.morealm.app.presentation.reader.ReaderToolBarViewModel
import com.morealm.app.presentation.source.SourceLoginViewModel
import com.morealm.app.ui.source.ChangeSourceDialog
import com.morealm.app.ui.source.SourceLoginOverlay
import com.morealm.app.ui.reader.toolbar.ReaderEditGuideTooltip
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.morealm.app.core.log.AppLog
import com.morealm.app.ui.theme.MoRealmColors
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

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
internal fun ReadAloudPositionScope(
    flow: kotlinx.coroutines.flow.StateFlow<Int>,
    content: @Composable (Int) -> Unit,
) {
    val pos by flow.collectAsStateWithLifecycle()
    content(pos)
}

@Composable
internal fun ChapterBookmarkPanel(
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
                    // 无目录 TXT 的伪章仍供渲染缓存、续读和 ScrollAnchor 使用，但目录只展示
                    // 一个“整本书”入口，避免 [BookChapter.displayTitle] 把伪章显示成重复书名。
                    // 搜索时恢复逐章列表；[isAutoSplitTxt] 由外层统一计算，避免本地口径漂移。
                    val showMergedWholeBook = isAutoSplitTxt && chapterSearch.isBlank()
                    val listState = rememberLazyListState(
                        initialFirstVisibleItemIndex = when {
                            showMergedWholeBook -> 0
                            chapterSearch.isBlank() -> (currentChapter - 2).coerceAtLeast(0)
                            else -> 0
                        }
                    )
                    Box(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 16.dp, top = 4.dp, end = 28.dp, bottom = 4.dp),
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
                                        style = MaterialTheme.typography.bodyMedium,
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
                                    // #8：非当前章用 primary.copy(0.7)，跟当前章同色系不同明度，
                                    // 视觉上像主题色"超链接列表"，强化"每行都可点"的暗示。
                                    // 当前章保持 primary 实色 + bold + ▶ 区分；过去用
                                    // onSurface.copy(0.6) 的中性灰让用户看着像不可点。
                                    Text(
                                        ch.displayTitle(book),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (isCurrent) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
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
                        ReaderCatalogScrollbar(listState, Modifier.align(Alignment.CenterEnd))
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
internal fun FullTextSearchPanel(
    visible: Boolean,
    searchResults: List<ReaderSearchController.SearchResult>,
    isSearching: Boolean,
    searchError: String?,
    searchMode: String,
    txtReplaceState: TxtReplaceState,
    isTxtBook: Boolean,
    moColors: MoRealmColors,
    onSearch: (String, Boolean, Boolean) -> Unit,
    onSearchInChapter: (String, Boolean, Boolean) -> Unit,
    onSearchModeChange: (String) -> Unit,
    onReplaceMatch: (ReaderSearchController.SearchResult, String, Boolean, Boolean) -> Unit,
    onReplaceAll: (String, String, Boolean, Boolean, Boolean) -> Unit,
    onUndoReplace: () -> Unit,
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
        var replacement by remember { mutableStateOf("") }
        var replaceExpanded by remember { mutableStateOf(false) }
        var inChapterMode by remember { mutableStateOf(false) }
        var isRegex by remember { mutableStateOf(false) }
        var isCaseSensitive by remember { mutableStateOf(false) }
        // null=未确认，false=当前章，true=整本。批量替换统一二次确认，单条替换仍可直接操作。
        var pendingBulkReplaceFullBook by remember { mutableStateOf<Boolean?>(null) }
        val triggerSearch: () -> Unit = {
            if (searchQuery.isNotBlank()) {
                if (inChapterMode) {
                    onSearchInChapter(searchQuery, isRegex, isCaseSensitive)
                } else {
                    onSearch(searchQuery, isRegex, isCaseSensitive)
                }
            }
        }
        LaunchedEffect(txtReplaceState.running, txtReplaceState.replacedCount) {
            if (!txtReplaceState.running && txtReplaceState.replacedCount > 0) triggerSearch()
        }
        LaunchedEffect(searchMode, inChapterMode) {
            if (searchQuery.isNotBlank()) triggerSearch()
        }

        Box {
            Surface(
                modifier = Modifier.fillMaxWidth().fillMaxHeight(
                    if (isTxtBook && replaceExpanded) 0.76f else 0.62f,
                ),
                color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.97f),
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                tonalElevation = 12.dp,
            ) {
                Column(modifier = Modifier.navigationBarsPadding().padding(top = 14.dp)) {
                    Box(
                        Modifier.width(40.dp).height(4.dp)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f), MaterialTheme.shapes.extraSmall)
                            .align(Alignment.CenterHorizontally)
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (isTxtBook) "查找与替换"
                            else if (inChapterMode) "章内搜索" else "全文搜索",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, "关闭", modifier = Modifier.size(18.dp))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    if (isTxtBook) {
                        // TXT 文件编辑是高风险动作，但入口也不能藏在标题栏角落：明确区分
                        // “查找”和“替换”两个工作区，搜索方向与范围仍由下面的选项控制。
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(
                                selected = !replaceExpanded,
                                onClick = { replaceExpanded = false },
                                label = { Text("查找", fontSize = 11.sp) },
                                modifier = Modifier.weight(1f).height(34.dp),
                            )
                            FilterChip(
                                selected = replaceExpanded,
                                onClick = { replaceExpanded = true },
                                label = { Text("替换", fontSize = 11.sp) },
                                modifier = Modifier.weight(1f).height(34.dp),
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        FilterChip(
                            selected = !inChapterMode,
                            onClick = { inChapterMode = false },
                            label = { Text("全书", fontSize = 10.sp) },
                            modifier = Modifier.height(32.dp),
                        )
                        FilterChip(
                            selected = inChapterMode,
                            onClick = { inChapterMode = true },
                            label = { Text("当前章", fontSize = 10.sp) },
                            modifier = Modifier.height(32.dp),
                        )
                        FilterChip(
                            selected = isRegex,
                            onClick = { isRegex = !isRegex },
                            label = { Text("正则", fontSize = 10.sp) },
                            modifier = Modifier.height(32.dp),
                        )
                        FilterChip(
                            selected = isCaseSensitive,
                            onClick = { isCaseSensitive = !isCaseSensitive },
                            label = { Text("Aa", fontSize = 10.sp) },
                            modifier = Modifier.height(32.dp),
                        )
                    }
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        listOf("all" to "全部", "forward" to "向前", "backward" to "向后").forEach { (mode, label) ->
                            FilterChip(
                                selected = searchMode == mode,
                                onClick = { onSearchModeChange(mode) },
                                label = { Text(label, fontSize = 10.sp) },
                                modifier = Modifier.height(30.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = {
                        Text(
                            if (inChapterMode) "在当前章节中查找" else "输入关键词搜索全书",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { triggerSearch() }),
                    trailingIcon = {
                        IconButton(onClick = triggerSearch) {
                            if (isSearching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                            else Icon(Icons.Default.Search, "搜索", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(48.dp),
                    textStyle = MaterialTheme.typography.bodySmall,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary, cursorColor = MaterialTheme.colorScheme.primary),
                )
                    if (!replaceExpanded && searchQuery.isNotEmpty() && !isSearching && searchError == null) {
                        Text(
                            "找到 ${searchResults.size} 处",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    if (isTxtBook && replaceExpanded) {
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = replacement,
                            onValueChange = { replacement = it },
                            placeholder = { Text("替换为（留空表示删除）", style = MaterialTheme.typography.bodySmall) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(48.dp),
                            textStyle = MaterialTheme.typography.bodySmall,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val statusText = txtReplaceState.error ?: txtReplaceState.message
                            Text(
                                text = statusText ?: "找到 ${searchResults.size} 处",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (txtReplaceState.error != null) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f),
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Button(
                                onClick = {
                                    pendingBulkReplaceFullBook = !inChapterMode
                                },
                                enabled = searchQuery.isNotEmpty() && !txtReplaceState.running,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            ) {
                                if (txtReplaceState.running) {
                                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                } else {
                                    Text(if (inChapterMode) "替换本章" else "替换全书", fontSize = 12.sp)
                                }
                            }
                            if (txtReplaceState.canUndo) {
                                Spacer(Modifier.width(6.dp))
                                TextButton(
                                    onClick = onUndoReplace,
                                    enabled = !txtReplaceState.running,
                                ) { Text("撤销", fontSize = 12.sp) }
                            }
                        }
                    }

                    val feedback = searchError ?: if (
                        searchResults.isEmpty() && !isSearching && searchQuery.isNotEmpty()
                    ) "未找到结果" else null
                    if (feedback != null) {
                        Text(
                            feedback,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (searchError != null) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        items(searchResults, key = { "sr_${it.chapterIndex}_${it.queryIndexInChapter}_${it.snippet.hashCode()}" }) { result ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f).clickable { onResultClick(result) }
                                        .padding(vertical = 3.dp),
                                ) {
                                    Text(result.chapterTitle, style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(result.snippet, style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                if (isTxtBook && replaceExpanded) {
                                    TextButton(
                                        onClick = { onReplaceMatch(result, replacement, isRegex, isCaseSensitive) },
                                        enabled = !txtReplaceState.running,
                                    ) { Text("替换", fontSize = 12.sp) }
                                }
                            }
                        }
                    }
                }
            }
            pendingBulkReplaceFullBook?.let { fullBook ->
                AlertDialog(
                    onDismissRequest = { pendingBulkReplaceFullBook = null },
                    title = { Text(if (fullBook) "替换整本 TXT？" else "替换当前章节？") },
                    text = {
                        Text(
                            "将“${searchQuery.take(40)}”替换为“${replacement.take(40)}”。" +
                                "\n\n这会直接修改原始 TXT，完成后可撤销一次。",
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            pendingBulkReplaceFullBook = null
                            onReplaceAll(searchQuery, replacement, !fullBook, isRegex, isCaseSensitive)
                        }) { Text("确认替换", color = MaterialTheme.colorScheme.error) }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingBulkReplaceFullBook = null }) { Text("取消") }
                    },
                )
            }
        }
    }
}

internal val EPUB_RICH_PAGE_ANIMATIONS = setOf(
    com.morealm.app.ui.reader.page.animation.PageAnimType.NONE,
    com.morealm.app.ui.reader.page.animation.PageAnimType.COVER,
    com.morealm.app.ui.reader.page.animation.PageAnimType.SLIDE,
    com.morealm.app.ui.reader.page.animation.PageAnimType.SIMULATION,
)

@Composable
internal fun NextBookPromptDialog(
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
internal fun SelectionWebPanel(
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
                            // 移动版 UA — 默认 WebView UA 部分网站会返回桌面版页面，渲染溢出。
                            // 用 Android Chrome UA，bing 等会返回更紧凑的移动版排版。
                            settings.userAgentString =
                                "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 " +
                                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                            // 加载错误兜底 —— 之前用 google.com 在国内被墙时 WebView
                            // 静默白屏（截图 16）。现在改成 bing 国内可访问，但仍可能遇到
                            // 偶发网络失败，给用户一个明确的错误提示而不是白屏。
                            webViewClient = object : WebViewClient() {
                                @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.M)
                                override fun onReceivedError(
                                    view: WebView,
                                    request: android.webkit.WebResourceRequest?,
                                    error: android.webkit.WebResourceError?,
                                ) {
                                    if (request?.isForMainFrame != true) return
                                    val msg = error?.description?.toString() ?: "加载失败"
                                    view.loadData(
                                        """<html><body style="padding:24px;font-family:sans-serif;color:#444">
                                        <h3>加载失败</h3>
                                        <p>$msg</p>
                                        <p style="color:#888;font-size:12px">检查网络后重试，或选中其他文字再试。</p>
                                        </body></html>""".trimIndent(),
                                        "text/html",
                                        "UTF-8",
                                    )
                                }
                            }
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
internal fun AutoPageIndicator(
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

/** 图片长按弹层目标：src + 长按点（view 坐标）+ 图片绘制矩形（选中态压暗/描边用；整页图 null）。 */
internal data class ImageActionTarget(
    val src: String,
    val anchor: androidx.compose.ui.geometry.Offset,
    val rect: androidx.compose.ui.geometry.Rect?,
)
