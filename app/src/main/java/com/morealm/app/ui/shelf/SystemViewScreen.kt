package com.morealm.app.ui.shelf

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.morealm.app.domain.entity.Book
import com.morealm.app.presentation.shelf.ShelfViewModel
import kotlinx.coroutines.flow.Flow
import com.morealm.app.presentation.shelf.SystemView

/**
 * Drill-down screen for a single [SystemView]. Listed books come from
 * [ShelfViewModel.booksInSystemView], which the ViewModel re-binds whenever
 * `selectSystemView()` is called.
 *
 * Each row is a minimal book entry — title + author + progress — keeping the
 * detail screen visually distinct from the main shelf grid. Tapping a row
 * routes through the same `onBookOpen` callback the shelf uses, so WEB books
 * still go through the detail-first router.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemViewScreen(
    viewName: String,
    onBack: () -> Unit,
    onBookOpen: (Book) -> Unit,
    viewModel: ShelfViewModel = hiltViewModel(),
) {
    val view = remember(viewName) {
        runCatching { SystemView.valueOf(viewName) }.getOrNull()
    }
    LaunchedEffect(view) {
        if (view != null) viewModel.selectSystemView(view)
    }
    val books by viewModel.booksInSystemView.collectAsStateWithLifecycle(initialValue = emptyList())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        TopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(view?.emoji ?: "", fontSize = 18.sp)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(view?.displayName ?: viewName, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        if (view != null) {
                            Text(view.description, fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
                        }
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
            ),
        )

        if (books.isEmpty()) {
            EmptyView(view)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(books, key = { it.id }) { book ->
                    BookRow(book = book, onClick = { onBookOpen(book) })
                }
            }
        }
    }
}

@Composable
private fun BookRow(book: Book, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(width = 40.dp, height = 56.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    book.title.take(1),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    book.title,
                    fontWeight = FontWeight.Medium,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                )
                if (book.author.isNotBlank()) {
                    Text(
                        book.author,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        maxLines = 1,
                    )
                }
                if (book.readProgress > 0f) {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { book.readProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                    )
                }
            }
            if (book.lastCheckCount > 0) {
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        "${book.lastCheckCount} 新",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyView(view: SystemView?) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(view?.emoji ?: "📭", fontSize = 48.sp)
            Spacer(Modifier.height(12.dp))
            Text(
                "这里还空着",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
            view?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    it.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                )
            }
        }
    }
}

// ── Helpers ────────────────────────────────────────────────────────────────

@Composable
private fun <T> Flow<T>.collectAsStateWithLifecycleSafe(initial: T) =
    collectAsStateWithLifecycle(initialValue = initial)
