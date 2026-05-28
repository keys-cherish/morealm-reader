package com.morealm.app.presentation.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morealm.app.di.ApplicationScope
import com.morealm.app.domain.preference.AppPreferences
import com.morealm.app.domain.repository.BackupRepository
import com.morealm.app.domain.sync.WebDavClient
import com.morealm.app.domain.sync.WebDavStatusBus
import com.morealm.app.core.log.AppLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class WebDavViewModel @Inject constructor(
    private val prefs: AppPreferences,
    private val backupRepo: BackupRepository,
    /**
     * 进程级 scope —— 用于 [webDavBackup] / [webDavRestore] 等「启动后必须跑完」
     * 的协程。WebDavScreen 在恢复确认对话框点完确认后，用户很可能立即按返回退
     * 出本页，[viewModelScope] 随之 cancel，导入半截被砍。状态反馈走
     * [WebDavStatusBus]（application 级 singleton），UI 永远能收到结果。
     */
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    // ── WebDav settings (read) ──
    val webDavUrl: StateFlow<String> = prefs.webDavUrl
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val webDavUser: StateFlow<String> = prefs.webDavUser
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val webDavPass: StateFlow<String> = prefs.webDavPass
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")
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
    val lastBackupTime: StateFlow<Long> = prefs.lastAutoBackup
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    // ── WebDav settings (write) ──
    fun setWebDavDir(dir: String) = viewModelScope.launch(Dispatchers.IO) { prefs.setWebDavDir(dir) }
    fun setWebDavDeviceName(name: String) = viewModelScope.launch(Dispatchers.IO) { prefs.setWebDavDeviceName(name) }
    fun setSyncBookProgress(enabled: Boolean) = viewModelScope.launch(Dispatchers.IO) { prefs.setSyncBookProgress(enabled) }
    fun setOnlyLatestBackup(enabled: Boolean) = viewModelScope.launch(Dispatchers.IO) { prefs.setOnlyLatestBackup(enabled) }
    fun setIgnoreLocalBook(enabled: Boolean) = viewModelScope.launch(Dispatchers.IO) { prefs.setIgnoreLocalBook(enabled) }
    fun setIgnoreReadConfig(enabled: Boolean) = viewModelScope.launch(Dispatchers.IO) { prefs.setIgnoreReadConfig(enabled) }
    fun setAutoBackup(enabled: Boolean) = viewModelScope.launch(Dispatchers.IO) { prefs.setAutoBackup(enabled) }
    fun setBackupPassword(pw: String) = viewModelScope.launch(Dispatchers.IO) { prefs.setBackupPassword(pw) }

    // ── Test / Save ──
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
            // Bug fix 2026-05-28：之前 saveWebDav 不动任何 status flow，
            // 用户点保存按钮看似无反应。写 _testResult（保存按钮正下方，与
            // "连接成功" 同位置），用户立刻能看到；同时 _webDavStatus 下方
            // 整页 status 区域也回显，备份/恢复反馈渠道一致。
            _testResult.value = "✓ 配置已保存"
            _webDavStatus.value = "配置已保存"
        }
    }

    // ── WebDAV status ──
    private val _webDavStatus = MutableStateFlow("")
    val webDavStatus: StateFlow<String> = _webDavStatus.asStateFlow()

    init {
        viewModelScope.launch {
            WebDavStatusBus.statuses.collect { status ->
                val prefix = when (status.source) {
                    WebDavStatusBus.Source.AUTO -> "[自动] "
                    WebDavStatusBus.Source.PROGRESS -> "[进度] "
                    WebDavStatusBus.Source.MANUAL -> ""
                }
                _webDavStatus.value = "$prefix${status.message}"
            }
        }
    }

    // ── Restore confirmation ──
    private val _isRestoreConfirmationVisible = MutableStateFlow(false)
    val isRestoreConfirmationVisible: StateFlow<Boolean> = _isRestoreConfirmationVisible.asStateFlow()

    private val _isBackupPickerVisible = MutableStateFlow(false)
    val isBackupPickerVisible: StateFlow<Boolean> = _isBackupPickerVisible.asStateFlow()

    private val _backupList = MutableStateFlow<List<WebDavClient.DavFile>>(emptyList())
    val backupList: StateFlow<List<WebDavClient.DavFile>> = _backupList.asStateFlow()

    private val _backupListLoading = MutableStateFlow(false)
    val backupListLoading: StateFlow<Boolean> = _backupListLoading.asStateFlow()

    private val _pendingRestorePath = MutableStateFlow<String?>(null)

    fun requestWebDavRestore() {
        val url = webDavUrl.value
        if (url.isBlank()) {
            _webDavStatus.value = "请先配置 WebDAV"
            return
        }
        _pendingRestorePath.value = null
        _isRestoreConfirmationVisible.value = true
    }

    fun requestBackupPicker() {
        val url = webDavUrl.value
        val user = webDavUser.value
        val pass = webDavPass.value
        if (url.isBlank()) {
            _webDavStatus.value = "请先配置 WebDAV"
            return
        }
        _isBackupPickerVisible.value = true
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
                _isBackupPickerVisible.value = false
            } finally {
                _backupListLoading.value = false
            }
        }
    }

    fun cancelBackupPicker() {
        _isBackupPickerVisible.value = false
    }

    fun selectBackupFile(file: WebDavClient.DavFile) {
        val dir = webDavDir.value.ifBlank { "MoRealm" }.trim('/')
        _pendingRestorePath.value = "$dir/${file.name}"
        _isBackupPickerVisible.value = false
        _isRestoreConfirmationVisible.value = true
    }

    fun cancelWebDavRestore() {
        _pendingRestorePath.value = null
        _isRestoreConfirmationVisible.value = false
    }

    fun webDavBackup() {
        val url = webDavUrl.value
        val user = webDavUser.value
        val pass = webDavPass.value
        if (url.isBlank()) { _webDavStatus.value = "请先配置 WebDAV"; return }

        val dir = webDavDir.value.ifBlank { "MoRealm" }.trim('/')
        val device = webDavDeviceName.value.trim()
        val onlyLatest = onlyLatestBackup.value

        // appScope（不是 viewModelScope）—— 备份可能耗时数秒到数十秒，用户在等待
        // 期间退出 WebDavScreen 是常见操作；旧实现绑在 viewModelScope 上会让退栈
        // 直接 cancel 上传，云端可能拿到半截或没拿到任何数据。状态写入 _webDavStatus
        // 在 VM 已 cleared 时是无害的 dead write；用户下次打开本页时由
        // [WebDavStatusBus] 的 replay=1 历史状态补显。
        appScope.launch(Dispatchers.IO) {
            _webDavStatus.value = "备份中..."
            try {
                val client = backupRepo.createWebDavClient(url, user, pass)
                client.mkdir(dir)
                val backupData = backupRepo.generateBackupBytes()
                if (backupData == null) {
                    _webDavStatus.value = "备份数据生成失败"
                    WebDavStatusBus.emit(WebDavStatusBus.Status(WebDavStatusBus.Source.MANUAL, "备份数据生成失败", success = false))
                    return@launch
                }
                val ts = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
                val deviceSuffix = if (device.isNotEmpty()) "_${device.replace(Regex("[^A-Za-z0-9_-]"), "")}" else ""
                if (!onlyLatest) {
                    client.upload("$dir/backup_${ts}${deviceSuffix}.zip", backupData)
                }
                client.upload("$dir/backup_latest${deviceSuffix}.zip", backupData)
                prefs.setLastAutoBackup(System.currentTimeMillis())
                _webDavStatus.value = "备份成功"
                WebDavStatusBus.emit(WebDavStatusBus.Status(WebDavStatusBus.Source.MANUAL, "备份成功", success = true))
                AppLog.info("WebDAV", "Backup uploaded: ${backupData.size} bytes (onlyLatest=$onlyLatest, device=$device)")
            } catch (e: Exception) {
                val msg = "备份失败：${e.message}"
                _webDavStatus.value = msg
                WebDavStatusBus.emit(WebDavStatusBus.Status(WebDavStatusBus.Source.MANUAL, msg, success = false))
                AppLog.error("WebDAV", "Backup failed", e)
            }
        }
    }

    fun webDavRestore() {
        val remotePath = _pendingRestorePath.value
        _pendingRestorePath.value = null
        _isRestoreConfirmationVisible.value = false
        val url = webDavUrl.value
        val user = webDavUser.value
        val pass = webDavPass.value
        if (url.isBlank()) { _webDavStatus.value = "请先配置 WebDAV"; return }

        val dir = webDavDir.value.ifBlank { "MoRealm" }.trim('/')
        val device = webDavDeviceName.value.trim()
        val deviceSuffix = if (device.isNotEmpty()) "_${device.replace(Regex("[^A-Za-z0-9_-]"), "")}" else ""
        val path = remotePath?.takeIf { it.isNotBlank() } ?: "$dir/backup_latest${deviceSuffix}.zip"
        val password = backupPassword.value

        // appScope —— 恢复对话框「确认覆盖」按下后，用户经常立即退栈回 ProfileScreen，
        // viewModelScope 一被 cancel 数据库就会写一半。`backupRepo.importBackupFromBytes`
        // 内部 BackupManager.mutex 串行化 + 字段级 runCatching，保证即便走到 appScope
        // 也是幂等 / 安全的。
        appScope.launch(Dispatchers.IO) {
            _webDavStatus.value = "恢复中..."
            try {
                val client = backupRepo.createWebDavClient(url, user, pass)
                val data = client.download(path)
                if (data.isEmpty()) {
                    val msg = "未找到备份文件"
                    _webDavStatus.value = msg
                    WebDavStatusBus.emit(WebDavStatusBus.Status(WebDavStatusBus.Source.MANUAL, msg, success = false))
                    return@launch
                }
                val ok = backupRepo.importBackupFromBytes(data, password)
                val msg = if (ok) "恢复成功" else "恢复失败（密码错误或备份损坏？）"
                _webDavStatus.value = msg
                WebDavStatusBus.emit(WebDavStatusBus.Status(WebDavStatusBus.Source.MANUAL, msg, success = ok))
            } catch (e: Exception) {
                val msg = "恢复失败：${e.message}"
                _webDavStatus.value = msg
                WebDavStatusBus.emit(WebDavStatusBus.Status(WebDavStatusBus.Source.MANUAL, msg, success = false))
                AppLog.error("WebDAV", "Restore failed", e)
            }
        }
    }
}
