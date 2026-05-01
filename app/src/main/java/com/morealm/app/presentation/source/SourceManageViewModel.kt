package com.morealm.app.presentation.source

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morealm.app.domain.entity.BookSource
import com.morealm.app.domain.repository.SourceRepository
import com.morealm.app.domain.source.BookSourceImporter
import com.morealm.app.domain.webbook.CheckSource
import com.morealm.app.domain.webbook.SourceDebug
import com.morealm.app.core.log.AppLog
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

@HiltViewModel
class BookSourceManageViewModel @Inject constructor(
    private val sourceRepo: SourceRepository,
) : ViewModel() {

    data class ImportProgress(
        val current: Int = 0,
        val total: Int = 0,
        val sourceName: String = "",
    )

    val sources: StateFlow<List<BookSource>> = sourceRepo.getAllSources()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    private val _importProgress = MutableStateFlow(ImportProgress())
    val importProgress: StateFlow<ImportProgress> = _importProgress.asStateFlow()

    private val _importResult = MutableStateFlow<String?>(null)
    val importResult: StateFlow<String?> = _importResult.asStateFlow()

    fun clearImportResult() { _importResult.value = null }

    fun toggleSource(source: BookSource) {
        viewModelScope.launch(Dispatchers.IO) {
            sourceRepo.insert(source.copy(enabled = !source.enabled))
        }
    }

    fun deleteSource(source: BookSource) {
        viewModelScope.launch(Dispatchers.IO) {
            sourceRepo.delete(source)
        }
    }

    fun saveSource(source: BookSource) {
        viewModelScope.launch(Dispatchers.IO) {
            sourceRepo.insert(source)
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
                    importSourcesIncrementally(imported)
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
                _importProgress.value = ImportProgress()
            }
        }
    }

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
                        sourceRepo.fetchSourceJson(input)
                    }
                }
                val imported = withContext(Dispatchers.IO) {
                    BookSourceImporter.importFromJson(json)
                }
                if (imported.isNotEmpty()) {
                    importSourcesIncrementally(imported)
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
                _importProgress.value = ImportProgress()
            }
        }
    }

    fun importFromUri(uri: Uri, readContent: (Uri) -> String) {
        viewModelScope.launch {
            _isImporting.value = true
            try {
                val json = withContext(Dispatchers.IO) {
                    AppLog.info("SourceManage", "Reading from URI: $uri")
                    readContent(uri)
                }
                val imported = withContext(Dispatchers.IO) {
                    BookSourceImporter.importFromJson(json)
                }
                if (imported.isNotEmpty()) {
                    importSourcesIncrementally(imported)
                    _importResult.value = "成功导入 ${imported.size} 个书源"
                    AppLog.info("SourceManage", "Imported ${imported.size} sources from file")
                } else {
                    _importResult.value = "未识别到有效书源"
                }
            } catch (e: Exception) {
                _importResult.value = "导入失败: ${e.message}"
                AppLog.error("SourceManage", "Import from file failed", e)
            } finally {
                _isImporting.value = false
                _importProgress.value = ImportProgress()
            }
        }
    }

    private suspend fun importSourcesIncrementally(sources: List<BookSource>) {
        _importProgress.value = ImportProgress(total = sources.size)
        withContext(Dispatchers.IO) {
            sources.forEachIndexed { index, source ->
                sourceRepo.insert(source)
                _importProgress.value = ImportProgress(
                    current = index + 1,
                    total = sources.size,
                    sourceName = source.bookSourceName.ifBlank { source.bookSourceUrl },
                )
            }
        }
    }

    // ── CheckSource 批量校验 ──

    private val _isChecking = MutableStateFlow(false)
    val isChecking: StateFlow<Boolean> = _isChecking.asStateFlow()

    private val _checkProgress = MutableStateFlow(0)
    val checkProgress: StateFlow<Int> = _checkProgress.asStateFlow()

    private val _checkTotal = MutableStateFlow(0)
    val checkTotal: StateFlow<Int> = _checkTotal.asStateFlow()

    private val _checkResults = MutableStateFlow<Map<String, CheckSource.CheckResult>>(emptyMap())
    val checkResults: StateFlow<Map<String, CheckSource.CheckResult>> = _checkResults.asStateFlow()

    private var checkJob: kotlinx.coroutines.Job? = null

    fun startCheckSources() {
        if (_isChecking.value) return
        checkJob?.cancel()
        val allSources = sources.value.filter { it.enabled }
        if (allSources.isEmpty()) {
            _importResult.value = "没有启用的书源"
            return
        }
        _isChecking.value = true
        _checkProgress.value = 0
        _checkTotal.value = allSources.size
        _checkResults.value = emptyMap()

        val completedCount = AtomicInteger(0)
        checkJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                CheckSource.checkAll(allSources, concurrency = 4) { _, result ->
                    _checkProgress.value = completedCount.incrementAndGet()
                    _checkResults.value = _checkResults.value + (result.sourceUrl to result)
                    // Persist outcome so the badge survives app restart and the
                    // user sees consistent "失效原因" without re-running the check.
                    // We look up the live source row each time because user edits
                    // may have changed it between batch start and this callback.
                    val live = allSources.firstOrNull { it.bookSourceUrl == result.sourceUrl }
                    if (live != null) {
                        live.errorMsg = if (result.isValid) null else result.error
                        live.lastCheckTime = System.currentTimeMillis()
                        // Persist async — onResult is called from the check coroutine
                        // (non-suspending callback contract), so spawn a child job
                        // off the IO dispatcher to do the DB write without blocking.
                        viewModelScope.launch(Dispatchers.IO) {
                            runCatching { sourceRepo.insert(live) }
                                .onFailure {
                                    com.morealm.app.core.log.AppLog.warn(
                                        "CheckSource",
                                        "persist failed: ${it.message?.take(120)}",
                                    )
                                }
                        }
                    }
                }
                val results = _checkResults.value
                val valid = results.values.count { it.isValid }
                _importResult.value = "校验完成: $valid/${allSources.size} 可用"
                // 仅当有失效本时弹出删除询问对话框（与"全部有效→只 toast"区分开）。
                val invalid = results.values.filter { !it.isValid }
                if (invalid.isNotEmpty()) {
                    _invalidCheckResults.value = invalid
                    _showInvalidResultsDialog.value = true
                }
            } catch (e: Exception) {
                _importResult.value = "校验失败: ${e.message}"
            } finally {
                _isChecking.value = false
            }
        }
    }

    fun cancelCheckSources() {
        checkJob?.cancel()
        _isChecking.value = false
    }

    // ── CheckSource 完成弹窗 ──
    /** 仅当有失效书源时为 true；UI 据此弹删除询问对话框。 */
    private val _showInvalidResultsDialog = MutableStateFlow(false)
    val showInvalidResultsDialog: StateFlow<Boolean> = _showInvalidResultsDialog.asStateFlow()

    /** 失效书源结果快照（弹窗展示数据源；包含 sourceUrl/sourceName/error）。 */
    private val _invalidCheckResults = MutableStateFlow<List<CheckSource.CheckResult>>(emptyList())
    val invalidCheckResults: StateFlow<List<CheckSource.CheckResult>> = _invalidCheckResults.asStateFlow()

    fun dismissInvalidResultsDialog() {
        _showInvalidResultsDialog.value = false
    }

    /**
     * 批量删除用户选中的失效书源。删除调用 sourceRepo.delete 走持久化；db 实时刷新让
     * sources StateFlow 自动重发。删完关弹窗。
     */
    fun deleteInvalidSources(sourceUrls: Collection<String>) {
        if (sourceUrls.isEmpty()) {
            _showInvalidResultsDialog.value = false
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val toDelete = sources.value.filter { it.bookSourceUrl in sourceUrls }
            for (s in toDelete) {
                runCatching { sourceRepo.delete(s) }
                    .onFailure {
                        AppLog.warn("CheckSource", "delete failed ${s.bookSourceUrl}: ${it.message}")
                    }
            }
            _showInvalidResultsDialog.value = false
            _importResult.value = "已删除 ${toDelete.size} 个失效书源"
        }
    }

    fun removeInvalidSources() {
        val results = _checkResults.value
        val invalidUrls = results.filter { !it.value.isValid }.keys
        if (invalidUrls.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val toDelete = sources.value.filter { it.bookSourceUrl in invalidUrls }
            toDelete.forEach { sourceRepo.delete(it) }
            _importResult.value = "已删除 ${toDelete.size} 个无效书源"
            _checkResults.value = emptyMap()
        }
    }

    // ── SourceDebug 书源调试 ──

    private val _debugSteps = MutableStateFlow<List<SourceDebug.DebugStep>>(emptyList())
    val debugSteps: StateFlow<List<SourceDebug.DebugStep>> = _debugSteps.asStateFlow()

    private val _isDebugging = MutableStateFlow(false)
    val isDebugging: StateFlow<Boolean> = _isDebugging.asStateFlow()

    private var debugJob: kotlinx.coroutines.Job? = null

    fun debugSource(source: BookSource, keyword: String) {
        debugJob?.cancel()
        _debugSteps.value = emptyList()
        _isDebugging.value = true

        debugJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                SourceDebug.debug(source, keyword) { step ->
                    _debugSteps.value = _debugSteps.value + step
                }
            } catch (e: Exception) {
                _debugSteps.value = _debugSteps.value + SourceDebug.DebugStep(
                    "错误", error = e.message, success = false
                )
            } finally {
                _isDebugging.value = false
            }
        }
    }

    fun cancelDebug() {
        debugJob?.cancel()
        _isDebugging.value = false
    }
}
