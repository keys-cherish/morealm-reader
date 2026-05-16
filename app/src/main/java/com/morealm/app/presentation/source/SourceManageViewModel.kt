package com.morealm.app.presentation.source

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morealm.app.domain.entity.BookSource
import com.morealm.app.domain.preference.AppPreferences
import com.morealm.app.domain.repository.SourceRepository
import com.morealm.app.domain.source.BookSourceImporter
import com.morealm.app.domain.webbook.CheckSource
import com.morealm.app.domain.webbook.SourceDebug
import com.morealm.app.service.CheckSourceService
import com.morealm.app.core.error.ErrorMessages
import com.morealm.app.core.log.AppLog
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

@HiltViewModel
class BookSourceManageViewModel @Inject constructor(
    private val sourceRepo: SourceRepository,
    private val prefs: AppPreferences,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    data class ImportProgress(
        val current: Int = 0,
        val total: Int = 0,
        val sourceName: String = "",
    )

    val sources: StateFlow<List<BookSource>> = sourceRepo.getAllSources()
        .onEach { AppLog.debug("SourceManage", "sources emit size=${it.size}") }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * 列表分组模式（持久化在 AppPreferences）：
     *   "none" / "group_name" / "domain" / "type"
     *
     * UI 直接对字符串做相等判断，未知值（旧版本写入的脏数据 / 用户手动改 DataStore）
     * 一律走默认 "none" 分支，不会崩。
     */
    val groupMode: StateFlow<String> = prefs.sourceGroupMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, "none")

    /** 切换分组模式并持久化；写入失败由 DataStore 自身的重试与错误处理保底。 */
    fun setGroupMode(mode: String) {
        viewModelScope.launch {
            // 记录用户的分组方式切换 —— 排查"列表显示不对"反馈时第一时间能看到当前 mode；
            // 写之前打日志（而不是写之后）即便 DataStore 抛异常也能看到用户意图。
            AppLog.info("SourceManage", "groupMode set -> '$mode'")
            prefs.setSourceGroupMode(mode)
        }
    }

    /**
     * 列表排序键 + 升降序，分别持久化在两个 DataStore key 中。Eagerly 启动让
     * UI 顶栏的菜单当前选项随时可读，无需等首次 collect。
     *
     * 默认值由 [AppPreferences.sourceSortBy] / [AppPreferences.sourceSortAscending] 决定，
     * 与 prefs 自身的 fallback 一致；StateFlow 的 initial value 给同一组默认，
     * 防止 UI 在 cold flow 还没 emit 第一个值时显示"空"。
     */
    val sortBy: StateFlow<String> = prefs.sourceSortBy
        .stateIn(viewModelScope, SharingStarted.Eagerly, "custom")
    val sortAscending: StateFlow<Boolean> = prefs.sourceSortAscending
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** 写入新排序键（合法值见 [SourceSortKey.key]）。失败由 DataStore 重试兜底。 */
    fun setSortBy(key: String) {
        viewModelScope.launch {
            AppLog.info("SourceManage", "sortBy set -> '$key'")
            prefs.setSourceSortBy(key)
        }
    }

    /** 切换升降序——典型调用：用户点"反向"菜单项，或重复点同一排序维度时翻转。 */
    fun setSortAscending(asc: Boolean) {
        viewModelScope.launch {
            AppLog.info("SourceManage", "sortAsc set -> $asc")
            prefs.setSourceSortAscending(asc)
        }
    }

    /**
     * 书源导出结果（一次性事件）：[Result.success] 携带导出条数，failure 携带异常。
     * UI 在 collect 后弹 Snackbar 给反馈。SharedFlow + extraBuffer=1 让 UI 在
     * 启动 export 之前订阅与之后订阅都不丢消息（typical case 是先 collect 再点导出）。
     */
    private val _exportResult = kotlinx.coroutines.flow.MutableSharedFlow<Result<Int>>(extraBufferCapacity = 1)
    val exportResult: kotlinx.coroutines.flow.SharedFlow<Result<Int>> = _exportResult.asSharedFlow()

    /** 导出专用 Json 配置：与 [BookSource] 的 jsonParser 不同，**关闭 encodeDefaults**，
     *  让默认值字段不出现在 JSON 里——这样导出 JSON 体积更小、与 Legado 原生导出风格
     *  一致（其他人/其他 App 看到时不会被一堆空 ""、0、false 字段淹没）。
     *  prettyPrint 让用户用文本编辑器直接看也好读。
     */
    private val exportJson = kotlinx.serialization.json.Json {
        encodeDefaults = false
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    /**
     * 把书源列表导出为 Legado 兼容 JSON 数组写到 [uri]（SAF CreateDocument 拿到的）。
     *
     * @param uri SAF 选中的目标位置；调用方负责通过 ActivityResultContracts.CreateDocument 拿到
     * @param urls 要导出的书源 [BookSource.bookSourceUrl] 集合；null / 空集 = 导出全部
     *
     * 实现要点：
     *  - 序列化用 [List<BookSource>] 直接 encode（BookSource 自身是 @Serializable）
     *  - 写文件走 [Context.contentResolver.openOutputStream]，对 SAF / file:// / content:// 都通用
     *  - 失败/成功都 emit 到 [exportResult]，UI 单一订阅源
     *
     * 不返回结果，由 [exportResult] 异步 emit；这样 UI 可以在 launch SAF 之前就订阅好。
     */
    fun exportToUri(uri: android.net.Uri, urls: Collection<String>?) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val all = sources.value
                val list = if (urls.isNullOrEmpty()) all
                else all.filter { it.bookSourceUrl in urls }
                if (list.isEmpty()) {
                    _exportResult.tryEmit(Result.failure(IllegalStateException("没有可导出的书源")))
                    return@launch
                }
                val text = exportJson.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(BookSource.serializer()),
                    list,
                )
                context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    out.write(text.toByteArray(Charsets.UTF_8))
                } ?: throw IllegalStateException("无法打开输出流")
                AppLog.info("SourceExport", "Exported ${list.size} source(s) to $uri")
                _exportResult.tryEmit(Result.success(list.size))
            } catch (e: Exception) {
                AppLog.error("SourceExport", "Export failed", e)
                _exportResult.tryEmit(Result.failure(e))
            }
        }
    }

    /**
     * 批量启用 / 停用一组 URL 对应的书源。供分组 header 的"全启用 / 全停用"菜单调用。
     *
     * - 按 `bookSourceUrl` 主键查找当前内存里的 [BookSource] 行，filter 掉本来就处于
     *   目标状态的（避免无意义写）；
     * - 仅写差量，命中行用 `copy(enabled = ...)` 后走 [SourceRepository.insert]
     *   (REPLACE) 持久化，DB 主键 PrimaryKey 即 url，UPSERT 安全；
     * - 命中条数为 0 时直接 return，避免空 IO 调度。
     *
     * 不返回结果：列表 StateFlow 自身订阅了 DAO，UPSERT 后 UI 自然刷新。
     *
     * 日志：入口、空命中、有命中三条都打 INFO，便于回放"用户点了停用整组但部分书源
     * 仍亮着"这类反馈 —— 看 hits/urls 比例就知道是 already-in-target 还是 DB 漏写。
     */
    fun setEnabledForUrls(urls: Collection<String>, enabled: Boolean) {
        if (urls.isEmpty()) {
            AppLog.info("SourceManage", "bulk enable=$enabled rejected (empty url list)")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val targets = sources.value.filter {
                it.bookSourceUrl in urls && it.enabled != enabled
            }
            if (targets.isEmpty()) {
                AppLog.info(
                    "SourceManage",
                    "bulk enable=$enabled noop urls=${urls.size} (all already in target state)",
                )
                return@launch
            }
            AppLog.info(
                "SourceManage",
                "bulk enable=$enabled writing hits=${targets.size}/${urls.size}",
            )
            targets.forEach { sourceRepo.insert(it.copy(enabled = enabled)) }
            AppLog.info(
                "SourceManage",
                "bulk enable=$enabled done hits=${targets.size}",
            )
        }
    }

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
            val t0 = System.currentTimeMillis()
            AppLog.info("SourceManage", "deleteSource ENTRY url=${source.bookSourceUrl} name=${source.bookSourceName}")
            runCatching { sourceRepo.delete(source) }
                .onFailure { AppLog.error("SourceManage", "deleteSource FAILED ${source.bookSourceUrl}", it) }
            AppLog.info("SourceManage", "deleteSource DONE url=${source.bookSourceUrl} dt=${System.currentTimeMillis() - t0}ms")
        }
    }

    fun deleteSources(urls: Collection<String>) {
        if (urls.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            val urlList = urls.toList()
            AppLog.info(
                "SourceManage",
                "deleteSources ENTRY wantUrls=${urls.size} totalSources=${sources.value.size} (batch deleteByUrls)",
            )
            runCatching { sourceRepo.deleteByUrls(urlList) }
                .onFailure { AppLog.error("SourceManage", "deleteSources batch FAILED", it) }
            AppLog.info(
                "SourceManage",
                "deleteSources DONE wantUrls=${urls.size} dt=${System.currentTimeMillis() - t0}ms",
            )
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
                    // Surface the importer's diagnostic — previously the silent
                    // catch produced "未识别到有效书源" with zero hint about
                    // why, making bad-format pastes impossible to diagnose.
                    val why = BookSourceImporter.lastImportError
                    _importResult.value = if (why.isNullOrBlank()) "未识别到有效书源" else "未识别到有效书源：$why"
                }
            } catch (e: Exception) {
                _importResult.value = ErrorMessages.forUser("导入", e)
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
                    val why = BookSourceImporter.lastImportError
                    _importResult.value = if (why.isNullOrBlank()) "未识别到有效书源" else "未识别到有效书源：$why"
                }
            } catch (e: Exception) {
                _importResult.value = ErrorMessages.forUser("导入", e)
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
                    val why = BookSourceImporter.lastImportError
                    _importResult.value = if (why.isNullOrBlank()) "未识别到有效书源" else "未识别到有效书源：$why"
                }
            } catch (e: Exception) {
                _importResult.value = ErrorMessages.forUser("导入", e)
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
            sources.chunked(200).forEachIndexed { chunkIndex, chunk ->
                sourceRepo.importAll(chunk)
                val current = ((chunkIndex + 1) * 200).coerceAtMost(sources.size)
                val last = chunk.lastOrNull()
                _importProgress.value = ImportProgress(
                    current = current,
                    total = sources.size,
                    sourceName = last?.bookSourceName?.ifBlank { last.bookSourceUrl }.orEmpty(),
                )
            }
        }
    }

    // ── CheckSource 批量校验 ──
    //
    // 2026-05 重构：跑批从 viewModelScope 搬到 [CheckSourceService] (前台服务)，
    // 解决"App 切后台被杀就停"的痼疾。这里保留原有 4 个 StateFlow 接口签名不变，
    // 让 UI 完全无感 —— init 块订阅 Service 全局 StateFlow 后映射到本地。
    //
    // DB 持久化（errorMsg / lastCheckTime）已下沉到 Service，本类不再写 DB；
    // dialog 触发逻辑（_invalidCheckResults / _isInvalidResultsDialogVisible）保留在
    // 这里，因为 dialog 是 UI 的事，Service 不该懂 UI。

    private val _isChecking = MutableStateFlow(false)
    val isChecking: StateFlow<Boolean> = _isChecking.asStateFlow()

    private val _checkProgress = MutableStateFlow(0)
    val checkProgress: StateFlow<Int> = _checkProgress.asStateFlow()

    private val _checkTotal = MutableStateFlow(0)
    val checkTotal: StateFlow<Int> = _checkTotal.asStateFlow()

    private val _checkResults = MutableStateFlow<Map<String, CheckSource.CheckResult>>(emptyMap())
    val checkResults: StateFlow<Map<String, CheckSource.CheckResult>> = _checkResults.asStateFlow()

    init {
        // Service.results 直接镜像到 _checkResults — Service 跑批前会清空，不需要本地清。
        viewModelScope.launch {
            CheckSourceService.results.collect {
                // NPE 防御：kotlinx-coroutines / R8 在某些边角情况下会让 StateFlow<T:Any>
                // 的 FlowCollector.emit 拿到 null sentinel，虽然类型系统保证不可空，
                // 赋给下游 MutableStateFlow.setValue 时会抛 `Object.getClass()` NPE。
                // 这里拿到 null 只 warn 跳过，不影响正常路径。
                @Suppress("SENSELESS_COMPARISON")
                if (it == null) {
                    AppLog.warn("SourceManage", "CheckSourceService.results emit NULL, skip")
                    return@collect
                }
                _checkResults.value = it
            }
        }
        // Service.state 投影到三个进度 flow + 触发 dialog（仅 Done 时）
        viewModelScope.launch {
            CheckSourceService.state.collect { s ->
                @Suppress("SENSELESS_COMPARISON")
                if (s == null) {
                    AppLog.warn("SourceManage", "CheckSourceService.state emit NULL, skip")
                    return@collect
                }
                when (s) {
                    is CheckSourceService.Companion.State.Idle -> {
                        _isChecking.value = false
                    }
                    is CheckSourceService.Companion.State.Running -> {
                        _isChecking.value = true
                        _checkProgress.value = s.done
                        _checkTotal.value = s.total
                    }
                    is CheckSourceService.Companion.State.Done -> {
                        _isChecking.value = false
                        _checkProgress.value = s.total
                        _checkTotal.value = s.total
                        if (s.invalidCount < 0) {
                            // Service 返回 -1 表示跑批本身崩了（异常 / IO 错误等）
                            _importResult.value = "校验失败"
                        } else {
                            val valid = s.total - s.invalidCount
                            _importResult.value = "校验完成: $valid/${s.total} 可用"
                            val invalid = _checkResults.value.values.filter { !it.isValid }
                            if (invalid.isNotEmpty()) {
                                _invalidCheckResults.value = invalid
                                _isInvalidResultsDialogVisible.value = true
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 启动批量校验。空启用列表会通过 [importResult] 做 toast 提示，不调 service。
     * 已经在校验中（Service.state == Running）则忽略，避免重复启动同一个 service。
     */
    fun startCheckSources() {
        if (_isChecking.value) return
        val allSources = sources.value.filter { it.enabled }
        if (allSources.isEmpty()) {
            _importResult.value = "没有启用的书源"
            return
        }
        AppLog.info("CheckSource", "Start check via service, ${allSources.size} sources")
        CheckSourceService.start(context, allSources.map { it.bookSourceUrl })
    }

    /**
     * 取消校验。直接通知 Service 停。Service 会 cancel 跑批 + stopSelf；
     * 本类的 _isChecking 由 init 的 collect 块自动同步到 false。
     */
    fun cancelCheckSources() {
        CheckSourceService.stop(context)
    }

    // ── CheckSource 完成弹窗 ──
    /** 仅当有失效书源时为 true；UI 据此弹删除询问对话框。 */
    private val _isInvalidResultsDialogVisible = MutableStateFlow(false)
    val isInvalidResultsDialogVisible: StateFlow<Boolean> = _isInvalidResultsDialogVisible.asStateFlow()

    /** 失效书源结果快照（弹窗展示数据源；包含 sourceUrl/sourceName/error）。 */
    private val _invalidCheckResults = MutableStateFlow<List<CheckSource.CheckResult>>(emptyList())
    val invalidCheckResults: StateFlow<List<CheckSource.CheckResult>> = _invalidCheckResults.asStateFlow()

    fun dismissInvalidResultsDialog() {
        _isInvalidResultsDialogVisible.value = false
    }

    /**
     * 批量删除用户选中的失效书源。删除调用 sourceRepo.delete 走持久化；db 实时刷新让
     * sources StateFlow 自动重发。删完关弹窗。
     */
    fun deleteInvalidSources(sourceUrls: Collection<String>) {
        if (sourceUrls.isEmpty()) {
            _isInvalidResultsDialogVisible.value = false
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            val urlList = sourceUrls.toList()
            AppLog.info(
                "SourceManage",
                "deleteInvalidSources ENTRY wantUrls=${sourceUrls.size}" +
                    " totalSources=${sources.value.size} (batch deleteByUrls)",
            )
            runCatching { sourceRepo.deleteByUrls(urlList) }
                .onFailure { AppLog.warn("SourceManage", "deleteInvalidSources batch FAILED: ${it.message}") }
            _isInvalidResultsDialogVisible.value = false
            _importResult.value = "已删除 ${sourceUrls.size} 个失效书源"
            AppLog.info(
                "SourceManage",
                "deleteInvalidSources DONE wantUrls=${sourceUrls.size} dt=${System.currentTimeMillis() - t0}ms",
            )
        }
    }

    fun removeInvalidSources() {
        val results = _checkResults.value
        val invalidUrls = results.filter { !it.value.isValid }.keys
        if (invalidUrls.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            val urlList = invalidUrls.toList()
            AppLog.info(
                "SourceManage",
                "removeInvalidSources ENTRY wantUrls=${invalidUrls.size}" +
                    " totalSources=${sources.value.size} (batch deleteByUrls)",
            )
            runCatching { sourceRepo.deleteByUrls(urlList) }
                .onFailure { AppLog.warn("SourceManage", "removeInvalidSources batch FAILED: ${it.message}") }
            _importResult.value = "已删除 ${invalidUrls.size} 个无效书源"
            _checkResults.value = emptyMap()
            AppLog.info(
                "SourceManage",
                "removeInvalidSources DONE wantUrls=${invalidUrls.size} dt=${System.currentTimeMillis() - t0}ms",
            )
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
