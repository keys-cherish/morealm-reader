package com.morealm.app.domain.sync

import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.entity.BookFormat
import com.morealm.app.domain.preference.AppPreferences
import com.morealm.app.domain.repository.BackupRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single book / archive entry sitting on the user's WebDav server.
 *
 * Mirrors 参照实现 `RemoteBook`: anything matching the supported book
 * file regex is exposed in the cloud-bookshelf UI for one-tap download.
 */
data class RemoteBookFile(
    /** Display filename (e.g. `示例.epub`). */
    val name: String,
    /** Remote relative path used by [WebDavClient.download]. */
    val remotePath: String,
    /** File size in bytes. 0 = server didn't return getcontentlength. */
    val size: Long,
    /**
     * Server's getlastmodified parsed to epoch ms. 0 = unknown; the UI
     * uses this to sort newest-first and display "X 天前".
     */
    val lastModifiedEpoch: Long,
    /** Raw RFC 1123 string from server, kept for human-readable display. */
    val lastModified: String,
)

/**
 * Domain-layer browser for the user's WebDav cloud bookshelf.
 *
 * Read-only listing of book files in `<webDavDir>/books/` plus a
 * download-to-bytes helper. The screen consumes this through
 * [com.morealm.app.presentation.profile.RemoteBookViewModel].
 *
 * Why a dedicated `books/` subdir rather than the root: 参照实现
 * `RemoteBookWebDav` hardcodes the same convention so users coming from
 * 参照实现 find their existing books in the expected place. The user can
 * still override the parent dir via [AppPreferences.webDavDir].
 */
