package com.morealm.app.domain.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.morealm.app.domain.http.okHttpClient
import com.morealm.app.domain.parser.MobiResourceLoader
import okhttp3.Request

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

    /** 清空缓存并回收所有 Bitmap */
    fun clear() {
        cache.evictAll()
    }
}
