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

    private val rawSources: StateFlow<List<BookSource>> = sourceRepo.getAllSources()
        .onEach { upstream ->
            AppLog.debug("SourceManage", "sources emit size=${upstream.size} enabled=${upstream.count { it.enabled }}")
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * 乐观 UI overlay —— url → 用户**意图**的 enabled 值。
     *
     * 为什么需要：Material3 Switch 是 stateless，视觉完全受 checked 参数控制。
     * toggleSource → DB UPDATE 是原子的，但 Room InvalidationTracker emit 延迟
     * 1-2 秒，期间 sources 不变 → Switch checked 参数不变 → 用户点击后 Switch
     * 弹回原态，感受"按了没反应"。
     *
     * 修复：点击瞬间写入 overlay；[sources] 在 ViewModel 层用 `combine` 把 overlay
     * 合并进每个 BookSource.enabled（原子单 StateFlow），UI 只读单一真值 source，
     * 避免 onEach 改 overlay 与 stateIn emit 跨 transaction 的 Compose snapshot race
     * （日志 191200 实锤：emit enabled=424 但 SourceItem 收到 src.enabled=false）。
     */
    private val _toggleOverlay = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    /**
     * 对外暴露的 sources —— 已合并 overlay。combine 保证 rawSources 和 overlay 在
     * 同一 emission 内一起更新，UI 重组时不会读到不一致快照。Room 真值赶上 overlay 时
     * 自动剥离 overlay 项，保持 list 反映「乐观意图 + 已确认真值」混合。
     */
    /**
     * Reference-equality wrapper —— 强制每次 combine emit 都新建 instance，绕开 StateFlow
     * 和 Compose SnapshotMutableState 内置的 structural equality dedup。日志 192744 实锤：
     * combine block emit 11 次（click 11 次连点），但 SourceItem 只在初始重组一次——
     * 内容相同的 list 被 stateIn / collectAsState 全程 dedup。用非 data class 包裹
     * 让默认 equals 走 reference identity，每次 emit 必触发 UI 重组。
     */
    class SourcesSnapshot(val items: List<BookSource>)

    val sources: StateFlow<SourcesSnapshot> = rawSources
        .combine(_toggleOverlay) { srcList, overlay ->
            val merged = if (overlay.isEmpty()) srcList
            else srcList.map { src ->
                overlay[src.bookSourceUrl]?.let { src.copy(enabled = it) } ?: src
            }
            SourcesSnapshot(merged)
        }
        .onEach { snapshot ->
            AppLog.info(
                "SourceToggleDiag",
                "combine emit size=${snapshot.items.size} enabled=${snapshot.items.count { it.enabled }} overlay=${_toggleOverlay.value.size}",
            )
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SourcesSnapshot(emptyList()))

    init {
        // overlay 清理移到独立 collector：rawSources 真值赶上 overlay 时剥离。
        // 这里清理只影响 _toggleOverlay 自己；下次 combine 重新派生 sources 不再加 override。
        viewModelScope.launch {
            rawSources.collect { upstream ->
                _toggleOverlay.update { overlay ->
                    if (overlay.isEmpty()) return@update overlay
                    overlay.filter { (url, optimistic) ->
                        upstream.firstOrNull { it.bookSourceUrl == url }?.enabled != optimistic
                    }
                }
            }
        }
    }

    /**
     * Toggle 失败事件 —— SQL UPDATE 异常 / 0 rows 时 emit 错误描述，UI Snackbar 提示用户。
     * extraBufferCapacity=2 允许快速失败两次都到达 UI（连点 / 短时间多次失败）。
     */
    private val _toggleError = MutableSharedFlow<String>(extraBufferCapacity = 2)
    val toggleError: SharedFlow<String> = _toggleError.asSharedFlow()

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
                val all = sources.value.items
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
            val targets = sources.value.items.filter {
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

    /**
     * 切换书源启用状态。**用原子 SQL UPDATE 而不是 read-modify-write**。
     *
     * 历史 bug 链：
     *   1. 旧实现接 BookSource 参数 → Compose lambda 闭包陷阱，连点时 source.enabled
     *      永远是初值 → 每次写同样的 new 值 → Room 看 list 没变不 emit → UI 不刷新。
     *   2. 改成接 url + DAO read 后再 write，闭包问题解决；但还有 race：
     *      `viewModelScope.launch(Dispatchers.IO)` 各 launch 抢 IO 池独立运行，
     *      连点时多个 launch 几乎同时 getByUrl 都读到旧值 → 都写同样的 new 值
     *      → Room 不 emit → UI 不刷新（日志 184231 line 444-446：7ms 内 3 次
     *      was=true → false，全部读到 stale）。
     *   3. 现在：单条 SQL `UPDATE ... SET enabled = 1 - enabled`，read 与 write
     *      在同一 statement 原子完成。N 次并发 = N 次真翻转，Room 即时 emit。
     *
     * 副作用：lastUpdateTime 不再同步更新（旧 insert 路径会保留传入对象的全字段，
     * 包括用户期望的"修改时间"）。现需要时另开 @Query UPDATE 加 lastUpdateTime
     * 字段更新，或在 caller 显式调 `sourceRepo.insert` 走老路径。
     */
    fun toggleSource(url: String) {
        // 乐观更新串行化：用 synchronized 防 update CAS retry（lambda 重跑会基于不同
        // sources.value snapshot 计算 optimistic，连点 N 次实际翻转可能 ≠ N）。点击事件
        // 本来就在 main thread 串行，synchronized 仅作为对 onEach 并发清理的保险。
        synchronized(this) {
            val cur = _toggleOverlay.value
            // fallback 用 rawSources（DB 真值）—— 不是 sources（已合并 overlay，会自己叠加自己）
            val current = cur[url] ?: rawSources.value.firstOrNull { it.bookSourceUrl == url }?.enabled
            if (current == null) {
                AppLog.warn("SourceToggleDiag", "toggleSource url=$url skip: no current value")
                return
            }
            val newOptimistic = !current
            _toggleOverlay.value = cur + (url to newOptimistic)
            AppLog.info(
                "SourceToggleDiag",
                "toggleSource url=$url overlay $current→$newOptimistic (overlayHad=${cur[url] != null} rawEnabled=${rawSources.value.firstOrNull { it.bookSourceUrl == url }?.enabled})",
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { sourceRepo.toggleEnabled(url) }
            result.onSuccess { rows ->
                AppLog.info("SourceToggleDiag", "toggleSource url=$url rows=$rows (atomic UPDATE)")
                if (rows == 0) {
                    // 罕见：bookSourceUrl 在 toggle 期间被删除 / 拼写不对 / DB 异常
                    rollbackOverlay(url, reason = "未找到该书源")
                }
            }.onFailure { e ->
                AppLog.error("SourceToggleDiag", "toggleSource url=$url FAILED: ${e.message}", e)
                rollbackOverlay(url, reason = "切换失败：${e.message ?: "未知错误"}")
            }
        }
    }

    /**
     * UPDATE 失败时回滚：移除 overlay[url] 让 UI 弹回 source.enabled 真值 + emit Toast。
     * 不在 main thread call site 同步做（IO 协程内调用），用 MutableStateFlow.update 即可。
     */
    private suspend fun rollbackOverlay(url: String, reason: String) {
        _toggleOverlay.update { it - url }
        _toggleError.emit("$reason（已回滚）")
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
                "deleteSources ENTRY wantUrls=${urls.size} totalSources=${sources.value.items.size} (batch deleteByUrls)",
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
        val allSources = sources.value.items.filter { it.enabled }
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
        // 重置 Service 状态 —— 否则 StateFlow 对新订阅者重发 Done 状态，下次重进
        // 书源管理界面会触发 collect 块重弹同一个对话框（StateFlow 语义）。
        CheckSourceService.clear()
    }

    /**
     * 批量删除用户选中的失效书源。删除调用 sourceRepo.delete 走持久化；db 实时刷新让
     * sources StateFlow 自动重发。删完关弹窗。
     */
    fun deleteInvalidSources(sourceUrls: Collection<String>) {
        if (sourceUrls.isEmpty()) {
            _isInvalidResultsDialogVisible.value = false
            CheckSourceService.clear()
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val t0 = System.currentTimeMillis()
            val urlList = sourceUrls.toList()
            AppLog.info(
                "SourceManage",
                "deleteInvalidSources ENTRY wantUrls=${sourceUrls.size}" +
                    " totalSources=${sources.value.items.size} (batch deleteByUrls)",
            )
            runCatching { sourceRepo.deleteByUrls(urlList) }
                .onFailure { AppLog.warn("SourceManage", "deleteInvalidSources batch FAILED: ${it.message}") }
            _isInvalidResultsDialogVisible.value = false
            // 删完后状态归零 —— 重进界面不再弹同一个 dialog（修复用户报"退出再进入还有 N 个待删除书源"）
            CheckSourceService.clear()
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
                    " totalSources=${sources.value.items.size} (batch deleteByUrls)",
            )
            runCatching { sourceRepo.deleteByUrls(urlList) }
                .onFailure { AppLog.warn("SourceManage", "removeInvalidSources batch FAILED: ${it.message}") }
            _importResult.value = "已删除 ${invalidUrls.size} 个无效书源"
            _checkResults.value = emptyMap()
            // 状态归零，重进界面不会重弹 dialog
            CheckSourceService.clear()
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
