package com.morealm.app.presentation.profile

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morealm.app.core.error.ErrorMessages
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.repository.BookGroupRepository
import com.morealm.app.domain.storage.FastFileScanner
import com.morealm.app.domain.sync.FailedImport
import com.morealm.app.domain.sync.ImportEngine
import com.morealm.app.domain.sync.ImportState
import com.morealm.app.domain.sync.ImportStateBus
import com.morealm.app.domain.sync.RemoteBookFile
import com.morealm.app.domain.sync.RemoteBookManager
import com.morealm.app.domain.sync.RemoteEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import javax.inject.Inject

/**
 * 远程书架 V2 ViewModel —— 层级浏览 + 自由导入 + 失败重试。
 *
 * 替代 V1 的「平铺列表 + 单本 downloadAndImport」：
 *  - V1 进页面 BFS 全量递归（耗时数十秒）；V2 仅 [RemoteBookManager.listOneLevel] 拉根目录一层
 *  - V1 download 整本 ByteArray 持内存；V2 [RemoteBookManager.downloadStream] 边读边写
 *  - V1 自己 insert Book 不抽封面；V2 走 [ImportEngine.importBatch] 自动跑 Phase 2 enrich
 *  - V1 无失败重试；V2 [failedImports] + [retryFailed] 支持部分失败可恢复
 *
 * 浏览栈 [pathStack] 是 SnapshotStateList，UI 通过 collectAsState/derivedStateOf 订阅，
 * `goBack` / `enterDir` / `jumpToBreadcrumb` 直接增删尾部即可触发重组。每层 listing 走
 * [listingCache] 命中缓存，back / breadcrumb 跳转零网络请求；[refresh] 强制重拉当前层。
 *
 * 导入并发：
 *  - BFS list 并发 [RemoteBookManager.bfsCollectFiles] 内已用 Semaphore(5) 限流
 *  - 单次 download 并发上限 [DOWNLOAD_CONCURRENCY]=4，避免被 WebDav 后端判 DDoS
 *  - 单批 [BFS_BUFFER_SIZE]=200 喂给 importBatch，控制 Phase 2 enrich 同时驻留 ≤ 200 本
 */
