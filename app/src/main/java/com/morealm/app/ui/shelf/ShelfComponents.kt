package com.morealm.app.ui.shelf

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.morealm.app.domain.entity.BookFormat
import com.morealm.app.ui.theme.LocalMoRealmColors
import java.io.File

/** Resolve cover URL to a Coil-compatible data source */
private fun resolveCoverData(coverUrl: String?): Any? {
    if (coverUrl == null) return null
    // Local file path — use File object for reliable Coil loading
    if (coverUrl.startsWith("/")) {
        val file = File(coverUrl)
        if (!file.exists()) return null
        return file
    }
    // Content URI or HTTP URL — use as-is
    return coverUrl
}

// ── Generated cover for books without cover images ──

/**
 * MD3-aligned tonal cover palette.
 * Uses neutral/muted tones instead of random saturated colors.
 * All colors are dark enough for white icon/text overlay.
 */
private val coverColorPalette = listOf(
    Color(0xFF37474F), // Blue Grey 800
    Color(0xFF455A64), // Blue Grey 700
    Color(0xFF4E342E), // Brown 800
    Color(0xFF5D4037), // Brown 700
    Color(0xFF424242), // Grey 800
    Color(0xFF546E7A), // Blue Grey 600
    Color(0xFF3E2723), // Brown 900
    Color(0xFF616161), // Grey 700
)

/**
 * Stable color for a book — uses folderId (so all volumes in a folder match),
 * falls back to title with trailing digits stripped (so "vol 1" and "vol 2" match).
 */
private fun coverColorForBook(title: String, folderId: String?): Color {
    val key = folderId
        ?: title.replace(Regex("[\\d\\s._\\-]+$"), "").ifBlank { title }
    val hash = key.hashCode().let { if (it < 0) -it else it }
    return coverColorPalette[hash % coverColorPalette.size]
}

private fun formatIcon(format: BookFormat): ImageVector = when (format) {
    BookFormat.TXT -> Icons.Default.Description
    BookFormat.PDF -> Icons.Default.PictureAsPdf
    BookFormat.EPUB, BookFormat.MOBI, BookFormat.AZW3, BookFormat.UMD ->
        Icons.AutoMirrored.Filled.MenuBook
    BookFormat.CBZ -> Icons.Default.Image
    BookFormat.WEB -> Icons.Default.Language
    BookFormat.UNKNOWN -> Icons.AutoMirrored.Filled.InsertDriveFile
}

