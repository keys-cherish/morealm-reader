package com.morealm.app.ui.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.morealm.app.presentation.discover.DiscoverBook
import com.morealm.app.presentation.discover.DiscoverViewModel
import com.morealm.app.domain.entity.SearchBook
import com.morealm.app.presentation.search.SearchResult
import com.morealm.app.presentation.search.SearchViewModel
import com.morealm.app.ui.theme.LocalMoRealmColors

@Composable
fun DiscoverScreen(
    onNavigateDetail: (String) -> Unit,
    isActive: Boolean = true,
    viewModel: DiscoverViewModel = hiltViewModel(),
    searchViewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(isActive) {
        if (isActive) viewModel.refresh()
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 20.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "发现",
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontFamily = FontFamily.Serif,
                            fontSize = 24.sp,
                            lineHeight = 29.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.sp,
                            platformStyle = PlatformTextStyle(includeFontPadding = false),
                        ),
                    )
                    Text(
                        text = "${state.sourceCount} 个书源 · ${state.books.size} 本",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = viewModel::refresh) {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新发现")
                }
            }
        }
        if (state.isRefreshing) {
            item {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
            }
        }
        state.message?.let { message ->
            item {
                Text(message, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(
            items = state.books,
            key = { "${it.sourceUrl}|${it.bookUrl}|${it.title}" },
        ) { book ->
            DiscoverBookRow(
                book = book,
                onClick = {
                    searchViewModel.viewBook(book.toSearchResult(), onBookReady = onNavigateDetail)
                },
            )
        }
    }
}

@Composable
private fun DiscoverBookRow(book: DiscoverBook, onClick: () -> Unit) {
    val isNight = LocalMoRealmColors.current.isNight
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = if (isNight) {
            MaterialTheme.colorScheme.surface
        } else {
            Color.White.copy(alpha = 0.76f)
        },
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(width = 56.dp, height = 76.dp),
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                if (!book.coverUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = book.coverUrl,
                        contentDescription = book.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            book.title,
                            fontSize = 9.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(5.dp),
                        )
                    }
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    book.title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = FontFamily.Serif,
                        fontSize = 14.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.sp,
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    book.author.ifBlank { book.sourceName },
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = FontFamily.Serif,
                        fontSize = 10.sp,
                        lineHeight = 13.sp,
                        letterSpacing = 0.sp,
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    book.displaySummary(),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Serif,
                        fontSize = 9.sp,
                        lineHeight = 12.sp,
                        letterSpacing = 0.sp,
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.32f),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

private fun DiscoverBook.displaySummary(): String = intro
    .trim()
    .removePrefix("简介：")
    .removePrefix("简介:")
    .replace("\\r", "")
    .replace("\\n", " ")
    .trim()
    .ifBlank {
        latestChapter?.takeIf(String::isNotBlank)?.let { "最新章节：$it" }
            ?: kind?.takeIf(String::isNotBlank)?.let { "$it 题材作品，等待你开启故事。" }
            ?: "来自 $sourceName 的精选作品。"
    }

private fun DiscoverBook.toSearchResult() = SearchResult(
    title = title,
    author = author,
    coverUrl = coverUrl,
    bookUrl = bookUrl,
    sourceName = sourceName,
    sourceUrl = sourceUrl,
    sourceType = sourceType,
    intro = intro,
    kind = kind,
    wordCount = wordCount,
    latestChapter = latestChapter,
    searchBook = SearchBook(
        bookUrl = bookUrl,
        origin = sourceUrl,
        originName = sourceName,
        type = sourceType,
        name = title,
        author = author,
        kind = kind,
        coverUrl = coverUrl,
        intro = intro,
        wordCount = wordCount,
        latestChapterTitle = latestChapter,
        tocUrl = tocUrl.orEmpty(),
        variable = variable,
    ),
)
