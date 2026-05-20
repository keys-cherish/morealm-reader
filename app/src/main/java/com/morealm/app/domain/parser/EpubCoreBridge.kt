package com.morealm.app.domain.parser

import android.content.Context
import android.net.Uri
import com.morealm.app.core.log.AppLog
import com.morealm.epub.EpubBook
import java.io.File

/**
 * 用 epub-core 打开 EPUB 的统一桥接。
 *
 * 现状：[EpubParser] 仍以 epublib + jsoup 为主路径，本桥接用于 Phase B 阶段逐步迁移。
 * caller 用 [withCoreBook] 拿到 [EpubBook]，在 block 内消费，离开 block book 自动 close。
 *
 * ## uri.scheme 处理
 * - **`file://`**（Phase A 后的新书 / 任何已落地到本地的书）→ 直接 `uri.path` →
 *   [EpubBook.open]。零拷贝，开销 = ZIP central directory + OPF + nav 解析（10-30ms）。
 * - **`content://`**（旧用户导入的书，未走 Phase A 落地流程）→ 先复制到 cacheDir/tmp，
 *   open 后 block 完用 use 自动 close + 临时文件在 finally 删。
 *   兼容老书的过渡路径，下版本砍。
 *
 * ## 失败语义
 * 任何 IO / 解析错误 → 返回 null，caller 自行 fallback 到原路径或提示用户。
 * 不抛异常上层，避免污染调用栈。
 */
object EpubCoreBridge {

    private const val TAG = "EpubCoreBridge"
    private const val TMP_DIR = "epub-core-tmp"

    /**
     * 用 epub-core 打开 [uri] 指向的 EPUB，在 [block] 内访问 [EpubBook]。
     * book 在 block 结束（含异常）后自动 close。返回 block 的结果，或 null 当打开失败。
     */
    fun <R> withCoreBook(context: Context, uri: Uri, block: (EpubBook) -> R?): R? {
        val resolved = resolvePath(context, uri) ?: return null
        return try {
            EpubBook.open(resolved.file.absolutePath).use { book -> block(book) }
        } catch (t: Throwable) {
            AppLog.error(TAG, "open failed uri=$uri path=${resolved.file.absolutePath}: ${t.message}", t)
            null
        } finally {
            if (resolved.isTmp) resolved.file.delete()
        }
    }

    private data class ResolvedPath(val file: File, val isTmp: Boolean)

    private fun resolvePath(context: Context, uri: Uri): ResolvedPath? {
        return when (uri.scheme) {
            "file" -> {
                val p = uri.path ?: return null
                File(p).takeIf { it.isFile && it.length() > 0L }?.let { ResolvedPath(it, isTmp = false) }
            }
            "content" -> copyToTmp(context, uri)?.let { ResolvedPath(it, isTmp = true) }
            null -> {
                // uri.toString() 是裸 file path（旧 caller 误用）—— 试着当 file 处理
                File(uri.toString()).takeIf { it.isFile }?.let { ResolvedPath(it, isTmp = false) }
            }
            else -> {
                AppLog.warn(TAG, "unsupported uri scheme: ${uri.scheme} for $uri")
                null
            }
        }
    }

    private fun copyToTmp(context: Context, uri: Uri): File? {
        val dir = File(context.cacheDir, TMP_DIR).apply { mkdirs() }
        val tmp = File(dir, "${uri.hashCode().toUInt()}-${System.nanoTime()}.epub")
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { out -> input.copyTo(out) }
            }
            if (tmp.isFile && tmp.length() > 0L) tmp else {
                tmp.delete()
                null
            }
        } catch (e: Throwable) {
            tmp.delete()
            AppLog.error(TAG, "copyToTmp failed for $uri: ${e.message}", e)
            null
        }
    }
}
