package com.morealm.app.domain.repository

import android.content.Context
import android.net.Uri
import com.morealm.app.domain.db.AppDatabase
import com.morealm.app.domain.preference.AppPreferences
import com.morealm.app.domain.sync.BackupManager
import com.morealm.app.domain.sync.WebDavClient
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    /**
     * Injected so the manager can dump / restore DataStore preferences as
     * part of the v3 backup format. Optional from the manager's POV
     * (test paths can pass null), but here it's always present because
     * Hilt resolves it from the application graph.
     */
    private val prefs: AppPreferences,
) {
    suspend fun testWebDav(url: String, user: String, pass: String): Boolean {
        val client = WebDavClient(url.trimEnd('/'), user, pass)
        return client.exists("")
    }

    suspend fun exportBackup(uri: Uri, password: String = ""): Boolean =
        BackupManager.exportBackup(context, db, uri, password, prefs = prefs)

    /**
     * Selective export — only the categories whose flag is `true` in [options]
     * land in the .zip. UI-side ProfileViewModel maps the user's "导出选项"
     * toggles into [BackupManager.BackupOptions] and passes it here.
     */
    suspend fun exportBackup(uri: Uri, password: String, options: BackupManager.BackupOptions): Boolean =
        BackupManager.exportBackup(context, db, uri, password, options, prefs)

    /** Per-category preview rows (count + estimated json bytes) for the export-options page. */
    suspend fun previewBackupSections(): List<BackupManager.BackupSectionInfo> =
        BackupManager.previewBackupSections(db, prefs)

    suspend fun importBackup(uri: Uri, password: String = ""): Boolean =
        BackupManager.importBackup(context, db, uri, password, prefs = prefs)

    /**
     * Selective restore — only sections whose flag is `true` in [opts] are
     * applied. UI side maps the restore-options-page checkboxes into
     * [BackupManager.RestoreOptions] and routes through this method.
     */
    suspend fun importBackup(uri: Uri, password: String, opts: BackupManager.RestoreOptions): Boolean =
        BackupManager.importBackup(context, db, uri, password, opts, prefs)

    /**
     * Per-category preview rows for the **restore** options page.
     * Reads the SAF [uri] once into memory, decrypts if needed, then reports
     * `(label, itemCount, conflictCount)` per section so the user can decide.
     */
    suspend fun previewRestoreSections(uri: Uri, password: String = ""): List<BackupManager.RestoreSectionInfo> {
        val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return emptyList()
        return BackupManager.previewRestoreSections(db, raw, password)
    }

    suspend fun generateBackupBytes(password: String = ""): ByteArray? =
        BackupManager.generateBackupBytes(context, db, password, prefs)

    suspend fun importBackupFromBytes(data: ByteArray, password: String = ""): Boolean =
        BackupManager.importBackupFromBytes(context, db, data, password, prefs = prefs)

    /** Selective WebDav restore — same contract as the SAF [importBackup]. */
    suspend fun importBackupFromBytes(data: ByteArray, password: String, opts: BackupManager.RestoreOptions): Boolean =
        BackupManager.importBackupFromBytes(context, db, data, password, opts, prefs)

    /**
     * Read-and-clear the most recent backup error diagnostic. Call this immediately
     * after a [exportBackup] / [importBackup] / [generateBackupBytes] /
     * [importBackupFromBytes] returned `false` / null to surface the underlying
     * exception message to the user (toast / status field) instead of a generic
     * "导出失败" with no actionable detail.
     */
    fun consumeLastBackupError(): String? = BackupManager.consumeLastErrorMessage()

    fun createWebDavClient(url: String, user: String, pass: String): WebDavClient =
        WebDavClient(url.trimEnd('/'), user, pass)
}
