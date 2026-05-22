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

    /**
     * LRU cache entry —— 同时存 [EpubBook] 实例和（content:// 路径下的）tmp file，
     * evict 时 close book + delete tmp（防止 cacheDir 增长无界）。
     *
     * **D1.b bugfix**：content:// 路径之前每次 withCoreBook 都 copyToTmp + open + close
     * （单次模式），某 EPUB这种 ~10MB EPUB + 474 章 spine 每次开销 ~7-10s。host 翻章
     * cache MISS 时多个 worker 同时触发 → 并发争 IO → SHIFT-NEXT-FAIL 卡死。修：
     * content:// 也走 LRU cache，cache key 用 uri.toString() 让 caller 共享同一 book。
     */
    private data class Entry(val book: EpubBook, val tmpFile: File?)

    private val cache: LinkedHashMap<String, Entry> =
        object : LinkedHashMap<String, Entry>(CACHE_CAPACITY, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, Entry>): Boolean {
                if (size > CACHE_CAPACITY) {
                    runCatching { eldest.value.book.close() }
                    eldest.value.tmpFile?.let { runCatching { it.delete() } }
                    return true
                }
                return false
            }
        }
    private val lock = Any()

    /**
     * Per-URI inflight lock —— 同一 URI 多 caller 同时 cache MISS 时让 opener 串行（其他
     * caller 等结果后从 cache 直读）。否则某 EPUB场景多个 worker 都会触发 copyToTmp + open
     * （~7-10s 每次） → IO 资源耗尽 + 重复开销让翻章卡死。entry 永不清，每本书 1 个
     * 微小对象，开销可忽略。
     */
    private val inflightLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

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
        // **D1.b bugfix**：cache key 用 uri.toString() 让 file:// / content:// 共享同语义。
        // 同 caller 重复 withCoreBook 同 URI 时复用同一 EpubBook 实例，避免 content:// 重复
        // copyToTmp + open（每次 5-10s）→ 多 worker 并发互争 IO 让翻章卡死。
        val cacheKey = uri.toString()
        val book = obtainCached(cacheKey) {
            // miss path：file:// → 直接拿 path；content:// → copyToTmp。返回 (book, tmpFile?)
            val resolved = resolvePath(context, uri) ?: return@obtainCached null
            try {
                val opened = EpubBook.open(resolved.file.absolutePath)
                Entry(opened, if (resolved.isTmp) resolved.file else null)
            } catch (t: Throwable) {
                AppLog.error(TAG, "open failed uri=$uri: ${t.message}", t)
                if (resolved.isTmp) resolved.file.delete()
                null
            }
        } ?: return null
        return try {
            block(book)
        } catch (t: Throwable) {
            AppLog.error(TAG, "block failed uri=$uri: ${t.message}", t)
            null
        }
    }

    /** 关闭并移除所有 cache entry。reader 退出 / 内存压力时调用。 */
    fun closeAll() {
        synchronized(lock) {
            for (entry in cache.values) {
                runCatching { entry.book.close() }
                entry.tmpFile?.let { runCatching { it.delete() } }
            }
            cache.clear()
        }
    }

    /** 关闭 [uri] 对应的 cache entry（如果存在）。caller 在书被删除 / 替换时调用。 */
    fun invalidate(context: Context, uri: Uri) {
        synchronized(lock) {
            cache.remove(uri.toString())?.let {
                runCatching { it.book.close() }
                it.tmpFile?.let { f -> runCatching { f.delete() } }
            }
        }
    }

    /**
     * Cache-aware obtain：命中直接复用，未命中调 [opener] 实际打开（IO 在 lock 外）后入 cache。
     * lock 外 race 时由 double-check 处理。
     */
    private fun obtainCached(key: String, opener: () -> Entry?): EpubBook? {
        // Fast path：cache 已命中直接返回，无锁竞争
        synchronized(lock) {
            val hit = cache[key]
            if (hit != null) return hit.book
        }
        // Slow path：per-URI 串行 open，避免多 worker 同时 copyToTmp 重复 IO
        val keyLock = inflightLocks.computeIfAbsent(key) { Any() }
        synchronized(keyLock) {
            // 进锁后再查 cache —— 前一个 inflight caller 可能刚 open 完
            synchronized(lock) {
                val hit2 = cache[key]
                if (hit2 != null) return hit2.book
            }
            // open / copyToTmp 在 keyLock 内但 lock 外 —— 同 URI 串行，不同 URI 并行
            val opened = opener() ?: return null
            synchronized(lock) {
                cache[key] = opened
                return opened.book
            }
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
