package com.morealm.app.ui.settings

import android.graphics.Typeface as AndroidTypeface
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Typeface as ComposeTypeface
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.morealm.app.domain.font.FontEntry
import com.morealm.app.presentation.settings.FontManagerViewModel
import java.io.File

/**
 * 字体管理页：双 Tab —— App 字库 / 外部文件夹。
 *
 *  - App 字库：扫 filesDir/fonts/，支持 + 导入单文件，长按删除。
 *  - 外部文件夹：扫用户挂的 SAF Tree URI，只读不删。
 *
 * 每条字体用其自身 Typeface 渲染预览文本（"永和九年岁在癸丑 / The quick brown fox"），
 * 与 Legado FontAdapter 等效。Snackbar 收 ViewModel 的 toast SharedFlow。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FontManagerScreen(
    onBack: () -> Unit = {},
    viewModel: FontManagerViewModel = hiltViewModel(),
) {
    val appFonts by viewModel.appFonts.collectAsStateWithLifecycle()
    val externalFonts by viewModel.externalFonts.collectAsStateWithLifecycle()
    val currentPath by viewModel.currentFontPath.collectAsStateWithLifecycle()
    val folderUri by viewModel.fontFolderUri.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.toast.collect { snackbarHostState.showSnackbar(it) }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val name = DocumentFile.fromSingleUri(context, uri)?.name
        viewModel.importFromUri(uri, name)
    }
    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        viewModel.pickFolder(uri)
    }

    var tabIndex by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("字体管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        floatingActionButton = {
            if (tabIndex == 0) {
                ExtendedFloatingActionButton(
                    onClick = {
                        // SAF 上 ttf/otf 经常被 provider 报为 application/octet-stream，
                        // 用 */* 让用户能选到文件，由 FontRepository 做后缀 + Typeface 双校验。
                        importLauncher.launch(arrayOf("*/*"))
                    },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("导入字体") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            TabRow(selectedTabIndex = tabIndex) {
                Tab(
                    selected = tabIndex == 0,
                    onClick = { tabIndex = 0 },
                    text = { Text("App 字库 (${appFonts.size})") },
                )
                Tab(
                    selected = tabIndex == 1,
                    onClick = { tabIndex = 1 },
                    text = { Text("外部文件夹 (${externalFonts.size})") },
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { viewModel.useSystemDefault() },
                    modifier = Modifier.weight(1f),
                ) {
                    if (currentPath.isEmpty()) {
                        Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                    }
                    Text("系统默认")
                }
                if (tabIndex == 1) {
                    OutlinedButton(
                        onClick = { folderLauncher.launch(null) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (folderUri.isEmpty()) "选择文件夹" else "更换文件夹")
                    }
                }
            }

            when (tabIndex) {
                0 -> FontList(
                    entries = appFonts,
                    currentPath = currentPath,
                    emptyHint = "App 字库为空。点右下「导入字体」从设备添加 .ttf / .otf 文件，导入后会复制到 App 私有目录，永久保存。",
                    onSelect = viewModel::selectFont,
                    onDelete = viewModel::deleteAppFont,
                )
                1 -> FontList(
                    entries = externalFonts,
                    currentPath = currentPath,
                    emptyHint = if (folderUri.isEmpty())
                        "尚未选择外部字体文件夹。点上方「选择文件夹」挂载一个含 .ttf / .otf 的目录，App 会扫描其中的字体。"
                    else
                        "该文件夹中没有可识别的 .ttf / .otf 字体文件，或访问权限已失效。",
                    onSelect = viewModel::selectFont,
                    onDelete = null,  // 外部字体不允许删
                )
            }
        }
    }
}

@Composable
private fun FontList(
    entries: List<FontEntry>,
    currentPath: String,
    emptyHint: String,
    onSelect: (FontEntry) -> Unit,
    onDelete: ((FontEntry) -> Unit)?,
) {
    if (entries.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                emptyHint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(entries, key = { it.path }) { entry ->
            FontItem(
                entry = entry,
                selected = entry.path == currentPath,
                onClick = { onSelect(entry) },
                onDelete = onDelete?.let { delete -> { delete(entry) } },
            )
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
}

@Composable
private fun FontItem(
    entry: FontEntry,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    val context = LocalContext.current
    // 直接复用 FontRepository.tryLoadFontFile 的相同思路：避免在 UI 层依赖 repo 注入。
    // 加载失败 → null → FontFamily.Default 兜底，UI 不会崩。
    val androidTypeface: AndroidTypeface? = remember(entry.path) {
        runCatching {
            when {
                entry.path.startsWith("content://") &&
                    android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O -> {
                    context.contentResolver
                        .openFileDescriptor(android.net.Uri.parse(entry.path), "r")
                        ?.use { AndroidTypeface.Builder(it.fileDescriptor).build() }
                }
                entry.path.startsWith("file://") -> {
                    val f = android.net.Uri.parse(entry.path).path?.let(::File)
                    if (f != null && f.canRead()) AndroidTypeface.createFromFile(f) else null
                }
                else -> null
            }
        }.getOrNull()
    }
    val previewFontFamily: FontFamily = remember(androidTypeface) {
        androidTypeface?.let { FontFamily(ComposeTypeface(it)) } ?: FontFamily.Default
    }

    val border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                  else BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.06f)
                else MaterialTheme.colorScheme.surface,
        border = border,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (selected) {
                        Icon(
                            Icons.Default.Check,
                            null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        entry.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        Formatter.formatShortFileSize(context, entry.sizeBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "永和九年岁在癸丑 · The quick brown fox 0123",
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = previewFontFamily),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (onDelete != null) {
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        "删除",
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                    )
                }
            }
        }
    }
}
