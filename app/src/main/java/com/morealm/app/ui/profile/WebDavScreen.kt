package com.morealm.app.ui.profile

import androidx.compose.foundation.background
import com.morealm.app.presentation.profile.ProfileViewModel
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import com.morealm.app.ui.widget.swipeBackEdge
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.morealm.app.ui.theme.LocalMoRealmColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebDavScreen(
    onBack: () -> Unit,
    onNavigateRemoteBooks: () -> Unit = {},
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val moColors = LocalMoRealmColors.current
    val savedUrl by viewModel.webDavUrl.collectAsStateWithLifecycle()
    val savedUser by viewModel.webDavUser.collectAsStateWithLifecycle()
    val savedPass by viewModel.webDavPass.collectAsStateWithLifecycle()
    val testResult by viewModel.testResult.collectAsStateWithLifecycle()
    // P0-1 follow-up: surface webDavStatus in the UI. Previously the VM
    // emitted "备份中..." / "备份成功" / "备份失败：…" but the screen never
    // collected the flow, leaving the user with no feedback after a click.
    val webDavStatus by viewModel.webDavStatus.collectAsStateWithLifecycle()
    val showRestoreConfirmation by viewModel.showRestoreConfirmation.collectAsStateWithLifecycle()
    val showBackupPicker by viewModel.showBackupPicker.collectAsStateWithLifecycle()
    val backupList by viewModel.backupList.collectAsStateWithLifecycle()
    val backupListLoading by viewModel.backupListLoading.collectAsStateWithLifecycle()
    // P1-D settings: collected directly from VM so user toggles propagate
    // through to the next backup / restore cycle without screen restart.
    val savedDeviceName by viewModel.webDavDeviceName.collectAsStateWithLifecycle()
    val autoBackup by viewModel.autoBackup.collectAsStateWithLifecycle()
    val onlyLatestBackup by viewModel.onlyLatestBackup.collectAsStateWithLifecycle()
    val syncBookProgress by viewModel.syncBookProgress.collectAsStateWithLifecycle()
    val ignoreLocalBook by viewModel.ignoreLocalBook.collectAsStateWithLifecycle()
    val ignoreReadConfig by viewModel.ignoreReadConfig.collectAsStateWithLifecycle()
    val lastBackupTime by viewModel.lastBackupTime.collectAsStateWithLifecycle()
    val savedBackupPassword by viewModel.backupPassword.collectAsStateWithLifecycle()
    var deviceName by remember(savedDeviceName) { mutableStateOf(savedDeviceName) }
    var backupPwInput by remember(savedBackupPassword) { mutableStateOf(savedBackupPassword) }
    var showBackupPw by remember { mutableStateOf(false) }
    var url by remember(savedUrl) { mutableStateOf(savedUrl) }
    var user by remember(savedUser) { mutableStateOf(savedUser) }
    var pass by remember(savedPass) { mutableStateOf(savedPass) }
    var showPass by remember { mutableStateOf(false) }
    // Strong-guidance gate: backup / restore actions require the user to
    // have explicitly clicked "保存配置" first, so we never run with an
    // accidentally-typed wrong credential. Edits-in-progress show a hint.
    val isConfigSaved = savedUrl.isNotBlank() && savedUser.isNotBlank()
    val isDirty = url != savedUrl || user != savedUser || pass != savedPass

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // UX-10：左缘水平拖动 → 返回，覆盖三键导航 / 老 Android 没有系统侧滑的场景
            .swipeBackEdge(onBack = onBack)
            .verticalScroll(rememberScrollState())
    ) {
        TopAppBar(
            title = { Text("WebDAV 同步", fontWeight = FontWeight.Bold) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
            ),
        )

        // Info card
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)),
        ) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Cloud, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("云端同步", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text("阅读进度、书架、书源、主题一键全同步",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // Server config
        Text("服务器配置", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // UX-3: 仅在首次配置（URL 为空）时自动聚焦 + 弹键盘；已有凭据时不打扰用户
                val urlFocus = remember { FocusRequester() }
                LaunchedEffect(Unit) { if (url.isBlank()) urlFocus.requestFocus() }
                // UX-4: 内联校验 — URL 必须 https://（WebDav 走 HTTPS 是事实标准；http 也算合法但提示）
                val urlError = url.isNotBlank() && !url.startsWith("https://", true) && !url.startsWith("http://", true)
                val urlInsecure = url.startsWith("http://", true)
                OutlinedTextField(
                    value = url, onValueChange = { url = it },
                    label = { Text("服务器地址") },
                    placeholder = { Text("https://dav.jianguoyun.com/dav/") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Link, null, modifier = Modifier.size(20.dp)) },
                    modifier = Modifier.fillMaxWidth().focusRequester(urlFocus),
                    isError = urlError,
                    supportingText = when {
                        urlError -> { { Text("地址需以 https:// 或 http:// 开头", color = MaterialTheme.colorScheme.error) } }
                        urlInsecure -> { { Text("使用 HTTP 明文传输，建议改用 HTTPS", color = MaterialTheme.colorScheme.tertiary) } }
                        else -> null
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        cursorColor = MaterialTheme.colorScheme.primary,
                        focusedLabelColor = MaterialTheme.colorScheme.primary,
                    ),
                )
                OutlinedTextField(
                    value = user, onValueChange = { user = it },
                    label = { Text("用户名") },
                    placeholder = { Text("your@email.com") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Person, null, modifier = Modifier.size(20.dp)) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        cursorColor = MaterialTheme.colorScheme.primary,
                        focusedLabelColor = MaterialTheme.colorScheme.primary,
                    ),
                )
                OutlinedTextField(
                    value = pass, onValueChange = { pass = it },
                    label = { Text("密码 / 应用密码") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Lock, null, modifier = Modifier.size(20.dp)) },
                    trailingIcon = {
                        IconButton(onClick = { showPass = !showPass }) {
                            Icon(
                                if (showPass) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                null, modifier = Modifier.size(20.dp),
                            )
                        }
                    },
                    visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        cursorColor = MaterialTheme.colorScheme.primary,
                        focusedLabelColor = MaterialTheme.colorScheme.primary,
                    ),
                )
                // Optional device name — appended to backup filename
                // (e.g. backup_20260501_Pixel.zip). Lets multi-device users
                // keep separate backup streams instead of overwriting each
                // other's "today's" backup.
                OutlinedTextField(
                    value = deviceName,
                    onValueChange = {
                        deviceName = it
                        viewModel.setWebDavDeviceName(it)
                    },
                    label = { Text("设备名（可选）") },
                    placeholder = { Text("留空则不区分设备") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.PhoneAndroid, null, modifier = Modifier.size(20.dp)) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        cursorColor = MaterialTheme.colorScheme.primary,
                        focusedLabelColor = MaterialTheme.colorScheme.primary,
                    ),
                )
                // P2: optional AES-GCM backup password. Empty = legacy
                // plain-zip behaviour; set ⇒ all uploaded backups are
                // encrypted and the same value is required to restore.
                // Stored in DataStore so a single setting drives both
                // the manual button and the auto-backup scheduler.
                OutlinedTextField(
                    value = backupPwInput,
                    onValueChange = {
                        backupPwInput = it
                        viewModel.setBackupPassword(it)
                    },
                    label = { Text("备份加密密码（可选）") },
                    placeholder = { Text("留空则不加密；设置后必须用同一密码恢复") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.EnhancedEncryption, null, modifier = Modifier.size(20.dp)) },
                    trailingIcon = {
                        IconButton(onClick = { showBackupPw = !showBackupPw }) {
                            Icon(
                                if (showBackupPw) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                null, modifier = Modifier.size(20.dp),
                            )
                        }
                    },
                    visualTransformation = if (showBackupPw) VisualTransformation.None else PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        cursorColor = MaterialTheme.colorScheme.primary,
                        focusedLabelColor = MaterialTheme.colorScheme.primary,
                    ),
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Action buttons
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(
                onClick = { viewModel.testWebDav(url, user, pass) },
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.medium,
            ) { Text("测试连接") }
            Button(
                onClick = {
                    viewModel.saveWebDav(url, user, pass)
                },
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) { Text("保存配置") }
        }

        if (testResult.isNotEmpty()) {
            Text(testResult, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        }

        Spacer(Modifier.height(24.dp))

        // Sync options
        Text("同步操作", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(8.dp))

        // Strong-guidance hints — show why the buttons are disabled, or that
        // the user has unsaved edits which won't be used by backup/restore.
        if (!isConfigSaved) {
            Text(
                "请先填写并点击「保存配置」后才能进行备份 / 恢复",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        } else if (isDirty) {
            Text(
                "提示：当前修改尚未保存，备份 / 恢复将使用上次保存的配置",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                // Removed redundant saveWebDav() inside the onClick — the
                // VM reads from prefs flow directly, and the explicit
                // "保存配置" button is now the single write path. This
                // prevents an accidentally-typed wrong credential from
                // silently overwriting saved settings.
                SyncItem(
                    icon = Icons.Default.Backup,
                    title = "备份到云端",
                    desc = "上传书架、进度、书源、主题",
                    enabled = isConfigSaved,
                    onClick = { viewModel.webDavBackup() },
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant)
                SyncItem(
                    icon = Icons.Default.CloudDownload,
                    title = "从云端恢复（最新）",
                    desc = "下载并覆盖本地数据",
                    enabled = isConfigSaved,
                    onClick = { viewModel.requestWebDavRestore() },
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant)
                SyncItem(
                    icon = Icons.Default.History,
                    title = "选择备份文件恢复",
                    desc = "查看历史备份并选择一个版本",
                    enabled = isConfigSaved,
                    onClick = { viewModel.requestBackupPicker() },
                )
                // P2-C: cloud bookshelf — list `<webDavDir>/books/` and
                // one-tap import. Only enabled once the user has saved
                // a working WebDav config (same gate as backup actions).
                SyncItem(
                    icon = Icons.Default.CloudDownload,
                    title = "WebDav 书架",
                    desc = "浏览并导入云端 books/ 目录中的电子书",
                    enabled = isConfigSaved,
                    onClick = onNavigateRemoteBooks,
                )
                // "自动同步" item removed — it was a dead {} onClick with no
                // VM binding. It will return in the P1 auto-backup phase as a
                // real Switch wired to AppPreferences.autoBackup.
            }
        }

        // Live status echo for the WebDav backup / restore action — this is
        // the single user-visible feedback path during long-running uploads.
        //
        // UX-3 (反馈/可见性): 仅文本时用户分不清「正在传 vs 已经卡住」, 进行中态前补
        // 一个 14dp spinner. ViewModel 里使用「备份中... / 恢复中...」三个 ASCII 点,
        // 和「正在...」前缀; 用 contains("...") 做主匹配, 再排除已结束态.
        if (webDavStatus.isNotEmpty()) {
            val isInProgress = remember(webDavStatus) {
                val active = webDavStatus.contains("...") ||
                    webDavStatus.startsWith("正在") ||
                    webDavStatus.startsWith("备份中") ||
                    webDavStatus.startsWith("恢复中") ||
                    webDavStatus.startsWith("同步中")
                val finished = webDavStatus.contains("成功") ||
                    webDavStatus.contains("失败") ||
                    webDavStatus.contains("未找到") ||
                    webDavStatus.contains("请先")
                active && !finished
            }
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isInProgress) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    webDavStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        webDavStatus.contains("成功") -> MaterialTheme.colorScheme.primary
                        webDavStatus.contains("失败") || webDavStatus.contains("请先") ||
                            webDavStatus.contains("未找到") -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    },
                )
            }
        }

        // "Last backup" line — read-only echo of prefs.lastAutoBackup so
        // the user can sanity-check that auto-backup is actually running
        // (nothing else exposes this; previously they had to grep logs).
        if (lastBackupTime > 0L) {
            val ago = formatLastBackupAgo(lastBackupTime)
            Text(
                "上次备份: $ago",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            )
        }

        Spacer(Modifier.height(24.dp))

        // ── P1-D: Sync options switches ──────────────────────────────────
        // Five toggles that fine-tune backup / restore behaviour. Each
        // setting flows back to AppPreferences via the VM and is consumed
        // on the NEXT click of the matching action — toggles take effect
        // without a screen restart.
        Text("同步选项", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                SwitchRow(
                    title = "自动备份",
                    desc = "App 启动时如距上次备份超过 24 小时则自动上传",
                    checked = autoBackup,
                    onCheckedChange = { viewModel.setAutoBackup(it) },
                )
                SwitchRow(
                    title = "只保留最新一份",
                    desc = "云端只保留 backup_latest.zip，不写时间戳副本",
                    checked = onlyLatestBackup,
                    onCheckedChange = { viewModel.setOnlyLatestBackup(it) },
                )
                SwitchRow(
                    title = "同步阅读进度",
                    desc = "每次切换章节自动上传进度，多端读到哪同步",
                    checked = syncBookProgress,
                    onCheckedChange = { viewModel.setSyncBookProgress(it) },
                )
                SwitchRow(
                    title = "恢复时跳过本地书",
                    desc = "避免从其它设备恢复带来无效本地文件路径",
                    checked = ignoreLocalBook,
                    onCheckedChange = { viewModel.setIgnoreLocalBook(it) },
                )
                SwitchRow(
                    title = "恢复时保留本机阅读样式",
                    desc = "不让云端覆盖当前主题、字体、间距等阅读偏好",
                    checked = ignoreReadConfig,
                    onCheckedChange = { viewModel.setIgnoreReadConfig(it) },
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        // Tips
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("使用提示", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("1. 推荐使用坚果云，注册后在设置中创建应用密码\n" +
                     "2. 服务器地址填写 WebDAV 根目录，墨境会自动创建 /MoRealm/ 子目录\n" +
                     "3. 同步内容包括：阅读进度、书架分组、书源列表、主题配置\n" +
                     "4. 本地书籍文件不会上传，仅同步元数据",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    lineHeight = MaterialTheme.typography.bodySmall.lineHeight * 1.4f)
            }
        }

        Spacer(Modifier.height(32.dp))
    }

    // P0-3 destructive-action gate: confirm before overwriting local data.
    // Without this, a single tap on "从云端恢复" wiped the entire local
    // database. The dialog is opt-in via the VM's request/cancel/confirm
    // tri-state so the same pattern can be reused for SAF import later.
    if (showRestoreConfirmation) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelWebDavRestore() },
            icon = {
                Icon(
                    Icons.Default.Warning, null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(28.dp),
                )
            },
            title = { Text("确认从云端恢复？") },
            text = {
                Text(
                    "此操作会下载云端备份并覆盖本地的：\n" +
                    "• 书架与分组\n" +
                    "• 阅读进度\n" +
                    "• 书源、替换规则\n" +
                    "• 主题与阅读样式\n\n" +
                    "本地未上传的修改将丢失，无法撤销。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.webDavRestore() },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("确认覆盖") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelWebDavRestore() }) {
                    Text("取消")
                }
            },
        )
    }

    // P1-C backup file picker — lists every `backup_*.zip` on the remote
    // root sorted newest-first; tapping a row stages the path into the
    // ViewModel and opens the destructive confirmation dialog above.
    if (showBackupPicker) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelBackupPicker() },
            title = { Text("选择要恢复的备份") },
            text = {
                when {
                    backupListLoading -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("正在读取云端备份列表…", style = MaterialTheme.typography.bodyMedium)
                    }
                    backupList.isEmpty() -> Text(
                        "云端目录里没有找到任何 backup_*.zip 文件",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    else -> Column(modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                        backupList.forEach { file ->
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .clickable { viewModel.selectBackupFile(file) }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.Archive, null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(file.name, style = MaterialTheme.typography.bodyMedium)
                                    val size = if (file.size > 0) "${file.size / 1024} KB · " else ""
                                    val date = file.lastModified.takeIf { it.isNotBlank() } ?: "未知时间"
                                    Text(
                                        "$size$date",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { viewModel.cancelBackupPicker() }) {
                    Text("关闭")
                }
            },
        )
    }
}

