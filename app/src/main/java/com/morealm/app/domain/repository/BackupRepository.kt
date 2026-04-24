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

    suspend fun exportBackup(uri: Uri): Boolean =
        BackupManager.exportBackup(context, db, uri)

    suspend fun importBackup(uri: Uri): Boolean =
        BackupManager.importBackup(context, db, uri)

    suspend fun generateBackupBytes(): ByteArray? =
        BackupManager.generateBackupBytes(context, db)

    suspend fun importBackupFromBytes(data: ByteArray): Boolean =
        BackupManager.importBackupFromBytes(context, db, data)

    fun createWebDavClient(url: String, user: String, pass: String): WebDavClient =
        WebDavClient(url.trimEnd('/'), user, pass)
}