@Singleton
class RemoteBookManager @Inject constructor(
    private val prefs: AppPreferences,
    private val backupRepo: BackupRepository,
) {

    /** Files we recognise as book / archive containers. */
    private val bookExtensions = setOf(
        "epub", "txt", "umd", "mobi", "azw3", "pdf", "cbz",
        "zip", "rar", "7z",
    )

    /**
     * 递归列出 `<webDavDir>/books/` 下所有书形文件（含**任意层级子文件夹**）。
     *
     * 之前实现只调 `client.listFiles(dir)` Depth=1，子文件夹内的书直接被
     * `!it.isDirectory` filter 掉看不到（用户 2026-05-21 反馈）。现在按 BFS 递归：
     *  - 遇到目录 → 加入待扫队列继续 listFiles
     *  - 遇到书 → 加入结果，[RemoteBookFile.remotePath] 反映完整路径
     *  - [maxDepth] 防深递归 + 循环符号链接；常见个人 WebDAV 嵌套 ≤ 3 层，给 6 容错
     *
     * 返回时按 lastModified 排序，不保留目录结构（UI 用平铺列表展示）。WebDav
     * 未配置 / 不可达 / dir 不存在时返空列表（创建 dir 由首次浏览触发）。
     */
    @Deprecated("v2 改用 listOneLevel + bfsCollectFiles，删除日期：1.6 发布后")
    suspend fun listBooks(): List<RemoteBookFile> {
        val client = createClient() ?: run {
            AppLog.info("RemoteBook", "WebDav unconfigured; returning empty list")
            return emptyList()
        }
        val rootDir = booksDir()
        return try {
            // Make sure the dir exists so a first-run user doesn't see a
            // hard 404. mkdir against an existing collection is a no-op.
            runCatching { client.mkdir(rootDir) }

            val result = ArrayList<RemoteBookFile>()
            val queue = ArrayDeque<Pair<String, Int>>().apply { addLast(rootDir to 0) }
            val maxDepth = 6
            while (queue.isNotEmpty()) {
                val (dir, depth) = queue.removeFirst()
                if (depth > maxDepth) continue
                val entries = runCatching { client.listFiles(dir) }
                    .onFailure { AppLog.warn("RemoteBook", "listFiles $dir failed: ${it.message?.take(80)}") }
                    .getOrDefault(emptyList())
                for (e in entries) {
                    if (e.isDirectory) {
                        // 拼子目录绝对路径，下次 BFS 迭代继续扫
                        queue.addLast("$dir/${e.name}" to depth + 1)
                    } else {
                        val ext = e.name.substringAfterLast('.', "").lowercase()
                        if (ext in bookExtensions) {
                            result.add(
                                RemoteBookFile(
                                    name = e.name,
                                    remotePath = "$dir/${e.name}",
                                    size = e.size,
                                    lastModifiedEpoch = e.lastModifiedEpoch,
                                    lastModified = e.lastModified,
                                ),
                            )
                        }
                    }
                }
            }
            AppLog.info("RemoteBook", "recursive list found ${result.size} books root=$rootDir")
            result.sortedByDescending { it.lastModifiedEpoch }
        } catch (e: Exception) {
            AppLog.error("RemoteBook", "List failed: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Download the bytes of [file]. Caller is responsible for persisting
     * to disk + spawning a [com.morealm.app.domain.entity.Book] row.
     *
     * Throws [com.morealm.app.domain.sync.WebDavException] on transport
     * errors so the UI can surface the same friendly 401/404/500 messages
     * as the rest of the WebDav stack via [WebDavClient.describeError].
     */
    suspend fun download(file: RemoteBookFile): ByteArray {
        val client = createClient() ?: throw WebDavException("WebDav 未配置")
        return client.download(file.remotePath)
    }

    /**
     * 列单层目录（PROPFIND Depth=1，不递归）。
     *
     * 比 [listBooks] 快 N 倍：v2 远程书架打开页面只拉根目录一层（≤ 2s），用户点
     * 文件夹再拉子层（≤ 1s）。配合 ViewModel 的 SnapshotStateMap<path, list> 缓存
     * 实现 back/breadcrumb 跳跃无重发。
     *
     * 行为：
     *  - 目录条目 **全部保留**（无论名字，留给用户决定是否进入）
     *  - 文件条目 **仅** 命中 [bookExtensions] 才保留（隐藏 .DS_Store、.epub.bak 等噪音）
     *  - 排序：文件夹优先 + 时间倒序（最新修改在前），与 [listBooks] 排序一致
     *  - WebDav 未配置 / 任何异常 → 返回空列表（不抛，UI 端 banner 由调用方决定）
     */
    suspend fun listOneLevel(path: String): List<RemoteEntry> {
        val client = createClient() ?: run {
            AppLog.info("RemoteShelfV2", "WebDav unconfigured; listOneLevel($path) returns empty")
            return emptyList()
        }
        return listOneLevelWith(path, bookExtensions) { client.listFiles(it) }
    }

    /**
     * BFS 递归扫整棵子树，**缓冲池满 [bufferSize] 个文件就 emit 一批**，避免：
     *  - 5 万本目录全收完才 emit 一次 → UI 长时间空白 + 内存峰值爆炸
     *  - 每发现 1 个文件 emit 1 次 → 下游 importBatch 频繁开事务
     *
     * 并发模型：每层 PROPFIND 用 `Semaphore(5)` 限流（详 design.md "BFS Semaphore
     * vs Channel buffered"），单条目录失败不阻断兄弟节点（runCatching 吞异常 +
     * 日志），失败 path 由调用方从 banner 流程统计。
     *
     * 取消语义：上游 `collect` 取消 → flow 协程取消 → BFS 队列循环 break →
     * 已 emit 的 chunk 保留（下游 importBatch 已 insert）。
     */
    fun bfsCollectFiles(rootPath: String, bufferSize: Int = 200): Flow<List<RemoteBookFile>> = flow {
        val client = createClient() ?: run {
            AppLog.info("RemoteShelfV2", "WebDav unconfigured; bfsCollectFiles($rootPath) empty")
            return@flow
        }
        bfsCollectFilesWith(rootPath, bufferSize, bookExtensions) { client.listFiles(it) }
            .collect { emit(it) }
    }

    /**
     * 流式下载远程文件到本地 [outFile]。OkHttp byteStream → 文件 copy，全程
     * 不 hold 整本 ByteArray，3-5 并发 50MB 文件 ≈ 50MB 峰值（v1 的
     * [download] ByteArray 路径会到 200MB+）。
     *
     * Atomicity：任何异常都 [java.io.File.delete] 半写文件，避免下次 import
     * 误识别残骸为完整书。
     */
    suspend fun downloadStream(remotePath: String, outFile: File) = withContext(Dispatchers.IO) {
        val client = createClient() ?: throw WebDavException("WebDav 未配置")
        try {
            client.downloadStream(remotePath, outFile)
        } catch (e: Exception) {
            runCatching { outFile.delete() }
            throw e
        }
    }

    /** Subdir where book files live; mirrors 参照实现 for cross-tool migration. */
    suspend fun booksDir(): String {
        val root = prefs.webDavDir.first().ifBlank { "MoRealm" }.trim('/')
        return "$root/books"
    }

    /**
     * 确保 [booksDir] 在 WebDav 上已存在 —— 首次进入远程书架时调一次。
     *
     * Phase 1 重写 [listOneLevel] 时丢失了 V1 [listBooks] 内的 mkdir 兜底
     * (2026-05-28 user 真机回归)：若 `<webDavDir>/books/` 不存在，PROPFIND
     * 直接 404 → 空列表 → 用户看到「没有可显示的内容」。
     *
     * mkdir 对已存在 collection 是 no-op，所以反复调用安全；放在 VM
     * `openRoot` 一次性触发，避免每次 [listOneLevel] 都打这个请求。
     */
    suspend fun ensureBooksDir() {
        val client = createClient() ?: return
        val dir = booksDir()
        runCatching { client.mkdir(dir) }
            .onFailure {
                AppLog.warn(
                    "RemoteShelfV2",
                    "ensureBooksDir($dir) failed: ${it.message?.take(80)}",
                )
            }
    }

    private suspend fun createClient(): WebDavClient? {
        val url = prefs.webDavUrl.first()
        val user = prefs.webDavUser.first()
        val pass = prefs.webDavPass.first()
        if (url.isBlank()) return null
        return backupRepo.createWebDavClient(url, user, pass)
    }

}

/**
 * BFS PROPFIND 并发上限 —— 经验值 5：alist 等 WebDav 代理后端是 OkHttp/nginx，
 * 5 并发安全（再多触发后端限流 → 429 / 503）。详 design.md "BFS Semaphore
 * vs Channel buffered"。File-level const 让单测可直接引用，无需穿过
 * RemoteBookManager 实例（实例需 Hilt 注入的 BackupRepository / AppPreferences）。
 */
internal const val BFS_CONCURRENCY = 5

/**
 * 测试 seam：把 `listFiles` 调用抽成 lambda 让单测可注入假数据 / 错误，
 * 不必构造真 WebDavClient 或 RemoteBookManager 实例。生产路径见
 * [RemoteBookManager.listOneLevel]。
 */
internal suspend fun listOneLevelWith(
    path: String,
    bookExtensions: Set<String>,
    listFn: suspend (String) -> List<WebDavClient.DavFile>,
): List<RemoteEntry> = try {
    toEntriesFromDav(listFn(path), path, bookExtensions)
} catch (e: Exception) {
    AppLog.warn("RemoteShelfV2", "listOneLevel($path) failed: ${e.message?.take(80)}")
    emptyList()
}

/**
 * 纯函数把一批 [WebDavClient.DavFile] 转成 [RemoteEntry] —— 应用文件夹保留 +
 * 文件扩展名过滤 + 文件夹优先时间倒序排序。`internal` 暴露给单测覆盖三种数据
 * 形态（混合 / 排序 / 空），不依赖网络。
 */
internal fun toEntriesFromDav(
    davFiles: List<WebDavClient.DavFile>,
    dirPath: String,
    bookExtensions: Set<String>,
): List<RemoteEntry> {
    val out = ArrayList<RemoteEntry>(davFiles.size)
    for (e in davFiles) {
        if (e.isDirectory) {
            out.add(
                RemoteEntry.Folder(
                    name = e.name,
                    remotePath = "$dirPath/${e.name}",
                    lastModifiedEpoch = e.lastModifiedEpoch,
                ),
            )
        } else {
            val ext = e.name.substringAfterLast('.', "").lowercase()
            if (ext in bookExtensions) {
                out.add(
                    RemoteEntry.File(
                        name = e.name,
                        remotePath = "$dirPath/${e.name}",
                        size = e.size,
                        lastModifiedEpoch = e.lastModifiedEpoch,
                        format = detectFormat(ext),
                    ),
                )
            }
        }
    }
    return out.sortedWith(
        compareByDescending<RemoteEntry> { it is RemoteEntry.Folder }
            .thenByDescending {
                when (it) {
                    is RemoteEntry.Folder -> it.lastModifiedEpoch
                    is RemoteEntry.File -> it.lastModifiedEpoch
                }
            },
    )
}

/**
 * 测试 seam：BFS 实现核心，与 [RemoteBookManager.bfsCollectFiles] 同语义但接受
 * lambda 而非内部 client，让单测可注入假数据验证 emit 节奏 / Semaphore 限并发 /
 * 嵌套树平铺。
 */
internal fun bfsCollectFilesWith(
    rootPath: String,
    bufferSize: Int,
    bookExtensions: Set<String>,
    listFn: suspend (String) -> List<WebDavClient.DavFile>,
): Flow<List<RemoteBookFile>> = flow {
    val sema = Semaphore(BFS_CONCURRENCY)
    // 每层 BFS：当前层目录 → 并发 PROPFIND → 收集子目录 + 文件
    var currentLevel = listOf(rootPath)
    val buffer = ArrayList<RemoteBookFile>(bufferSize)
    while (currentLevel.isNotEmpty()) {
        val nextLevel = ArrayList<String>()
        val results = coroutineScope {
            currentLevel.map { dir ->
                async {
                    sema.acquire()
                    try {
                        runCatching { listFn(dir) }
                            .onFailure {
                                AppLog.warn(
                                    "RemoteShelfV2",
                                    "bfs listFiles $dir failed: ${it.message?.take(80)}",
                                )
                            }
                            .getOrDefault(emptyList()) to dir
                    } finally {
                        sema.release()
                    }
                }
            }.awaitAll()
        }
        for ((entries, dir) in results) {
            for (e in entries) {
                if (e.isDirectory) {
                    nextLevel.add("$dir/${e.name}")
                } else {
                    val ext = e.name.substringAfterLast('.', "").lowercase()
                    if (ext in bookExtensions) {
                        buffer.add(
                            RemoteBookFile(
                                name = e.name,
                                remotePath = "$dir/${e.name}",
                                size = e.size,
                                lastModifiedEpoch = e.lastModifiedEpoch,
                                lastModified = e.lastModified,
                            ),
                        )
                        if (buffer.size >= bufferSize) {
                            emit(buffer.toList())
                            buffer.clear()
                        }
                    }
                }
            }
        }
        currentLevel = nextLevel
    }
    if (buffer.isNotEmpty()) emit(buffer.toList())
}

/** 扩展名 → BookFormat 映射，给 [toEntriesFromDav] 用，UI 层不暴露。 */
private fun detectFormat(ext: String): BookFormat = when (ext) {
    "epub" -> BookFormat.EPUB
    "mobi" -> BookFormat.MOBI
    "azw3" -> BookFormat.AZW3
    "pdf" -> BookFormat.PDF
    "cbz" -> BookFormat.CBZ
    "umd" -> BookFormat.UMD
    "txt" -> BookFormat.TXT
    else -> BookFormat.UNKNOWN
}
