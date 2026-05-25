package com.morealm.app.domain.sync

import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.preference.AppPreferences
import com.morealm.app.domain.repository.BackupRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single book / archive entry sitting on the user's WebDav server.
 *
 * Mirrors Legado's `RemoteBook`: anything matching the supported book
 * file regex is exposed in the cloud-bookshelf UI for one-tap download.
 */
data class RemoteBookFile(
    /** Display filename (e.g. `示例书 A.epub`). */
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
 * Why a dedicated `books/` subdir rather than the root: Legado's
 * `RemoteBookWebDav` hardcodes the same convention so users coming from
 * Legado find their existing books in the expected place. The user can
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

    /** Subdir where book files live; mirrors Legado for cross-tool migration. */
    suspend fun booksDir(): String {
        val root = prefs.webDavDir.first().ifBlank { "MoRealm" }.trim('/')
        return "$root/books"
    }

    private suspend fun createClient(): WebDavClient? {
        val url = prefs.webDavUrl.first()
        val user = prefs.webDavUser.first()
        val pass = prefs.webDavPass.first()
        if (url.isBlank()) return null
        return backupRepo.createWebDavClient(url, user, pass)
    }
}
