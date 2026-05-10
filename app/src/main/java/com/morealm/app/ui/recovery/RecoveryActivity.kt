package com.morealm.app.ui.recovery

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.db.recovery.ProcessRestarter
import com.morealm.app.domain.db.recovery.RecoveryGuard
import com.morealm.app.domain.db.recovery.RecoveryMarker
import com.morealm.app.domain.db.recovery.RecoveryReason
import com.morealm.app.domain.db.snapshot.ImportReport
import com.morealm.app.domain.db.snapshot.SnapshotManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
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
 *
 * ## UX 稳定性保证
 *
 * - **不强制重启**：import 失败时停在错误页面让用户看到失败原因，**只有完全
 *   成功才** restart 进 MainActivity。历史 bug：旧实现失败也 restart，配合
 *   [RecoveryGuard.shouldEnterRecovery] 的版本号误判形成"恢复界面无限循环弹"
 *   的现象 —— 用户描述的"动不动闪退"。
 * - **onCreate 重入保护**：屏幕旋转 / 夜间切换会让系统重建 Activity，但
 *   [importStarted] 是进程级的 AtomicBoolean，确保同一份 marker 在同一进程内
 *   只跑一次 import；用户主动重试时显式 set(true) 后再次跑。
 * - **状态进程级缓存**：[ImportStateHolder] 在 companion 内持有 SnapshotState，
 *   Activity 重建复用同一份状态，重建后界面不会闪回 Loading。
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
                    onRetryResume = { runResumeImport(reason as RecoveryReason.ResumeImport) },
                    onAbandonResume = { abandonResume() },
                    onContinueAfterPartial = { finishAndRestart() },
                )
            }
        }

        // 进入 ResumeImport 路径直接跑 import，不需要用户操作。
        // 用进程级 AtomicBoolean 防止屏幕旋转 / 夜间模式切换造成的 Activity
        // 重建重复触发同一份 import。
        if (reason is RecoveryReason.ResumeImport && importStarted.compareAndSet(false, true)) {
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
     * 所以我们绕过 Room：通过 EntryPoint 拿 db 实例 —— 把 import 的实际数据
     * 操作交给 [SnapshotManager.importFromObject]。
     *
     * 完成判断：
     * - import 全部成功（totalFailed = 0）→ 清 marker + restart 进 MainActivity
     * - 部分失败 / 完全失败 / 异常 → **不重启**，把失败信息写入 [ImportStateHolder]，
     *   UI 显示错误页让用户决定再试还是放弃。这样恢复界面"始终如一"，不会
     *   动不动退出。
     */
    private fun runResumeImport(reason: RecoveryReason.ResumeImport) {
        // 重试时允许重新进入；首次进入由 onCreate 的 compareAndSet 保护
        importStarted.set(true)
        ImportStateHolder.state = ImportUiState.Loading(reason.marker)
        // 在真正开始 import 前 +1 计数写回 marker —— 即使本次 process 被 kill，
        // 下次启动 RecoveryGuard 也能看到这次尝试已经发生过。
        val updatedMarker = runCatching {
            RecoveryGuard.markAttemptStart(this@RecoveryActivity)
        }.getOrNull() ?: reason.marker
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val snapshotFile = File(
                        snapshotManager.latestSnapshotFile.parentFile,
                        updatedMarker.snapshotFileName,
                    )
                    if (!snapshotFile.exists()) {
                        return@runCatching ImportOutcome.Failure(
                            attemptCount = updatedMarker.attemptCount,
                            errorMessage = "找不到快照文件: ${updatedMarker.snapshotFileName}",
                        )
                    }
                    val entryPoint = dagger.hilt.android.EntryPointAccessors.fromApplication(
                        applicationContext,
                        DatabaseEntryPoint::class.java,
                    )
                    val db = entryPoint.database().openHelper.writableDatabase
                    val report = snapshotManager.importFromFile(db, snapshotFile).getOrThrow()
                    AppLog.info(
                        "Recovery",
                        "Import done: total=${report.totalInserted} failed=${report.totalFailed}",
                    )
                    if (report.totalFailed == 0) {
                        ImportOutcome.Success(report)
                    } else {
                        ImportOutcome.PartialFailure(
                            attemptCount = updatedMarker.attemptCount,
                            report = report,
                        )
                    }
                }.getOrElse { e ->
                    AppLog.error("Recovery", "Resume import failed", e)
                    ImportOutcome.Failure(
                        attemptCount = updatedMarker.attemptCount,
                        errorMessage = e.message ?: e::class.java.simpleName,
                    )
                }
            }
            // 全部成功才清 marker + 重启；其他情况保留 marker，让 UI 决定下一步。
            when (outcome) {
                is ImportOutcome.Success -> {
                    RecoveryGuard.clearMarker(this@RecoveryActivity)
                    ImportStateHolder.state = ImportUiState.SuccessAndRestart(outcome.report)
                    ProcessRestarter.restart(this@RecoveryActivity)
                }
                is ImportOutcome.PartialFailure -> {
                    ImportStateHolder.state = ImportUiState.PartialFailure(
                        report = outcome.report,
                        attemptCount = outcome.attemptCount,
                    )
                }
                is ImportOutcome.Failure -> {
                    ImportStateHolder.state = ImportUiState.Failure(
                        message = outcome.errorMessage,
                        attemptCount = outcome.attemptCount,
                    )
                }
            }
        }
    }

    /**
     * 用户在错误页上选择"放弃恢复"：清 marker、清进程标志、重启进 MainActivity。
     * 重启后 MainActivity 看到 marker 已不存在 → 走正常路径进 app（数据是 import
     * 已经写入的部分 + Room 自动建表的空表）。
     */
    private fun abandonResume() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { RecoveryGuard.clearMarker(this@RecoveryActivity) }
                    .onFailure { AppLog.error("Recovery", "abandonResume clearMarker failed", it) }
            }
            finishAndRestart()
        }
    }

    private fun finishAndRestart() {
        ProcessRestarter.restart(this@RecoveryActivity)
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

        /**
         * 进程级"是否已经触发过 import"标志。Activity 重建（旋转 / 夜间切换）时
         * 复用同一进程，这个标志保证不会重复跑同一份 import；用户主动重试时由
         * [runResumeImport] 显式 set(true) 重置后再次跑。
         */
        private val importStarted = AtomicBoolean(false)

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

/**
 * import 单次跑完的结果（不含 UI 状态）—— 用于 [RecoveryActivity.runResumeImport]
 * 内部把后台协程的产出在 main 线程里 dispatch 到 [ImportUiState]。
 */
private sealed interface ImportOutcome {
    data class Success(val report: ImportReport) : ImportOutcome
    data class PartialFailure(val attemptCount: Int, val report: ImportReport) : ImportOutcome
    data class Failure(val attemptCount: Int, val errorMessage: String) : ImportOutcome
}

/**
 * RecoveryActivity 的可见状态机。
 *
 * - [Idle]：还没开始 / 不在 ResumeImport 路径
 * - [Loading]：import 跑步中
 * - [SuccessAndRestart]：全部成功，已经触发 restart，UI 短暂显示成功提示
 * - [PartialFailure]：部分行 / 部分表失败，让用户决定"再试一次"还是"接受现状继续"
 * - [Failure]：完全失败（异常 / 文件丢失），让用户"再试"或"放弃"
 */
private sealed interface ImportUiState {
    data object Idle : ImportUiState
    data class Loading(val marker: RecoveryMarker) : ImportUiState
    data class SuccessAndRestart(val report: ImportReport) : ImportUiState
    data class PartialFailure(val report: ImportReport, val attemptCount: Int) : ImportUiState
    data class Failure(val message: String, val attemptCount: Int) : ImportUiState
}

/**
 * 进程级状态持有者。用 Compose 的 mutableStateOf 让 UI 自动跟踪刷新；放在
 * 顶层 object 是为了 Activity 重建时复用同一份 SnapshotState（otherwise
 * 重建后 Loading 闪一下又被 background 协程的最新结果覆盖，体感差）。
 *
 * 写入只发生在 [RecoveryActivity.runResumeImport] / [RecoveryActivity.abandonResume]
 * 这些受控位置；Compose 端只读。
 */
private object ImportStateHolder {
    var state: ImportUiState by mutableStateOf(ImportUiState.Idle)
}

@Composable
private fun RecoveryScreen(
    reason: RecoveryReason,
    snapshotManager: SnapshotManager,
    onPickAndRestore: (File) -> Unit,
    onRetryResume: () -> Unit,
    onAbandonResume: () -> Unit,
    onContinueAfterPartial: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize().systemBarsPadding(),
        color = MaterialTheme.colorScheme.background,
    ) {
        when (reason) {
            is RecoveryReason.ResumeImport -> ResumeImportContent(
                onRetry = onRetryResume,
                onAbandon = onAbandonResume,
                onContinue = onContinueAfterPartial,
            )
            is RecoveryReason.SchemaDowngrade -> SchemaDowngradeContent(
                reason = reason,
                snapshotManager = snapshotManager,
                onPickAndRestore = onPickAndRestore,
            )
        }
    }
}

