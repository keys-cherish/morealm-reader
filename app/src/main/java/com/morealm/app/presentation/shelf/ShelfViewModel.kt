package com.morealm.app.presentation.shelf

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.morealm.app.domain.entity.Book
import com.morealm.app.core.text.sortedNaturalBy
import com.morealm.app.core.text.sortedNaturalWith
import com.morealm.app.domain.entity.BookGroup
import com.morealm.app.domain.entity.ShelfGroup
import com.morealm.app.domain.preference.AppPreferences
import com.morealm.app.domain.repository.AutoGroupClassifier
import com.morealm.app.domain.repository.BookRepository
import com.morealm.app.domain.repository.BookGroupRepository
import com.morealm.app.domain.repository.ShelfGroupRepository
import com.morealm.app.domain.repository.SourceRepository
import com.morealm.app.core.log.AppLog
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 文件夹导入 UI 状态。v1.6 由 [com.morealm.app.domain.sync.ImportStateBus] 单向回填，
 * Banner UI 仍订阅此 [FolderImportState] StateFlow 不变。
 *
 * 新增字段 [imported] / [total] / [phase] 用于显示 `LinearProgressIndicator` + 数字进度
 * （AC5）。旧字段 [running] / [folderName] / [importedCount] / [message] / [error] 保留
 * 让旧 UI 文案路径继续工作，方便分阶段迁移。
 */
data class FolderImportState(
    val running: Boolean = false,
    val folderName: String = "",
    val importedCount: Int = 0,
    val message: String = "",
    val error: String? = null,
    // ── v1.6 新增 ──
    val imported: Int = 0,
    val total: Int = 0,
    val phase: ImportPhase = ImportPhase.Idle,
)

/** 文件夹导入阶段，给 UI 决定显示哪种 progress 样式 / 文案。 */
enum class ImportPhase { Idle, Scanning, Phase1, Phase2, Done, Error }

