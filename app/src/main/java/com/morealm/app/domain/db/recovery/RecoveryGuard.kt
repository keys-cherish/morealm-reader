package com.morealm.app.domain.db.recovery

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Process
import com.morealm.app.core.log.AppLog
import com.morealm.app.di.APP_DB_SCHEMA_VERSION
import com.morealm.app.di.readSqliteUserVersionSafely
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 启动时为什么需要进入恢复流程。RecoveryActivity 用它决定 UI 与按钮文案。
 */
sealed class RecoveryReason {
    /**
     * 上一次 RecoveryActivity 已写下 [RecoveryMarker] —— 进程已被重启、DB 文件已被
     * 删除，新 process 现在应当继续执行 import 步骤。
     */
    data class ResumeImport(val marker: RecoveryMarker) : RecoveryReason()

    /**
     * DB 文件 user_version 高于代码 schema 版本（降级场景，例如开发期 v29 残留 +
     * 装回 v28）。Room 不支持降级会 throw —— 必须先用快照 reset。
     */
    data class SchemaDowngrade(val dbVersion: Int, val appSchemaVersion: Int) : RecoveryReason()
}

/**
 * Marker 文件：跨 process 重启时携带"用哪份快照恢复"的指令。
 *
 * 流程：
 * 1. RecoveryActivity 用户选了 snapshot 文件 → 写一份 marker → 关 DB → 删 db 文件
 *    → 重启 process（[ProcessRestarter.restart]）
 * 2. 新 process 启动 → [RecoveryGuard.shouldEnterRecovery] 检测到 marker
 *    → 跳回 RecoveryActivity → import → 删 marker → 再重启进 MainActivity
 */
@Serializable
data class RecoveryMarker(
    /** 待恢复的 snapshot 文件名（不含路径，位于 `filesDir/db_snapshot/`）。 */
    val snapshotFileName: String,
    /** 写入 marker 的时间戳。便于诊断卡死的恢复流程。 */
    val createdAtMs: Long,
)

/**
 * 全静态恢复检测器。在 MainActivity.onCreate 头部调用 [shouldEnterRecovery]
 * —— **必须在 Hilt 注入数据库相关依赖之前**，否则 Room 第一次访问就会 throw。
 *
 * 与 [com.morealm.app.domain.db.snapshot.SnapshotManager] 平行：
 * - SnapshotManager 管"备份与恢复的实际数据操作"
 * - RecoveryGuard 管"启动时判断是否要走恢复路径 + 进程重启协调"
 */
object RecoveryGuard {

    private const val MARKER_FILE_NAME = "recovery_pending.json"
    private const val DB_FILE = "morealm.db"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 启动时检查是否需要进入 RecoveryActivity。
     *
     * 检查顺序（先重要后次要）：
     * 1. Marker 存在 → ResumeImport（上一轮恢复未完成，必须接着做）
     * 2. SQLite user_version > 当前 schema → SchemaDowngrade（Room 不能打开降级 DB）
     *
     * 返回 null 表示一切正常，可以进入 MainActivity。
     */
    fun shouldEnterRecovery(context: Context): RecoveryReason? {
        readMarker(context)?.let { return RecoveryReason.ResumeImport(it) }

        val priorVersion = readSqliteUserVersionSafely(context, DB_FILE)
        if (priorVersion > APP_DB_SCHEMA_VERSION) {
            AppLog.warn(
                "Recovery",
                "DB downgrade detected: file=v$priorVersion code=v$APP_DB_SCHEMA_VERSION",
            )
            return RecoveryReason.SchemaDowngrade(
                dbVersion = priorVersion,
                appSchemaVersion = APP_DB_SCHEMA_VERSION,
            )
        }
        return null
    }

    /** Marker 文件位置（`filesDir/recovery_pending.json`）。 */
    fun markerFile(context: Context): File = File(context.filesDir, MARKER_FILE_NAME)

    /** 读 marker。文件不存在或解析失败均返回 null（删除损坏的 marker，避免死循环）。 */
    fun readMarker(context: Context): RecoveryMarker? {
        val file = markerFile(context)
        if (!file.exists()) return null
        return runCatching { json.decodeFromString<RecoveryMarker>(file.readText()) }
            .onFailure {
                AppLog.error("Recovery", "Marker corrupt, deleting: ${it.message}")
                file.delete()
            }
            .getOrNull()
    }

    /**
     * 写 marker（即将启动 process 重启前调用）。
     *
     * 用 tmp + rename 原子写入，避免 process 被 kill 时留下半截文件。
     */
    fun writeMarker(context: Context, snapshotFileName: String) {
        val marker = RecoveryMarker(
            snapshotFileName = snapshotFileName,
            createdAtMs = System.currentTimeMillis(),
        )
        val target = markerFile(context)
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(json.encodeToString(marker))
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    /** 清除 marker（恢复 import 完成后调用）。 */
    fun clearMarker(context: Context) {
        markerFile(context).delete()
    }

    /**
     * 删除 DB 主文件 + WAL/SHM 副文件。
     *
     * **前置**：调用方必须保证 Room 已 close —— 否则 Linux 文件系统会保留
     * 已打开的 inode，"删了"但 Room 句柄仍然有效，导致下次启动时数据库其实还在。
     */
    fun deleteDbFiles(context: Context) {
        val dbFile = context.getDatabasePath(DB_FILE)
        listOf(
            dbFile,
            File(dbFile.path + "-wal"),
            File(dbFile.path + "-shm"),
            File(dbFile.path + "-journal"),
        ).forEach { f ->
            if (f.exists()) {
                val ok = f.delete()
                AppLog.info("Recovery", "delete ${f.name}: $ok")
            }
        }
    }
}

/**
 * 杀当前进程并通过 AlarmManager 在 1s 后冷启动 launcher Activity。
 *
 * 这是恢复流程的关键 —— Hilt 的 @Singleton 一旦创建无法重建，AppDatabase 关闭
 * 后无法重新打开同一实例。所以恢复必须**重启整个 process**。
 *
 * 不引入 ProcessPhoenix 第三方库（30 行可以手写，且要严格审查 PendingIntent
 * 标志位与 Android 12+ 的 mutable 默认值）。
 */
object ProcessRestarter {

    fun restart(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: error("getLaunchIntentForPackage returned null for ${context.packageName}")
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)

        val pi = PendingIntent.getActivity(
            context,
            REQ_RESTART,
            intent,
            // FLAG_IMMUTABLE 是 Android 12+ 必需；FLAG_CANCEL_CURRENT 防止旧 PI 被复用。
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT,
        )

        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // 100ms 让当前 Activity 有机会 finish；太短会被系统忽略。
        alarm.set(AlarmManager.RTC, System.currentTimeMillis() + 100, pi)

        AppLog.info("Recovery", "Process restart scheduled, killing self pid=${Process.myPid()}")
        Process.killProcess(Process.myPid())
        // killProcess 是异步的，加这行兜底——理论上不会执行到。
        kotlin.system.exitProcess(0)
    }

    private const val REQ_RESTART = 0x4D52 // "MR"
}