@Composable
private fun ResumeImportContent(
    onRetry: () -> Unit,
    onAbandon: () -> Unit,
    onContinue: () -> Unit,
) {
    when (val state = ImportStateHolder.state) {
        is ImportUiState.Idle -> ImportLoadingPage(markerName = "")
        is ImportUiState.Loading -> ImportLoadingPage(markerName = state.marker.snapshotFileName)
        is ImportUiState.SuccessAndRestart -> ImportSuccessPage(state)
        is ImportUiState.PartialFailure -> ImportPartialFailurePage(
            state = state,
            onRetry = onRetry,
            onContinue = onContinue,
            onAbandon = onAbandon,
        )
        is ImportUiState.Failure -> ImportFailurePage(
            state = state,
            onRetry = onRetry,
            onAbandon = onAbandon,
        )
    }
}

@Composable
private fun ImportLoadingPage(markerName: String) {
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
        if (markerName.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "快照：$markerName",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "请勿在此期间退出应用 —— 完成后会自动重启进入主界面。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        )
    }
}

@Composable
private fun ImportSuccessPage(state: ImportUiState.SuccessAndRestart) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "恢复完成",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "已成功恢复 ${state.report.totalInserted} 行数据，正在重启…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(16.dp))
        CircularProgressIndicator()
    }
}

