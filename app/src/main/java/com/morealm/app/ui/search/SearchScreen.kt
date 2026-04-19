package com.morealm.app.ui.search

import androidx.compose.foundation.background
import com.morealm.app.presentation.search.SearchViewModel
import com.morealm.app.presentation.search.SourceSearchProgress
import com.morealm.app.presentation.search.SourceStatus
import com.morealm.app.presentation.search.SearchResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.morealm.app.domain.entity.Book
import com.morealm.app.ui.theme.LocalMoRealmColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onBack: () -> Unit = {},
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val moColors = LocalMoRealmColors.current
    var query by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val results by viewModel.results.collectAsState()
    val localResults by viewModel.localResults.collectAsState()
    val sourceProgress by viewModel.sourceProgress.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val disclaimerAccepted by viewModel.disclaimerAccepted.collectAsState()

    // Don't auto-focus — it causes issues when embedded in HorizontalPager
    // Focus will be requested when user taps the search field

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        TopAppBar(
            title = {},
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
            ),
        )

        // Disclaimer
        if (!disclaimerAccepted) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "搜索结果来自用户导入的书源，MoRealm 不提供任何内容，请遵守当地法律法规。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "知道了",
                        style = MaterialTheme.typography.labelSmall,
                        color = moColors.accent,
                        modifier = Modifier.clickable { viewModel.acceptDisclaimer() }
                            .padding(start = 8.dp),
                    )
                }
            }
        }

        // Search bar
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .focusRequester(focusRequester),
            placeholder = { Text("搜索书名或作者") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            shape = RoundedCornerShape(12.dp),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = moColors.accent,
                cursorColor = moColors.accent,
            ),
            keyboardActions = KeyboardActions(
                onSearch = { viewModel.search(query) }
            ),
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Search
            ),
        )

        // Source search progress indicator
        if (sourceProgress.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            SourceProgressBar(sourceProgress)
        }

        Spacer(Modifier.height(8.dp))

        if (results.isEmpty() && localResults.isEmpty() && !isSearching) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (sourceProgress.isEmpty()) "输入关键词搜索" else "暂无结果",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Local shelf results
                if (localResults.isNotEmpty()) {
                    item {
                        Text(
                            "书架 (${localResults.size})",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                    items(localResults, key = { it.id }) { book ->
                        LocalBookItem(book)
                    }
                }

                // Online results
                if (results.isNotEmpty()) {
                    item {
                        Text(
                            "在线 (${results.size})",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                        )
                    }
                    items(results, key = { "${it.sourceId}_${it.bookUrl}" }) { result ->
                        SearchResultItem(result)
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceProgressBar(progress: List<SourceSearchProgress>) {
    val moColors = LocalMoRealmColors.current
    val total = progress.size
    val done = remember(progress) { progress.count { it.status == SourceStatus.DONE || it.status == SourceStatus.FAILED } }
    val searching = remember(progress) { progress.filter { it.status == SourceStatus.SEARCHING } }

    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        LinearProgressIndicator(
            progress = { if (total > 0) done.toFloat() / total else 0f },
            modifier = Modifier.fillMaxWidth().height(3.dp),
            color = moColors.accent,
            trackColor = moColors.accent.copy(alpha = 0.12f),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (searching.isNotEmpty()) {
                "正在搜索: ${searching.joinToString(", ") { it.sourceName }} ($done/$total)"
            } else {
                "搜索完成 $done/$total"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
        )
    }
}

@Composable
private fun LocalBookItem(book: Book) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { /* TODO: navigate to reader */ },
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    book.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (book.author.isNotBlank()) {
                    Text(
                        book.author,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }
            Text(
                book.format.name,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            )
        }
    }
}

@Composable
private fun SearchResultItem(result: SearchResult) {
    val moColors = LocalMoRealmColors.current
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { /* TODO: navigate to detail */ },
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                result.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (result.author.isNotBlank()) {
                Text(
                    result.author,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            Row {
                Text(
                    "来源: ${result.sourceName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = moColors.accent.copy(alpha = 0.8f),
                )
            }
        }
    }
}