/** Generated gradient cover with centered icon — for TXT/unknown books without cover images */
@Composable
private fun GeneratedCover(
    title: String,
    format: BookFormat,
    folderId: String? = null,
    modifier: Modifier = Modifier,
    iconSize: Int = 36,
    showBadge: Boolean = true,
) {
    val baseColor = coverColorForBook(title, folderId)
    Box(
        modifier = modifier
            .background(
                Brush.linearGradient(
                    listOf(baseColor, baseColor.copy(alpha = 0.7f)),
                )
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            formatIcon(format),
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.55f),
            modifier = Modifier.size(iconSize.dp),
        )
        // Format badge (top-right)
        if (showBadge && format != BookFormat.UNKNOWN && format != BookFormat.WEB) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .background(
                        MaterialTheme.colorScheme.primary,
                        MaterialTheme.shapes.small,
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    if (format == BookFormat.WEB) "在线" else "本地",
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
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
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .then(if (selected) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)) else Modifier)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Cover
        Box(
            modifier = Modifier
                .aspectRatio(0.7f)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (book.coverUrl != null) {
                val context = LocalContext.current
                val imageRequest = remember(context, book.coverUrl) {
                    ImageRequest.Builder(context)
                        .data(resolveCoverData(book.coverUrl))
                        .size(240, 340)
                        .crossfade(80)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .allowHardware(true)
                        .build()
                }
                AsyncImage(
                    model = imageRequest,
                    contentDescription = book.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // Text-based cover for local books
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
                    Text(
                        text = book.format.name,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
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
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                )
            }
            // Selection checkmark
            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(22.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Check, null,
                        tint = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.size(14.dp))
                }
            } else if (book.lastCheckCount > 0) {
                // Legado-parity "N 新" badge: rendered top-end of cover, only when
                // batch refresh discovered new chapters since the user last opened
                // the book. Cleared when user navigates into the reader.
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .background(
                            MaterialTheme.colorScheme.error,
                            RoundedCornerShape(8.dp),
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (book.lastCheckCount > 99) "99+" else "${book.lastCheckCount} 新",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = MaterialTheme.colorScheme.onError,
                        fontWeight = FontWeight.Bold,
                    )
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
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
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
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                if (book.coverUrl != null) {
                    val context = LocalContext.current
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(resolveCoverData(book.coverUrl))
                            .size(96, 128)
                            .crossfade(80)
                            .memoryCachePolicy(CachePolicy.ENABLED)
                            .diskCachePolicy(CachePolicy.ENABLED)
                            .allowHardware(true)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        Icons.AutoMirrored.Filled.MenuBook,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "继续阅读",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
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
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
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

/** Folder list item — left cover + right name/count, matching BookListItem style */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FolderListItem(
    name: String,
    bookCount: Int,
    coverUrl: String? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val moColors = LocalMoRealmColors.current
    val subColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f)
    val subStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Cover — same size as BookListItem
        Box(
            modifier = Modifier
                .size(60.dp, 84.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (coverUrl != null) {
                val context = LocalContext.current
                // remember 同 BookGridItem / FolderCard 修复 — 滚动时不重建 ImageRequest
                val req = remember(context, coverUrl) {
                    ImageRequest.Builder(context)
                        .data(resolveCoverData(coverUrl))
                        .size(180, 252)
                        .crossfade(80)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .allowHardware(true)
                        .build()
                }
                AsyncImage(
                    model = req,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    Icons.Default.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    modifier = Modifier.size(32.dp),
                )
            }
        }

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "$bookCount 本书",
                style = subStyle,
                color = subColor,
            )
        }

        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f),
            modifier = Modifier.size(20.dp),
        )
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
            .clip(MaterialTheme.shapes.medium)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .aspectRatio(0.7f)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            val validCovers = coverUrls.filterNotNull().take(4)
            if (validCovers.isNotEmpty()) {
                // Show cover mosaic: 1 cover = full, 2 = side by side, 3-4 = 2x2 grid
                //
                // 性能要点：每个 AsyncImage 的 ImageRequest 用 remember 缓存。
                // 之前每次重组都重新 build()，滚动时一屏 4 个 folder × 4 张图 = 16 个
                // 新对象/帧，主线程构建 + Coil pipeline 检查导致明显卡顿。
                // BookGridItem 早就用了同样的 remember 模式，FolderCard 这里漏了。
                when (validCovers.size) {
                    1 -> {
                        val req = remember(context, validCovers[0]) {
                            ImageRequest.Builder(context)
                                .data(resolveCoverData(validCovers[0])).size(240, 340).crossfade(80)
                                .memoryCachePolicy(CachePolicy.ENABLED).diskCachePolicy(CachePolicy.ENABLED)
                                .allowHardware(true).build()
                        }
                        AsyncImage(
                            model = req,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    else -> {
                        // 2x2 grid (or 2x1 for 2 covers)
                        val req0 = remember(context, validCovers[0]) {
                            ImageRequest.Builder(context)
                                .data(resolveCoverData(validCovers[0])).size(120, 170).crossfade(80)
                                .memoryCachePolicy(CachePolicy.ENABLED).diskCachePolicy(CachePolicy.ENABLED)
                                .allowHardware(true).build()
                        }
                        val req1 = remember(context, validCovers[1]) {
                            ImageRequest.Builder(context)
                                .data(resolveCoverData(validCovers[1])).size(120, 170).crossfade(80)
                                .memoryCachePolicy(CachePolicy.ENABLED).diskCachePolicy(CachePolicy.ENABLED)
                                .allowHardware(true).build()
                        }
                        val req2 = if (validCovers.size >= 3) {
                            remember(context, validCovers[2]) {
                                ImageRequest.Builder(context)
                                    .data(resolveCoverData(validCovers[2])).size(120, 170).crossfade(80)
                                    .memoryCachePolicy(CachePolicy.ENABLED).diskCachePolicy(CachePolicy.ENABLED)
                                    .allowHardware(true).build()
                            }
                        } else null
                        val req3 = if (validCovers.size >= 4) {
                            remember(context, validCovers[3]) {
                                ImageRequest.Builder(context)
                                    .data(resolveCoverData(validCovers[3])).size(120, 170).crossfade(80)
                                    .memoryCachePolicy(CachePolicy.ENABLED).diskCachePolicy(CachePolicy.ENABLED)
                                    .allowHardware(true).build()
                            }
                        } else null
                        Column(Modifier.fillMaxSize()) {
                            Row(Modifier.weight(1f).fillMaxWidth()) {
                                AsyncImage(
                                    model = req0,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.weight(1f).fillMaxHeight().padding(end = 0.5.dp),
                                )
                                AsyncImage(
                                    model = req1,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.weight(1f).fillMaxHeight().padding(start = 0.5.dp),
                                )
                            }
                            if (validCovers.size >= 3) {
                                Row(Modifier.weight(1f).fillMaxWidth().padding(top = 1.dp)) {
                                    AsyncImage(
                                        model = req2!!,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.weight(1f).fillMaxHeight().padding(end = 0.5.dp),
                                    )
                                    if (validCovers.size >= 4) {
                                        AsyncImage(
                                            model = req3!!,
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.weight(1f).fillMaxHeight().padding(start = 0.5.dp),
                                        )
                                    } else {
                                        Box(
                                            Modifier.weight(1f).fillMaxHeight()
                                                .padding(start = 0.5.dp)
                                                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
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
                                MaterialTheme.shapes.small
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
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
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

/** List view item — cover + title/author/chapter/progress (ported from Legado item_bookshelf_list) */
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
    val subColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f)
    val subStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .then(
                if (selected) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), MaterialTheme.shapes.medium)
                else Modifier
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Cover — 60x84dp (Legado uses 66x90, we go slightly smaller for Compose density)
        Box(
            modifier = Modifier
                .size(60.dp, 84.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (book.coverUrl != null) {
                val context = LocalContext.current
                val imageRequest = remember(context, book.coverUrl) {
                    ImageRequest.Builder(context)
                        .data(resolveCoverData(book.coverUrl))
                        .size(180, 252)
                        .crossfade(80)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .allowHardware(true)
                        .build()
                }
                AsyncImage(
                    model = imageRequest,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxSize().padding(4.dp),
                ) {
                    Text(
                        book.title.take(6),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // Progress bar at bottom of cover
            if (book.readProgress > 0f) {
                LinearProgressIndicator(
                    progress = { book.readProgress },
                    modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                )
            }
            // Selection checkmark
            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(3.dp)
                        .size(18.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Check, null,
                        tint = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.size(12.dp))
                }
            }
        }

        Spacer(Modifier.width(14.dp))

        // Right side: title + author + chapter info + last read time
        Column(modifier = Modifier.weight(1f)) {
            // Row 1: Book title
            Text(
                book.title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))

            // Row 2: Author · Format
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (book.author.isNotBlank()) {
                    Text(book.author, style = subStyle, color = subColor, maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false))
                    Text(" · ", style = subStyle, color = subColor.copy(alpha = 0.5f))
                }
                Text(
                    book.format.name,
                    style = subStyle,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                )
            }
            Spacer(Modifier.height(3.dp))

            // Row 3: Reading progress (chapter position)
            val chapterInfo = when {
                book.totalChapters > 0 && book.lastReadChapter > 0 ->
                    "读到第 ${book.lastReadChapter + 1}/${book.totalChapters} 章"
                book.readProgress > 0f ->
                    "已读 ${(book.readProgress * 100).toInt()}%"
                else -> "未开始阅读"
            }
            Text(chapterInfo, style = subStyle, color = subColor, maxLines = 1)
            Spacer(Modifier.height(3.dp))

            // Row 4: Last read time
            val timeText = if (book.lastReadAt > 0) {
                formatTimeAgo(book.lastReadAt)
            } else ""
            if (timeText.isNotEmpty()) {
                Text(timeText, style = subStyle.copy(fontSize = 11.sp),
                    color = subColor.copy(alpha = 0.6f), maxLines = 1)
            }
        }
    }
}

/** Format timestamp to relative time string */
private fun formatTimeAgo(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3600_000 -> "${diff / 60_000} 分钟前"
        diff < 86400_000 -> "${diff / 3600_000} 小时前"
        diff < 2592000_000 -> "${diff / 86400_000} 天前"
        else -> {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            sdf.format(java.util.Date(timestamp))
        }
    }
}
