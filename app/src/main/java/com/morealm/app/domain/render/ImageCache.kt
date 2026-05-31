package com.morealm.app.domain.render

import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.http.okHttpClient
import com.morealm.app.domain.parser.MobiResourceLoader
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/**
 * 图片 LRU 缓存，避免每次绘制都重新解码。
 * 参考 Legado ImageProvider 的动态缓存策略：
 * - 最小 50MB，最大 256MB
 * - 在 [50MB, maxMemory/4] 范围内动态选择
 *
 * 支持本地文件、网络图片（带 header 注入）、mobi-img:// 虚拟协议。
 */
object ImageCache {

    private const val MIN_CACHE_SIZE_KB = 50 * 1024  // 50MB
    private const val MAX_CACHE_SIZE_KB = 256 * 1024  // 256MB

    private val cache: LruCache<String, Bitmap>

    /** Application context — 由 MoRealmApp 在 onCreate 时注入。 */
    var appContext: Context? = null

    init {
        val maxMemory = Runtime.getRuntime().maxMemory() / 1024 // KB
        val dynamicSize = (maxMemory / 4).toInt()
        val cacheSize = dynamicSize.coerceIn(MIN_CACHE_SIZE_KB, MAX_CACHE_SIZE_KB)
        cache = object : LruCache<String, Bitmap>(cacheSize) {
            override fun sizeOf(key: String, bitmap: Bitmap): Int {
                return bitmap.byteCount / 1024 // KB
            }

            override fun entryRemoved(
                evicted: Boolean,
                key: String,
                oldValue: Bitmap,
                newValue: Bitmap?,
            ) {
                if (evicted && !oldValue.isRecycled) {
                    oldValue.recycle()
                }
            }
        }
    }

    /**
     * 统一入口 —— 根据 src 前缀分流：
     *   - `mobi-img://` → 按需从 MOBI 文件流式读取
     *   - `file://` 或无前缀 → 本地文件 BitmapFactory.decodeFile
     */
    fun get(src: String, targetWidth: Int = 0): Bitmap? {
        if (src.startsWith("mobi-img://")) return getMobi(src, targetWidth)
        val path = src.removePrefix("file://")
        return getFile(path, targetWidth)
    }

