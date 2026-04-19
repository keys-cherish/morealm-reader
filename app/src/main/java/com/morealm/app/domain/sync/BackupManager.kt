package com.morealm.app.domain.sync

import android.content.Context
import android.net.Uri
import com.morealm.app.domain.db.AppDatabase
import com.morealm.app.core.log.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Local backup/restore — exports books, bookmarks, read progress, sources, and settings
 * as a ZIP file containing JSON data.
 */
object BackupManager {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

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

    suspend fun exportBackup(context: Context, db: AppDatabase, outputUri: Uri): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val bookDao = db.bookDao()
                val bookmarkDao = db.bookmarkDao()
                val sourceDao = db.bookSourceDao()
                val progressDao = db.readProgressDao()
                val groupDao = db.bookGroupDao()
                val replaceRuleDao = db.replaceRuleDao()

                // PLACEHOLDER_EXPORT
                val books = json.encodeToString(bookDao.getAllBooksSync())
                val bookmarks = json.encodeToString(bookmarkDao.getAllSync())
                val sources = json.encodeToString(sourceDao.getEnabledSourcesList())
                val progress = json.encodeToString(progressDao.getAllSync())
                val groups = json.encodeToString(groupDao.getAllGroupsSync())
                val replaceRules = json.encodeToString(replaceRuleDao.getAllSync())
                val themes = json.encodeToString(db.themeDao().getAllSync())
                val readerStyles = json.encodeToString(db.readerStyleDao().getAllSync())

                val backup = BackupData(
                    books = books,
                    bookmarks = bookmarks,
                    sources = sources,
                    progress = progress,
                    groups = groups,
                    replaceRules = replaceRules,
                    themes = themes,
                    readerStyles = readerStyles,
                )

