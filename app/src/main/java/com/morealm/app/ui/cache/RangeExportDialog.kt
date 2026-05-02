package com.morealm.app.ui.cache

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.entity.BookChapter
import com.morealm.app.presentation.cache.CacheBookViewModel

/**
 * Stage C：导出格式枚举。Stage B 单本 EPUB 入口走「per-book ⋮ 菜单」直达，
 * 走范围对话框时仍可二选一。
 */
enum class ExportFormat { TXT, EPUB }

/**
 * Stage A/C 范围导出对话框（顶部菜单触发）。两步式：
 *
 *  1. **选书** — 列出全部 web 书；带搜索框过滤 title/author。每行右侧显示
 *     已缓存章节数，未缓存任何章节的书做灰色处理但仍可点（用户也许只是想
 *     "先选好范围再缓存这个范围"，不强制阻断）。
 *  2. **选范围** — 显示该书章节列表（只列非卷标），用户点起始章 + 终止章。
 *     点击节奏：第一次点 = 起始；第二次点 = 终止；再点则重置。下方两个数字
 *     输入也可手动输入（1-based，对用户友好）。同时提供：
 *     - **格式**（TXT / EPUB）单选
 *     - **EPUB 分卷大小**（仅 EPUB 模式可见；0 = 单文件）— 参考 Legado
 *       CustomExporter.size 把超长本切分；当前实现切分由调用方分多次写入
 *       多个文件，对话框这里只回传 size 让上层负责拆分。
 *
 * 点「确认导出」回调 [onConfirm]，给到 (book, startIndex, endIndex, format, epubSize)。
 * 上层负责 SAF 创建文档（按 format 选 MIME / 后缀） + ViewModel.exportTxt/Epub 调用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RangeExportDialog(
    books: List<Book>,
    cacheStats: Map<String, CacheBookViewModel.CacheStat>,
    /** key = bookId. 由 ViewModel 在用户进入选书第二步时按需加载。 */
    chaptersByBook: Map<String, List<BookChapter>>,
    onLoadChapters: (bookId: String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (book: Book, startIndex: Int, endIndex: Int, format: ExportFormat, epubSize: Int) -> Unit,
) {
    var selectedBook by remember { mutableStateOf<Book?>(null) }
    var query by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selectedBook != null) {
                    IconButton(onClick = { selectedBook = null }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回选书")
                    }
                }
                Text(if (selectedBook == null) "选择要导出的书" else "选择章节范围",
                    fontWeight = FontWeight.Bold)
            }
        },
        text = {
            val book = selectedBook
            if (book == null) {
                BookPickerStep(
                    books = books,
                    cacheStats = cacheStats,
                    query = query,
                    onQueryChange = { query = it },
                    onPick = {
                        selectedBook = it
                        onLoadChapters(it.id)
                    },
                )
            } else {
                val chapters = chaptersByBook[book.id]
                if (chapters == null) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                    }
                } else if (chapters.isEmpty()) {
                    Text("此书未获取到任何可导出章节，请先在书架刷新目录。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error)
                } else {
                    RangePickerStep(
                        chapters = chapters,
                        onConfirm = { from, to, fmt, epubSize ->
                            onConfirm(book, from, to, fmt, epubSize)
                        },
                        onCancel = onDismiss,
                    )
                }
            }
        },
        // RangePickerStep 自带「确认导出」按钮；选书阶段不显示主按钮，避免冲突。
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun BookPickerStep(
    books: List<Book>,
    cacheStats: Map<String, CacheBookViewModel.CacheStat>,
    query: String,
    onQueryChange: (String) -> Unit,
    onPick: (Book) -> Unit,
) {
    val filtered = remember(books, query) {
        if (query.isBlank()) books
        else books.filter {
            it.title.contains(query, ignoreCase = true) || it.author.contains(query, ignoreCase = true)
        }
    }
    Column(modifier = Modifier.heightIn(max = 380.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text("搜索书名/作者") },
            leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                Text("没有匹配的书", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(filtered, key = { it.id }) { book ->
                    val stat = cacheStats[book.id]
                    val cached = stat?.cachedChapters ?: 0
                    val total = stat?.totalChapters ?: 0
                    val noCache = cached == 0
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(book) }
                            .padding(horizontal = 4.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Book, null,
                            tint = if (noCache) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                                   else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(book.title, style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (total == 0) "未获取目录" else "缓存 $cached/$total",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (noCache) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 范围选择步骤。两个数字输入（1-based）做主，章节列表做辅助预览（点击 = 设置该
 * 端点）。点击节奏：尚未起 → 设起；已起未终 → 设终（且修正若 < 起）。
 *
 * Stage C 增量：
 *  - 顶部加格式单选（TXT / EPUB）
 *  - EPUB 模式下加「分卷大小」数字输入；0 = 单文件，>0 = 每 N 章一份
 *    （由调用方循环多次走 SAF 完成；本对话框只回传该数）
 */
@Composable
private fun RangePickerStep(
    chapters: List<BookChapter>,
    onConfirm: (startIndex: Int, endIndex: Int, format: ExportFormat, epubSize: Int) -> Unit,
    onCancel: () -> Unit,
) {
    val total = chapters.size
    var fromText by remember { mutableStateOf("1") }
    var toText by remember { mutableStateOf(total.toString()) }
    var format by remember { mutableStateOf(ExportFormat.TXT) }
    /** EPUB 分卷大小输入（字符串），空或 "0" 视为不分卷。 */
    var epubSizeText by remember { mutableStateOf("0") }
    val fromInt = fromText.toIntOrNull()?.coerceIn(1, total) ?: 1
    val toInt = toText.toIntOrNull()?.coerceIn(fromInt, total) ?: total
    val epubSize = epubSizeText.toIntOrNull()?.coerceAtLeast(0) ?: 0

    Column(modifier = Modifier.heightIn(max = 460.dp)) {
        // 格式单选
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("格式：", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(4.dp))
            FilterChip(
                selected = format == ExportFormat.TXT,
                onClick = { format = ExportFormat.TXT },
                label = { Text("TXT") },
            )
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = format == ExportFormat.EPUB,
                onClick = { format = ExportFormat.EPUB },
                label = { Text("EPUB") },
            )
        }
        Spacer(Modifier.height(8.dp))

        Text("共 $total 章。请输入 1–$total 之间的起止章节序号：",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = fromText,
                onValueChange = { fromText = it.filter { c -> c.isDigit() }.take(6) },
                label = { Text("起始章") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Text("—")
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = toText,
                onValueChange = { toText = it.filter { c -> c.isDigit() }.take(6) },
                label = { Text("终止章") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }

        // EPUB 分卷大小（参考 Legado CustomExporter.size）
        if (format == ExportFormat.EPUB) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = epubSizeText,
                onValueChange = { epubSizeText = it.filter { c -> c.isDigit() }.take(5) },
                label = { Text("EPUB 分卷大小（章数；0 = 不分卷）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(8.dp))
        val rangeCount = (toInt - fromInt + 1).coerceAtLeast(0)
        val volumeCount = if (format == ExportFormat.EPUB && epubSize > 0) {
            (rangeCount + epubSize - 1) / epubSize
        } else 1
        Text(
            buildString {
                append("将导出 $rangeCount 章")
                if (volumeCount > 1) append("，分 $volumeCount 卷")
                append("（${format.name}）")
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )

        Spacer(Modifier.height(8.dp))
        // 章节预览列表 — 行内点击设置 from/to。视觉提示当前选中区间。
        Column(modifier = Modifier
            .heightIn(max = 180.dp)
            .verticalScroll(rememberScrollState())
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
        ) {
            chapters.forEachIndexed { i, ch ->
                val n = i + 1
                val inRange = n in fromInt..toInt
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            // 单击切换：n < from → 设 from；n > to → 设 to；区间内 → 设 to
                            when {
                                n < fromInt -> fromText = n.toString()
                                n > toInt -> toText = n.toString()
                                else -> toText = n.toString()
                            }
                        }
                        .background(
                            if (inRange) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                            else androidx.compose.ui.graphics.Color.Transparent,
                        )
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    Text(
                        "$n. ${ch.title}",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (inRange) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onCancel) { Text("取消") }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    // 输出 0-based 给上层（ViewModel/Repository 用 0-based 索引）
                    onConfirm(fromInt - 1, toInt - 1, format, epubSize)
                },
                enabled = fromInt in 1..total && toInt in fromInt..total,
            ) { Text("确认导出") }
        }
    }
}
