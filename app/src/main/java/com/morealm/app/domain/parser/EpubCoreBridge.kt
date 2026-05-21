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
    private const val CACHE_CAPACITY = 3

    // LRU cache for opened EpubBook instances. accessOrder = true → 最近用的留下，
    // 超过 capacity 的 eldest 被 close 释放。LinkedHashMap 自身非线程安全，所有访问
    // 包 synchronized(lock)。漫画并发读图片场景下减少 ZIP 重开开销（mmap + OPF 解析）。
    private val cache: LinkedHashMap<String, EpubBook> =
        object : LinkedHashMap<String, EpubBook>(CACHE_CAPACITY, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, EpubBook>): Boolean {
                if (size > CACHE_CAPACITY) {
                    runCatching { eldest.value.close() }
                    return true
                }
                return false
            }
        }
    private val lock = Any()

    /**
     * 用 epub-core 打开 [uri] 指向的 EPUB，在 [block] 内访问 [EpubBook]。
     *
     * - file:// URI（Phase A 后的新书 / 已迁移老书）→ 命中 LRU cache，零开销复用；
     *   未命中则 [EpubBook.open] 后加入 cache，未来阅读 / 漫画图片读取共享同实例。
     * - content:// URI（未迁移老书）→ 每次复制到 tmp + open + use.close，**不入 cache**
     *   （tmp 文件可能被 mmap 引用，乱删会出错；走单次路径绕开）。
     *
     * block 异常被吞掉返回 null；底层 IO 错误（open 失败 / 解析 OPF 失败）也返回 null。
     */
    fun <R> withCoreBook(context: Context, uri: Uri, block: (EpubBook) -> R?): R? {
        val resolved = resolvePath(context, uri) ?: return null
        val key = resolved.file.absolutePath
        // content:// 临时文件不入 cache，单次 open + use.close
        if (resolved.isTmp) {
            return try {
                EpubBook.open(key).use { book -> block(book) }
            } catch (t: Throwable) {
                AppLog.error(TAG, "tmp-open failed uri=$uri: ${t.message}", t)
                null
            } finally {
                resolved.file.delete()
            }
        }
        val book = obtainCached(key) ?: return null
        return try {
            block(book)
        } catch (t: Throwable) {
            AppLog.error(TAG, "block failed uri=$uri path=$key: ${t.message}", t)
            null
        }
    }

    /** 关闭并移除所有 cache entry。reader 退出 / 内存压力时调用。 */
    fun closeAll() {
        synchronized(lock) {
            for (entry in cache.values) runCatching { entry.close() }
            cache.clear()
        }
    }

    /** 关闭 [uri] 对应的 cache entry（如果存在）。caller 在书被删除 / 替换时调用。 */
    fun invalidate(context: Context, uri: Uri) {
        val resolved = resolvePath(context, uri) ?: return
        if (resolved.isTmp) {
            resolved.file.delete()
            return
        }
        synchronized(lock) {
            cache.remove(resolved.file.absolutePath)?.let { runCatching { it.close() } }
        }
    }

    private fun obtainCached(key: String): EpubBook? {
        synchronized(lock) {
            val hit = cache[key]
            if (hit != null) return hit
        }
        // open 在 lock 外，避免 IO 期间阻塞其他 cache 读
        val opened = try {
            EpubBook.open(key)
        } catch (t: Throwable) {
            AppLog.error(TAG, "open failed path=$key: ${t.message}", t)
            return null
        }
        synchronized(lock) {
            // double-check：lock 外另一线程可能已经把同 key open 加进 cache
            val racing = cache[key]
            if (racing != null) {
                runCatching { opened.close() }
                return racing
            }
            cache[key] = opened
            return opened
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