@Composable
private fun ImportPartialFailurePage(
    state: ImportUiState.PartialFailure,
    onRetry: () -> Unit,
    onContinue: () -> Unit,
    onAbandon: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
    ) {
        Text(
            "恢复部分成功",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "已恢复 ${state.report.totalInserted} 行，有 ${state.report.totalFailed} 行失败。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "尝试次数：${state.attemptCount}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(12.dp))
        TableReportList(state.report)
        Spacer(Modifier.height(24.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onAbandon, modifier = Modifier.weight(1f)) {
                Text("放弃恢复")
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onRetry, modifier = Modifier.weight(1f)) {
                Text("再试一次")
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onContinue,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) { Text("接受并继续") }
        }
    }
}

@Composable
private fun ImportFailurePage(
    state: ImportUiState.Failure,
    onRetry: () -> Unit,
    onAbandon: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
    ) {
        Text(
            "恢复失败",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            state.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "尝试次数：${state.attemptCount}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(24.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onAbandon, modifier = Modifier.weight(1f)) {
                Text("放弃恢复")
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onRetry,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) { Text("再试一次") }
        }
    }
}

@Composable
private fun TableReportList(report: ImportReport) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        report.tables.forEach { (name, stat) ->
            val text = when {
                stat.skippedTableMissing ->
                    "$name —— 已跳过（当前 schema 不存在该表，原 ${stat.totalInJson} 行）"
                stat.failed > 0 ->
                    "$name —— 成功 ${stat.inserted} / 失败 ${stat.failed} / 共 ${stat.totalInJson}"
                else ->
                    "$name —— 成功 ${stat.inserted} / 共 ${stat.totalInJson}"
            }
            val color = when {
                stat.skippedTableMissing -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                stat.failed > 0 -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onBackground
            }
            Text(text, style = MaterialTheme.typography.bodySmall, color = color)
        }
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
