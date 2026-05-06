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
class BackupExportViewModel @Inject constructor(
    private val backupRepo: BackupRepository,
    private val prefs: AppPreferences,
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
        viewModelScope.launch(Dispatchers.IO) {
            _backupStatus.value = "导出中..."
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
            val ok = backupRepo.exportBackup(uri, backupPassword.value, options)
            val finalMsg = if (ok) {
                "导出成功"
            } else {
                val reason = backupRepo.consumeLastBackupError()
                if (reason.isNullOrBlank()) "导出失败" else "导出失败：$reason"
            }
            _backupStatus.value = finalMsg
            BackupStatusBus.emit(finalMsg)
        }
    }
}
