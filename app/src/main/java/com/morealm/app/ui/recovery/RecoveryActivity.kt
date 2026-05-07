package com.morealm.app.ui.recovery

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.db.recovery.ProcessRestarter
import com.morealm.app.domain.db.recovery.RecoveryGuard
import com.morealm.app.domain.db.recovery.RecoveryReason
import com.morealm.app.domain.db.snapshot.SnapshotManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * 数据库恢复入口。两种进入路径：
 *
 * 1. **MainActivity 启动检测**到 [RecoveryReason.SchemaDowngrade] →
 *    跳到这里让用户选快照恢复。
 * 2. **进程重启后**[RecoveryReason.ResumeImport]：上轮已写 marker + 删 db 文件，
 *    新 process 检测到 marker 跳来这里继续 import。
 *
 * Hilt 注入了 [SnapshotManager] —— 注意 SnapshotManager 不依赖 AppDatabase，
 * 所以即使 Room 状态异常，这里仍能正常初始化。这一点是恢复流程能跑通的前提。
 *
 * 不依赖 AppDatabase / 任何 DAO —— 文件操作全在 RecoveryGuard / SnapshotManager
 * 层完成。
 */
@AndroidEntryPoint
class RecoveryActivity : ComponentActivity() {

    @Inject lateinit var snapshotManager: SnapshotManager

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val reason: RecoveryReason = parseReasonFromIntent(intent) ?: run {
            AppLog.warn("Recovery", "RecoveryActivity launched without reason; finishing.")
            finish()
            return
        }

        AppLog.info("Recovery", "RecoveryActivity opened: $reason")

        setContent {
            // 不套 MoRealmTheme —— ThemeViewModel 走 DAO，DB 此刻可能不可用。
            // 用 MaterialTheme 默认配色保证渲染。
            MaterialTheme {
                RecoveryScreen(
                    reason = reason,
                    snapshotManager = snapshotManager,
                    onPickAndRestore = { snapshotFile -> startRestore(snapshotFile) },
                    onResumeImport = { runResumeImport(reason as RecoveryReason.ResumeImport) },
                )
            }
        }

        // 进入 ResumeImport 路径直接跑 import，不需要用户操作
        if (reason is RecoveryReason.ResumeImport) {
            runResumeImport(reason)
        }
    }

    /**
     * 用户选了 snapshot file，触发恢复流程：
     * 写 marker → 关 DB（无 DB 实例可关，因为 Hilt 没注入 AppDatabase 到本 Activity）
     * → 删 db 文件 → 重启 process。
     *
     * 重启后新 process 的 MainActivity 检测到 marker → 跳回 RecoveryActivity
     * → 走 [runResumeImport] 路径。
     */
    private fun startRestore(snapshotFile: File) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    AppLog.info("Recovery", "Writing marker for ${snapshotFile.name}")
                    RecoveryGuard.writeMarker(this@RecoveryActivity, snapshotFile.name)
                    AppLog.info("Recovery", "Deleting DB files")
                    RecoveryGuard.deleteDbFiles(this@RecoveryActivity)
                } catch (e: Exception) {
                    AppLog.error("Recovery", "Pre-restart prep failed", e)
                    return@withContext
                }
            }
            ProcessRestarter.restart(this@RecoveryActivity)
        }
    }

    /**
     * 进程重启后跑实际的 import：此刻 DB 文件已被删，Hilt 注入 AppDatabase
     * 会让 Room 创建空 DB（当前 schema）。但本 Activity 没注入 AppDatabase，
     * 所以我们绕过 Room：用原生 SQLite 打开新建的空 DB 文件，调
     * [SnapshotManager.importFromObject] 直接 INSERT。
     *
     * 完成后清 marker + 重启 process 进入 MainActivity。
     */
    private fun runResumeImport(reason: RecoveryReason.ResumeImport) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val snapshotFile = File(
                        snapshotManager.latestSnapshotFile.parentFile,
                        reason.marker.snapshotFileName,
                    )
                    if (!snapshotFile.exists()) {
                        AppLog.error(
                            "Recovery",
                            "Marker references missing file: ${reason.marker.snapshotFileName}",
                        )
                        RecoveryGuard.clearMarker(this@RecoveryActivity)
                        return@withContext
                    }
                    // 让 Room 通过 Hilt 创建空 DB —— 但本 Activity 不能直接拿
                    // database 实例（不引入 DB 依赖防止循环）。改用原生 SQLite 打开
                    // 由 Room 第一次启动后会自动建表，这里我们用 EntryPoint 拿 db。
                    val entryPoint = dagger.hilt.android.EntryPointAccessors.fromApplication(
                        applicationContext,
                        DatabaseEntryPoint::class.java,
                    )
                    val db = entryPoint.database().openHelper.writableDatabase
                    // 把 marker 文件指向的 snapshot 导入新建的空 DB
                    val report = snapshotManager.importFromFile(db, snapshotFile).getOrThrow()
                    AppLog.info(
                        "Recovery",
                        "Import done: total=${report.totalInserted} failed=${report.totalFailed}",
                    )
                    RecoveryGuard.clearMarker(this@RecoveryActivity)
                } catch (e: Exception) {
                    AppLog.error("Recovery", "Resume import failed", e)
                    // 不清 marker —— 让用户下次再试 / 看到错误状态
                }
            }
            ProcessRestarter.restart(this@RecoveryActivity)
        }
    }

    /**
     * Intent → RecoveryReason 解析。ResumeImport 的 marker 直接从文件读，不通过
     * Intent 传——marker 文件本来就是跨 process 持久化的真理来源。
     */
    private fun parseReasonFromIntent(intent: Intent): RecoveryReason? {
        val type = intent.getStringExtra(EXTRA_REASON_TYPE) ?: return null
        return when (type) {
            REASON_DOWNGRADE -> RecoveryReason.SchemaDowngrade(
                dbVersion = intent.getIntExtra(EXTRA_DOWNGRADE_DB_VERSION, -1),
                appSchemaVersion = intent.getIntExtra(EXTRA_DOWNGRADE_APP_VERSION, -1),
            )
            REASON_RESUME -> RecoveryGuard.readMarker(this)?.let {
                RecoveryReason.ResumeImport(it)
            }
            else -> null
        }
    }

    @dagger.hilt.EntryPoint
    @dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
    interface DatabaseEntryPoint {
        fun database(): com.morealm.app.domain.db.AppDatabase
    }

    companion object {
        private const val EXTRA_REASON_TYPE = "reason_type"
        private const val EXTRA_DOWNGRADE_DB_VERSION = "downgrade_db_version"
        private const val EXTRA_DOWNGRADE_APP_VERSION = "downgrade_app_version"
        private const val REASON_DOWNGRADE = "downgrade"
        private const val REASON_RESUME = "resume"

        fun newIntent(context: Context, reason: RecoveryReason): Intent =
            Intent(context, RecoveryActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                when (reason) {
                    is RecoveryReason.SchemaDowngrade -> {
                        putExtra(EXTRA_REASON_TYPE, REASON_DOWNGRADE)
                        putExtra(EXTRA_DOWNGRADE_DB_VERSION, reason.dbVersion)
                        putExtra(EXTRA_DOWNGRADE_APP_VERSION, reason.appSchemaVersion)
                    }
                    is RecoveryReason.ResumeImport -> {
                        putExtra(EXTRA_REASON_TYPE, REASON_RESUME)
                    }
                }
            }
    }
}

