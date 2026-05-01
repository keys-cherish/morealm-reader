package com.morealm.app.presentation.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.net.Uri
import com.morealm.app.domain.preference.AppPreferences
import com.morealm.app.domain.repository.BackupRepository
import com.morealm.app.domain.repository.BookRepository
import com.morealm.app.domain.repository.ReadStatsRepository
import com.morealm.app.core.log.AppLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class AnnualReport(
    val year: Int,
    val totalBooks: Int,
    val totalWordsWan: Int,       // 万字
    val totalDurationHours: Int,
    val activeDays: Int,
    val longestSessionMin: Int,
    val peakHour: String,         // e.g. "23:00"
    val favoriteBook: String,
    val tags: List<String>,
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val bookRepo: BookRepository,
    private val readStatsRepo: ReadStatsRepository,
    private val prefs: AppPreferences,
    private val backupRepo: BackupRepository,
) : ViewModel() {

    val totalBooks: StateFlow<Int> = flow { emit(bookRepo.countLogicalBooks()) }
        .stateIn(viewModelScope, SharingStarted.Lazily, 0)

    private val recentStats = readStatsRepo.getRecent(30)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val totalReadMs: StateFlow<Long> = recentStats
        .map { stats -> stats.sumOf { it.readDurationMs } }
        .stateIn(viewModelScope, SharingStarted.Lazily, 0L)

    val todayReadMs: StateFlow<Long> = recentStats.map { stats ->
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        stats.find { it.date == today }?.readDurationMs ?: 0L
    }.stateIn(viewModelScope, SharingStarted.Lazily, 0L)

    val recentDays: StateFlow<Int> = recentStats.map { stats ->
        if (stats.isEmpty()) return@map 0
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val dates = stats.map { it.date }.toSet()
        var count = 0
        val cal = java.util.Calendar.getInstance()
        while (dates.contains(fmt.format(cal.time))) {
            count++
            cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
        }
        count
    }.stateIn(viewModelScope, SharingStarted.Lazily, 0)

    val webDavUrl: StateFlow<String> = prefs.webDavUrl
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val webDavUser: StateFlow<String> = prefs.webDavUser
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val webDavPass: StateFlow<String> = prefs.webDavPass
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    // ── WebDav P1 settings (read) ────────────────────────────────────────
    val webDavDir: StateFlow<String> = prefs.webDavDir
        .stateIn(viewModelScope, SharingStarted.Eagerly, "MoRealm")
    val webDavDeviceName: StateFlow<String> = prefs.webDavDeviceName
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val syncBookProgress: StateFlow<Boolean> = prefs.syncBookProgress
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val onlyLatestBackup: StateFlow<Boolean> = prefs.onlyLatestBackup
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val ignoreLocalBook: StateFlow<Boolean> = prefs.ignoreLocalBook
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val ignoreReadConfig: StateFlow<Boolean> = prefs.ignoreReadConfig
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val autoBackup: StateFlow<Boolean> = prefs.autoBackup
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val backupPassword: StateFlow<String> = prefs.backupPassword
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    // ── WebDav P1 settings (write) ───────────────────────────────────────
    fun setWebDavDir(dir: String) = viewModelScope.launch(Dispatchers.IO) { prefs.setWebDavDir(dir) }
    fun setWebDavDeviceName(name: String) = viewModelScope.launch(Dispatchers.IO) { prefs.setWebDavDeviceName(name) }
    fun setSyncBookProgress(enabled: Boolean) = viewModelScope.launch(Dispatchers.IO) { prefs.setSyncBookProgress(enabled) }
    fun setOnlyLatestBackup(enabled: Boolean) = viewModelScope.launch(Dispatchers.IO) { prefs.setOnlyLatestBackup(enabled) }
    fun setIgnoreLocalBook(enabled: Boolean) = viewModelScope.launch(Dispatchers.IO) { prefs.setIgnoreLocalBook(enabled) }
    fun setIgnoreReadConfig(enabled: Boolean) = viewModelScope.launch(Dispatchers.IO) { prefs.setIgnoreReadConfig(enabled) }
    fun setAutoBackup(enabled: Boolean) = viewModelScope.launch(Dispatchers.IO) { prefs.setAutoBackup(enabled) }
    fun setBackupPassword(pw: String) = viewModelScope.launch(Dispatchers.IO) { prefs.setBackupPassword(pw) }

    /** Wall-clock of the last successful backup; 0 means "never". UI renders as "上次备份: ..." */
    val lastBackupTime: StateFlow<Long> = prefs.lastAutoBackup
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    init {
        // Subscribe to the cross-process status bus so background-only
        // backup paths (auto-backup scheduler, future progress sync echoes)
        // surface in `_webDavStatus`. Without this subscription the user
        // never sees auto-backup outcomes — they fire-and-forget into the
        // app-scope coroutine.
        viewModelScope.launch {
            com.morealm.app.domain.sync.WebDavStatusBus.statuses.collect { status ->
                val prefix = when (status.source) {
                    com.morealm.app.domain.sync.WebDavStatusBus.Source.AUTO -> "[自动] "
                    com.morealm.app.domain.sync.WebDavStatusBus.Source.PROGRESS -> "[进度] "
                    com.morealm.app.domain.sync.WebDavStatusBus.Source.MANUAL -> ""
                }
                _webDavStatus.value = "$prefix${status.message}"
            }
        }
    }

    private val _testResult = MutableStateFlow("")
    val testResult: StateFlow<String> = _testResult.asStateFlow()

    fun testWebDav(url: String, user: String, pass: String) {
        _testResult.value = "测试中..."
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ok = backupRepo.testWebDav(url, user, pass)
                _testResult.value = if (ok) "连接成功" else "连接失败：服务器无响应"
            } catch (e: Exception) {
                _testResult.value = "连接失败：${e.message}"
            }
        }
    }

    fun saveWebDav(url: String, user: String, pass: String) {
        viewModelScope.launch(Dispatchers.IO) {
            prefs.update(
                androidx.datastore.preferences.core.stringPreferencesKey("webdav_url"), url)
            prefs.update(
                androidx.datastore.preferences.core.stringPreferencesKey("webdav_user"), user)
            prefs.update(
                androidx.datastore.preferences.core.stringPreferencesKey("webdav_pass"), pass)
        }
    }

    private val _backupStatus = MutableStateFlow("")
    val backupStatus: StateFlow<String> = _backupStatus.asStateFlow()

    fun exportBackup(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _backupStatus.value = "导出中..."
            val ok = backupRepo.exportBackup(uri, backupPassword.value)
            _backupStatus.value = if (ok) "导出成功" else "导出失败"
        }
    }

    fun importBackup(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _backupStatus.value = "导入中..."
            val ok = backupRepo.importBackup(uri, backupPassword.value)
            _backupStatus.value = if (ok) "导入成功" else "导入失败（密码错误或文件损坏？）"
        }
    }

    // ── WebDAV Backup/Restore ──

    private val _webDavStatus = MutableStateFlow("")
    val webDavStatus: StateFlow<String> = _webDavStatus.asStateFlow()

    /**
     * Set to true when the user clicks "从云端恢复" — the screen observes
     * this and shows a confirmation dialog before any destructive write.
     * Confirming calls [webDavRestore]; cancelling calls [cancelWebDavRestore].
     * Without this gate the previous code overwrote the entire local
     * database on a single tap.
     */
    private val _showRestoreConfirmation = MutableStateFlow(false)
    val showRestoreConfirmation: StateFlow<Boolean> = _showRestoreConfirmation.asStateFlow()

    /**
     * Picker state: when true the screen shows a dialog listing all
     * `backup_*.zip` entries on the remote, sorted by lastModifiedEpoch
     * descending. The user picks one or "use latest"; that selection sinks
     * into [_pendingRestorePath] and triggers the confirmation dialog.
     */
    private val _showBackupPicker = MutableStateFlow(false)
    val showBackupPicker: StateFlow<Boolean> = _showBackupPicker.asStateFlow()

    private val _backupList = MutableStateFlow<List<com.morealm.app.domain.sync.WebDavClient.DavFile>>(emptyList())
    val backupList: StateFlow<List<com.morealm.app.domain.sync.WebDavClient.DavFile>> = _backupList.asStateFlow()

    private val _backupListLoading = MutableStateFlow(false)
    val backupListLoading: StateFlow<Boolean> = _backupListLoading.asStateFlow()

    /**
     * Path picked from the backup file picker. `null` means "use the
     * default `backup_latest<deviceSuffix>.zip`". Captured at pick-time and
     * consumed by [webDavRestore].
     */
    private val _pendingRestorePath = MutableStateFlow<String?>(null)

    /**
     * Validate config and ask the screen to surface the restore confirmation
     * dialog. The actual restore only fires after the user confirms via
     * [webDavRestore]; this two-step flow is the P0 fix for "single-click
     * accidentally wipes local data".
     */
    fun requestWebDavRestore() {
        val url = webDavUrl.value
        if (url.isBlank()) {
            _webDavStatus.value = "请先配置 WebDAV"
            return
        }
        _pendingRestorePath.value = null  // default = latest
        _showRestoreConfirmation.value = true
    }

    /**
     * Open the backup-file picker dialog and asynchronously load the list
     * of `backup_*.zip` files on the remote. The picker is the long-form
     * counterpart to [requestWebDavRestore]: it lets the user pick a
     * specific historical backup instead of always using "latest".
     */
    fun requestBackupPicker() {
        val url = webDavUrl.value
        val user = webDavUser.value
        val pass = webDavPass.value
        if (url.isBlank()) {
            _webDavStatus.value = "请先配置 WebDAV"
            return
        }
        _showBackupPicker.value = true
        _backupListLoading.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val client = backupRepo.createWebDavClient(url, user, pass)
                val dir = webDavDir.value.ifBlank { "MoRealm" }.trim('/')
                val files = client.listFiles(dir)
                    .filter { !it.isDirectory && it.name.startsWith("backup") && it.name.endsWith(".zip") }
                    .sortedByDescending { it.lastModifiedEpoch }
                _backupList.value = files
            } catch (e: Exception) {
                _webDavStatus.value = "读取备份列表失败：${e.message}"
                AppLog.error("WebDAV", "List backups failed", e)
                _backupList.value = emptyList()
                _showBackupPicker.value = false
            } finally {
                _backupListLoading.value = false
            }
        }
    }

    /** User dismissed the backup-picker dialog without picking. */
    fun cancelBackupPicker() {
        _showBackupPicker.value = false
    }

    /**
     * User picked a backup file from the picker. Builds the relative path
     * (`<dir>/<filename>`), stashes it in [_pendingRestorePath], closes the
     * picker, and opens the confirmation dialog.
     */
    fun selectBackupFile(file: com.morealm.app.domain.sync.WebDavClient.DavFile) {
        val dir = webDavDir.value.ifBlank { "MoRealm" }.trim('/')
        _pendingRestorePath.value = "$dir/${file.name}"
        _showBackupPicker.value = false
        _showRestoreConfirmation.value = true
    }

    /** User dismissed the confirmation dialog without confirming. */
    fun cancelWebDavRestore() {
        _pendingRestorePath.value = null
        _showRestoreConfirmation.value = false
    }

    fun webDavBackup() {
        val url = webDavUrl.value
        val user = webDavUser.value
        val pass = webDavPass.value
        if (url.isBlank()) { _webDavStatus.value = "请先配置 WebDAV"; return }

        // Read P1 settings at trigger-time so a user setting change is picked
        // up on the next click without having to restart the screen.
        val dir = webDavDir.value.ifBlank { "MoRealm" }.trim('/')
        val device = webDavDeviceName.value.trim()
        val onlyLatest = onlyLatestBackup.value

        viewModelScope.launch(Dispatchers.IO) {
            _webDavStatus.value = "备份中..."
            try {
                val client = backupRepo.createWebDavClient(url, user, pass)
                client.mkdir(dir)
                val backupData = backupRepo.generateBackupBytes()
                if (backupData == null) {
                    _webDavStatus.value = "备份数据生成失败"
                    return@launch
                }
                // Filename strategy:
                //  • onlyLatest=true → single file, overwritten each time
                //  • else → timestamped + always update latest pointer
                //  • device suffix appended when set, so multi-device backups
                //    don't clobber each other's "today's" file
                val ts = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
                val deviceSuffix = if (device.isNotEmpty()) "_${device.replace(Regex("[^A-Za-z0-9_-]"), "")}" else ""
                if (!onlyLatest) {
                    client.upload("$dir/backup_${ts}${deviceSuffix}.zip", backupData)
                }
                client.upload("$dir/backup_latest${deviceSuffix}.zip", backupData)
                prefs.setLastAutoBackup(System.currentTimeMillis())
                _webDavStatus.value = "备份成功"
                AppLog.info("WebDAV", "Backup uploaded: ${backupData.size} bytes (onlyLatest=$onlyLatest, device=$device)")
            } catch (e: Exception) {
                _webDavStatus.value = "备份失败：${e.message}"
                AppLog.error("WebDAV", "Backup failed", e)
            }
        }
    }

    /**
     * Actually perform the WebDav restore. Should ONLY be called from the
     * confirmation dialog's positive button — the public entry point that
     * the screen wires to the SyncItem click is [requestWebDavRestore].
     */
    /**
     * Actually perform the WebDav restore. Should ONLY be called from the
     * confirmation dialog's positive button — the public entry points are
     * [requestWebDavRestore] (latest) or [selectBackupFile] (specific file
     * picked from the backup picker). Reads the path from
     * [_pendingRestorePath]; null means "use the default latest pointer".
     */
    fun webDavRestore() {
        val remotePath = _pendingRestorePath.value
        _pendingRestorePath.value = null
        _showRestoreConfirmation.value = false
        val url = webDavUrl.value
        val user = webDavUser.value
        val pass = webDavPass.value
        if (url.isBlank()) { _webDavStatus.value = "请先配置 WebDAV"; return }

        val dir = webDavDir.value.ifBlank { "MoRealm" }.trim('/')
        val device = webDavDeviceName.value.trim()
        val deviceSuffix = if (device.isNotEmpty()) "_${device.replace(Regex("[^A-Za-z0-9_-]"), "")}" else ""
        val path = remotePath?.takeIf { it.isNotBlank() } ?: "$dir/backup_latest${deviceSuffix}.zip"

        viewModelScope.launch(Dispatchers.IO) {
            _webDavStatus.value = "恢复中..."
            try {
                val client = backupRepo.createWebDavClient(url, user, pass)
                val data = client.download(path)
                if (data.isEmpty()) {
                    _webDavStatus.value = "未找到备份文件"
                    return@launch
                }
                // P2: pass the user-set encryption password through; an
                // empty pw means BackupManager treats the data as plain
                // (legacy zip) and returns false only if the bytes carry
                // the MoREncBk magic without a matching password.
                val ok = backupRepo.importBackupFromBytes(data, backupPassword.value)
                _webDavStatus.value = if (ok) "恢复成功" else "恢复失败（密码错误或备份损坏？）"
            } catch (e: Exception) {
                _webDavStatus.value = "恢复失败：${e.message}"
                AppLog.error("WebDAV", "Restore failed", e)
            }
        }
    }

    // ── Annual Report ──

    private val _annualReport = MutableStateFlow<AnnualReport?>(null)
    val annualReport: StateFlow<AnnualReport?> = _annualReport.asStateFlow()

    fun loadAnnualReport(year: Int = Calendar.getInstance().get(Calendar.YEAR)) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val prefix = "$year"
                val yearStats = readStatsRepo.getByYear(prefix)
                val totalMs = yearStats.sumOf { it.readDurationMs }
                val totalWords = yearStats.sumOf { it.wordsRead }
                val booksFinished = yearStats.sumOf { it.booksFinished }
                val activeDays = yearStats.count { it.readDurationMs > 0 }
                val longestMs = yearStats.maxOfOrNull { it.readDurationMs } ?: 0L

                // Peak reading hour: daily granularity only, default estimate
                val peakHour = "22:00"

                // Favorite book: highest read progress
                val allBooks = bookRepo.getAllBooksSync()
                val favoriteBook = allBooks
                    .filter { it.lastReadAt > 0 && it.readProgress > 0f }
                    .maxByOrNull { it.readProgress }
                    ?.title ?: allBooks.firstOrNull()?.title ?: ""

                // Tags: collect unique categories/kinds
                val tags = allBooks
                    .flatMap { listOfNotNull(it.category, it.kind) }
                    .flatMap { it.split(",", "\u3001", "/", ";") }
                    .map { it.trim() }
                    .filter { it.isNotBlank() && it.length <= 6 }
                    .groupingBy { it }
                    .eachCount()
                    .entries
                    .sortedByDescending { it.value }
                    .take(5)
                    .map { it.key }

                _annualReport.value = AnnualReport(
                    year = year,
                    totalBooks = if (booksFinished > 0) booksFinished else allBooks.size,
                    totalWordsWan = (totalWords / 10000).toInt(),
                    totalDurationHours = (totalMs / 3600000).toInt(),
                    activeDays = activeDays,
                    longestSessionMin = (longestMs / 60000).toInt(),
                    peakHour = peakHour,
                    favoriteBook = favoriteBook,
                    tags = tags,
                )
            } catch (e: Exception) {
                AppLog.error("Profile", "Failed to load annual report", e)
            }
        }
    }
}
