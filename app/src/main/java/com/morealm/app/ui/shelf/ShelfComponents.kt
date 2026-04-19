package com.morealm.app.ui.shelf

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Size
import com.morealm.app.domain.entity.Book
import com.morealm.app.ui.theme.LocalMoRealmColors
import java.io.File

/** Resolve cover URL to a Coil-compatible data source */
private fun resolveCoverData(coverUrl: String?): Any? {
    if (coverUrl == null) return null
    // Local file path — use File object for reliable Coil loading
    if (coverUrl.startsWith("/")) return File(coverUrl)
    // Content URI or HTTP URL — use as-is
    return coverUrl
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookGridItem(
    book: Book,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val moColors = LocalMoRealmColors.current

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .then(if (selected) Modifier.background(moColors.accent.copy(alpha = 0.12f)) else Modifier)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Cover
        Box(
            modifier = Modifier
                .aspectRatio(0.7f)
                .clip(RoundedCornerShape(8.dp))
                .background(moColors.surfaceGlass),
            contentAlignment = Alignment.Center,
        ) {
            if (book.coverUrl != null) {
                val context = LocalContext.current
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(resolveCoverData(book.coverUrl))
                        .size(240, 340)
                        .crossfade(150)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .build(),
                    contentDescription = book.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // Generated cover for local books — show title + author like HTML prototype
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxSize().padding(10.dp),
                ) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = book.title,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            lineHeight = 16.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (book.author.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = book.author,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    // Format badge at bottom
                    Text(
                        text = book.format.name,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = moColors.accent.copy(alpha = 0.6f),
                    )
                    Spacer(Modifier.height(2.dp))
                }
            }

            // Progress indicator
            if (book.readProgress > 0f) {
                LinearProgressIndicator(
                    progress = { book.readProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter),
                    color = moColors.accent,
                    trackColor = moColors.accent.copy(alpha = 0.15f),
                )
            }
            // Selection checkmark
            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(22.dp)
                        .background(moColors.accent, RoundedCornerShape(11.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Check, null,
                        tint = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.size(14.dp))
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        Text(
            text = book.title,
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
            ),
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        if (book.author.isNotBlank()) {
            Text(
                text = book.author,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun ContinueReadingCard(
    book: Book,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val moColors = LocalMoRealmColors.current

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = moColors.surfaceGlass,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Mini cover
            Box(
                modifier = Modifier
                    .size(48.dp, 64.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(moColors.accent.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                if (book.coverUrl != null) {
                    val context = LocalContext.current
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(resolveCoverData(book.coverUrl))
                            .size(96, 128)
                            .crossfade(150)
                            .memoryCachePolicy(CachePolicy.ENABLED)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        Icons.AutoMirrored.Filled.MenuBook,
                        contentDescription = null,
                        tint = moColors.accent,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "继续阅读",
                    style = MaterialTheme.typography.labelSmall,
                    color = moColors.accent,
                )
                Text(
                    book.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { book.readProgress },
                    modifier = Modifier.fillMaxWidth(),
                    color = moColors.accent,
                    trackColor = moColors.accent.copy(alpha = 0.15f),
                )
            }
        }
    }
}

@Composable
fun EmptyShelf(
    onImportFile: () -> Unit,
    onImportFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "📚",
            fontSize = 48.sp,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "书架空空如也",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "导入本地书籍开始阅读",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
        )
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onImportFile) {
                Text("选择文件")
            }
            Button(onClick = onImportFolder) {
                Text("导入文件夹")
            }
        }
    }
}

/** Folder card — shows a mosaic of book cover thumbnails */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FolderCard(
    name: String,
    bookCount: Int,
    coverUrls: List<String?> = emptyList(),
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val moColors = LocalMoRealmColors.current
    val context = LocalContext.current

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .aspectRatio(0.7f)
                .clip(RoundedCornerShape(12.dp))
                .background(moColors.surfaceGlass),
            contentAlignment = Alignment.Center,
        ) {
            val validCovers = coverUrls.filterNotNull().take(4)
            if (validCovers.isNotEmpty()) {
                // Show cover mosaic: 1 cover = full, 2 = side by side, 3-4 = 2x2 grid
                when (validCovers.size) {
                    1 -> {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(resolveCoverData(validCovers[0])).size(240, 340).crossfade(150)
                                .memoryCachePolicy(CachePolicy.ENABLED).build(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    else -> {
                        // 2x2 grid (or 2x1 for 2 covers)
                        Column(Modifier.fillMaxSize()) {
                            Row(Modifier.weight(1f).fillMaxWidth()) {
                                AsyncImage(
                                    model = ImageRequest.Builder(context)
                                        .data(resolveCoverData(validCovers[0])).size(120, 170).crossfade(150)
                                        .memoryCachePolicy(CachePolicy.ENABLED).build(),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.weight(1f).fillMaxHeight().padding(end = 0.5.dp),
                                )
                                AsyncImage(
                                    model = ImageRequest.Builder(context)
                                        .data(resolveCoverData(validCovers[1])).size(120, 170).crossfade(150)
                                        .memoryCachePolicy(CachePolicy.ENABLED).build(),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.weight(1f).fillMaxHeight().padding(start = 0.5.dp),
                                )
                            }
                            if (validCovers.size >= 3) {
                                Row(Modifier.weight(1f).fillMaxWidth().padding(top = 1.dp)) {
                                    AsyncImage(
                                        model = ImageRequest.Builder(context)
                                            .data(resolveCoverData(validCovers[2])).size(120, 170).crossfade(150)
                                            .memoryCachePolicy(CachePolicy.ENABLED).build(),
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.weight(1f).fillMaxHeight().padding(end = 0.5.dp),
                                    )
                                    if (validCovers.size >= 4) {
                                        AsyncImage(
                                            model = ImageRequest.Builder(context)
                                                .data(resolveCoverData(validCovers[3])).size(120, 170).crossfade(150)
                                                .memoryCachePolicy(CachePolicy.ENABLED).build(),
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.weight(1f).fillMaxHeight().padding(start = 0.5.dp),
                                        )
                                    } else {
                                        Box(
                                            Modifier.weight(1f).fillMaxHeight()
                                                .padding(start = 0.5.dp)
                                                .background(moColors.surfaceGlass),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                "+${bookCount - 3}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                // Overlay: book count badge
                if (bookCount > 1) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                            .background(
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                                RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            "$bookCount 本",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                    }
                }
            } else {
                // No covers — fallback to folder icon
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        Icons.Default.Folder,
                        contentDescription = null,
                        tint = moColors.accent.copy(alpha = 0.7f),
                        modifier = Modifier.size(36.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "$bookCount 本",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            name,
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = FontWeight.Medium, fontSize = 12.sp,
            ),
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** List view item — compact row with cover thumbnail, title, author, progress */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookListItem(
    book: Book,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val moColors = LocalMoRealmColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .then(if (selected) Modifier.background(moColors.accent.copy(alpha = 0.08f)) else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Mini cover
        Box(
            modifier = Modifier
                .size(40.dp, 56.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(moColors.surfaceGlass),
            contentAlignment = Alignment.Center,
        ) {
            if (book.coverUrl != null) {
                val context = LocalContext.current
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(resolveCoverData(book.coverUrl))
                        .size(80, 112)
                        .crossfade(150)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(Icons.AutoMirrored.Filled.MenuBook, null,
                    tint = moColors.accent.copy(alpha = 0.5f),
                    modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                book.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (book.author.isNotBlank()) {
                    Text(book.author,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                        maxLines = 1)
                    Text(" · ", color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f),
                        style = MaterialTheme.typography.labelSmall)
                }
                Text(book.format.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = moColors.accent.copy(alpha = 0.6f))
                if (book.readProgress > 0f) {
                    Text(" · ${(book.readProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f))
                }
            }
        }
        if (selected) {
            Icon(Icons.Default.Check, null,
                tint = moColors.accent, modifier = Modifier.size(20.dp))
        }
    }
}