    /**
     * 获取本地文件图片，优先从缓存读取，未命中则解码并放入缓存。
     */
    fun getFile(path: String, targetWidth: Int = 0): Bitmap? {
        cache.get(path)?.let { bmp ->
            if (!bmp.isRecycled) return bmp
            cache.remove(path)
        }
        return try {
            val opts = BitmapFactory.Options()
            if (targetWidth > 0) {
                opts.inJustDecodeBounds = true
                BitmapFactory.decodeFile(path, opts)
                opts.inSampleSize = calculateInSampleSize(opts.outWidth, opts.outHeight, targetWidth)
                opts.inJustDecodeBounds = false
            }
            BitmapFactory.decodeFile(path, opts)?.also { bmp ->
                cache.put(path, bmp)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * mobi-img://{hash}/{imageIndex} → 从 MOBI 原始文件按需流式读取字节并解码。
     * 不写本地 cacheDir，解码后进 LRU 内存缓存。
     */
    private fun getMobi(src: String, targetWidth: Int): Bitmap? {
        cache.get(src)?.let { bmp ->
            if (!bmp.isRecycled) return bmp
            cache.remove(src)
        }
        val ctx = appContext ?: return null
        val parts = src.removePrefix("mobi-img://").split("/", limit = 2)
        if (parts.size < 2) return null
        val hash = parts[0]
        val idx = parts[1].toIntOrNull() ?: return null
        val bytes = MobiResourceLoader.readBytes(ctx, hash, idx) ?: return null
        return try {
            val opts = BitmapFactory.Options()
            if (targetWidth > 0) {
                opts.inJustDecodeBounds = true
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                opts.inSampleSize = calculateInSampleSize(opts.outWidth, opts.outHeight, targetWidth)
                opts.inJustDecodeBounds = false
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.also { bmp ->
                cache.put(src, bmp)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 获取网络图片，带 header 注入（书源图片需要 Referer/Cookie 等）。
     */
    fun getUrl(url: String, headers: Map<String, String> = emptyMap(), targetWidth: Int = 0): Bitmap? {
        val cacheKey = "url:$url"
        cache.get(cacheKey)?.let { bmp ->
            if (!bmp.isRecycled) return bmp
            cache.remove(cacheKey)
        }
        return try {
            val reqBuilder = Request.Builder().url(url)
            headers.forEach { (k, v) -> reqBuilder.header(k, v) }
            val response = okHttpClient.newCall(reqBuilder.build()).execute()
            val bytes = response.body?.bytes() ?: return null
            val opts = BitmapFactory.Options()
            if (targetWidth > 0) {
                opts.inJustDecodeBounds = true
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                opts.inSampleSize = calculateInSampleSize(opts.outWidth, opts.outHeight, targetWidth)
                opts.inJustDecodeBounds = false
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.also { bmp ->
                cache.put(cacheKey, bmp)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun calculateInSampleSize(rawW: Int, rawH: Int, targetW: Int): Int {
        var inSampleSize = 1
        if (rawW > targetW) {
            val halfW = rawW / 2
            while (halfW / inSampleSize >= targetW) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    /**
     * 同 src image 多次 emit 时复用 header 解析结果。bounds 是 immutable Pair（≈32B/entry），
     * 不参与 LRU / onTrimMemory（单本书 image 数 < 200，6KB 量级可忽略）。
     */
    private val boundsCache = ConcurrentHashMap<String, Pair<Int, Int>>()

    /**
     * **同步**读 image header 拿 (intrinsicWidth, intrinsicHeight)，不 decode 整张 bitmap。
     * 用 `inJustDecodeBounds=true` 走 header 解析（KB 级 I/O，10ms 量级），让 layout
     * 阶段拿到真实 aspect ratio 而不是 4:3 fallback 让 cover 等图片被压扁。
     *
     * 用途：[com.morealm.app.domain.render.layout.ScrollLayoutEngine.emitImage] 的
     * `imageDimensionsResolver` 桥接实现调本方法
     * (`ScrollImageDimensionsResolver { src, _ -> ImageCache.getBounds(src) }`)。
     *
     * 支持的 src 协议：
     *  - `mobi-img://` → [MobiResourceLoader] 读 bytes + decodeByteArray
     *  - `file://` 或裸 path → BitmapFactory.decodeFile
     *  - 其他（http(s):// 等） → null，调用方走 4:3 fallback
     */
    fun getBounds(src: String): Pair<Int, Int>? {
        boundsCache[src]?.let { return it }
        return decodeBounds(src)?.also { boundsCache[src] = it }
    }

    private fun decodeBounds(src: String): Pair<Int, Int>? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            if (src.startsWith("mobi-img://")) {
                val ctx = appContext ?: return null
                val parts = src.removePrefix("mobi-img://").split("/", limit = 2)
                if (parts.size < 2) return null
                val hash = parts[0]
                val idx = parts[1].toIntOrNull() ?: return null
                val bytes = MobiResourceLoader.readBytes(ctx, hash, idx) ?: return null
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            } else {
                val path = src.removePrefix("file://")
                BitmapFactory.decodeFile(path, opts)
            }
        } catch (_: Exception) {
            return null
        }
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        return opts.outWidth to opts.outHeight
    }

    /** 清空缓存并回收所有 Bitmap */
    fun clear() {
        cache.evictAll()
        boundsCache.clear()
    }

    /**
     * 由 [com.morealm.app.MoRealmApp.onTrimMemory] 转发系统内存压力信号。
     *
     * 之前 ImageCache 是「按 maxMemory/4 设容量然后不动」的死缓存——系统报
     * RUNNING_LOW 时它不收缩，导致 256MB heap 在漫画/插图 EPUB 场景下被 64MB
     * Bitmap 缓存 + Compose / ChapterProvider 的临时分配挤爆，触发 OOM。
     *
     * **level 值非单调，必须精确匹配不能用 `>=`**：`UI_HIDDEN(20) > RUNNING_CRITICAL(15)`，
     * 旧代码 `level >= RUNNING_CRITICAL` 把「切后台(UI_HIDDEN)」误判成内存危急 → 每次切后台
     * 全清图片缓存，书架封面消失（用户反馈）。按语义分组：
     * - **RUNNING_CRITICAL(15) / COMPLETE(80)**：真危急（前台内存紧张 / 进程将被杀）→ 全清防 OOM
     * - **RUNNING_MODERATE(5)/RUNNING_LOW(10)/UI_HIDDEN(20)/BACKGROUND(40)/MODERATE(60)**：
     *   温和回收一半（切后台 / 一般压力，保留 LRU 近用项，回前台少 miss）
     */
    fun onTrimMemory(level: Int) {
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                val before = cache.size()
                cache.evictAll()
                AppLog.info("ImageCache", "onTrimMemory($level) critical → evictAll (was ${before}KB)")
            }
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN,
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE -> {
                val before = cache.size()
                cache.trimToSize(cache.maxSize() / 2)
                AppLog.info("ImageCache", "onTrimMemory($level) moderate → trim ${before}KB→${cache.size()}KB")
            }
        }
    }
}
