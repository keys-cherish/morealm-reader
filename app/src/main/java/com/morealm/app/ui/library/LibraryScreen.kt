package com.morealm.app.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.morealm.app.domain.entity.Book
import com.morealm.app.presentation.library.LibraryFilter
import com.morealm.app.presentation.library.LibrarySection
import com.morealm.app.presentation.library.LibraryViewModel
import com.morealm.app.util.PinyinInitials
import kotlinx.coroutines.launch

/** 网格列数；段头行独占整行。 */
private const val COLUMNS = 3

/**
 * 图书馆页：
 * 衬线大标题 + 常驻检索框 + 状态 chips + 拼音字母分段网格 + 右侧 A-Z 索引条。
 * 配色走当前主题 colorScheme，日/夜主题自动适配。
 */
@Composable
fun LibraryScreen(
    onBookClick: (String) -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    // 段头字母 → grid item index（header 与 book 混排的线性索引）
    val headerIndexByLetter = remember(state.sections) {
        var index = 0
        buildMap {
            state.sections.forEach { section ->
                put(section.letter, index)
                index += 1 + section.books.size
            }
        }
    }
    var draggingLetter by remember { mutableStateOf<Char?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(COLUMNS),
            contentPadding = PaddingValues(start = 24.dp, end = 34.dp, bottom = 120.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                LibraryHeader(totalCount = state.totalCount)
            }
            item(key = "search", span = { GridItemSpan(maxLineSpan) }) {
                LibrarySearchField(query = query, onQueryChange = viewModel::setQuery)
            }
            item(key = "chips", span = { GridItemSpan(maxLineSpan) }) {
                LibraryFilterChips(
                    current = state.filter,
                    counts = state.filterCounts,
                    onSelect = viewModel::setFilter,
                )
            }
            state.sections.forEach { section ->
                item(key = "sec_${section.letter}", span = { GridItemSpan(maxLineSpan) }) {
                    SectionHeader(section = section)
                }
                items(
                    count = section.books.size,
                    key = { i -> "b_${section.books[i].id}" },
                ) { i ->
                    val book = section.books[i]
                    LibraryBookItem(book = book, onClick = { onBookClick(book.id) })
                }
            }
        }

        AlphabetRail(
            presentLetters = headerIndexByLetter.keys,
            activeLetter = draggingLetter,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 2.dp),
            onLetterFocused = { letter ->
                draggingLetter = letter
                headerIndexByLetter[letter]?.let { target ->
                    scope.launch { gridState.scrollToItem(target) }
                }
            },
            onRelease = { draggingLetter = null },
        )

        draggingLetter?.let { letter ->
            LetterBubble(
                letter = letter,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 44.dp),
            )
        }
    }
}

@Composable
private fun LibraryHeader(totalCount: Int) {
    Column(modifier = Modifier.padding(top = 18.dp)) {
        Text(
            text = "图书馆",
            fontWeight = FontWeight.Bold,
            fontSize = 30.sp,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "%,d 册 · 按拼音排列".format(totalCount),
            fontSize = 12.sp,
            letterSpacing = 1.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LibrarySearchField(query: String, onQueryChange: (String) -> Unit) {
    // 聚焦时隐藏占位文字，避免光标压在「书名…」第一个字上
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = TextStyle(
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
            interactionSource = interactionSource,
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty() && !focused) {
                        Text(
                            text = "书名 / 作者 / 拼音首字母…",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    inner()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun LibraryFilterChips(
    current: LibraryFilter,
    counts: Map<LibraryFilter, Int>,
    onSelect: (LibraryFilter) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LibraryFilter.entries.forEach { filter ->
            val selected = filter == current
            val bg = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.surface
            val fg = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurface
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(bg)
                    .border(
                        1.dp,
                        if (selected) bg else MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(16.dp),
                    )
                    .clickable { onSelect(filter) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Text(text = filter.label, fontSize = 12.sp, color = fg)
                counts[filter]?.let { count ->
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = compactCount(count),
                        fontSize = 11.sp,
                        color = fg.copy(alpha = 0.55f),
                    )
                }
            }
        }
    }
}

/** 102406 → 102k；chips 上的紧凑计数。 */
private fun compactCount(n: Int): String =
    if (n >= 10_000) "${n / 1000}k" else n.toString()

@Composable
private fun SectionHeader(section: LibrarySection) {
    Row(
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier.padding(top = 10.dp),
    ) {
        Text(
            text = section.letter.toString(),
            fontSize = 26.sp,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = "%,d 册".format(section.books.size),
            fontSize = 11.sp,
            letterSpacing = 2.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
    }
}

@Composable
private fun LibraryBookItem(book: Book, onClick: () -> Unit) {
    Column(modifier = Modifier.clickable(onClick = onClick)) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            shadowElevation = 2.dp,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 2.9f),
        ) {
            val cover = book.displayCoverUrl
            if (cover != null) {
                AsyncImage(
                    model = cover,
                    contentDescription = book.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Text(
                        text = book.title,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            text = book.title,
            fontSize = 11.5.sp,
            lineHeight = 15.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 右侧 A-Z 索引条：点按/拖动聚焦字母并回调跳转。
 * 无书的字母置灰且不响应；按住期间由调用方显示气泡，松手回调 [onRelease]。
 */
@Composable
private fun AlphabetRail(
    presentLetters: Set<Char>,
    activeLetter: Char?,
    onLetterFocused: (Char) -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val letters = PinyinInitials.GROUPS
    var railHeightPx by remember { mutableStateOf(1f) }

    fun letterAt(y: Float): Char? {
        val idx = ((y / railHeightPx) * letters.size).toInt().coerceIn(0, letters.size - 1)
        val letter = letters[idx]
        return letter.takeIf { it in presentLetters }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxHeight()
            .width(26.dp)
            .padding(vertical = 90.dp)
            .onSizeChanged { railHeightPx = it.height.toFloat().coerceAtLeast(1f) }
            .pointerInput(presentLetters) {
                detectTapGestures(
                    onPress = { offset ->
                        letterAt(offset.y)?.let(onLetterFocused)
                        tryAwaitRelease()
                        onRelease()
                    },
                )
            }
            .pointerInput(presentLetters) {
                detectDragGestures(
                    onDrag = { change, _ ->
                        change.consume()
                        letterAt(change.position.y)?.let(onLetterFocused)
                    },
                    onDragEnd = onRelease,
                    onDragCancel = onRelease,
                )
            },
    ) {
        letters.forEach { letter ->
            val present = letter in presentLetters
            val isActive = letter == activeLetter
            // 每个字母 weight(1f) 等分槽位 —— 与 letterAt 的均分命中公式严格一致，
            // 消除 SpaceBetween 排布下「手指位置和命中字母错位」的几何偏差。
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = letter.toString(),
                    fontSize = 9.sp,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                    color = when {
                        isActive -> MaterialTheme.colorScheme.onPrimary
                        present -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                    },
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            if (isActive) MaterialTheme.colorScheme.primary
                            else Color.Transparent
                        )
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
    }
}

@Composable
private fun LetterBubble(letter: Char, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primary),
    ) {
        Text(
            text = letter.toString(),
            fontSize = 28.sp,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}
