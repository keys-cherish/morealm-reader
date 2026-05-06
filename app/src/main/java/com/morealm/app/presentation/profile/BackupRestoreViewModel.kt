package com.morealm.app.presentation.profile

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morealm.app.domain.preference.AppPreferences
import com.morealm.app.domain.repository.BackupRepository
import com.morealm.app.domain.sync.BackupManager
import com.morealm.app.domain.sync.BackupStatusBus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BackupRestoreViewModel @Inject constructor(
    private val backupRepo: BackupRepository,
    private val prefs: AppPreferences,
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
        viewModelScope.launch(Dispatchers.IO) {
            _backupStatus.value = "导入中..."
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
            val ok = backupRepo.importBackup(uri, effectiveRestorePassword(), opts)
            _restorePendingUri.value = null
            _restorePasswordOverride.value = ""
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
            _backupStatus.value = finalMsg
            BackupStatusBus.emit(finalMsg)
        }
    }

    fun importBackup(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _backupStatus.value = "导入中..."
            val ok = backupRepo.importBackup(uri, backupPassword.value)
            val finalMsg = if (ok) {
                "导入成功"
            } else {
                val reason = backupRepo.consumeLastBackupError()
                when {
                    !reason.isNullOrBlank() -> "导入失败：$reason"
                    else -> "导入失败（密码错误或文件损坏？）"
                }
            }
            _backupStatus.value = finalMsg
            BackupStatusBus.emit(finalMsg)
        }
    }
}