@HiltViewModel
class ShelfViewModel @Inject constructor(
    private val bookRepo: BookRepository,
    private val groupRepo: BookGroupRepository,
    private val shelfGroupRepo: ShelfGroupRepository,
    private val autoGroupClassifier: AutoGroupClassifier,
    private val prefs: AppPreferences,
    private val cacheRepo: com.morealm.app.domain.repository.CacheRepository,
    private val refreshController: ShelfRefreshController,
    private val databaseSeeder: com.morealm.app.domain.db.DatabaseSeeder,
    private val sourceRepo: SourceRepository,
    private val coverStorage: com.morealm.app.domain.cover.CoverStorage,
    private val readStatsRepo: com.morealm.app.domain.repository.ReadStatsRepository,
    private val importEngine: com.morealm.app.domain.sync.ImportEngine,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    // StateFlow 保留尚未消费的目标：文件夹导入在前台服务中完成时，即使用户暂时离开
    // 书架，返回后仍能定位；SharedFlow(replay=0) 会在页面未组合时丢事件。
    private val _importFocusTarget = MutableStateFlow<ShelfImportFocusTarget?>(null)
    val importFocusTarget: StateFlow<ShelfImportFocusTarget?> = _importFocusTarget.asStateFlow()

    // ── 今日阅读时长 ──
    // 顶栏副文本要显示「今日已阅读 X 小时 Y 分钟」，从 read_stats 表里取今天那条。
    // 与 ProfileStatsViewModel.todayReadMs 算法一致——都是按本地时区 yyyy-MM-dd 命中。
    // 进程本地化是必要的：read_stats 写入时也用同一时区算 date 串，跨时区不会
    // 错位。SharingStarted.Lazily 让 stats 只在 UI 订阅时才打开 DB Flow。
    val todayReadMs: StateFlow<Long> = readStatsRepo.getRecent(7).map { stats ->
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            .format(java.util.Date())
        stats.find { it.date == today }?.readDurationMs ?: 0L
    }.stateIn(viewModelScope, SharingStarted.Lazily, 0L)

    // ── Extracted Controllers ──
    val import = ShelfImportController(
        bookRepo = bookRepo,
        groupRepo = groupRepo,
        autoGroupClassifier = autoGroupClassifier,
        context = context,
        scope = viewModelScope,
        onBookInserted = { book ->
            _importFocusTarget.value = ShelfImportFocusTarget.BookTarget(book.id)
        },
    )

    val organize = ShelfOrganizeController(
        bookRepo = bookRepo,
        groupRepo = groupRepo,
        autoGroupClassifier = autoGroupClassifier,
        sourceRepo = sourceRepo,
        scope = viewModelScope,
    )

    val lastReadBook: StateFlow<Book?> = bookRepo.getLastReadBook()
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try { databaseSeeder.seedIfNeeded() } catch (e: Exception) {
                AppLog.warn("Shelf", "Tag seeder failed: ${e.message}")
            }
        }
        // ── Phase 2 enrichment 自愈补跑 ──
        //
        // 大部头 EPUB 导入时的封面/元数据补全跑在导入方 scope 里且无重试，进程死亡 /
        // scope 取消会让书永远停在「文件名标题 + 无封面」。启动后补一轮把它修回来。
        // 延迟几秒：候选存在时每本要完整开一次 EPUB（数秒），别跟书架首帧抢 IO；
        // 进程级 once 门在 repairMissingEnrichment 内部，VM 重建不会重复跑。
        viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(3_000)
            try { importEngine.repairMissingEnrichment() } catch (e: Exception) {
                AppLog.warn("Shelf", "Enrich repair failed: ${e.message}")
            }
        }
        // ── ImportStateBus → FolderImportState 单向回填 ──
        //
        // ImportService 通过 ImportStateBus 广播 Phase1/Phase2/Done 进度。这里把
        // bus 状态映射回 controller 的 FolderImportState，让 ShelfScreen 现有
        // Banner 订阅不变。bus.state 是 StateFlow 默认从 Idle 开始；用户从未触发
        // 过导入时 mapBusToFolderImportState 走 Idle 分支保留 controller 现状
        // （比如 importLocalBook 留下的"已导入 xxx"文案不会被覆盖）。
        viewModelScope.launch {
            com.morealm.app.domain.sync.ImportStateBus.state.collect { busState ->
                val mapped = mapBusToFolderImportState(busState) ?: return@collect
                import.setFolderImportState(mapped)
                if (busState is com.morealm.app.domain.sync.ImportState.Done) {
                    busState.focusFolderId?.let { folderId ->
                        _importFocusTarget.value = ShelfImportFocusTarget.FolderTarget(folderId)
                    }
                }
            }
        }
    }

    /**
     * Bus state → UI state 映射。返回 null 表示"别覆盖当前 FolderImportState"
     * （Idle 在用户没主动触发文件夹导入时不该 reset 单本 import 留下的提示）。
     */
    private fun mapBusToFolderImportState(busState: com.morealm.app.domain.sync.ImportState): FolderImportState? {
        return when (busState) {
            is com.morealm.app.domain.sync.ImportState.Idle -> null
            is com.morealm.app.domain.sync.ImportState.Scanning -> FolderImportState(
                running = true,
                folderName = busState.folderName,
                phase = ImportPhase.Scanning,
                message = "正在扫描：${busState.folderName}",
            )
            is com.morealm.app.domain.sync.ImportState.Phase1 -> FolderImportState(
                running = !busState.cancelled,
                folderName = busState.folderName,
                imported = busState.imported,
                total = busState.total,
                importedCount = busState.imported,
                phase = ImportPhase.Phase1,
                message = if (busState.cancelled) "正在取消…" else "已导入 ${busState.imported} / ${busState.total} 本",
            )
            is com.morealm.app.domain.sync.ImportState.Phase2 -> FolderImportState(
                running = true,
                folderName = busState.folderName,
                imported = busState.total, // Phase1 已全部入库，imported 锁定在终值
                total = busState.total,
                importedCount = busState.total,
                phase = ImportPhase.Phase2,
                message = "补元数据：${busState.enriched} / ${busState.total}",
            )
            is com.morealm.app.domain.sync.ImportState.Done -> FolderImportState(
                running = false,
                folderName = busState.folderName,
                imported = busState.imported,
                total = busState.imported,
                importedCount = busState.imported,
                phase = ImportPhase.Done,
                message = if (busState.cancelled) "已取消，共导入 ${busState.imported} 本"
                else "导入完成：${busState.imported} 本（耗时 ${busState.durationMs / 1000}s）",
            )
            is com.morealm.app.domain.sync.ImportState.Error -> FolderImportState(
                running = false,
                folderName = busState.folderName,
                phase = ImportPhase.Error,
                message = "导入失败",
                error = busState.message,
            )
        }
    }

    val resumeLastRead: StateFlow<Boolean> = prefs.resumeLastRead
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * 进程级标记：本次 Activity 生命周期内，「启动后继续阅读」是否已经触发过。
     *
     * # 死循环根因
     * 原实现用 `remember { mutableStateOf(false) }` 持有 hasResumed —
     * ShelfScreen 跳到 ReaderScreen 时退出组合，按返回回到书架后 remember 重置为
     * false，LaunchedEffect 立即再次 fire → 进入同一本书 → 返回 → 再进 → 死循环。
     *
     * 修复：状态搬到 ViewModel（同一 NavBackStackEntry 下存活），首次触发后置 true，
     * 后续重组直接被 hasResumedOnLaunch 拦截。
     */
    private var hasResumedOnLaunch: Boolean = false
    fun markResumedOnLaunch() { hasResumedOnLaunch = true }
    fun shouldResumeOnLaunch(): Boolean = !hasResumedOnLaunch

    fun resumeLastRead(onNavigate: (String) -> Unit) {
        viewModelScope.launch {
            val book = lastReadBook.value ?: return@launch
            onNavigate(book.id)
        }
    }

    private val _booksLoaded = MutableStateFlow(false)
    val booksLoaded: StateFlow<Boolean> = _booksLoaded.asStateFlow()

    val folderImportState: StateFlow<FolderImportState> = import.folderImportState
    fun clearFolderImportMessage() = import.clearFolderImportMessage()

    /**
     * 用户点"取消导入"调用。设置 cancel flag → ImportEngine 在 chunk 边界消费 →
     * 当前 chunk 跑完后 emit Phase1(cancelled=true) → 切到 Done(cancelled=true)。
     */
    fun requestCancelFolderImport() {
        com.morealm.app.domain.sync.ImportStateBus.requestCancel()
    }

    /**
     * Done / Error 状态停留 N 秒后清状态，让 Banner 自动隐藏。UI 用 LaunchedEffect
     * 配 delay 调本函数即可。
     */
    fun resetFolderImportState() {
        com.morealm.app.domain.sync.ImportStateBus.reset()
        import.setFolderImportState(FolderImportState())
    }

    /** 仅消费仍为同一实例的请求，避免滚动期间到达的新导入目标被旧协程清掉。 */
    fun consumeImportFocusTarget(target: ShelfImportFocusTarget) {
        _importFocusTarget.compareAndSet(target, null)
    }

    val allGroups: StateFlow<List<BookGroup>> = groupRepo.getAllGroups()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val groupNames: StateFlow<Map<String, String>> = allGroups
        .map { groups -> groups.associate { it.id to it.name } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    // ── 书架 tab 自定义分组（与文件夹体系并存，见 ShelfGroup KDoc）──

    /** Eagerly：tab 条参与书架首帧，Lazily 会闪一下「只有全部」。 */
    val shelfGroups: StateFlow<List<ShelfGroup>> = shelfGroupRepo.getAllGroups()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** groupId → 成员 bookId 集。全量订阅（百级行数），切 tab 零查询延迟。 */
    val shelfGroupBookIds: StateFlow<Map<String, Set<String>>> = shelfGroupRepo.getAllRelations()
        .map { rels -> rels.groupBy({ it.groupId }, { it.bookId }).mapValues { it.value.toSet() } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** 被用户隐藏的预置智能 tab key（reading/wanted/finished）；「全部」不可隐藏。 */
    val hiddenSmartTabs: StateFlow<Set<String>> = prefs.shelfHiddenSmartTabs
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    fun createShelfGroup(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val nextOrder = (shelfGroups.value.maxOfOrNull { it.sortOrder } ?: 0) + 1
            shelfGroupRepo.insert(
                ShelfGroup(
                    id = java.util.UUID.randomUUID().toString(),
                    name = trimmed,
                    sortOrder = nextOrder,
                )
            )
        }
    }

    fun renameShelfGroup(groupId: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val group = shelfGroups.value.find { it.id == groupId } ?: return@launch
            shelfGroupRepo.insert(group.copy(name = trimmed))
        }
    }

    fun deleteShelfGroup(groupId: String) {
        viewModelScope.launch(Dispatchers.IO) { shelfGroupRepo.deleteGroup(groupId) }
    }

    fun addBooksToShelfGroup(groupId: String, bookIds: Collection<String>) {
        if (bookIds.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            shelfGroupRepo.addBooks(groupId, bookIds.toList())
        }
    }

    fun removeBooksFromShelfGroup(groupId: String, bookIds: Collection<String>) {
        if (bookIds.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            shelfGroupRepo.removeBooks(groupId, bookIds.toList())
        }
    }

    fun setShelfSmartTabHidden(key: String, hidden: Boolean) {
        viewModelScope.launch { prefs.setShelfSmartTabHidden(key, hidden) }
    }

    private val _navigateToFolder = MutableSharedFlow<String?>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val navigateToFolder: SharedFlow<String?> = _navigateToFolder.asSharedFlow()

    fun requestNavigateToFolder(folderId: String?) {
        viewModelScope.launch { _navigateToFolder.emit(folderId) }
    }

    /**
     * 书架排序模式 — 持久化到 [AppPreferences.shelfSortMode]，重启 App 后恢复。
     *
     * `stateIn` 用 Eagerly 立即拉取首值，避免 books 流首次 emit 时 sortMode 还是初始值
     * 触发一次"先按 title 排，紧接着切到用户偏好排"的视觉抖动。
     */
    val sortMode: StateFlow<String> = prefs.shelfSortMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, "recent")

    fun setSortMode(mode: String) {
        viewModelScope.launch { prefs.setShelfSortMode(mode) }
    }

    /**
     * 书架视图模式（"grid" / "list"）— 持久化到 [AppPreferences.shelfViewMode]，重启
     * App 后恢复。旧实现用 ShelfScreen 内的 `rememberSaveable` 仅活在 Bundle 里，冷
     * 启动会回退到默认 "grid"，用户切到列表视图后下次打开又得手动切。
     *
     * Eagerly 立即拉首值，避免首帧用 "grid" 默认值绘制后再切到 "list" 的视觉跳。
     */
    val shelfViewMode: StateFlow<String> = prefs.shelfViewMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, "grid")

    /** 系统文件选择器下次启动位置；文件 URI 与 tree URI 均由 DocumentsUI 官方支持。 */
    val lastImportLocationUri: StateFlow<String> = prefs.lastImportLocationUri
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    fun setShelfViewMode(mode: String) {
        viewModelScope.launch { prefs.setShelfViewMode(mode) }
    }

    fun rememberImportLocation(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            prefs.setLastImportLocationUri(uri.toString())
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val books: StateFlow<List<Book>> = sortMode.flatMapLatest { sort ->
        bookRepo.getAllBooks().map { list ->
            withContext(Dispatchers.Default) {
                sortBooks(list, sort)
            }
        }
    }.onEach { _booksLoaded.value = true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val folderBookCounts: StateFlow<Map<String, Int>> = books
        .map { list ->
            withContext(Dispatchers.Default) {
                list.filter { it.folderId != null }
                    .groupBy { it.folderId!! }
                    .mapValues { it.value.size }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyMap())

    val folderCoverUrls: StateFlow<Map<String, List<String?>>> = books
        .map { list ->
            withContext(Dispatchers.Default) {
                list.filter { it.folderId != null }
                    .groupBy { it.folderId!! }
                    .mapValues { entry ->
                        entry.value.take(4).map { it.coverUrl }
                    }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyMap())

    private fun sortBooks(list: List<Book>, sort: String): List<Book> = when (sort) {
        "recent" -> list.sortedByDescending { it.lastReadAt }
        "addTime" -> list.sortedByDescending { it.addedAt }
        // 走 Schwartzian transform：title 只 tokenize 一次，比 sortedWith(compareBy(...))
        // 在大书架（千书）+ 含中文数字解析时显著省时。
        "format" -> list.sortedNaturalWith(compareBy { it.format.name }) { it.title }
        else -> list.sortedNaturalBy { it.title }
    }

    fun togglePinBook(bookId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val book = bookRepo.getById(bookId) ?: return@launch
            bookRepo.update(book.copy(pinned = !book.pinned))
        }
    }

    fun togglePinFolder(folderId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val group = groupRepo.getById(folderId) ?: return@launch
            groupRepo.insert(group.copy(pinned = !group.pinned))
        }
    }

    fun setCustomBookCover(bookId: String, sourceUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val book = bookRepo.getById(bookId) ?: return@launch
            val savedUri = coverStorage.saveCover(
                sourceUri,
                com.morealm.app.domain.cover.CoverKind.BOOK,
                bookId,
            ) ?: return@launch
            bookRepo.update(book.copy(customCoverUrl = savedUri))
        }
    }

    fun clearCustomBookCover(bookId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val book = bookRepo.getById(bookId) ?: return@launch
            coverStorage.deleteCover(com.morealm.app.domain.cover.CoverKind.BOOK, bookId)
            bookRepo.update(book.copy(customCoverUrl = null))
        }
    }

    fun setCustomGroupCover(groupId: String, sourceUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val group = groupRepo.getById(groupId) ?: return@launch
            val savedUri = coverStorage.saveCover(
                sourceUri,
                com.morealm.app.domain.cover.CoverKind.GROUP,
                groupId,
            ) ?: return@launch
            groupRepo.insert(group.copy(customCoverUrl = savedUri))
        }
    }

    fun clearCustomGroupCover(groupId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val group = groupRepo.getById(groupId) ?: return@launch
            coverStorage.deleteCover(com.morealm.app.domain.cover.CoverKind.GROUP, groupId)
            groupRepo.insert(group.copy(customCoverUrl = null))
        }
    }

    fun importLocalBook(uri: Uri) {
        _importFocusTarget.value = null
        import.importLocalBook(uri)
    }

    fun importFolder(uri: Uri) {
        _importFocusTarget.value = null
        import.importFolder(uri)
    }

    // ── Search (Flow-based) ──
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val searchResults: StateFlow<List<Book>> = _searchQuery
        .debounce(300)
        .flatMapLatest { q ->
            if (q.isBlank()) flowOf(emptyList())
            else flow { emit(withContext(Dispatchers.IO) { bookRepo.searchBooks(q) }) }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun setSearchQuery(query: String) { _searchQuery.value = query }

    fun deleteFolder(folderId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val group = groupRepo.getById(folderId)
            if (group?.auto == true && folderId.startsWith("auto:")) {
                val tagId = folderId.removePrefix("auto:")
                prefs.addAutoFolderIgnored(tagId)
                AppLog.info("Shelf", "Ignoring future auto-folder for tag $tagId")
            }
            coverStorage.deleteCover(com.morealm.app.domain.cover.CoverKind.GROUP, folderId)
            bookRepo.deleteFolder(folderId)
            AppLog.info("Shelf", "Deleted folder: $folderId")
        }
    }

    /**
     * 批量删除分组（连同分组里的书一起从 DB 删除，但保留本地文件 / 封面文件以支持撤销）。
     *
     * 与单选 [deleteFolder] 的差异：
     *  - 不调 [coverStorage.deleteCover]，分组封面 file 保留 → 撤销时显示完整
     *  - auto: 前缀的分组照样写 ignore，避免下次"立即整理"又把它建回来；
     *    [restoreFolders] 会把对应 ignore 移除。
     *  - 与 batchDeleteSoft 同一思路（DB 立删，文件延迟），让 Snackbar 撤销期内零代价恢复。
     */
    fun batchDeleteFolders(folderIds: Set<String>) {
        if (folderIds.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            folderIds.forEach { fid ->
                val group = groupRepo.getById(fid)
                if (group?.auto == true && fid.startsWith("auto:")) {
                    prefs.addAutoFolderIgnored(fid.removePrefix("auto:"))
                }
                bookRepo.deleteFolder(fid)
            }
            AppLog.info("Shelf", "Batch deleted ${folderIds.size} folders (covers retained)")
        }
    }

    /**
     * 撤销批量删除分组：先 re-insert groups，再 re-insert 该批 books。
     * auto: 分组同时把上一步写进 prefs 的 ignored tagId 撤掉，
     * 否则下次"立即整理"还会把分组吃掉。
     */
    fun restoreFolders(groups: List<BookGroup>, books: List<Book>) {
        if (groups.isEmpty() && books.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                groups.forEach { g ->
                    groupRepo.insert(g)
                    if (g.auto && g.id.startsWith("auto:")) {
                        prefs.removeAutoFolderIgnored(g.id.removePrefix("auto:"))
                    }
                }
                if (books.isNotEmpty()) bookRepo.insertAll(books)
                AppLog.info("Shelf", "Restored ${groups.size} folders + ${books.size} books")
            } catch (e: Exception) {
                AppLog.warn("Shelf", "Restore folders failed: ${e.message}")
            }
        }
    }

    fun batchDelete(bookIds: Set<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            bookIds.forEach { id ->
                coverStorage.deleteCover(com.morealm.app.domain.cover.CoverKind.BOOK, id)
                bookRepo.deleteById(id)
            }
            AppLog.info("Shelf", "Batch deleted ${bookIds.size} books")
        }
    }

    fun batchDeleteSoft(bookIds: Set<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            bookIds.forEach { id -> bookRepo.deleteById(id) }
            AppLog.info("Shelf", "Batch soft-deleted ${bookIds.size} books (covers retained)")
        }
    }

    fun restoreBooks(books: List<Book>) {
        if (books.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                bookRepo.insertAll(books)
                AppLog.info("Shelf", "Restored ${books.size} books")
            } catch (e: Exception) {
                AppLog.warn("Shelf", "Restore failed: ${e.message}")
            }
        }
    }

    fun commitCoverDeletion(bookIds: Set<String>) {
        if (bookIds.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            bookIds.forEach { id ->
                coverStorage.deleteCover(com.morealm.app.domain.cover.CoverKind.BOOK, id)
            }
        }
    }

    fun createGroup(name: String, keywords: String = "") {
        viewModelScope.launch(Dispatchers.IO) {
            val nextOrder = (groupRepo.getAllGroupsSync().maxOfOrNull { it.sortOrder } ?: 0) + 1
            groupRepo.insert(
                BookGroup(
                    id = java.util.UUID.randomUUID().toString(),
                    name = name,
                    sortOrder = nextOrder,
                    autoKeywords = keywords.takeIf { it.isNotBlank() } ?: "",
                )
            )
        }
    }

    fun renameGroup(groupId: String, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val group = groupRepo.getById(groupId) ?: return@launch
            groupRepo.insert(group.copy(name = newName))
        }
    }

    fun updateGroup(groupId: String, newName: String, keywords: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val group = groupRepo.getById(groupId) ?: return@launch
            groupRepo.insert(group.copy(name = newName, autoKeywords = keywords.takeIf { it.isNotBlank() } ?: ""))
        }
    }

    fun updateGroup(group: BookGroup) {
        viewModelScope.launch(Dispatchers.IO) {
            groupRepo.insert(group)
        }
    }

    fun moveToGroup(bookIds: Set<String>, targetFolderId: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            bookIds.forEach { id ->
                val book = bookRepo.getById(id) ?: return@forEach
                bookRepo.update(book.copy(folderId = targetFolderId))
            }
        }
    }

    fun reclassifyUngroupedBooks() = organize.reclassifyUngroupedBooks()

    val isOrganizing: StateFlow<Boolean> = organize.isOrganizing
    val organizeReport: StateFlow<String?> = organize.organizeReport
    fun consumeOrganizeReport() = organize.consumeOrganizeReport()

    fun organizeShelf() = organize.organizeShelf()

    // ── Refresh ──
    val isRefreshing: StateFlow<Boolean> = refreshController.isRefreshing
    val refreshProgress: StateFlow<Pair<Int, Int>> = refreshController.progress
    val refreshErrorCount: StateFlow<Int> = refreshController.errorCount

    fun refreshAllBooks() = refreshController.refresh(books.value)
    fun cancelRefresh() = refreshController.cancel()

    fun clearNewChapterBadge(bookId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            bookRepo.clearLastCheckCount(bookId)
        }
    }

    // ── Cache download ──
    val isCacheDownloading: StateFlow<Boolean> = cacheRepo.isDownloading
    val downloadProgress: StateFlow<com.morealm.app.service.CacheBookService.DownloadProgress> =
        cacheRepo.progresses.map { it.values.firstOrNull() ?: com.morealm.app.service.CacheBookService.DownloadProgress() }
            .stateIn(viewModelScope, SharingStarted.Lazily, com.morealm.app.service.CacheBookService.DownloadProgress())

    fun startCacheBook(bookId: String, sourceUrl: String) {
        cacheRepo.startDownload(bookId, sourceUrl)
    }

    fun stopCacheBook() {
        cacheRepo.stopDownload()
    }

    // ── Update badges ──
    val groupHasUpdate: StateFlow<Map<String, Boolean>> = books
        .map { list ->
            withContext(Dispatchers.Default) {
                list.asSequence()
                    .filter { it.folderId != null && it.lastCheckCount > 0 }
                    .map { it.folderId!! }
                    .toSet()
                    .associateWith { true }
            }
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val hasAnyUpdate: StateFlow<Boolean> = books
        .map { list -> list.any { it.lastCheckCount > 0 } }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // ── Auto-refresh on cold start ──
    init {
        viewModelScope.launch {
            books.first { it.isNotEmpty() }
            delay(5_000L)
            refreshAllBooks()
        }
    }
}
