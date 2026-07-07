package com.morealm.app.domain.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.morealm.app.core.log.AppLog
import java.io.File

/**
 * 本地书文件指纹（size + mtime）—— 章节 DB 缓存的失效校验依据。
 *
 * 用法：导入 / 首次解析时把指纹写进 Book.fileSize/fileMtime；下次打开时重新取指纹，
 * 一致 → 章节目录直接用 chapters 表缓存（大 TXT 二次打开秒开），不一致 → 文件被
 * 外部替换 / 追更 → 重新解析。
 *
 * 取不到（文件不存在 / SAF 授权失效 / provider 崩了）返回 null —— caller 据此
 * 走「文件已移动或删除」兜底提示，而不是让 openInputStream 在深处炸出难懂的堆栈。
 */
object LocalFileFingerprint {

    private const val TAG = "FileFingerprint"

    data class Fingerprint(
        /** 文件字节数。> 0 才有效（0 字节文件本来就该被 HealthChecker 拦）。 */
        val size: Long,
        /** lastModified（ms）。部分 SAF provider 不给 → 0，此时仅按 size 校验。 */
        val mtime: Long,
    )

    /** 取 [uri] 当前指纹；文件不可访问返回 null。 */
    fun of(context: Context, uri: Uri): Fingerprint? = try {
        when (uri.scheme) {
            null, "file" -> {
                val f = File(requireNotNull(uri.path) { "file uri without path" })
                if (f.isFile) Fingerprint(f.length(), f.lastModified()) else null
            }
            else -> ofContentUri(context, uri)
        }
    } catch (e: Exception) {
        AppLog.warn(TAG, "fingerprint failed for $uri: ${e.message}")
        null
    }

    private fun ofContentUri(context: Context, uri: Uri): Fingerprint? {
        // 先走 query 拿 SIZE + LAST_MODIFIED；部分三方 provider 不认识投影列会直接抛 ——
        // 单独 catch 后降级到 AFD 长度（仅 size，无 mtime），不让 stat 失败误判成文件丢失。
        try {
            val projection = arrayOf(
                OpenableColumns.SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )
            context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                    val mtimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    val size = if (sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else -1L
                    val mtime = if (mtimeIdx >= 0 && !c.isNull(mtimeIdx)) c.getLong(mtimeIdx) else 0L
                    if (size >= 0) return Fingerprint(size, mtime)
                }
            }
        } catch (e: Exception) {
            AppLog.warn(TAG, "query fingerprint failed for $uri: ${e.message}")
        }
        // query 没给 SIZE / 抛异常 → 退回 AFD 长度
        return context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
            val len = afd.length
            if (len >= 0) Fingerprint(len, 0L) else null
        }
    }

    /**
     * 缓存有效性判定：导入/上次解析记录的 (bookSize, bookMtime) 与当前指纹是否一致。
     *
     * - bookSize == 0：老书（v36 迁移前）或从未回填 → 视为无指纹，缓存不可信
     * - mtime 任一侧为 0（SAF 拿不到）→ 仅比 size
     */
    fun matches(bookSize: Long, bookMtime: Long, current: Fingerprint): Boolean {
        if (bookSize <= 0L || bookSize != current.size) return false
        if (bookMtime == 0L || current.mtime == 0L) return true
        return bookMtime == current.mtime
    }
}
