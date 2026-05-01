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

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /**
     * Single-flight guard: serialises every backup / restore entry point so
     * concurrent invocations queue instead of racing the database.
     */
    private val mutex = Mutex()

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

    // ── Public entry points ───────────────────────────────────────────────

    /** Export backup to a SAF Uri (local file). */
    suspend fun exportBackup(context: Context, db: AppDatabase, outputUri: Uri): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching {
                    val bytes = zipBackup(buildBackupData(db))
                    context.contentResolver.openOutputStream(outputUri)?.use { it.write(bytes) }
                        ?: error("openOutputStream returned null for $outputUri")
                    AppLog.info("Backup", "Export completed (${bytes.size} bytes)")
                    true
                }.getOrElse {
                    AppLog.error("Backup", "Export failed: ${it.message}")
                    false
                }
            }
        }

    /** Import backup from a SAF Uri. */
    suspend fun importBackup(context: Context, db: AppDatabase, inputUri: Uri): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching {
                    val text = context.contentResolver.openInputStream(inputUri)?.use {
                        readBackupJson(it)
                    } ?: ""
                    if (text.isEmpty()) return@runCatching false
                    val data = json.decodeFromString<BackupData>(text)
                    applyBackup(db, data)
                    AppLog.info("Backup", "Import completed")
                    true
                }.getOrElse {
                    AppLog.error("Backup", "Import failed: ${it.message}")
                    false
                }
            }
        }

    /** Generate backup as a ByteArray — used by WebDav upload. */
    suspend fun generateBackupBytes(@Suppress("UNUSED_PARAMETER") context: Context, db: AppDatabase): ByteArray? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching { zipBackup(buildBackupData(db)) }
                    .getOrElse {
                        AppLog.error("Backup", "Generate bytes failed: ${it.message}")
                        null
                    }
            }
        }

    /** Apply a backup ByteArray — used by WebDav restore. */
    suspend fun importBackupFromBytes(@Suppress("UNUSED_PARAMETER") context: Context, db: AppDatabase, data: ByteArray): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching {
                    val text = ByteArrayInputStream(data).use { readBackupJson(it) }
                    if (text.isEmpty()) return@runCatching false
                    val parsed = json.decodeFromString<BackupData>(text)
                    applyBackup(db, parsed)
                    AppLog.info("Backup", "Import from bytes completed")
                    true
                }.getOrElse {
                    AppLog.error("Backup", "Import from bytes failed: ${it.message}")
                    false
                }
            }
        }

    // ── Shared core ───────────────────────────────────────────────────────

    /**
     * Build the [BackupData] payload from the database.
     *
     * Both export paths (SAF + WebDav) call this single function so the
     * persisted field set can never diverge between the two — the previous
     * `generateBackupBytes()` was missing themes / reader styles, causing
     * silent loss on multi-device WebDav restore.
     */
    private suspend fun buildBackupData(db: AppDatabase): BackupData {
        val bookDao = db.bookDao()
        val bookmarkDao = db.bookmarkDao()
        val sourceDao = db.bookSourceDao()
        val progressDao = db.readProgressDao()
        val groupDao = db.bookGroupDao()
        val replaceRuleDao = db.replaceRuleDao()
        val themeDao = db.themeDao()
        val readerStyleDao = db.readerStyleDao()

        return BackupData(
            books = json.encodeToString(bookDao.getAllBooksSync()),
            bookmarks = json.encodeToString(bookmarkDao.getAllSync()),
            sources = json.encodeToString(sourceDao.getEnabledSourcesList()),
            progress = json.encodeToString(progressDao.getAllSync()),
            groups = json.encodeToString(groupDao.getAllGroupsSync()),
            replaceRules = json.encodeToString(replaceRuleDao.getAllSync()),
            themes = json.encodeToString(themeDao.getAllSync()),
            readerStyles = json.encodeToString(readerStyleDao.getAllSync()),
            // httpTts intentionally left empty here — restore handles missing
            // string transparently. P1 will widen the field set; this commit
            // only fixes the export-path divergence bug.
        )
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
