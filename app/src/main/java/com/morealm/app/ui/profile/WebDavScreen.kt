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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.morealm.app.ui.theme.LocalMoRealmColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebDavScreen(
    onBack: () -> Unit,
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
                OutlinedTextField(
                    value = url, onValueChange = { url = it },
                    label = { Text("服务器地址") },
                    placeholder = { Text("https://dav.jianguoyun.com/dav/") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Link, null, modifier = Modifier.size(20.dp)) },
                    modifier = Modifier.fillMaxWidth(),
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
                    title = "从云端恢复",
                    desc = "下载并覆盖本地数据",
                    enabled = isConfigSaved,
                    onClick = { viewModel.webDavRestore() },
                )
                // "自动同步" item removed — it was a dead {} onClick with no
                // VM binding. It will return in the P1 auto-backup phase as a
                // real Switch wired to AppPreferences.autoBackup.
            }
        }

        // Live status echo for the WebDav backup / restore action — this is
        // the single user-visible feedback path during long-running uploads.
        if (webDavStatus.isNotEmpty()) {
            Text(
                webDavStatus,
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    webDavStatus.startsWith("备份成功") || webDavStatus.startsWith("恢复成功") ->
                        MaterialTheme.colorScheme.primary
                    webDavStatus.contains("失败") || webDavStatus.startsWith("请先") ||
                        webDavStatus.startsWith("未找到") ->
                        MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
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
