package com.morealm.app.domain.sync

import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.preference.AppPreferences
import com.morealm.app.domain.repository.BackupRepository
import kotlinx.coroutines.flow.first
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centralised "build a backup zip and push it to WebDav" workflow shared
 * by the manual button in WebDavScreen and by [runIfDue] (the once-a-day
 * auto-backup scheduler called from `MoRealmApp.onCreate`).
 *
 * Pulling the body out of `ProfileViewModel.webDavBackup` keeps the two
 * call paths byte-identical: a setting toggle (device name, onlyLatest,
 * webDavDir) takes effect on either path on the very next run, with no
 * risk of code drift like the original `BackupManager` had between SAF
 * export and WebDav upload.
 *
 * All entry points are non-throwing; errors are logged + folded into
 * the returned [Result] message so the caller can surface a status
 * line without a try/catch.
 */
@Singleton
class WebDavBackupRunner @Inject constructor(
    private val prefs: AppPreferences,
    private val backupRepo: BackupRepository,
) {

    data class Result(val success: Boolean, val message: String, val sizeBytes: Long = 0L)

    /**
     * Build a fresh backup zip, upload it to `<webDavDir>/`, and update
     * [AppPreferences.setLastAutoBackup]. The same logic backs both the
     * manual "备份到云端" button and the auto-backup scheduler so they
     * can never fall out of sync.
     */
    suspend fun runOnce(): Result {
        val url = prefs.webDavUrl.first()
        val user = prefs.webDavUser.first()
        val pass = prefs.webDavPass.first()
        if (url.isBlank()) return Result(false, "请先配置 WebDAV")

        return try {
            val client = backupRepo.createWebDavClient(url, user, pass)
            val dir = prefs.webDavDir.first().ifBlank { "MoRealm" }.trim('/')
            val device = prefs.webDavDeviceName.first().trim()
            val onlyLatest = prefs.onlyLatestBackup.first()

            client.mkdir(dir)
            val data = backupRepo.generateBackupBytes()
                ?: return Result(false, "备份数据生成失败")

            val ts = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
            val deviceSuffix = if (device.isNotEmpty()) {
                // Strip anything that could break a path; keep dashes/underscores
                "_${device.replace(Regex("[^A-Za-z0-9_-]"), "")}"
            } else ""

            if (!onlyLatest) {
                client.upload("$dir/backup_${ts}${deviceSuffix}.zip", data)
            }
            client.upload("$dir/backup_latest${deviceSuffix}.zip", data)
            prefs.setLastAutoBackup(System.currentTimeMillis())
            AppLog.info(
                "WebDAV",
                "Backup completed: ${data.size} bytes " +
                    "(onlyLatest=$onlyLatest, device='$device', dir='$dir')",
            )
            Result(true, "备份成功", data.size.toLong())
        } catch (e: Exception) {
            AppLog.error("WebDAV", "Backup failed", e)
            Result(false, "备份失败：${e.message}")
        }
    }

    /**
     * Auto-backup scheduler hook: runs at most once per 24 h, only when
     * `autoBackup` is enabled and a `lastAutoBackup` is older than the
     * threshold. Cheap to call on every cold start.
     */
    suspend fun runIfDue() {
        if (!prefs.autoBackup.first()) return
        val last = prefs.lastAutoBackup.first()
        val threshold = TimeUnit.HOURS.toMillis(AUTO_BACKUP_INTERVAL_HOURS)
        if (System.currentTimeMillis() - last < threshold) return
        AppLog.info("WebDAV", "Auto-backup window reached (last=${Date(last)}); running")
        runOnce()
    }

    companion object {
        /** Mirror Legado's daily auto-backup cadence. */
        const val AUTO_BACKUP_INTERVAL_HOURS = 24L
    }
}
