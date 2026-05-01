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
    /** Display filename (e.g. `三体.epub`). */
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
     * List every book-shaped file under `<webDavDir>/books/`. Returns an
     * empty list when WebDav is unconfigured / unreachable / the dir
     * doesn't exist (creates it lazily on the first browse, matching the
     * way `BackupManager` lazily creates the backup root).
     */
    suspend fun listBooks(): List<RemoteBookFile> {
        val client = createClient() ?: run {
            AppLog.info("RemoteBook", "WebDav unconfigured; returning empty list")
            return emptyList()
        }
        val dir = booksDir()
        return try {
            // Make sure the dir exists so a first-run user doesn't see a
            // hard 404. mkdir against an existing collection is a no-op.
            runCatching { client.mkdir(dir) }

            client.listFiles(dir)
                .filter { !it.isDirectory && it.name.substringAfterLast('.', "")
                    .lowercase() in bookExtensions }
                .map {
                    RemoteBookFile(
                        name = it.name,
                        remotePath = "$dir/${it.name}",
                        size = it.size,
                        lastModifiedEpoch = it.lastModifiedEpoch,
                        lastModified = it.lastModified,
                    )
                }
                .sortedByDescending { it.lastModifiedEpoch }
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