@Composable
private fun RecoveryScreen(
    reason: RecoveryReason,
    snapshotManager: SnapshotManager,
    onPickAndRestore: (File) -> Unit,
    onResumeImport: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize().systemBarsPadding(),
        color = MaterialTheme.colorScheme.background,
    ) {
        when (reason) {
            is RecoveryReason.ResumeImport -> ResumeImportContent(reason)
            is RecoveryReason.SchemaDowngrade -> SchemaDowngradeContent(
                reason = reason,
                snapshotManager = snapshotManager,
                onPickAndRestore = onPickAndRestore,
            )
        }
    }
}

@Composable
private fun ResumeImportContent(reason: RecoveryReason.ResumeImport) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(
            "正在从快照恢复数据…",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "快照：${reason.marker.snapshotFileName}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        )
    }
}

@Composable
private fun SchemaDowngradeContent(
    reason: RecoveryReason.SchemaDowngrade,
    snapshotManager: SnapshotManager,
    onPickAndRestore: (File) -> Unit,
) {
    val snapshots = remember { snapshotManager.listSnapshots() }
    var selectedFile by remember { mutableStateOf<File?>(snapshots.firstOrNull()) }
    var confirming by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text(
            "数据库版本异常",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "检测到现有数据库版本（v${reason.dbVersion}）高于当前 app 支持版本" +
                "（v${reason.appSchemaVersion}）。这通常是从更高版本降级或异常残留导致。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "选择一份快照恢复数据：",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(16.dp))

        if (snapshots.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "没有可用快照。\n请用 adb pull 或文件管理器把 db 文件拷出，从 WebDav 备份恢复。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(snapshots, key = { it.name }) { file ->
                SnapshotRow(
                    file = file,
                    selected = file == selectedFile,
                    onClick = { selectedFile = file },
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { confirming = true },
                enabled = selectedFile != null,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text("从此快照恢复")
            }
        }
    }

    if (confirming && selectedFile != null) {
        ConfirmDialog(
            file = selectedFile!!,
            onConfirm = {
                confirming = false
                onPickAndRestore(selectedFile!!)
            },
            onDismiss = { confirming = false },
        )
    }
}

@Composable
private fun SnapshotRow(file: File, selected: Boolean, onClick: () -> Unit) {
    val border = if (selected) MaterialTheme.colorScheme.primary
                 else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                else MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = border,
        ),
        onClick = onClick,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                file.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "${formatTime(file.lastModified())} · ${formatSize(file.length())}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun ConfirmDialog(file: File, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认恢复") },
        text = {
            Text(
                "将清除当前数据库并从快照 ${file.name} 恢复。\n\n" +
                    "此操作无法撤销，恢复后 app 会自动重启。",
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { Text("确认恢复") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private fun formatTime(ts: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts))

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "${bytes}B"
    bytes < 1024 * 1024 -> "${bytes / 1024}KB"
    else -> String.format(Locale.ROOT, "%.1fMB", bytes / 1024.0 / 1024.0)
}
