package com.morealm.app.presentation.source

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morealm.app.domain.entity.BookSource
import com.morealm.app.domain.repository.SourceRepository
import com.morealm.app.domain.source.BookSourceImporter
import com.morealm.app.core.log.AppLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltViewModel
class BookSourceManageViewModel @Inject constructor(
    private val sourceRepo: SourceRepository,
) : ViewModel() {

    val sources: StateFlow<List<BookSource>> = sourceRepo.getAllSources()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    private val _importResult = MutableStateFlow<String?>(null)
    val importResult: StateFlow<String?> = _importResult.asStateFlow()

    fun clearImportResult() { _importResult.value = null }

    fun toggleSource(source: BookSource) {
        viewModelScope.launch {
            sourceRepo.insert(source.copy(enabled = !source.enabled))
        }
    }

    fun deleteSource(source: BookSource) {
        viewModelScope.launch {
            sourceRepo.delete(source)
        }
    }

    fun importFromJson(json: String) {
        viewModelScope.launch {
            _isImporting.value = true
            try {
                val imported = withContext(Dispatchers.IO) {
                    BookSourceImporter.importFromJson(json)
                }
                if (imported.isNotEmpty()) {
                    sourceRepo.importAll(imported)
                    _importResult.value = "成功导入 ${imported.size} 个书源"
                    AppLog.info("SourceManage", "Imported ${imported.size} sources from JSON")
                } else {
                    _importResult.value = "未识别到有效书源"
                }
            } catch (e: Exception) {
                _importResult.value = "导入失败: ${e.message}"
                AppLog.error("SourceManage", "Import failed", e)
            } finally {
                _isImporting.value = false
            }
        }
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun importFromUrl(urlOrJson: String) {
        viewModelScope.launch {
            _isImporting.value = true
            try {
                val input = urlOrJson.trim()
                val json = if (input.startsWith("[") || input.startsWith("{")) {
                    input
                } else {
                    withContext(Dispatchers.IO) {
                        AppLog.info("SourceManage", "Fetching: $input")
                        val response = httpClient.newCall(
                            Request.Builder().url(input)
                                .header("User-Agent", "Mozilla/5.0")
                                .build()
                        ).execute()
                        if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                        response.body?.string() ?: throw Exception("Empty response")
                    }
                }
                val imported = withContext(Dispatchers.IO) {
                    BookSourceImporter.importFromJson(json)
                }
                if (imported.isNotEmpty()) {
                    sourceRepo.importAll(imported)
                    _importResult.value = "成功导入 ${imported.size} 个书源"
                    AppLog.info("SourceManage", "Imported ${imported.size} sources")
                } else {
                    _importResult.value = "未识别到有效书源"
                }
            } catch (e: Exception) {
                _importResult.value = "导入失败: ${e.message}"
                AppLog.error("SourceManage", "Import failed", e)
            } finally {
                _isImporting.value = false
            }
        }
    }
}
