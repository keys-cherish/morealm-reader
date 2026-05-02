package com.morealm.app.presentation.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morealm.app.domain.font.FontEntry
import com.morealm.app.domain.font.FontRepository
import com.morealm.app.domain.preference.AppPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * FontManagerScreen 的 ViewModel。负责：
 *   - 暴露 App 字库 / 外部字体 列表 + 当前选中路径 / 文件夹 URI
 *   - 处理用户的导入、删除、切换、清除选中、外部文件夹挂载
 *   - 把所有 IO 调度到 viewModelScope（FontRepository 内部已 withContext(Dispatchers.IO)）
 *
 * UI 通过 [refreshLists] 在导入 / 删除 / 文件夹切换后主动刷新；列表本身不通过 Flow 反应式
 * 监听文件系统变化，避免无谓的轮询。
 */
@HiltViewModel
class FontManagerViewModel @Inject constructor(
    private val prefs: AppPreferences,
    private val fontRepo: FontRepository,
) : ViewModel() {

    /** 当前选中的字体路径（空 = 用 fontFamily 对应的系统字体）。 */
    val currentFontPath: StateFlow<String> = prefs.customFontUri
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** 外部文件夹 URI。空 = 用户没挂外部目录。 */
    val fontFolderUri: StateFlow<String> = prefs.fontFolderUri
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    private val _appFonts = MutableStateFlow<List<FontEntry>>(emptyList())
    val appFonts: StateFlow<List<FontEntry>> = _appFonts.asStateFlow()

    private val _externalFonts = MutableStateFlow<List<FontEntry>>(emptyList())
    val externalFonts: StateFlow<List<FontEntry>> = _externalFonts.asStateFlow()

    /** 一次性事件：导入失败 / 字体损坏 等。UI 侧 collect 后弹 Snackbar。 */
    private val _toast = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val toast: SharedFlow<String> = _toast.asSharedFlow()

    init {
        // 启动时拉一次。文件夹 URI 变化时也要重扫，所以 collect。
        viewModelScope.launch {
            _appFonts.value = fontRepo.listAppFonts()
        }
        viewModelScope.launch {
            prefs.fontFolderUri.collect { uri ->
                _externalFonts.value = fontRepo.listExternalFonts(uri)
            }
        }
    }

    fun refreshAppLibrary() = viewModelScope.launch {
        _appFonts.value = fontRepo.listAppFonts()
    }

    fun importFromUri(uri: Uri, suggestedName: String?) = viewModelScope.launch {
        runCatching { fontRepo.importFromUri(uri, suggestedName) }
            .onSuccess { entry ->
                _appFonts.value = fontRepo.listAppFonts()
                // 自动选中新导入的字体（与用户预期一致：导入即用）
                prefs.setCustomFont(entry.path, entry.displayName)
                _toast.tryEmit("已导入：${entry.displayName}")
            }
            .onFailure { _toast.tryEmit("导入失败：${it.localizedMessage}") }
    }

    fun pickFolder(uri: Uri) = viewModelScope.launch {
        prefs.setFontFolderUri(uri.toString())
    }

    fun selectFont(entry: FontEntry) = viewModelScope.launch {
        // 先验证能加载，避免挑了一个坏字体后阅读器全屏崩
        val tf = fontRepo.tryLoadFontFile(entry.path)
        if (tf == null) {
            _toast.tryEmit("无法加载该字体，可能已损坏或权限失效")
            return@launch
        }
        prefs.setCustomFont(entry.path, entry.displayName)
        _toast.tryEmit("已切换到：${entry.displayName}")
    }

    fun useSystemDefault() = viewModelScope.launch {
        prefs.clearCustomFont()
        _toast.tryEmit("已切换回系统默认字体")
    }

    fun deleteAppFont(entry: FontEntry) = viewModelScope.launch {
        val ok = fontRepo.deleteAppFont(entry)
        if (ok) {
            // 如果删的就是当前选中的，自动切回默认
            if (currentFontPath.value == entry.path) prefs.clearCustomFont()
            _appFonts.value = fontRepo.listAppFonts()
            _toast.tryEmit("已删除：${entry.displayName}")
        } else {
            _toast.tryEmit("删除失败")
        }
    }
}
