package com.morealm.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.documentfile.provider.DocumentFile
import com.morealm.app.core.log.AppLog
import com.morealm.app.di.ApplicationScope
import com.morealm.app.domain.entity.BookFormat
import com.morealm.app.domain.entity.BookGroup
import com.morealm.app.domain.repository.BookGroupRepository
import com.morealm.app.domain.storage.FastFileScanner
import com.morealm.app.domain.storage.StorageAccessHelper
import com.morealm.app.domain.sync.ImportEngine
import com.morealm.app.domain.sync.ImportState
import com.morealm.app.domain.sync.ImportStateBus
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * SAF 批量导入前台服务 —— v1.6 修复"6000 本一次导入书架为空"的根因。
 *
 * ## 为什么前台化
 *
 * 之前 [com.morealm.app.presentation.shelf.ShelfImportController.importFolder]
 * 直接 `viewModelScope.launch`，等同于把"跑 10-20 分钟的 IO 任务"绑到 UI 生命
 * 周期：用户切走 / 系统压内存 / OEM 杀进程 → Job 取消，4.35 GB 文件已落盘但
 * Book row 全丢。
 *
 * 改成 Foreground Service 后：
 *  - 持久通知 + DATA_SYNC 类型让系统给最高级前台优先级
 *  - [ApplicationScope] 跑 ImportEngine，不受 ShelfViewModel 生命周期约束
 *  - OEM 激进杀进程时仍能在通知栏看到进度，不会"导入跑没了"
 *
 * ## 通知策略
 *
 * - 频率：订阅 [ImportStateBus.state]，用 `sample(1.seconds)` 节流，最多 1 次/秒
 * - 内容：title="MoRealm 正在导入"，text="已导入 N / total 本（folderName）"
 * - 进度条：determinate `max=total, current=N`
 * - 点击：跳启动 Activity（用户回书架看新书）
 * - 不可滑动取消：`setOngoing(true) + setSilent(true)`，IMPORTANCE_LOW 不响铃
 *
 * ## 重入保护
 *
 * 第二次 startForegroundService 时检查 [ImportStateBus.state] 是否处于活跃
 * 状态（Scanning / Phase1 / Phase2），如是则 Toast 提示用户并立即 stopSelf。
 * 单 session 限制见 ImportStateBus KDoc。
 *
 * ## 与 [CacheBookService] / [CheckSourceService] 的关系
 *
 * 三者都是 dataSync 类型前台服务，channel id 互相独立（cache=morealm_cache,
 * check=morealm_check_source, import=morealm_import）。并行运行时通知栏会显示
 * 三条独立通知，不冲突。
 */
@AndroidEntryPoint
class ImportService : Service() {

    companion object {
        const val CHANNEL_ID = "morealm_import"
        const val NOTIFICATION_ID = 2003 // 不与 CacheBookService(2001) / CheckSourceService(2002) 冲突
        const val EXTRA_TREE_URI = "treeUri"

        /** 文件夹递归扫描深度上限。漫画压缩包通常 ≤ 5，给 10 容错。 */
        private const val MAX_FOLDER_IMPORT_DEPTH = 10

        /** 通知节流间隔：StateFlow emit 频率 50-200/min（取决于 chunk 速度），节流到 1/s 避免风暴。 */
        private const val NOTIFICATION_THROTTLE_MS = 1_000L
    }

