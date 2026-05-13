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
class BackupExportViewModel @Inject constructor(
    private val backupRepo: BackupRepository,
    private val prefs: AppPreferences,
    /**
     * 与 [BackupRestoreViewModel] / [WebDavViewModel] 对称：导出也属于
     * 「启动后必须跑完」的 IO，用户在导出进行中（数 KB～几 MB 写盘）随时可能
     * 退栈返回 Profile 主页，[viewModelScope] 会被 onCleared 取消，半截 zip 留
     * 在 SAF 选定的位置。导出走 appScope 后，无论页面是否还在，结果都会落盘并
     * 通过 [BackupStatusBus] 全局 toast。预览读 DB（[loadBackupSections]）保留
     * 在 viewModelScope —— 它本来就是 UI 寿命，离开页面取消才合理。
     */
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    val backupPassword: StateFlow<String> = prefs.backupPassword
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    private val _backupStatus = MutableStateFlow("")
    val backupStatus: StateFlow<String> = _backupStatus.asStateFlow()

    private val _backupSections =
        MutableStateFlow<List<BackupManager.BackupSectionInfo>>(emptyList())
    val backupSections: StateFlow<List<BackupManager.BackupSectionInfo>> =
        _backupSections.asStateFlow()

    private val _backupSelections = MutableStateFlow<Set<String>>(emptySet())
    val backupSelections: StateFlow<Set<String>> = _backupSelections.asStateFlow()

    private val _backupSectionsLoading = MutableStateFlow(false)
    val backupSectionsLoading: StateFlow<Boolean> = _backupSectionsLoading.asStateFlow()

    fun loadBackupSections() {
        viewModelScope.launch(Dispatchers.IO) {
            _backupSectionsLoading.value = true
            val sections = backupRepo.previewBackupSections()
            _backupSections.value = sections
            if (_backupSelections.value.isEmpty() && sections.isNotEmpty()) {
                _backupSelections.value = sections.map { it.key }.toSet()
            }
            _backupSectionsLoading.value = false
        }
    }

    fun toggleBackupSection(key: String) {
        val current = _backupSelections.value
        _backupSelections.value = if (key in current) current - key else current + key
    }

    fun selectAllBackupSections() {
        _backupSelections.value = _backupSections.value.map { it.key }.toSet()
    }

    fun clearBackupSections() {
        _backupSelections.value = emptySet()
    }

    fun exportBackup(uri: Uri) {
        val password = backupPassword.value
        val sel = _backupSelections.value
        val options = if (sel.isEmpty()) {
            BackupManager.BackupOptions()
        } else {
            BackupManager.BackupOptions(
                includeBooks = "books" in sel,
                includeBookmarks = "bookmarks" in sel,
                includeSources = "sources" in sel,
                includeProgress = "progress" in sel,
                includeGroups = "groups" in sel,
                includeReplaceRules = "replaceRules" in sel,
                includeThemes = "themes" in sel,
                includeReaderStyles = "readerStyles" in sel,
            )
        }
        // appScope —— 见类头注释。BackupStatusBus 走 application-level 总线，
        // 导出过程中即使 BackupExportScreen 退栈、本 VM cleared，最终 toast 仍
        // 会被 ProfileScreen 的全局监听器 / MoRealmNavHost 顶层订阅捕获并弹出。
        appScope.launch(Dispatchers.IO) {
            BackupStatusBus.emit("导出中...")
            val ok = backupRepo.exportBackup(uri, password, options)
            val finalMsg = if (ok) {
                "导出成功"
            } else {
                val reason = backupRepo.consumeLastBackupError()
                if (reason.isNullOrBlank()) "导出失败" else "导出失败：$reason"
            }
            BackupStatusBus.emit(finalMsg)
        }
        _backupStatus.value = "导出中..."
    }
}