/**
 * Render the last-backup wall-clock as "刚刚 / N 分钟前 / N 小时前 / yyyy-MM-dd HH:mm"
 * so the user can tell at a glance whether auto-backup is running on cadence.
 * Cheap: computed on every recomposition of the WebDav screen, no caching needed.
 */
private fun formatLastBackupAgo(timestamp: Long): String {
    val deltaMs = System.currentTimeMillis() - timestamp
    return when {
        deltaMs < 0 -> "时间异常"
        deltaMs < 60_000L -> "刚刚"
        deltaMs < 3_600_000L -> "${deltaMs / 60_000L} 分钟前"
        deltaMs < 86_400_000L -> "${deltaMs / 3_600_000L} 小时前"
        deltaMs < 7L * 86_400_000L -> "${deltaMs / 86_400_000L} 天前"
        else -> java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(timestamp))
    }
}

@Composable
private fun SwitchRow(
    title: String,
    desc: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun SyncItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    desc: String,
    enabled: Boolean = true,
    onClick: () -> Unit = {},
) {
    val moColors = LocalMoRealmColors.current
    val tintAlpha = if (enabled) 1f else 0.38f
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon, null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = tintAlpha),
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = tintAlpha),
            )
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f * tintAlpha),
            )
        }
        Icon(
            Icons.Default.ChevronRight, null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f * tintAlpha),
            modifier = Modifier.size(20.dp),
        )
    }
}
