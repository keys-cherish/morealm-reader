package com.morealm.app.presentation.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import android.net.Uri
import com.morealm.app.domain.db.AppDatabase
import com.morealm.app.domain.db.BookDao
import com.morealm.app.domain.db.ReadStatsDao
import com.morealm.app.domain.preference.AppPreferences
import com.morealm.app.domain.sync.BackupManager
import com.morealm.app.core.log.AppLog
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val bookDao: BookDao,
    private val readStatsDao: ReadStatsDao,
    private val prefs: AppPreferences,
    private val db: AppDatabase,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    /** Logical book count: folder = 1 book, loose file = 1 book */
    val totalBooks: StateFlow<Int> = flow { emit(bookDao.countLogicalBooks()) }
        .stateIn(viewModelScope, SharingStarted.Lazily, 0)

    private val recentStats = readStatsDao.getRecent(30)
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
        // Count consecutive days from today backwards
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
                val client = com.morealm.app.domain.sync.WebDavClient(url.trimEnd('/'), user, pass)
                val ok = client.exists("")
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
            val ok = BackupManager.exportBackup(context, db, uri)
            _backupStatus.value = if (ok) "导出成功" else "导出失败"
        }
    }

    fun importBackup(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _backupStatus.value = "导入中..."
            val ok = BackupManager.importBackup(context, db, uri)
            _backupStatus.value = if (ok) "导入成功" else "导入失败"
        }
    }

    // ── WebDAV Backup/Restore ──

    private val _webDavStatus = MutableStateFlow("")
    val webDavStatus: StateFlow<String> = _webDavStatus.asStateFlow()

    fun webDavBackup() {
        val url = webDavUrl.value
        val user = webDavUser.value
        val pass = webDavPass.value
        if (url.isBlank()) { _webDavStatus.value = "请先配置 WebDAV"; return }

        viewModelScope.launch(Dispatchers.IO) {
            _webDavStatus.value = "备份中..."
            try {
                val client = com.morealm.app.domain.sync.WebDavClient(url.trimEnd('/'), user, pass)
                // Create backup directory
                client.mkdir("MoRealm")
                // Generate backup data
                val backupData = BackupManager.generateBackupBytes(context, db)
                if (backupData == null) {
                    _webDavStatus.value = "备份数据生成失败"
                    return@launch
                }
                val ts = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
                client.upload("MoRealm/backup_$ts.zip", backupData)
                // Also keep a "latest" copy for easy restore
                client.upload("MoRealm/backup_latest.zip", backupData)
                _webDavStatus.value = "备份成功"
                AppLog.info("WebDAV", "Backup uploaded: ${backupData.size} bytes")
            } catch (e: Exception) {
                _webDavStatus.value = "备份失败：${e.message}"
                AppLog.error("WebDAV", "Backup failed", e)
            }
        }
    }

    fun webDavRestore() {
        val url = webDavUrl.value
        val user = webDavUser.value
        val pass = webDavPass.value
        if (url.isBlank()) { _webDavStatus.value = "请先配置 WebDAV"; return }

        viewModelScope.launch(Dispatchers.IO) {
            _webDavStatus.value = "恢复中..."
            try {
                val client = com.morealm.app.domain.sync.WebDavClient(url.trimEnd('/'), user, pass)
                val data = client.download("MoRealm/backup_latest.zip")
                if (data.isEmpty()) {
                    _webDavStatus.value = "未找到备份文件"
                    return@launch
                }
                val ok = BackupManager.importBackupFromBytes(context, db, data)
                _webDavStatus.value = if (ok) "恢复成功" else "恢复失败"
            } catch (e: Exception) {
                _webDavStatus.value = "恢复失败：${e.message}"
                AppLog.error("WebDAV", "Restore failed", e)
            }
        }
    }
}
