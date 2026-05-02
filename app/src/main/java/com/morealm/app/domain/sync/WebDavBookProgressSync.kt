package com.morealm.app.domain.sync

import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.entity.ReadProgress
import com.morealm.app.domain.preference.AppPreferences
import com.morealm.app.domain.repository.BackupRepository
import com.morealm.app.domain.repository.BookRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-book reading progress sync over WebDav.
 *
 * Storage layout on remote: `<webDavDir>/bookProgress/<bookId>.json`.
 *
 * Two entry points:
 *  - [maybeUpload]    fired by the reader after a successful local save;
 *                     no-ops unless [AppPreferences.syncBookProgress] is
 *                     enabled, and skips the network round-trip when the
 *                     chapter index hasn't moved since the last upload
 *                     for the same book (so scrolling within a chapter
 *                     doesn't spam PUTs).
 *  - [downloadAll]    called once per app start; lists every progress
 *                     JSON, fetches each, and applies the merge rule
 *                     "remote wins iff strictly newer cursor AND newer
 *                     timestamp than local" — symmetrical to Legado.
 *
 * All work runs on the caller's dispatcher; both methods swallow their
 * own exceptions and log so a transient network blip doesn't propagate
 * up into the reader UI.
 */
@Singleton
class WebDavBookProgressSync @Inject constructor(
    private val prefs: AppPreferences,
    private val backupRepo: BackupRepository,
    private val bookRepo: BookRepository,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * Per-book "last uploaded chapter index". Backed by a concurrent map
     * because the reader can switch books and call from different
     * coroutines. The map only holds bookIds active in the current
     * process — losing it on app restart is fine, [downloadAll] gets
     * called instead.
     */
    private val lastUploadedChapter = ConcurrentHashMap<String, Int>()

    /** Single-flight guard so two rapid chapter changes don't race a PUT. */
    private val uploadMutex = Mutex()

    /**
     * Reader hook: called from `ReaderProgressController.saveProgress()`
     * on every successful DB save. Cheap when sync is off.
     */
    suspend fun maybeUpload(book: Book, progress: ReadProgress) {
        if (!prefs.syncBookProgress.first()) return
        if (lastUploadedChapter[book.id] == progress.chapterIndex) return
        upload(book, progress)
    }

    /** Force an upload regardless of the per-chapter throttle. */
    suspend fun upload(book: Book, progress: ReadProgress) {
        val client = createClient() ?: return
        val dir = currentDir()
        val progressDir = WebDavLayout.subPath(dir, WebDavLayout.SUBDIR_BOOK_PROGRESS)
        uploadMutex.withLock {
            try {
                client.mkdir(progressDir)
                val payload = BookProgress.from(book, progress)
                val bytes = json.encodeToString(payload).toByteArray()
                client.upload(
                    "$progressDir/${book.id}.json",
                    bytes,
                    "application/json",
                )
                lastUploadedChapter[book.id] = progress.chapterIndex
                AppLog.info("WebDAV", "Progress uploaded for ${book.title} @ ch ${progress.chapterIndex}")
            } catch (e: Exception) {
                AppLog.error("WebDAV", "Progress upload failed for ${book.id}: ${e.message}", e)
            }
        }
    }

    /**
     * App-start sweep: fetch every progress JSON, merge the ones that
     * represent strictly-fresher reading than what we have locally.
     *
     * @return number of books updated.
     */
    suspend fun downloadAll(): Int {
        if (!prefs.syncBookProgress.first()) return 0
        val client = createClient() ?: return 0
        val dir = currentDir()
        val progressDir = WebDavLayout.subPath(dir, WebDavLayout.SUBDIR_BOOK_PROGRESS)
        val files = try {
            client.listFiles(progressDir)
        } catch (e: Exception) {
            AppLog.warn("WebDAV", "list bookProgress failed: ${e.message}")
            return 0
        }
        var merged = 0
        for (file in files) {
            if (file.isDirectory || !file.name.endsWith(".json")) continue
            try {
                val raw = client.download("$progressDir/${file.name}")
                if (raw.isEmpty()) continue
                val remote = json.decodeFromString<BookProgress>(String(raw))
                val local = bookRepo.getById(remote.bookId) ?: continue
                if (shouldMerge(local, remote)) {
                    bookRepo.update(
                        local.copy(
                            lastReadChapter = remote.chapterIndex,
                            lastReadPosition = remote.chapterPosition,
                            readProgress = remote.totalProgress,
                            lastReadAt = remote.updatedAt,
                        )
                    )
                    lastUploadedChapter[remote.bookId] = remote.chapterIndex
                    merged++
                }
            } catch (e: Exception) {
                AppLog.warn("WebDAV", "Progress merge skipped for ${file.name}: ${e.message}")
            }
        }
        if (merged > 0) AppLog.info("WebDAV", "Merged $merged remote book progresses")
        return merged
    }

    /**
     * Merge predicate. The remote wins only when it represents strictly
     * fresher reading: a later chapter, OR same chapter but later
     * position, AND its wall-clock is newer than ours. The wall-clock
     * tiebreaker prevents an older device with stale progress from
     * silently rolling back a newer device's bookmark on app start.
     */
    private fun shouldMerge(local: Book, remote: BookProgress): Boolean {
        if (remote.updatedAt <= local.lastReadAt) return false
        return remote.chapterIndex > local.lastReadChapter ||
            (remote.chapterIndex == local.lastReadChapter &&
                remote.chapterPosition > local.lastReadPosition)
    }

    private suspend fun createClient(): WebDavClient? {
        val url = prefs.webDavUrl.first()
        val user = prefs.webDavUser.first()
        val pass = prefs.webDavPass.first()
        if (url.isBlank()) return null
        return backupRepo.createWebDavClient(url, user, pass)
    }

    private suspend fun currentDir(): String =
        WebDavLayout.normalizeDir(prefs.webDavDir.first())
}
