package com.morealm.app.domain.repository

import android.content.Context
import android.net.Uri
import com.morealm.app.domain.db.AppDatabase
import com.morealm.app.domain.sync.BackupManager
import com.morealm.app.domain.sync.WebDavClient
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
) {
    suspend fun testWebDav(url: String, user: String, pass: String): Boolean {
        val client = WebDavClient(url.trimEnd('/'), user, pass)
        return client.exists("")
    }

    suspend fun exportBackup(uri: Uri, password: String = ""): Boolean =
        BackupManager.exportBackup(context, db, uri, password)

    /**
     * Selective export — only the categories whose flag is `true` in [options]
     * land in the .zip. UI-side ProfileViewModel maps the user's "导出选项"
     * toggles into [BackupManager.BackupOptions] and passes it here.
     */
    suspend fun exportBackup(uri: Uri, password: String, options: BackupManager.BackupOptions): Boolean =
        BackupManager.exportBackup(context, db, uri, password, options)

    /** Per-category preview rows (count + estimated json bytes) for the export-options page. */
    suspend fun previewBackupSections(): List<BackupManager.BackupSectionInfo> =
        BackupManager.previewBackupSections(db)

    suspend fun importBackup(uri: Uri, password: String = ""): Boolean =
        BackupManager.importBackup(context, db, uri, password)

    suspend fun generateBackupBytes(password: String = ""): ByteArray? =
        BackupManager.generateBackupBytes(context, db, password)

    suspend fun importBackupFromBytes(data: ByteArray, password: String = ""): Boolean =
        BackupManager.importBackupFromBytes(context, db, data, password)

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
