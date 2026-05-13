package com.morealm.app.presentation.profile

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morealm.app.di.ApplicationScope
import com.morealm.app.domain.preference.AppPreferences
import com.morealm.app.domain.repository.BackupRepository
import com.morealm.app.domain.sync.BackupManager
import com.morealm.app.domain.sync.BackupStatusBus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BackupRestoreViewModel @Inject constructor(
    private val backupRepo: BackupRepository,
    private val prefs: AppPreferences,
    /**
     * 进程级 scope —— 用于 [runImportWithSelections] / [importBackup] 这种
     * 「启动后必须跑完，不能被退栈打断」的协程。
     *
     * 修复的回归：BackupImportScreen 在「确认恢复」后立即 `onBack()` 关页面，
     * 此 ViewModel 随之 onCleared，`viewModelScope` 被取消 → 导入协程刚 launch
     * 就抛 `JobCancellationException: Job was cancelled`，DB 写一半（甚至全没写）。
     *
     * 预览类协程（[loadRestoreSections]）依旧用 [viewModelScope] —— 用户离开
     * 预览页时它本就该取消，省 IO。
     */
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    val backupPassword: StateFlow<String> = prefs.backupPassword
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    private val _backupStatus = MutableStateFlow("")
    val backupStatus: StateFlow<String> = _backupStatus.asStateFlow()

    private val _restoreSections =
        MutableStateFlow<List<BackupManager.RestoreSectionInfo>>(emptyList())
    val restoreSections: StateFlow<List<BackupManager.RestoreSectionInfo>> =
        _restoreSections.asStateFlow()

    private val _restoreSelections = MutableStateFlow<Set<String>>(emptySet())
    val restoreSelections: StateFlow<Set<String>> = _restoreSelections.asStateFlow()

    private val _restorePendingUri = MutableStateFlow<Uri?>(null)
    val restorePendingUri: StateFlow<Uri?> = _restorePendingUri.asStateFlow()

    private val _restoreSectionsLoading = MutableStateFlow(false)
    val restoreSectionsLoading: StateFlow<Boolean> = _restoreSectionsLoading.asStateFlow()

    private val _restorePreviewError = MutableStateFlow<String?>(null)
    val restorePreviewError: StateFlow<String?> = _restorePreviewError.asStateFlow()

    private val _restorePasswordOverride = MutableStateFlow("")
    val restorePasswordOverride: StateFlow<String> = _restorePasswordOverride.asStateFlow()

    fun setRestorePasswordOverride(value: String) {
        _restorePasswordOverride.value = value
    }

    private fun effectiveRestorePassword(): String =
        _restorePasswordOverride.value.ifEmpty { backupPassword.value }

    fun loadRestoreSections(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _restoreSectionsLoading.value = true
            _restorePreviewError.value = null
            _restorePendingUri.value = uri
            try {
                val sections = backupRepo.previewRestoreSections(uri, effectiveRestorePassword())
                _restoreSections.value = sections
                if (sections.isNotEmpty()) {
                    _restoreSelections.value = sections.map { it.key }.toSet()
                } else {
                    _restoreSelections.value = emptySet()
                    val reason = backupRepo.consumeLastBackupError()
                    _restorePreviewError.value = reason
                        ?: "无法读取备份内容（密码错误？文件损坏？）"
                }
            } catch (e: Throwable) {
                _restoreSections.value = emptyList()
                _restoreSelections.value = emptySet()
                _restorePreviewError.value = "读取备份失败：${e.message ?: e.javaClass.simpleName}"
            } finally {
                _restoreSectionsLoading.value = false
            }
        }
    }

    fun reloadRestorePreview() {
        val uri = _restorePendingUri.value ?: return
        loadRestoreSections(uri)
    }

    fun toggleRestoreSection(key: String) {
        val current = _restoreSelections.value
        _restoreSelections.value = if (key in current) current - key else current + key
    }

    fun selectAllRestoreSections() {
        _restoreSelections.value = _restoreSections.value.map { it.key }.toSet()
    }

    fun clearRestoreSelections() {
        _restoreSelections.value = emptySet()
    }

    fun runImportWithSelections() {
        val uri = _restorePendingUri.value ?: return
        val selectedKeys = _restoreSelections.value
        // appScope（不是 viewModelScope）—— BackupImportScreen 在「确认恢复」后会立刻
        // onBack() 弹栈，本 ViewModel 随之 onCleared，viewModelScope 会被取消。导入是
        // 一次性「必须跑完」的 DB 写入，绑在 ViewModel 上等同于自爆。结果通过
        // BackupStatusBus（singleton）通知全局 toast，不依赖此 VM 是否还活着。
        appScope.launch(Dispatchers.IO) {
            BackupStatusBus.emit("导入中...")
            val opts = BackupManager.RestoreOptions(
                includeBooks = "books" in selectedKeys,
                includeBookmarks = "bookmarks" in selectedKeys,
                includeSources = "sources" in selectedKeys,
                includeProgress = "progress" in selectedKeys,
                includeGroups = "groups" in selectedKeys,
                includeReplaceRules = "replaceRules" in selectedKeys,
                includeThemes = "themes" in selectedKeys,
                includeReaderStyles = "readerStyles" in selectedKeys,
                includePreferences = "preferences" in selectedKeys,
            )
            val effectivePassword = effectiveRestorePassword()
            val ok = backupRepo.importBackup(uri, effectivePassword, opts)
            val finalMsg = if (ok) {
                val n = selectedKeys.size
                "导入成功（$n 项已恢复）"
            } else {
                val reason = backupRepo.consumeLastBackupError()
                when {
                    !reason.isNullOrBlank() -> "导入失败：$reason"
                    else -> "导入失败（密码错误或文件损坏？）"
                }
            }
            BackupStatusBus.emit(finalMsg)
        }
        // VM 还活着的话同步清下本地状态，让用户立即看到「未选文件」UI；ViewModel
        // 已 onCleared 时这两个写入是无害的 dead write（StateFlow 无订阅）。
        _restorePendingUri.value = null
        _restorePasswordOverride.value = ""
        _backupStatus.value = "导入中..."
    }

    fun importBackup(uri: Uri) {
        // 同 runImportWithSelections —— 用 appScope 续命，避免页面退栈时被砍。
        val password = backupPassword.value
        appScope.launch(Dispatchers.IO) {
            BackupStatusBus.emit("导入中...")
            val ok = backupRepo.importBackup(uri, password)
            val finalMsg = if (ok) {
                "导入成功"
            } else {
                val reason = backupRepo.consumeLastBackupError()
                when {
                    !reason.isNullOrBlank() -> "导入失败：$reason"
                    else -> "导入失败（密码错误或文件损坏？）"
                }
            }
            BackupStatusBus.emit(finalMsg)
        }
        _backupStatus.value = "导入中..."
    }
}
