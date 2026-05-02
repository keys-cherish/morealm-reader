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
     *
     * @param source which pathway invoked the runner — affects the
     *               [WebDavStatusBus] tag so the UI can label "自动" /
     *               "手动" when echoing the message back to the user.
     */
    suspend fun runOnce(source: WebDavStatusBus.Source = WebDavStatusBus.Source.MANUAL): Result {
        val url = prefs.webDavUrl.first()
        val user = prefs.webDavUser.first()
        val pass = prefs.webDavPass.first()
        if (url.isBlank()) {
            return Result(false, "请先配置 WebDAV").also { publish(source, it) }
        }

        return try {
            val client = backupRepo.createWebDavClient(url, user, pass)
            val dir = WebDavLayout.normalizeDir(prefs.webDavDir.first())
            val device = prefs.webDavDeviceName.first().trim()
            val onlyLatest = prefs.onlyLatestBackup.first()

            // 一次性把 Legado 公约里的全部子目录建好（bookProgress / books / background）。
            // 之前只 mkdir 了 dir 自身，子目录靠 just-in-time MKCOL — 第一次同步进度
            // 或导出书时延迟一个网络往返，且某些服务器在并发 MKCOL 同名时返回不一致
            // 状态码。集中初始化一次开销小（每个 MKCOL 几十毫秒），避免后续踩坑。
            client.makeAsDir(dir)
            for (sub in WebDavLayout.ALL_SUBDIRS) {
                runCatching { client.makeAsDir(WebDavLayout.subPath(dir, sub)) }
                    .onFailure { AppLog.warn("WebDAV", "mkdir $dir/$sub skipped: ${it.message}") }
            }

            // P2: pull the optional backup password just-in-time so a
            // user changing it between Settings and clicking "备份" picks
            // up the new value on the next run without needing to restart.
            val backupPw = prefs.backupPassword.first()
            val data = backupRepo.generateBackupBytes(backupPw)
                ?: return Result(false, "备份数据生成失败").also { publish(source, it) }

            val ts = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
            val deviceSuffix = WebDavLayout.deviceSuffix(device)

            if (!onlyLatest) {
                client.upload("$dir/${WebDavLayout.backupFileName(ts, deviceSuffix)}", data)
            }
            client.upload("$dir/${WebDavLayout.latestBackupFileName(deviceSuffix)}", data)

            // 清理：onlyLatest 模式下，把历史 `backup_yyyyMMdd_*.zip` 全部删掉，避免
            // 用户误以为"只保留最新"=占用最少空间，结果存了几个月的历史包。注意：
            //  - 仅删本设备相关（deviceSuffix 匹配），不动其它设备的备份
            //  - 仅删时间戳格式（不动 latest 自己）
            if (onlyLatest) {
                runCatching { cleanupOldBackups(client, dir, deviceSuffix) }
                    .onFailure { AppLog.warn("WebDAV", "cleanup old backups skipped: ${it.message}") }
            }

            prefs.setLastAutoBackup(System.currentTimeMillis())
            AppLog.info(
                "WebDAV",
                "Backup completed: ${data.size} bytes " +
                    "(onlyLatest=$onlyLatest, device='$device', dir='$dir', source=$source)",
            )
            Result(true, "备份成功", data.size.toLong()).also { publish(source, it) }
        } catch (e: Exception) {
            AppLog.error("WebDAV", "Backup failed", e)
            Result(false, "备份失败：${e.message}").also { publish(source, it) }
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
        runOnce(WebDavStatusBus.Source.AUTO)
    }

    private fun publish(source: WebDavStatusBus.Source, result: Result) {
        WebDavStatusBus.emit(
            WebDavStatusBus.Status(
                source = source,
                message = result.message,
                success = result.success,
            ),
        )
    }

    /**
     * onlyLatest 模式上传完成后的清理：把根目录里时间戳形式的 `backup_yyyyMMdd_*.zip`
     * 全部删掉，**只保留** `backup_latest[_device].zip`。
     *
     * 安全约束：
     *  - 只删 [deviceSuffix] 匹配的备份；多设备共用 dir 时不会误删其它设备的历史
     *  - 不删 `backup_latest...` 文件（latestTag 检测）
     *  - 不删非 backup 前缀的任何文件（[WebDavLayout.isBackupFile] 守门）
     *  - 单文件删除失败不阻塞总流程（`runCatching`）
     *
     * 计算量：一次 PROPFIND + 每个待删一次 DELETE。typical case <5 个文件，量级毫秒。
     */
    private suspend fun cleanupOldBackups(
        client: WebDavClient,
        dir: String,
        deviceSuffix: String,
    ) {
        val files = client.listFiles(dir)
        var removed = 0
        for (f in files) {
            if (f.isDirectory) continue
            val name = f.name
            if (!WebDavLayout.isBackupFile(name)) continue
            // latest 文件不删，无论后缀
            if (name.contains("_${WebDavLayout.LATEST_TAG}")) continue
            // 只删本设备：当 deviceSuffix 非空时，文件名必须以该 suffix + .zip 结尾；
            // deviceSuffix 为空时，仅删除「无设备后缀」的备份（=本设备生成）。
            val matchesDevice = if (deviceSuffix.isEmpty()) {
                // 无设备后缀的命名形如 backup_yyyyMMdd_HHmm.zip，结尾应直接接 .zip
                // 而不是 _foo.zip。安全识别：去掉前缀 backup_ 后剩下的部分如果含
                // 第二个 `_` 之后还有非空字符串，那就是带设备后缀的，跳过。
                val mid = name.removePrefix(WebDavLayout.BACKUP_PREFIX).removeSuffix(WebDavLayout.BACKUP_SUFFIX)
                // mid 形如 "20260501_1832" 或 "20260501_1832_Pixel"
                val parts = mid.split("_")
                parts.size <= 2  // 仅 yyyyMMdd_HHmm（无设备后缀）
            } else {
                name.endsWith("$deviceSuffix${WebDavLayout.BACKUP_SUFFIX}")
            }
            if (!matchesDevice) continue
            runCatching { client.delete("$dir/$name") }
                .onSuccess { removed++ }
                .onFailure { AppLog.warn("WebDAV", "cleanup delete $name failed: ${it.message}") }
        }
        if (removed > 0) AppLog.info("WebDAV", "Cleaned $removed old backup(s) (device='${deviceSuffix.ifEmpty { "<none>" }}', dir='$dir')")
    }

    /**
     * 给手动入口（"测试连接"按钮）用的轻量 init：一次性 mkdir 全部子目录，验证
     * auth 链路通畅。Legado 在 saveWebDav 后调用一次 [AppWebDav.upConfig] 起到
     * 同样作用 — MoRealm 这里给 ProfileViewModel 一个可调用的入口。
     *
     * 不抛异常：失败信息折叠进 [Result.message]，调用方决定如何回显。
     */
    suspend fun ensureLayout(): Result {
        val url = prefs.webDavUrl.first()
        val user = prefs.webDavUser.first()
        val pass = prefs.webDavPass.first()
        if (url.isBlank()) return Result(false, "请先配置 WebDAV")

        return try {
            val client = backupRepo.createWebDavClient(url, user, pass)
            client.check()
            val dir = WebDavLayout.normalizeDir(prefs.webDavDir.first())
            client.makeAsDir(dir)
            for (sub in WebDavLayout.ALL_SUBDIRS) {
                runCatching { client.makeAsDir(WebDavLayout.subPath(dir, sub)) }
                    .onFailure { AppLog.warn("WebDAV", "ensureLayout sub-mkdir $sub failed: ${it.message}") }
            }
            AppLog.info("WebDAV", "Layout ensured under '$dir' with subdirs ${WebDavLayout.ALL_SUBDIRS}")
            Result(true, "连接正常，目录已就绪")
        } catch (e: WebDavException) {
            AppLog.warn("WebDAV", "ensureLayout failed: ${e.message}")
            Result(false, e.message ?: "连接失败")
        } catch (e: Exception) {
            AppLog.error("WebDAV", "ensureLayout unexpected", e)
            Result(false, "连接失败：${e.message}")
        }
    }

    companion object {
        /** Mirror Legado's daily auto-backup cadence. */
        const val AUTO_BACKUP_INTERVAL_HOURS = 24L
    }
}
