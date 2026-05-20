package com.morealm.app.domain.parser

import android.content.Context
import android.net.Uri
import com.morealm.app.core.log.AppLog
import java.io.File
import java.security.MessageDigest

/**
 * 本地书文件管理：SAF 拿到的源 Uri 内容**一次性**复制到 app 私有目录
 * `filesDir/books/{hash}.{ext}`；之后业务层使用稳定的 file:// Uri 读取，
 * 不再依赖 SAF 授权 / 不依赖原文件位置。
 *
 * ## 收益
 * - **权限稳定**：app 私有目录 owned by app，永不掉 SAF 授权
 * - **mmap 友好**：file path 可走 NIO FileChannel.map，章节随机读 ns 级
 * - **解耦原位置**：原文件移动 / 改名 / 系统重置授权都不影响已导入书
 *
 * ## 代价
 * - 一次复制 IO（10MB epub ≈ 200ms in app fs，50MB 漫画 ≈ 1s）
 * - 双倍磁盘占用（源 + 副本）—— 用户期望"导入到书架"即生效
 *
 * ## 去重
 * 文件名 = `SHA256(first 256KB + size).hex.take(16)`。前 256KB 含 ZIP 中央目录索引 +
 * OPF metadata 区，对 EPUB / MOBI / AZW3 都有强区分度；额外混入 size 防同前缀不同尾的
 * 罕见碰撞。重复 hash 直接复用 target，跳过复制（dedup 在文件层完成，DB 层只做最终
 * 重复检测）。
 */
object LocalBookStorage {

    private const val TAG = "LocalBookStorage"
    private const val DIR_NAME = "books"
    private const val HASH_SAMPLE_BYTES = 256 * 1024
    private const val COPY_BUF_SIZE = 64 * 1024

    /** app 私有目录下统一存放本地书的根目录。caller 不需直接拿，但单测有用。 */
    fun storageDir(context: Context): File = File(context.filesDir, DIR_NAME).apply { mkdirs() }

    /**
     * 把 [sourceUri] 内容复制到 [storageDir]，返回稳定的 file:// [Uri]。
     *
     * 失败返回 null（IO 错 / Uri 无法打开等），caller 应回退到原 Uri 路径或提示用户。
     */
    fun saveAsLocal(context: Context, sourceUri: Uri, ext: String): Uri? {
        val cr = context.contentResolver
        val dir = storageDir(context)
        val tmpFile = File.createTempFile("book-", ".tmp", context.cacheDir)
        return try {
            // 1. 复制到 cacheDir tmp，同时记录 size
            val size = cr.openInputStream(sourceUri)?.use { input ->
                tmpFile.outputStream().use { out ->
                    input.copyTo(out, bufferSize = COPY_BUF_SIZE)
                }
            } ?: run {
                tmpFile.delete()
                AppLog.warn(TAG, "openInputStream returned null for $sourceUri")
                return null
            }
            if (size <= 0L) {
                tmpFile.delete()
                AppLog.warn(TAG, "empty source for $sourceUri")
                return null
            }

            // 2. 计算 hash
            val hash = hashSample(tmpFile, size)
            val safeExt = ext.lowercase().ifBlank { "bin" }
            val target = File(dir, "$hash.$safeExt")

            // 3. dedup：target 同 hash 同 size → 复用，跳过 rename
            if (target.exists() && target.length() == size) {
                tmpFile.delete()
                AppLog.info(TAG, "reuse existing $hash.$safeExt (${size}B)")
                return Uri.fromFile(target)
            }

            // 4. 原子 move：tmp → target；rename 失败时回退到 copy
            if (target.exists()) target.delete()
            if (!tmpFile.renameTo(target)) {
                tmpFile.copyTo(target, overwrite = true)
                tmpFile.delete()
            }
            AppLog.info(TAG, "saved $hash.$safeExt (${size}B)")
            Uri.fromFile(target)
        } catch (e: Throwable) {
            tmpFile.delete()
            AppLog.error(TAG, "saveAsLocal failed: ${e.message}", e)
            null
        }
    }

    /**
     * SHA-256(first 256KB + size little-endian 8 bytes) 前 8 字节 hex = 16 字符。
     */
    private fun hashSample(file: File, size: Long): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(COPY_BUF_SIZE)
            var remaining = HASH_SAMPLE_BYTES
            while (remaining > 0) {
                val n = input.read(buf, 0, minOf(buf.size, remaining))
                if (n <= 0) break
                md.update(buf, 0, n)
                remaining -= n
            }
        }
        val sizeBytes = ByteArray(8)
        var v = size
        for (i in 0 until 8) {
            sizeBytes[i] = (v and 0xff).toByte()
            v = v shr 8
        }
        md.update(sizeBytes)
        val digest = md.digest()
        val sb = StringBuilder(16)
        for (i in 0 until 8) {
            sb.append("%02x".format(digest[i]))
        }
        return sb.toString()
    }
}