                context.contentResolver.openOutputStream(outputUri)?.use { out ->
                    ZipOutputStream(out).use { zos ->
                        zos.putNextEntry(ZipEntry("backup.json"))
                        zos.write(json.encodeToString(backup).toByteArray())
                        zos.closeEntry()
                    }
                }
                AppLog.info("Backup", "Export completed")
                true
            } catch (e: Exception) {
                AppLog.error("Backup", "Export failed: ${e.message}")
                false
            }
        }

    suspend fun importBackup(context: Context, db: AppDatabase, inputUri: Uri): Boolean =
        withContext(Dispatchers.IO) {
            try {
                var backupJson = ""
                context.contentResolver.openInputStream(inputUri)?.use { stream ->
                    ZipInputStream(stream).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            if (entry.name == "backup.json") {
                                backupJson = zis.bufferedReader().readText()
                                break
                            }
                            entry = zis.nextEntry
                        }
                    }
                }
                if (backupJson.isEmpty()) return@withContext false

                val backup = json.decodeFromString<BackupData>(backupJson)
                val bookDao = db.bookDao()
                val bookmarkDao = db.bookmarkDao()
                val sourceDao = db.bookSourceDao()
                val progressDao = db.readProgressDao()
                val groupDao = db.bookGroupDao()
                val replaceRuleDao = db.replaceRuleDao()

                // Import books (skip duplicates by ID)
                val books = json.decodeFromString<List<com.morealm.app.domain.entity.Book>>(backup.books)
                bookDao.insertAll(books)

                if (backup.bookmarks.isNotEmpty()) {
                    val bookmarks = json.decodeFromString<List<com.morealm.app.domain.entity.Bookmark>>(backup.bookmarks)
                    bookmarks.forEach { bookmarkDao.insert(it) }
                }

                if (backup.sources.isNotEmpty()) {
                    val sources = json.decodeFromString<List<com.morealm.app.domain.entity.BookSource>>(backup.sources)
                    sourceDao.insertAll(sources)
                }

                if (backup.progress.isNotEmpty()) {
                    val progress = json.decodeFromString<List<com.morealm.app.domain.entity.ReadProgress>>(backup.progress)
                    progress.forEach { progressDao.save(it) }
                }

                if (backup.groups.isNotEmpty()) {
                    val groups = json.decodeFromString<List<com.morealm.app.domain.entity.BookGroup>>(backup.groups)
                    groups.forEach { groupDao.insert(it) }
                }

                if (backup.replaceRules.isNotEmpty()) {
                    val rules = json.decodeFromString<List<com.morealm.app.domain.entity.ReplaceRule>>(backup.replaceRules)
                    rules.forEach { replaceRuleDao.insert(it) }
                }

                if (backup.themes.isNotEmpty()) {
                    try {
                        val themes = json.decodeFromString<List<com.morealm.app.domain.entity.ThemeEntity>>(backup.themes)
                        db.themeDao().upsertAll(themes)
                    } catch (_: Exception) {}
                }

                if (backup.readerStyles.isNotEmpty()) {
                    try {
                        val styles = json.decodeFromString<List<com.morealm.app.domain.entity.ReaderStyle>>(backup.readerStyles)
                        db.readerStyleDao().upsertAll(styles)
                    } catch (_: Exception) {}
                }

                AppLog.info("Backup", "Import completed: ${books.size} books")
                true
            } catch (e: Exception) {
                AppLog.error("Backup", "Import failed: ${e.message}")
                false
            }
        }

    /** Generate backup as byte array (for WebDAV upload) */
    suspend fun generateBackupBytes(context: Context, db: AppDatabase): ByteArray? =
        withContext(Dispatchers.IO) {
            try {
                val bookDao = db.bookDao()
                val bookmarkDao = db.bookmarkDao()
                val sourceDao = db.bookSourceDao()
                val progressDao = db.readProgressDao()
                val groupDao = db.bookGroupDao()
                val replaceRuleDao = db.replaceRuleDao()

                val backup = BackupData(
                    books = json.encodeToString(bookDao.getAllBooksSync()),
                    bookmarks = json.encodeToString(bookmarkDao.getAllSync()),
                    sources = json.encodeToString(sourceDao.getEnabledSourcesList()),
                    progress = json.encodeToString(progressDao.getAllSync()),
                    groups = json.encodeToString(groupDao.getAllGroupsSync()),
                    replaceRules = json.encodeToString(replaceRuleDao.getAllSync()),
                )

                val baos = ByteArrayOutputStream()
                ZipOutputStream(baos).use { zos ->
                    zos.putNextEntry(ZipEntry("backup.json"))
                    zos.write(json.encodeToString(backup).toByteArray())
                    zos.closeEntry()
                }
                baos.toByteArray()
            } catch (e: Exception) {
                AppLog.error("Backup", "Generate bytes failed: ${e.message}")
                null
            }
        }

    /** Import backup from byte array (for WebDAV restore) */
    suspend fun importBackupFromBytes(context: Context, db: AppDatabase, data: ByteArray): Boolean =
        withContext(Dispatchers.IO) {
            try {
                var backupJson = ""
                ZipInputStream(ByteArrayInputStream(data)).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (entry.name == "backup.json") {
                            backupJson = zis.bufferedReader().readText()
                            break
                        }
                        entry = zis.nextEntry
                    }
                }
                if (backupJson.isEmpty()) return@withContext false

                val backup = json.decodeFromString<BackupData>(backupJson)
                val bookDao = db.bookDao()
                val bookmarkDao = db.bookmarkDao()
                val sourceDao = db.bookSourceDao()
                val progressDao = db.readProgressDao()
                val groupDao = db.bookGroupDao()
                val replaceRuleDao = db.replaceRuleDao()

                val books = json.decodeFromString<List<com.morealm.app.domain.entity.Book>>(backup.books)
                bookDao.insertAll(books)

                if (backup.bookmarks.isNotEmpty()) {
                    val bookmarks = json.decodeFromString<List<com.morealm.app.domain.entity.Bookmark>>(backup.bookmarks)
                    bookmarks.forEach { bookmarkDao.insert(it) }
                }
                if (backup.sources.isNotEmpty()) {
                    val sources = json.decodeFromString<List<com.morealm.app.domain.entity.BookSource>>(backup.sources)
                    sourceDao.insertAll(sources)
                }
                if (backup.progress.isNotEmpty()) {
                    val progress = json.decodeFromString<List<com.morealm.app.domain.entity.ReadProgress>>(backup.progress)
                    progress.forEach { progressDao.save(it) }
                }
                if (backup.groups.isNotEmpty()) {
                    val groups = json.decodeFromString<List<com.morealm.app.domain.entity.BookGroup>>(backup.groups)
                    groups.forEach { groupDao.insert(it) }
                }
                if (backup.replaceRules.isNotEmpty()) {
                    val rules = json.decodeFromString<List<com.morealm.app.domain.entity.ReplaceRule>>(backup.replaceRules)
                    rules.forEach { replaceRuleDao.insert(it) }
                }

                AppLog.info("Backup", "Import from bytes completed: ${books.size} books")
                true
            } catch (e: Exception) {
                AppLog.error("Backup", "Import from bytes failed: ${e.message}")
                false
            }
        }
}