@HiltViewModel
class RemoteBookViewModel @Inject constructor(
    private val manager: RemoteBookManager,
    private val groupRepo: BookGroupRepository,
    private val importEngine: ImportEngine,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private companion object {
        private const val TAG = "RemoteShelfV2"

        /** 单次导入下载并发上限，详 prd.md R4。 */
        private const val DOWNLOAD_CONCURRENCY = 4

        /** BFS emit 缓冲池大小 —— 累积 N 个文件 emit 一批，详 prd.md R4。 */
        private const val BFS_BUFFER_SIZE = 200
    }

    // ── 浏览栈 ────────────────────────────────────────────────────

    /**
     * 当前所在路径栈 —— 首元素 = booksDir（根），末元素 = 当前所在层的绝对远程路径。
     *
     * 用 [SnapshotStateList] 而非 StateFlow：UI 用 `derivedStateOf` / 直接读列表元素
     * 即可订阅；增删尾部即可触发 UI 重组。`goBack` = removeAt(lastIndex)；
     * `jumpToBreadcrumb(index)` = trim 到 index+1 长度。
     */
    val pathStack: SnapshotStateList<String> = mutableStateListOf()

    /**
     * Listing 缓存 —— path → 该 path 一层的 RemoteEntry 列表。会话内有效：用户在
     * 嵌套目录间用 back / breadcrumb 跳转不重新拉网络；离开页面 ViewModel 销毁缓存
     * 全清。远程目录易变，故不持久化。
     */
    val listingCache: SnapshotStateMap<String, List<RemoteEntry>> = mutableStateMapOf()

    /**
     * 当前层 entries —— 单向 push：[pathStack] 变化或 listing 加载完成都显式调
     * [recomputeCurrentEntries] 同步到这里。比 derivedStateOf/combine 三方 join 简单。
     */
    private val _currentEntries = MutableStateFlow<List<RemoteEntry>>(emptyList())
    val currentEntries: StateFlow<List<RemoteEntry>> = _currentEntries.asStateFlow()

    // ── 状态 ─────────────────────────────────────────────────────

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    /**
     * 过滤后的 entries —— [currentEntries] 与 [searchQuery] 的 substring 合并。
     * combine 保证两者同一 emission 内一致，避免 UI 读不一致快照。
     */
    val filteredEntries: StateFlow<List<RemoteEntry>>

    // ── 多选 ─────────────────────────────────────────────────────

    private val _selectionMode = MutableStateFlow(false)
    val selectionMode: StateFlow<Boolean> = _selectionMode.asStateFlow()

    private val _selection = MutableStateFlow<Set<RemoteEntry>>(emptySet())
    val selection: StateFlow<Set<RemoteEntry>> = _selection.asStateFlow()

    // ── 失败 + 进度 ──────────────────────────────────────────────

    private val _failedImports = MutableStateFlow<List<FailedImport>>(emptyList())
    val failedImports: StateFlow<List<FailedImport>> = _failedImports.asStateFlow()

    /** 单 active 导入 job —— [cancelImport] 用；新 import 自动取消上一个。 */
    @Volatile
    private var importJob: Job? = null

    /** 进度直接转发自 [ImportStateBus.state]；新一次 import 由 banner 显示。 */
    val importState: StateFlow<ImportState> = ImportStateBus.state

    init {
        filteredEntries = combine(_currentEntries, _searchQuery) { entries, query ->
            if (query.isBlank()) entries
            else entries.filter { it.name.contains(query, ignoreCase = true) }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

        // 进页面先加载 root 层
        viewModelScope.launch { openRoot() }
    }

    /** 把当前 [pathStack] 末层从 [listingCache] 同步到 [_currentEntries]。 */
    private fun recomputeCurrentEntries() {
        val key = pathStack.lastOrNull()
        _currentEntries.value = if (key == null) emptyList() else listingCache[key] ?: emptyList()
    }

    // ── 公开方法：浏览 ──────────────────────────────────────────

    /** 首次进入：拉 booksDir 一层 + push 到 stack。 */
    private suspend fun openRoot() {
        val root = manager.booksDir()
        pathStack.clear()
        pathStack.add(root)
        recomputeCurrentEntries()
        loadCurrentLayer(forceReload = true)
    }

    /** 进入子目录 —— 不二次拉如缓存命中。 */
    fun enterDir(folder: RemoteEntry.Folder) {
        if (folder.remotePath == pathStack.lastOrNull()) return
        pathStack.add(folder.remotePath)
        recomputeCurrentEntries()
        viewModelScope.launch { loadCurrentLayer(forceReload = false) }
    }

    /**
     * 返回上一层。
     * @return true = 处理了；false = 已在 root，调用方应触发 onBack 退出页面
     */
    fun goBack(): Boolean {
        if (pathStack.size <= 1) return false
        pathStack.removeAt(pathStack.lastIndex)
        recomputeCurrentEntries()
        return true
    }

    /**
     * 面包屑跳转 —— 跳到 index 段（含），trim 后续段。
     * @param index 0-based，0 = 跳到 root
     */
    fun jumpToBreadcrumb(index: Int) {
        if (index < 0 || index >= pathStack.size - 1) return
        while (pathStack.size > index + 1) {
            pathStack.removeAt(pathStack.lastIndex)
        }
        recomputeCurrentEntries()
    }

    /** 强制重新拉当前层（用户菜单点刷新触发）。 */
    fun refresh() {
        viewModelScope.launch { loadCurrentLayer(forceReload = true) }
    }

    private suspend fun loadCurrentLayer(forceReload: Boolean) {
        val key = pathStack.lastOrNull() ?: return
        if (!forceReload && listingCache.containsKey(key)) {
            recomputeCurrentEntries()
            return
        }
        _loading.value = true
        _status.value = ""
        try {
            val entries = manager.listOneLevel(key)
            listingCache[key] = entries
            if (entries.isEmpty()) {
                _status.value = "「${key.substringAfterLast('/')}」目录为空"
            }
            recomputeCurrentEntries()
        } catch (e: Exception) {
            _status.value = ErrorMessages.forUser("读取目录", e)
            AppLog.error(TAG, "loadCurrentLayer($key) failed", e)
        } finally {
            _loading.value = false
        }
    }

    // ── 公开方法：搜索 ──────────────────────────────────────────

    fun search(query: String) {
        _searchQuery.value = query
    }

    // ── 公开方法：多选 ──────────────────────────────────────────

    fun toggleSelectionMode() {
        val next = !_selectionMode.value
        _selectionMode.value = next
        if (!next) _selection.value = emptySet()
    }

    fun toggleSelection(entry: RemoteEntry) {
        _selection.update { cur ->
            if (entry in cur) cur - entry else cur + entry
        }
    }

    fun clearSelection() {
        _selection.value = emptySet()
        _selectionMode.value = false
    }

    // ── 公开方法：导入 ──────────────────────────────────────────

    /** 单文件导入 —— download + 喂 importBatch；当前层为 group。 */
    fun importFile(file: RemoteEntry.File) {
        importJob?.cancel()
        importJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val folderId = ensureGroupForCurrentLayer()
                val folderName = pathStack.lastOrNull()?.substringAfterLast('/') ?: "远程书架"
                val tempFile = downloadToTemp(file.remotePath, file.name)
                    ?: return@launch  // failedImports 已记
                val scanItem = FastFileScanner.ScanItem(
                    uri = Uri.fromFile(tempFile),
                    name = file.name,
                    filePath = tempFile.absolutePath,
                )
                ImportStateBus.update(ImportState.Scanning(folderName))
                ImportStateBus.update(ImportState.Phase1(folderName, imported = 0, total = 1))
                val result = importEngine.importBatch(
                    files = listOf(scanItem),
                    folderId = folderId,
                    folderName = folderName,
                )
                ImportStateBus.update(
                    ImportState.Done(
                        folderName = folderName,
                        imported = result.phase1Inserted,
                        durationMs = 0L,
                    )
                )
                runCatching { tempFile.delete() }
                AppLog.info(TAG, "importFile done ${file.name} folderId=$folderId")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.error(TAG, "importFile failed ${file.name}", e)
                _failedImports.update {
                    it + FailedImport(file.remotePath, file.name, e.message?.take(120))
                }
                _status.value = ErrorMessages.forUser("导入", e)
            }
        }
    }

    /** 文件夹递归导入 —— BFS + 嵌套 group + 分批喂 importBatch。详 design.md "文件夹导入"。 */
    fun importFolder(folder: RemoteEntry.Folder) {
        importJob?.cancel()
        importJob = viewModelScope.launch(Dispatchers.IO) {
            importFolderImpl(folder.remotePath, folder.name)
        }
    }

    /** 多选批量导入 —— 文件直接合一批；文件夹各自走 importFolderImpl。 */
    fun importSelected() {
        val snapshot = _selection.value
        if (snapshot.isEmpty()) return
        clearSelection()
        importJob?.cancel()
        importJob = viewModelScope.launch(Dispatchers.IO) {
            val files = snapshot.filterIsInstance<RemoteEntry.File>()
            val folders = snapshot.filterIsInstance<RemoteEntry.Folder>()
            val folderName = pathStack.lastOrNull()?.substringAfterLast('/') ?: "远程书架"
            // 1) 当前层 files：一次性 download + importBatch
            if (files.isNotEmpty()) {
                try {
                    val folderId = ensureGroupForCurrentLayer()
                    val downloaded = downloadAllConcurrent(files.map { it.remotePath to it.name })
                    if (downloaded.isNotEmpty()) {
                        ImportStateBus.update(ImportState.Scanning(folderName))
                        ImportStateBus.update(
                            ImportState.Phase1(folderName, imported = 0, total = downloaded.size)
                        )
                        val items = downloaded.map { (_, tempFile, originalName) ->
                            FastFileScanner.ScanItem(
                                uri = Uri.fromFile(tempFile),
                                name = originalName,
                                filePath = tempFile.absolutePath,
                            )
                        }
                        importEngine.importBatch(items, folderId, folderName)
                        downloaded.forEach { (_, tempFile, _) -> runCatching { tempFile.delete() } }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.error(TAG, "importSelected files failed", e)
                    _status.value = ErrorMessages.forUser("批量导入", e)
                }
            }
            // 2) 选中的文件夹 —— 各跑一遍 importFolderImpl；job 被取消会自动跳出
            for (f in folders) {
                if (!currentCoroutineContext().isActive) break
                importFolderImpl(f.remotePath, f.name)
            }
            ImportStateBus.update(
                ImportState.Done(folderName = folderName, imported = 0, durationMs = 0L)
            )
        }
    }

    /** 取消当前导入 job。Phase 1 已 insert 的书保留。 */
    fun cancelImport() {
        ImportStateBus.requestCancel()
        importJob?.cancel()
        importJob = null
    }

    /**
     * 重试失败列表 —— 把 [failedImports] 清空，把这些 path 转回 RemoteBookFile 跑
     * 同样的下载+导入流程。失败的会重新进 [failedImports] 不会丢。
     *
     * 注意：当前实现按 file 名当 group 名（没有原 folder tree 信息），所以重试的书
     * 都挂在当前层 group 下。这是 v2 短期 trade-off；完整恢复 path 树是 follow-up。
     */
    fun retryFailed() {
        val toRetry = _failedImports.value
        if (toRetry.isEmpty()) return
        _failedImports.value = emptyList()
        importJob?.cancel()
        importJob = viewModelScope.launch(Dispatchers.IO) {
            val folderName = pathStack.lastOrNull()?.substringAfterLast('/') ?: "远程书架"
            try {
                val folderId = ensureGroupForCurrentLayer()
                val downloaded = downloadAllConcurrent(toRetry.map { it.remotePath to it.fileName })
                if (downloaded.isNotEmpty()) {
                    ImportStateBus.update(ImportState.Scanning(folderName))
                    ImportStateBus.update(
                        ImportState.Phase1(folderName, imported = 0, total = downloaded.size)
                    )
                    val items = downloaded.map { (_, tempFile, originalName) ->
                        FastFileScanner.ScanItem(
                            uri = Uri.fromFile(tempFile),
                            name = originalName,
                            filePath = tempFile.absolutePath,
                        )
                    }
                    importEngine.importBatch(items, folderId, folderName)
                    downloaded.forEach { (_, tempFile, _) -> runCatching { tempFile.delete() } }
                }
                ImportStateBus.update(
                    ImportState.Done(folderName = folderName, imported = downloaded.size, durationMs = 0L)
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.error(TAG, "retryFailed failed", e)
                _status.value = ErrorMessages.forUser("重试", e)
            }
        }
    }

    // ── 内部 helper ─────────────────────────────────────────────

    /**
     * 文件夹导入的真实实现 —— BFS Flow.collect → chunk 内并发 download + 嵌套 group +
     * importBatch。详 design.md "文件夹导入" Data Flow。
     */
    private suspend fun importFolderImpl(rootRemotePath: String, rootFolderName: String) {
        try {
            // 1) 在当前层下建本地嵌套 group 根（rootFolderName 是这次导入的根目录名）
            val currentLayerSegments = relativePathSegments(pathStack.lastOrNull() ?: return)
            val baseSegments = currentLayerSegments + rootFolderName
            val baseFolderId = groupRepo.ensureGroupTree(baseSegments)
            AppLog.info(TAG, "importFolder root=$rootRemotePath baseFolderId=$baseFolderId")

            ImportStateBus.update(ImportState.Scanning(rootFolderName))

            // path → folderId 缓存，整次 import 共享避免重复 DB 查
            val pathToFolderId = mutableMapOf<String, String>()
            pathToFolderId[""] = baseFolderId

            var importedSoFar = 0
            // 2) BFS collect 每批；BFS 内部 Semaphore(5) 已限并发，这里不重复加
            manager.bfsCollectFiles(rootRemotePath, bufferSize = BFS_BUFFER_SIZE)
                .collect { chunk: List<RemoteBookFile> ->
                    if (chunk.isEmpty()) return@collect
                    // 2a) 给 chunk 内每个 file 算嵌套 group id（按相对 root 的中间 path）
                    for (file in chunk) {
                        val rel = file.remotePath.removePrefix("$rootRemotePath/")
                        val segments = rel.split('/').dropLast(1)  // 去掉文件名
                        val key = segments.joinToString("/")
                        if (key !in pathToFolderId) {
                            pathToFolderId[key] = if (segments.isEmpty()) {
                                baseFolderId
                            } else {
                                groupRepo.ensureGroupTree(baseSegments + segments)
                            }
                        }
                    }
                    // 2b) 并发 download 这个 chunk
                    val downloaded = downloadAllConcurrent(chunk.map { it.remotePath to it.name })
                    if (downloaded.isEmpty()) return@collect

                    // 2c) 按 folderId 分组，每组 importBatch
                    val grouped = downloaded.groupBy { (remotePath, _, _) ->
                        val rel = remotePath.removePrefix("$rootRemotePath/")
                        val segments = rel.split('/').dropLast(1)
                        val key = segments.joinToString("/")
                        pathToFolderId[key] ?: baseFolderId
                    }
                    for ((folderId, group) in grouped) {
                        val items = group.map { (_, tempFile, name) ->
                            FastFileScanner.ScanItem(
                                uri = Uri.fromFile(tempFile),
                                name = name,
                                filePath = tempFile.absolutePath,
                            )
                        }
                        val result = importEngine.importBatch(
                            files = items,
                            folderId = folderId,
                            folderName = rootFolderName,
                            progressOffset = importedSoFar,
                            // 总数动态：当前已完成 + 当前 chunk size；BFS 不预知 total
                            totalForProgress = importedSoFar + items.size,
                        )
                        importedSoFar += result.phase1Inserted
                    }
                    // 2d) 删除已 import 的 tempFile（saveAsLocal 已 copy 到永久位置）
                    downloaded.forEach { (_, tempFile, _) -> runCatching { tempFile.delete() } }
                }
            ImportStateBus.update(
                ImportState.Done(
                    folderName = rootFolderName,
                    imported = importedSoFar,
                    durationMs = 0L,
                )
            )
            AppLog.info(TAG, "importFolder done $rootRemotePath imported=$importedSoFar")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.error(TAG, "importFolderImpl failed $rootRemotePath", e)
            _status.value = ErrorMessages.forUser("文件夹导入", e)
            ImportStateBus.update(
                ImportState.Error(folderName = rootFolderName, message = e.message ?: "未知错误")
            )
        }
    }

    /**
     * 当前所在层映射的本地 group id —— 用 pathStack 末层减去 root 段。
     * 根目录返回 null（直接挂书架）。
     */
    private suspend fun ensureGroupForCurrentLayer(): String? {
        val segments = relativePathSegments(pathStack.lastOrNull() ?: return null)
        return if (segments.isEmpty()) null else groupRepo.ensureGroupTree(segments)
    }

    /**
     * 当前路径相对于 root（pathStack[0]）的 segments，根目录返回空 list。
     * 用于 ensureGroupTree 的 segment 链；空 segments → 不建 group。
     */
    private fun relativePathSegments(currentPath: String): List<String> {
        val root = pathStack.firstOrNull() ?: return emptyList()
        if (currentPath == root) return emptyList()
        return currentPath.removePrefix("$root/").split('/').filter { it.isNotBlank() }
    }

    /**
     * 下载 [items]（remotePath, displayName）到 tempfile，并发上限 [DOWNLOAD_CONCURRENCY]。
     * 失败的 file 不抛，仅写入 [failedImports]；返回成功的 tripleList (remotePath, tempFile, name)。
     */
    private suspend fun downloadAllConcurrent(
        items: List<Pair<String, String>>,
    ): List<Triple<String, File, String>> = coroutineScope {
        @Suppress("OPT_IN_USAGE")
        val dispatcher = Dispatchers.IO.limitedParallelism(DOWNLOAD_CONCURRENCY)
        items.map { (remotePath, name) ->
            async(dispatcher) {
                val temp = downloadToTemp(remotePath, name)
                if (temp != null) Triple(remotePath, temp, name) else null
            }
        }.awaitAll().filterNotNull()
    }

    /**
     * 下载单个文件到 tempfile —— filesDir/remoteBooks/<UUID8>-<sanitized>。
     * UUID 前缀避免不同子目录同名 file 互相覆盖（多 chunk 并发场景）。
     * 失败时记到 [failedImports] 并返回 null。
     */
    private suspend fun downloadToTemp(remotePath: String, displayName: String): File? {
        val dir = File(context.filesDir, "remoteBooks").also { it.mkdirs() }
        val unique = UUID.randomUUID().toString().take(8)
        val sanitized = sanitize(displayName)
        val tempFile = File(dir, "$unique-$sanitized")
        return runCatching {
            manager.downloadStream(remotePath, tempFile)
            tempFile
        }.onFailure { t ->
            AppLog.warn(TAG, "download failed $remotePath: ${t.message?.take(80)}")
            _failedImports.update {
                it + FailedImport(remotePath, displayName, t.message?.take(120))
            }
        }.getOrNull()
    }

    /** 防路径注入 —— 同 V1 行为。 */
    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
}
