package com.morealm.app.domain.storage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import com.morealm.app.domain.annotation.HandwrittenNote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Local annotation storage. Atomic metadata writes preserve the last saved drawing on failure. */
class HandwritingRepository(private val directory: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun folder(bookId: String): File {
        val key = MessageDigest.getInstance("SHA-256").digest(bookId.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(directory, key)
    }
    suspend fun list(bookId: String): List<HandwrittenNote> = withContext(Dispatchers.IO) {
        folder(bookId).listFiles().orEmpty().mapNotNull {
            when {
                it.extension == "json" -> it
                it.name.endsWith(".json.bak") -> File(it.parentFile, it.name.removeSuffix(".bak"))
                else -> null
            }
        }.distinctBy { it.name }.mapNotNull {
            runCatching {
                json.decodeFromString(HandwrittenNote.serializer(),
                    AtomicFile(it).readFully().toString(Charsets.UTF_8))
            }.getOrNull()
        }.filter { it.bookId == bookId }.sortedByDescending { it.createdAt }
    }
    suspend fun page(note: HandwrittenNote): Bitmap? = withContext(Dispatchers.IO) {
        val file = File(folder(note.bookId), "${note.id}.png")
        if (!file.isFile && !File(file.path + ".bak").isFile) null
        else AtomicFile(file).openRead().use { BitmapFactory.decodeStream(it) }
    }
    suspend fun save(note: HandwrittenNote, page: Bitmap?) = withContext(Dispatchers.IO) {
        val dir = folder(note.bookId)
        check(dir.isDirectory || dir.mkdirs()) { "无法创建批注目录" }
        if (page != null) writeAtomic(File(dir, "${note.id}.png")) {
            check(page.compress(Bitmap.CompressFormat.PNG, 100, it)) { "无法保存批注页面" }
        }
        writeAtomic(File(dir, "${note.id}.json")) {
            it.write(json.encodeToString(HandwrittenNote.serializer(), note).toByteArray(Charsets.UTF_8))
        }
    }
    suspend fun delete(note: HandwrittenNote) = withContext(Dispatchers.IO) {
        for (extension in listOf("json", "png")) {
            val file = File(folder(note.bookId), "${note.id}.$extension")
            AtomicFile(file).delete()
            check(!file.exists() && !File(file.path + ".bak").exists()) { "无法删除批注" }
        }
        Unit
    }
    private fun writeAtomic(target: File, write: (FileOutputStream) -> Unit) {
        val atomic = AtomicFile(target)
        val stream = atomic.startWrite()
        try { write(stream); atomic.finishWrite(stream) }
        catch (error: Throwable) { atomic.failWrite(stream); throw error }
    }
}
