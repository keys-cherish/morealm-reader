package com.morealm.app.domain.sync

import android.content.Context
import android.net.Uri
import com.morealm.app.domain.db.AppDatabase
import com.morealm.app.core.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Local + WebDav backup/restore — exports books, bookmarks, read progress,
 * sources, groups, replace rules, themes and reader styles as a ZIP file
 * containing one `backup.json` entry.
 *
 * Both the SAF (`exportBackup` / `importBackup`) and the WebDav
 * (`generateBackupBytes` / `importBackupFromBytes`) entry points share the
 * SAME [buildBackupData] / [applyBackup] core so the field set can no longer
 * drift between them — that drift was a P0 silent-data-loss bug where WebDav
 * uploads silently shipped without the user's themes and reader styles.
 *
 * All four entry points are guarded by a single object-level [mutex] so a
 * double-tap (or a backup colliding with a restore) cannot corrupt the local
 * database or upload a half-read snapshot to the cloud.
 */
object BackupManager {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        // Tolerate NaN / Infinity in Float fields (e.g. Book.lastReadOffset can become
        // NaN when a reader-progress row was saved before any layout completed). Without
        // this flag, kotlinx-serialization throws IllegalArgumentException("Unexpected
        // NaN value...") which used to silently abort the entire export through
        // `runCatching` — the user only saw a 0-byte file. The flag round-trips the
        // value as the literal string "NaN"/"Infinity" so restore is also lenient.
        allowSpecialFloatingPointValues = true
    }

    /**
     * Single-flight guard: serialises every backup / restore entry point so
     * concurrent invocations queue instead of racing the database.
     */
    private val mutex = Mutex()

    /**
     * Last error surfaced by any of the four entry points, set when an exception
     * was caught and used to be silently swallowed by `runCatching`. Read-and-
     * clear via [consumeLastErrorMessage] so the UI layer can show a meaningful
     * "导出失败：xxx" instead of a generic "导出失败".
     *
     * Single string is safe because [mutex] serialises every entry point — there
     * is at most one in-flight backup at a time.
     */
    @Volatile private var lastErrorMessage: String? = null

    /**
     * Read and clear the most recent failure message. The clear-on-read keeps
     * stale errors from leaking into a later (successful) operation's status.
     */
    fun consumeLastErrorMessage(): String? {
        val m = lastErrorMessage
        lastErrorMessage = null
        return m
    }

    /** Capture the diagnostic from a thrown exception in a UX-friendly way. */
    private fun recordError(prefix: String, e: Throwable) {
        lastErrorMessage = "${e.javaClass.simpleName}: ${e.message ?: "(no message)"}"
        AppLog.error("Backup", "$prefix: ${e.javaClass.simpleName}: ${e.message}", e)
    }

    @Serializable
    data class BackupData(
        val version: Int = 2,
        val timestamp: Long = System.currentTimeMillis(),
        val books: String = "",
        val bookmarks: String = "",
        val sources: String = "",
        val progress: String = "",
        val groups: String = "",
        val replaceRules: String = "",
        val themes: String = "",
        val readerStyles: String = "",
        val httpTts: String = "",
    )

    /**
     * Per-category opt-in for [exportBackup]. Default = everything ON, matching
     * the legacy "export everything" behaviour. The "导出选项" page in
     * ProfileScreen flips individual flags off when the user unchecks a
     * category. Categories that are off are written as empty strings in the
     * BackupData json (the restore path already skips empty fields, so old
     * backups stay forward-compatible).
     */
    data class BackupOptions(
        val includeBooks: Boolean = true,
        val includeBookmarks: Boolean = true,
        val includeSources: Boolean = true,
        val includeProgress: Boolean = true,
        val includeGroups: Boolean = true,
        val includeReplaceRules: Boolean = true,
        val includeThemes: Boolean = true,
        val includeReaderStyles: Boolean = true,
    ) {
        /** Quick "did the user disable anything?" probe used by the UI for the summary line. */
        fun isFullExport(): Boolean = includeBooks && includeBookmarks && includeSources &&
            includeProgress && includeGroups && includeReplaceRules &&
            includeThemes && includeReaderStyles
    }

    /**
     * Per-category preview row shown on the export-options page so the user
     * can decide whether each section is worth exporting (e.g. "300 个书源
     * = 200 KB" might be worth dropping if the user just wants their books).
     *
     * @param key stable identifier matching [BackupOptions] field, also used
     *            as the toggle key in ProfileViewModel.backupSelections.
     * @param label Chinese, user-facing label.
     * @param itemCount how many rows the section currently has in the DB.
     * @param estimatedBytes JSON-serialized size of the section's full payload —
     *                       so the displayed size matches what actually goes
     *                       into the .zip if the user keeps the default selection.
     */
    data class BackupSectionInfo(
        val key: String,
        val label: String,
        val itemCount: Int,
        val estimatedBytes: Int,
    )

    // ── Public entry points ───────────────────────────────────────────────

    /**
     * Export backup to a SAF Uri (local file).
     *
     * @param password optional AES-GCM password; non-empty wraps the zip
     *                 with [BackupCrypto.encrypt]. Empty = legacy plain
     *                 zip, which is forward-compatible with restore code
     *                 detecting the magic header.
     */
    suspend fun exportBackup(
        context: Context,
        db: AppDatabase,
        outputUri: Uri,
        password: String = "",
        options: BackupOptions = BackupOptions(),
    ): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                lastErrorMessage = null
                runCatching {
                    val plainZip = zipBackup(buildBackupData(db, options))
                    val finalBytes = if (password.isNotEmpty()) {
                        BackupCrypto.encrypt(plainZip, password)
                    } else plainZip
                    context.contentResolver.openOutputStream(outputUri)?.use { it.write(finalBytes) }
                        ?: error("openOutputStream returned null for $outputUri")
                    AppLog.info("Backup", "Export completed (${finalBytes.size} bytes, encrypted=${password.isNotEmpty()}, full=${options.isFullExport()})")
                    true
                }.getOrElse {
                    recordError("Export failed", it)
                    runCatching {
                        context.contentResolver.delete(outputUri, null, null)
                    }.onFailure { delErr ->
                        AppLog.warn("Backup", "Could not remove 0-byte placeholder: ${delErr.message}")
                    }
                    false
                }
            }
        }

    /**
     * Import backup from a SAF Uri.
     *
     * Auto-detects encryption: if the first bytes match `MoREncBk`, the
     * blob is decrypted with [password] before unzipping. A wrong (or
     * blank) password against an encrypted blob returns false with an
     * `AppLog.warn` rather than crashing.
     */
    suspend fun importBackup(
        context: Context,
        db: AppDatabase,
        inputUri: Uri,
        password: String = "",
    ): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                lastErrorMessage = null
                runCatching {
                    val raw = context.contentResolver.openInputStream(inputUri)?.use { it.readBytes() }
                        ?: ByteArray(0)
                    val plainZip = unwrapMaybeEncrypted(raw, password) ?: return@runCatching false
                    val text = ByteArrayInputStream(plainZip).use { readBackupJson(it) }
                    if (text.isEmpty()) return@runCatching false
                    val data = json.decodeFromString<BackupData>(text)
                    applyBackup(db, data)
                    AppLog.info("Backup", "Import completed")
                    true
                }.getOrElse {
                    recordError("Import failed", it)
                    false
                }
            }
        }

    /** Generate backup as a ByteArray — used by WebDav upload. */
    suspend fun generateBackupBytes(
        @Suppress("UNUSED_PARAMETER") context: Context,
        db: AppDatabase,
        password: String = "",
    ): ByteArray? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                lastErrorMessage = null
                runCatching {
                    val plain = zipBackup(buildBackupData(db))
                    if (password.isNotEmpty()) BackupCrypto.encrypt(plain, password) else plain
                }.getOrElse {
                    recordError("Generate bytes failed", it)
                    null
                }
            }
        }

    /** Apply a backup ByteArray — used by WebDav restore. */
    suspend fun importBackupFromBytes(
        @Suppress("UNUSED_PARAMETER") context: Context,
        db: AppDatabase,
        data: ByteArray,
        password: String = "",
    ): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                lastErrorMessage = null
                runCatching {
                    val plain = unwrapMaybeEncrypted(data, password) ?: return@runCatching false
                    val text = ByteArrayInputStream(plain).use { readBackupJson(it) }
                    if (text.isEmpty()) return@runCatching false
                    val parsed = json.decodeFromString<BackupData>(text)
                    applyBackup(db, parsed)
                    AppLog.info("Backup", "Import from bytes completed")
                    true
                }.getOrElse {
                    recordError("Import from bytes failed", it)
                    false
                }
            }
        }

    /**
     * If [raw] looks encrypted, decrypt with [password] (returns null if
     * decryption fails — caller treats as restore-failed). Otherwise
     * return [raw] unchanged so legacy plain-zip backups still work.
     */
    private fun unwrapMaybeEncrypted(raw: ByteArray, password: String): ByteArray? {
        if (!BackupCrypto.isEncrypted(raw)) return raw
        if (password.isEmpty()) {
            AppLog.warn("Backup", "Encrypted backup but no password provided")
            return null
        }
        val plain = BackupCrypto.decrypt(raw, password)
        if (plain == null) {
            AppLog.warn("Backup", "Decryption failed — wrong password or tampered blob")
        }
        return plain
    }

    // ── Shared core ───────────────────────────────────────────────────────

    /**
     * Build the [BackupData] payload from the database.
     *
     * Both export paths (SAF + WebDav) call this single function so the
     * persisted field set can never diverge between the two — the previous
     * `generateBackupBytes()` was missing themes / reader styles, causing
     * silent loss on multi-device WebDav restore.
     *
     * Categories disabled in [options] are written as empty strings; the
     * restore path's `if (foo.isNotBlank()) ...` guards already skip those.
     */
    private suspend fun buildBackupData(
        db: AppDatabase,
        options: BackupOptions = BackupOptions(),
    ): BackupData {
        val bookDao = db.bookDao()
        val bookmarkDao = db.bookmarkDao()
        val sourceDao = db.bookSourceDao()
        val progressDao = db.readProgressDao()
        val groupDao = db.bookGroupDao()
        val replaceRuleDao = db.replaceRuleDao()
        val themeDao = db.themeDao()
        val readerStyleDao = db.readerStyleDao()

        return BackupData(
            books = if (options.includeBooks) json.encodeToString(bookDao.getAllBooksSync()) else "",
            bookmarks = if (options.includeBookmarks) json.encodeToString(bookmarkDao.getAllSync()) else "",
            sources = if (options.includeSources) json.encodeToString(sourceDao.getEnabledSourcesList()) else "",
            progress = if (options.includeProgress) json.encodeToString(progressDao.getAllSync()) else "",
            groups = if (options.includeGroups) json.encodeToString(groupDao.getAllGroupsSync()) else "",
            replaceRules = if (options.includeReplaceRules) json.encodeToString(replaceRuleDao.getAllSync()) else "",
            themes = if (options.includeThemes) json.encodeToString(themeDao.getAllSync()) else "",
            readerStyles = if (options.includeReaderStyles) json.encodeToString(readerStyleDao.getAllSync()) else "",
            // httpTts intentionally left empty here — restore handles missing
            // string transparently. P1 will widen the field set; this commit
            // only fixes the export-path divergence bug.
        )
    }

    /**
     * Compute per-category preview rows for the export-options page.
     *
     * `estimatedBytes` is the **ZIP-compressed** byte count (DEFLATE) of that
     * section's JSON payload, so the value displayed to the user closely
     * matches what actually lands inside the final .zip. Previously this
     * field reported raw JSON length, which inflated the displayed total
     * 5-10x compared to the real export — a 700 KB JSON body typically zips
     * down to ~120 KB. Wrapping overhead (single-entry zip header,
     * encryption header) is not included; the sum across selected sections
     * is therefore a slight upper bound vs. one combined zip (which can
     * share the deflate dictionary across sections), but it tracks the
     * actual file size much better than the old raw-JSON estimate.
     *
     * Reads each table once on Dispatchers.IO; all eight queries are sync
     * Room calls already used by [buildBackupData], so cost mirrors a
     * regular full export's read phase.
     */
    suspend fun previewBackupSections(db: AppDatabase): List<BackupSectionInfo> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching {
                    val books = db.bookDao().getAllBooksSync()
                    val bookmarks = db.bookmarkDao().getAllSync()
                    val sources = db.bookSourceDao().getEnabledSourcesList()
                    val progress = db.readProgressDao().getAllSync()
                    val groups = db.bookGroupDao().getAllGroupsSync()
                    val replaceRules = db.replaceRuleDao().getAllSync()
                    val themes = db.themeDao().getAllSync()
                    val readerStyles = db.readerStyleDao().getAllSync()

                    listOf(
                        BackupSectionInfo("books", "书籍", books.size, zippedSize(json.encodeToString(books))),
                        BackupSectionInfo("bookmarks", "书签", bookmarks.size, zippedSize(json.encodeToString(bookmarks))),
                        BackupSectionInfo("sources", "书源", sources.size, zippedSize(json.encodeToString(sources))),
                        BackupSectionInfo("progress", "阅读进度", progress.size, zippedSize(json.encodeToString(progress))),
                        BackupSectionInfo("groups", "分组", groups.size, zippedSize(json.encodeToString(groups))),
                        BackupSectionInfo("replaceRules", "替换规则", replaceRules.size, zippedSize(json.encodeToString(replaceRules))),
                        BackupSectionInfo("themes", "主题", themes.size, zippedSize(json.encodeToString(themes))),
                        BackupSectionInfo("readerStyles", "阅读样式", readerStyles.size, zippedSize(json.encodeToString(readerStyles))),
                    )
                }.getOrElse {
                    recordError("Preview sections failed", it)
                    emptyList()
                }
            }
        }

    /**
     * Zip a single JSON string into a one-entry archive (DEFLATE, default
     * level) and return the resulting byte count. Used by
     * [previewBackupSections] so the preview UI reports compressed sizes
     * comparable to the real export. Cheap: pure in-memory, ~100 ms even
     * for a couple of MB of source text.
     */
    private fun zippedSize(jsonStr: String): Int {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("section.json"))
            zos.write(jsonStr.toByteArray())
            zos.closeEntry()
        }
        return baos.size()
    }

    /**
     * Apply a [BackupData] payload to the database.
     *
     * Both restore paths (SAF + WebDav) share this. Empty / blank string fields
     * are skipped so old backup zips that pre-date a new field still restore
     * cleanly; deserialisation errors per section are isolated so one bad
     * field cannot abort the whole restore.
     */
    private suspend fun applyBackup(db: AppDatabase, backup: BackupData) {
        val bookDao = db.bookDao()
        val bookmarkDao = db.bookmarkDao()
        val sourceDao = db.bookSourceDao()
        val progressDao = db.readProgressDao()
        val groupDao = db.bookGroupDao()
        val replaceRuleDao = db.replaceRuleDao()

        // Books are mandatory — let parse failure propagate so the user gets
        // a real error instead of "imported 0 books, all good".
        val books = json.decodeFromString<List<com.morealm.app.domain.entity.Book>>(backup.books)
        bookDao.insertAll(books)

        if (backup.bookmarks.isNotBlank()) runCatching {
            json.decodeFromString<List<com.morealm.app.domain.entity.Bookmark>>(backup.bookmarks)
                .forEach { bookmarkDao.insert(it) }
        }.onFailure { AppLog.error("Backup", "bookmarks decode failed: ${it.message}") }

        if (backup.sources.isNotBlank()) runCatching {
            val sources = json.decodeFromString<List<com.morealm.app.domain.entity.BookSource>>(backup.sources)
            sourceDao.insertAll(sources)
        }.onFailure { AppLog.error("Backup", "sources decode failed: ${it.message}") }

        if (backup.progress.isNotBlank()) runCatching {
            json.decodeFromString<List<com.morealm.app.domain.entity.ReadProgress>>(backup.progress)
                .forEach { progressDao.save(it) }
        }.onFailure { AppLog.error("Backup", "progress decode failed: ${it.message}") }

        if (backup.groups.isNotBlank()) runCatching {
            json.decodeFromString<List<com.morealm.app.domain.entity.BookGroup>>(backup.groups)
                .forEach { groupDao.insert(it) }
        }.onFailure { AppLog.error("Backup", "groups decode failed: ${it.message}") }

        if (backup.replaceRules.isNotBlank()) runCatching {
            json.decodeFromString<List<com.morealm.app.domain.entity.ReplaceRule>>(backup.replaceRules)
                .forEach { replaceRuleDao.insert(it) }
        }.onFailure { AppLog.error("Backup", "replaceRules decode failed: ${it.message}") }

        if (backup.themes.isNotBlank()) runCatching {
            val themes = json.decodeFromString<List<com.morealm.app.domain.entity.ThemeEntity>>(backup.themes)
            db.themeDao().upsertAll(themes)
        }.onFailure { AppLog.error("Backup", "themes decode failed: ${it.message}") }

        if (backup.readerStyles.isNotBlank()) runCatching {
            val styles = json.decodeFromString<List<com.morealm.app.domain.entity.ReaderStyle>>(backup.readerStyles)
            db.readerStyleDao().upsertAll(styles)
        }.onFailure { AppLog.error("Backup", "readerStyles decode failed: ${it.message}") }

        AppLog.info("Backup", "Applied: ${books.size} books restored")
    }

    // ── Zip helpers ───────────────────────────────────────────────────────

    /** Encode [BackupData] as JSON and wrap it in a single-entry zip. */
    private fun zipBackup(data: BackupData): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("backup.json"))
            zos.write(json.encodeToString(data).toByteArray())
            zos.closeEntry()
        }
        return baos.toByteArray()
    }

    /** Read the `backup.json` entry from a zip [InputStream]; returns "" if absent. */
    private fun readBackupJson(stream: InputStream): String {
        ZipInputStream(stream).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == "backup.json") {
                    return zis.bufferedReader().readText()
                }
                entry = zis.nextEntry
            }
        }
        return ""
    }
}