    @Inject lateinit var importEngine: ImportEngine
    @Inject lateinit var groupRepo: BookGroupRepository
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private var mainJob: Job? = null
    private var notificationJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ── 重入保护 ──
        //
        // 第二次 startForegroundService 触发时若已有 import 在跑（state 非 Idle/Done/Error），
        // Toast 告知用户并立即退出。当前 v1.6 不支持多 session 并发（见 ImportStateBus KDoc）。
        val currentState = ImportStateBus.state.value
        if (currentState !is ImportState.Idle &&
            currentState !is ImportState.Done &&
            currentState !is ImportState.Error
        ) {
            AppLog.warn("ImportSvc", "Import already running, rejecting new request: $currentState")
            mainHandler.post {
                Toast.makeText(this, "已有导入任务在运行，请等待完成或取消", Toast.LENGTH_SHORT).show()
            }
            // 这里**不**调 stopForeground —— 前一次导入仍在跑，它的前台状态还得保留
            // stopSelfResult(startId) 只丢弃**本次** intent，不影响前次的 startId
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        // Android 13+ 兼容写法：getParcelableExtra 弃用 untyped 重载
        val treeUri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_TREE_URI, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_TREE_URI) as? Uri
        }
        if (treeUri == null) {
            AppLog.error("ImportSvc", "Missing treeUri extra, stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        // ── 启动前台 ──
        //
        // 必须在 onStartCommand 5s 内调 startForeground 否则 ANR。这里用占位通知
        // （folderName/total 未知），主任务跑起来扫描完后立即更新真实进度。
        val placeholder = buildNotification("MoRealm 正在导入", "准备扫描…", current = 0, total = 0)
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, placeholder,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )

        // 启动主任务（ApplicationScope，进程级，不受 Service onDestroy 影响）+ 通知节流监听
        startNotificationUpdater()
        startMainImport(treeUri)
        return START_NOT_STICKY
    }

    /**
     * 主任务：扫描 SAF folder → 决定层级（sub-folder / direct files / deep scan）
     * → 每段调 [ImportEngine.importBatch] 喂数据 → 全部完成后 emit Done + stopSelf。
     *
     * 该逻辑沿用原 ShelfImportController.importFolder 主循环，差异：
     *  - import 主循环跑在 [appScope]，Service 销毁不影响 ApplicationScope 协程
     *  - 用 [ImportEngine] 替代原 importFilesWithDeferredCovers，让 Phase 1 chunk 边界
     *    可见进度 + 可取消
     *  - 进度通过 [ImportStateBus] 广播给 UI + 通知栏，不再 _folderImportState 直推
     */
    private fun startMainImport(treeUri: Uri) {
        mainJob = appScope.launch {
            val startTime = System.currentTimeMillis()
            val folderName = resolveTreeName(treeUri)
            ImportStateBus.update(ImportState.Scanning(folderName))

            // 持久化 SAF 权限：用户重启 APP 后仍可读
            runCatching {
                contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }.onFailure { AppLog.warn("ImportSvc", "takePersistableUriPermission failed: ${it.message}") }
            runCatching {
                contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }.onFailure { AppLog.debug("ImportSvc", "write permission unavailable: ${it.message}") }

            try {
                val topChildren = FastFileScanner.listImmediateChildren(this@ImportService, treeUri)
                // 忽略 . 开头的隐藏目录（.git / .thumbnails / .trash 等系统目录，不含正文书）
                val subFolders = topChildren.filter { it.isDirectory && !it.name.startsWith(".") }
                val directFiles = topChildren
                    .filter { !it.isDirectory && detectFormat(it.name) != BookFormat.UNKNOWN }
                    .map { ch -> FastFileScanner.ScanItem(uri = ch.uri, name = ch.name, filePath = ch.filePath) }
                AppLog.info(
                    "ImportSvc",
                    "Scan done folder=$folderName sub=${subFolders.size} direct=${directFiles.size}",
                )

                // ── 预扫描算总数（让 Phase1 emit total 一开始就准确） ──
                //
                // 对 sub-folder 路径，先把每个 sub-folder 内的文件数加起来；direct files 单独算。
                // 这一步耗时跟 SAF 二次 scan 一致（cache 会复用 query），但收益是 UI 进度
                // 一开始就显示正确分母而不是"0 / 0"或"50 / 50"假满。
                val perSubFolder: List<Pair<BookGroup, List<FastFileScanner.ScanItem>>> =
                    if (subFolders.isNotEmpty()) {
                        subFolders.mapNotNull { folder ->
                            val descendants = FastFileScanner.scanFolder(
                                context = this@ImportService,
                                treeUri = folder.uri,
                                isWanted = { detectFormat(it) != BookFormat.UNKNOWN },
                                maxDepth = MAX_FOLDER_IMPORT_DEPTH,
                            )
                            if (descendants.isEmpty()) null
                            // stable id（localfolder:根名/子名）：重导同一子文件夹复用同组、
                            // 不再每次建随机 UUID 新组（DAO insert REPLACE 幂等）。与用户手建
                            // 组的 UUID id / auto: / webdav: 命名空间隔离。
                            else BookGroup(id = "localfolder:$folderName/${folder.name}", name = folder.name) to descendants
                        }
                    } else emptyList()

                val deepFiles: List<FastFileScanner.ScanItem> =
                    if (subFolders.isEmpty() && directFiles.isEmpty()) {
                        FastFileScanner.scanFolder(
                            context = this@ImportService,
                            treeUri = treeUri,
                            isWanted = { detectFormat(it) != BookFormat.UNKNOWN },
                            maxDepth = MAX_FOLDER_IMPORT_DEPTH,
                        )
                    } else emptyList()

                val grandTotal =
                    directFiles.size +
                    perSubFolder.sumOf { it.second.size } +
                    deepFiles.size

                if (grandTotal == 0) {
                    ImportStateBus.update(
                        ImportState.Done(
                            folderName = folderName,
                            imported = 0,
                            durationMs = System.currentTimeMillis() - startTime,
                        )
                    )
                    AppLog.info("ImportSvc", "Nothing to import in '$folderName'")
                    return@launch
                }

                // 切换到 Phase1 初始状态（0 / total），让 UI 立刻看到 progress bar
                ImportStateBus.update(
                    ImportState.Phase1(folderName = folderName, imported = 0, total = grandTotal)
                )

                // ── 喂给 ImportEngine ──
                //
                // 顺序：每个 sub-folder（创建 group → importBatch）→ direct files（无 group）
                // → deep scan（fallback，无 group）。progressOffset 累加让 Phase1 emit 数
                // 跨多次调用单调增长。
                var importedSoFar = 0
                var focusFolderId: String? = null
                for ((group, files) in perSubFolder) {
                    groupRepo.insert(group)
                    val result = importEngine.importBatch(
                        files = files,
                        folderId = group.id,
                        folderName = folderName, // emit 用整个根 folder 名（用户感知是"整批导入"）
                        progressOffset = importedSoFar,
                        totalForProgress = grandTotal,
                    )
                    val booksInGroup = result.phase1Inserted + result.regrouped + result.matchedInTargetGroup
                    if (booksInGroup == 0) {
                        // 新增、收编、同组 dedup 均为 0，才可判定本次稳定组没有对应书。
                        groupRepo.deleteById(group.id)
                    } else if (focusFolderId == null) {
                        focusFolderId = group.id
                    }
                    // imported 计数纳入 regrouped：收编的旧书也算「这次整理进组」，
                    // 否则全是已存在书时 done 显示 imported=0，用户误以为没成功。
                    importedSoFar += result.phase1Inserted + result.regrouped

                    // chunk 边界已检查 cancel；如果整个 batch 完成时 state 是 cancelled，
                    // 跳出 sub-folder 循环（剩下的不再 process）。
                    val s = ImportStateBus.state.value
                    if (s is ImportState.Phase1 && s.cancelled) break
                }

                // ── 顶层直接的书 + 深扫兜底 → 归入以「选中文件夹名」命名的组 ──
                //
                // 原先这两批 folderId=null 平铺散落（用户报「导入文件夹全是散落的文件」）。
                // 现按文件夹名建一个组：导入 X 文件夹 → 书架出现「X」组，文件夹内直接的书
                // 都进去；子文件夹仍各自成组（上面的 perSubFolder）。
                // directFiles 与 deepFiles 互斥（deepFiles 仅在 sub/direct 全空时才扫出），
                // 共用同一个 rootGroup；两者皆空（纯子文件夹结构）则不建根组。
                val rootGroupFiles = if (directFiles.isNotEmpty()) directFiles else deepFiles
                val cancelledBeforeRoot = (ImportStateBus.state.value as? ImportState.Phase1)?.cancelled == true
                if (!cancelledBeforeRoot && rootGroupFiles.isNotEmpty()) {
                    // stable id（localfolder:根名）：重导同名文件夹复用同一根组、不再
                    // 每次建新 UUID 组（避免「书在旧组、新建空组又被删、imported=0」）。
                    val rootGroup = BookGroup(id = "localfolder:$folderName", name = folderName)
                    groupRepo.insert(rootGroup)
                    val result = importEngine.importBatch(
                        files = rootGroupFiles,
                        folderId = rootGroup.id,
                        folderName = folderName,
                        progressOffset = importedSoFar,
                        totalForProgress = grandTotal,
                    )
                    val booksInGroup = result.phase1Inserted + result.regrouped + result.matchedInTargetGroup
                    if (booksInGroup == 0) {
                        // 新增、收编、同组 dedup 均为 0，才清理真正的空组。
                        groupRepo.deleteById(rootGroup.id)
                    } else if (focusFolderId == null) {
                        focusFolderId = rootGroup.id
                    }
                    // imported 计数纳入 regrouped：收编的旧书也算「这次整理进组」，
                    // 否则全是已存在书时 done 显示 imported=0，用户误以为没成功。
                    importedSoFar += result.phase1Inserted + result.regrouped
                }

                val wasCancelled = (ImportStateBus.state.value as? ImportState.Phase1)?.cancelled == true
                ImportStateBus.update(
                    ImportState.Done(
                        folderName = folderName,
                        imported = importedSoFar,
                        durationMs = System.currentTimeMillis() - startTime,
                        cancelled = wasCancelled,
                        focusFolderId = focusFolderId,
                    )
                )
                AppLog.info(
                    "ImportSvc",
                    "Folder import '$folderName' done: imported=$importedSoFar / total=$grandTotal " +
                        "cancelled=$wasCancelled in ${System.currentTimeMillis() - startTime}ms",
                )
            } catch (e: Exception) {
                AppLog.error("ImportSvc", "Import failed", e)
                ImportStateBus.update(
                    ImportState.Error(folderName = folderName, message = e.message ?: "未知错误")
                )
            } finally {
                // 不立刻停止 —— 给通知 / UI 看一眼"已完成 N 本"再退（约 2s）
                kotlinx.coroutines.delay(2_000)
                stopForegroundAndSelf()
            }
        }
    }

    /**
     * 订阅 [ImportStateBus.state]，节流后更新前台通知。
     *
     * `sample(NOTIFICATION_THROTTLE_MS)` 让 ImportEngine 每个 chunk 50 本 emit 一次
     * Phase1 时通知最多 1 次/秒更新——避免 NotificationManager 风暴 + 系统 ratelimit。
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun startNotificationUpdater() {
        notificationJob = appScope.launch {
            ImportStateBus.state
                .sample(NOTIFICATION_THROTTLE_MS)
                .collectLatest { state -> updateNotification(state) }
        }
    }

    private fun updateNotification(state: ImportState) {
        val (text, current, total) = when (state) {
            is ImportState.Idle -> Triple("准备中…", 0, 0)
            is ImportState.Scanning -> Triple("正在扫描：${state.folderName}", 0, 0)
            is ImportState.Phase1 -> {
                val suffix = if (state.cancelled) "（正在取消…）" else ""
                Triple(
                    "已导入 ${state.imported} / ${state.total} 本（${state.folderName}）$suffix",
                    state.imported,
                    state.total,
                )
            }
            is ImportState.Phase2 -> Triple(
                "正在补元数据：${state.enriched} / ${state.total}",
                state.enriched,
                state.total,
            )
            is ImportState.Done -> Triple(
                if (state.cancelled) "已取消，共导入 ${state.imported} 本"
                else "导入完成：${state.imported} 本",
                state.imported,
                state.imported.coerceAtLeast(1),
            )
            is ImportState.Error -> Triple("导入失败：${state.message.take(60)}", 0, 0)
        }
        val notification = buildNotification(
            title = "MoRealm 正在导入",
            text = text,
            current = current,
            total = total,
        )
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification)
        }
    }

    /** 拿用户选的根 tree 文件夹名 —— File API 失败 fallback 到 DocumentFile.fromTreeUri。 */
    private fun resolveTreeName(treeUri: Uri): String {
        StorageAccessHelper.treeUriToFilePath(treeUri)?.let { path ->
            return java.io.File(path).name.ifBlank { "导入文件夹" }
        }
        return DocumentFile.fromTreeUri(this, treeUri)?.name ?: "导入文件夹"
    }

    private fun detectFormat(filename: String): BookFormat {
        val ext = filename.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "txt" -> BookFormat.TXT
            "epub" -> BookFormat.EPUB
            "pdf" -> BookFormat.PDF
            "mobi" -> BookFormat.MOBI
            "azw3", "azw" -> BookFormat.AZW3
            "zip" -> BookFormat.CBZ
            "umd" -> BookFormat.UMD
            else -> BookFormat.UNKNOWN
        }
    }

    private fun stopForegroundAndSelf() {
        notificationJob?.cancel()
        notificationJob = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ── Notification ──

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "导入书籍",
                NotificationManager.IMPORTANCE_LOW, // LOW = 不响铃 / 不震动 / 不浮窗，仅状态栏图标
            ).apply { description = "批量导入文件夹时显示进度" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, text: String, current: Int, total: Int): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setProgress(total, current, total == 0)
            .setContentIntent(buildContentIntent())
            .build()
    }

    /**
     * 点通知跳启动 Activity，让用户回书架看新增的书。用 PackageManager 解析启动 Intent
     * 避免编译期硬依赖 MainActivity 类（参考 TtsNotificationProvider 同款做法）。
     */
    private fun buildContentIntent(): PendingIntent? {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        launchIntent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getActivity(this, 0, launchIntent, flags)
    }

    override fun onDestroy() {
        notificationJob?.cancel()
        // mainJob 跑在 appScope，**不**在这里 cancel —— 让导入跑完
        super.onDestroy()
    }
}
