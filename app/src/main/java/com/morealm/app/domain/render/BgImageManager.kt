package com.morealm.app.domain.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.util.LruCache
import java.io.FileInputStream
import kotlin.math.roundToInt

/**
 * Background image utilities — ported from Legado's BitmapUtils + getMeanColor.
 *
 * Key features:
 * - inSampleSize downsampling to avoid OOM on large images
 * - Resize to exact screen dimensions then recycle the original
 * - Gaussian blur via RenderScript Toolkit
 * - Mean color extraction for simulation page-curl back-face
 * - LRU cache keyed by (uri + width + height + blur) to avoid re-decode
 */
object BgImageManager {

    // C3 chapter bg image：某 EPUB 这种章节级 EPUB bg image 不同章用不同图（back03/back05/p4
    // /qmp0000 等十余张），3 张 cache 远不够 → LRU 反复 evict → 切章时 main thread 同步
    // 解码 100-300ms → 滚动卡死（user 2026-05-22 实测 idx 12→1 连续切章累积阻塞）。
    // 16 张覆盖该类 EPUB 常用 bg image 集合 + 用户全局 day/night/blur variant。
    // 内存代价：1080x1848 ARGB_8888 = 8MB/张，16 张 = ~128MB。BgImageManager.onTrimMemory
    // 在系统内存紧张时 evict 一半，最坏情况 64MB 占用，可接受。
    private const val CACHE_SIZE = 16

    private val cache = object : LruCache<String, BgEntry>(CACHE_SIZE) {
        override fun entryRemoved(
            evicted: Boolean,
            key: String,
            oldValue: BgEntry,
            newValue: BgEntry?,
        ) {
            if (evicted && !oldValue.bitmap.isRecycled) {
                oldValue.bitmap.recycle()
            }
        }
    }

    data class BgEntry(
        val bitmap: Bitmap,
        val meanColor: Int,
    )

    /**
     * Get a background bitmap sized to (width x height), optionally blurred.
     * Returns null if uri is blank or decode fails.
     *
     * The bitmap is cached — subsequent calls with the same parameters return
     * the cached instance without re-decoding.
     */
    fun getBgBitmap(
        context: Context,
        uri: String,
        width: Int,
        height: Int,
        blurRadius: Int = 0,
    ): BgEntry? {
        if (uri.isBlank() || width <= 0 || height <= 0) return null
        val key = "$uri|${width}x${height}|blur$blurRadius"
        cache.get(key)?.let { entry ->
            if (!entry.bitmap.isRecycled) return entry
            cache.remove(key)
        }
        val raw = decodeBitmap(context, uri, width, height) ?: return null
        val resized = resizeAndRecycle(raw, width, height)
        val blurred = if (blurRadius > 0) {
            blurBitmap(resized, blurRadius)
        } else {
            resized
        }
        val meanColor = getMeanColor(blurred)
        val entry = BgEntry(blurred, meanColor)
        cache.put(key, entry)
        return entry
    }

    /**
     * Get just the mean color for a solid background (no image).
     */
    fun getMeanColorForSolid(argb: Int): Int = argb

    fun clear() {
        cache.evictAll()
    }

    // ── Decode with inSampleSize (ported from Legado BitmapUtils) ──

    private fun decodeBitmap(context: Context, uri: String, width: Int, height: Int): Bitmap? {
        return try {
            if (uri.startsWith("content://") || uri.startsWith("file://")) {
                decodeFromUri(context, Uri.parse(uri), width, height)
            } else {
                decodeFromPath(uri, width, height)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeFromUri(context: Context, uri: Uri, width: Int, height: Int): Bitmap? {
        // First pass: get dimensions
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
        opts.inSampleSize = calculateInSampleSize(opts, width, height)
        opts.inJustDecodeBounds = false
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    }

    private fun decodeFromPath(path: String, width: Int, height: Int): Bitmap? {
        val fis = FileInputStream(path)
        return fis.use {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFileDescriptor(fis.fd, null, opts)
            opts.inSampleSize = calculateInSampleSize(opts, width, height)
            opts.inJustDecodeBounds = false
            BitmapFactory.decodeFileDescriptor(fis.fd, null, opts)
        }
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int,
    ): Int {
        val w = options.outWidth
        val h = options.outHeight
        var inSampleSize = 1
        if (h > reqHeight || w > reqWidth) {
            val halfH = h / 2
            val halfW = w / 2
            while (halfH / inSampleSize >= reqHeight && halfW / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    // ── Resize + recycle (ported from Legado) ──

    private fun resizeAndRecycle(src: Bitmap, newWidth: Int, newHeight: Int): Bitmap {
        if (src.width == newWidth && src.height == newHeight) return src
        val resized = Bitmap.createScaledBitmap(src, newWidth, newHeight, true)
        if (resized !== src) src.recycle()
        return resized
    }

    /**
     * Simple box blur fallback (no RenderScript dependency).
     * Only used when blurRadius > 0 (background image blur).
     */
    private fun blurBitmap(src: Bitmap, radius: Int): Bitmap {
        val r = radius.coerceIn(1, 25)
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        // Horizontal pass
        for (y in 0 until h) {
            for (x in 0 until w) {
                var rr = 0; var gg = 0; var bb = 0; var count = 0
                for (dx in -r..r) {
                    val nx = (x + dx).coerceIn(0, w - 1)
                    val c = pixels[y * w + nx]
                    rr += (c shr 16) and 0xFF; gg += (c shr 8) and 0xFF; bb += c and 0xFF; count++
                }
                out[y * w + x] = (0xFF shl 24) or ((rr / count) shl 16) or ((gg / count) shl 8) or (bb / count)
            }
        }
        // Vertical pass
        for (x in 0 until w) {
            for (y in 0 until h) {
                var rr = 0; var gg = 0; var bb = 0; var count = 0
                for (dy in -r..r) {
                    val ny = (y + dy).coerceIn(0, h - 1)
                    val c = out[ny * w + x]
                    rr += (c shr 16) and 0xFF; gg += (c shr 8) and 0xFF; bb += c and 0xFF; count++
                }
                pixels[y * w + x] = (0xFF shl 24) or ((rr / count) shl 16) or ((gg / count) shl 8) or (bb / count)
            }
        }
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, w, 0, 0, w, h)
        src.recycle()
        return result
    }

    // ── Mean color (ported from Legado Bitmap.getMeanColor) ──

    /**
     * Sample the bottom 30% of the image in a 100x30 grid to get the average color.
     * Used for simulation page-curl back-face rendering.
     */
    private fun getMeanColor(bitmap: Bitmap): Int {
        val width = bitmap.width
        val height = bitmap.height
        if (width == 0 || height == 0) return Color.WHITE
        var pixelSumRed = 0L
        var pixelSumGreen = 0L
        var pixelSumBlue = 0L
        var count = 0
        for (i in 0 until 100) {
            for (j in 70 until 100) {
                val x = (i * width / 100f).roundToInt().coerceIn(0, width - 1)
                val y = (j * height / 100f).roundToInt().coerceIn(0, height - 1)
                val pixel = bitmap.getPixel(x, y)
                pixelSumRed += Color.red(pixel)
                pixelSumGreen += Color.green(pixel)
                pixelSumBlue += Color.blue(pixel)
                count++
            }
        }
        if (count == 0) return Color.WHITE
        return Color.rgb(
            (pixelSumRed / count).toInt().coerceIn(0, 255),
            (pixelSumGreen / count).toInt().coerceIn(0, 255),
            (pixelSumBlue / count).toInt().coerceIn(0, 255),
        )
    }
}
