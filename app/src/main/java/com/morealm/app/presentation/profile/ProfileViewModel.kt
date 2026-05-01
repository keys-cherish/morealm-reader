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
            val ok = backupRepo.exportBackup(uri)
            _backupStatus.value = if (ok) "导出成功" else "导出失败"
        }
    }

    fun importBackup(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _backupStatus.value = "导入中..."
            val ok = backupRepo.importBackup(uri)
            _backupStatus.value = if (ok) "导入成功" else "导入失败"
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
        _showRestoreConfirmation.value = true
    }

    /** User dismissed the confirmation dialog without confirming. */
    fun cancelWebDavRestore() {
        _showRestoreConfirmation.value = false
    }

    fun webDavBackup() {
        val url = webDavUrl.value
        val user = webDavUser.value
        val pass = webDavPass.value
        if (url.isBlank()) { _webDavStatus.value = "请先配置 WebDAV"; return }

        viewModelScope.launch(Dispatchers.IO) {
            _webDavStatus.value = "备份中..."
            try {
                val client = backupRepo.createWebDavClient(url, user, pass)
                client.mkdir("MoRealm")
                val backupData = backupRepo.generateBackupBytes()
                if (backupData == null) {
                    _webDavStatus.value = "备份数据生成失败"
                    return@launch
                }
                val ts = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
                client.upload("MoRealm/backup_$ts.zip", backupData)
                client.upload("MoRealm/backup_latest.zip", backupData)
                _webDavStatus.value = "备份成功"
                AppLog.info("WebDAV", "Backup uploaded: ${backupData.size} bytes")
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
    fun webDavRestore() {
        _showRestoreConfirmation.value = false
        val url = webDavUrl.value
        val user = webDavUser.value
        val pass = webDavPass.value
        if (url.isBlank()) { _webDavStatus.value = "请先配置 WebDAV"; return }

        viewModelScope.launch(Dispatchers.IO) {
            _webDavStatus.value = "恢复中..."
            try {
                val client = backupRepo.createWebDavClient(url, user, pass)
                val data = client.download("MoRealm/backup_latest.zip")
                if (data.isEmpty()) {
                    _webDavStatus.value = "未找到备份文件"
                    return@launch
                }
                val ok = backupRepo.importBackupFromBytes(data)
                _webDavStatus.value = if (ok) "恢复成功" else "恢复失败"
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
