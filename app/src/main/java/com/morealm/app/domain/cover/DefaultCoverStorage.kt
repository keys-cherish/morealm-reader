package com.morealm.app.domain.cover

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.morealm.app.core.log.AppLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [CoverStorage] 默认实现。
 *
 * Pipeline（整条都在 Dispatchers.IO）：
 *  1. 读流 → inJustDecodeBounds decode → 拿到 (srcW, srcH)
 *  2. 算 inSampleSize 让最大边 ≥ TARGET_LONG_EDGE_PX 的最小 2^n 值（避免内存溢出）
 *  3. 正式 decode 出低采样 Bitmap
 *  4. 如果仍大于 TARGET_LONG_EDGE_PX，用 Bitmap.createScaledBitmap 精确缩到目标尺寸
 *  5. 原子写入：先写 .tmp，成功后 rename 覆盖（避免进程崩溃留下半截文件）
 *  6. 返回 file:// URI
 *
 * 内存峰值：
 *  - sample 后的 bitmap 最多 TARGET_LONG_EDGE_PX*2 像素 ≈ 1200px 长边
 *  - 1200 * 1600 * 4B = 7.7MB （decode 瞬时峰值，用完立即 recycle）
 *  - scaled bitmap 最终 600 * 800 * 4B = 1.9MB
 *  - encode WebP 时内部还有一个 buffer，整个 pipeline 总峰值 ~10MB，可控
 *
 * 最终磁盘大小：典型 30-80KB
 */
@Singleton
class DefaultCoverStorage @Inject constructor(
    @ApplicationContext private val context: Context,
) : CoverStorage {

    companion object {
        /** 目标长边像素，2× 于书架最大显示尺寸 (300dp ≈ 300px)，够锐利 */
        private const val TARGET_LONG_EDGE_PX = 600

        /** WebP 压缩质量 0-100。80 是画质/体积的甜点 */
        private const val WEBP_QUALITY = 80

        private const val TAG = "CoverStorage"
    }

    override suspend fun saveCover(
        sourceUri: Uri,
        kind: CoverKind,
        ownerId: String,
    ): String? = withContext(Dispatchers.IO) {
        try {
            val targetFile = fileFor(kind, ownerId)
            targetFile.parentFile?.mkdirs()

            // Step 1: 只读尺寸，不 decode 像素（原图 2-5MB 时这一步也只读几 KB header）
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(sourceUri).use { input ->
                if (input == null) {
                    AppLog.warn(TAG, "openInputStream returned null for $sourceUri")
                    return@withContext null
                }
                BitmapFactory.decodeStream(input, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                AppLog.warn(TAG, "invalid image bounds: ${bounds.outWidth}x${bounds.outHeight}")
                return@withContext null
            }

            // Step 2: 算降采样因子
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = calcSampleSize(
                    bounds.outWidth,
                    bounds.outHeight,
                    TARGET_LONG_EDGE_PX,
                )
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            // Step 3: 正式 decode（降采样后的小 bitmap）
            val sampled: Bitmap = context.contentResolver.openInputStream(sourceUri).use { input ->
                if (input == null) return@withContext null
                BitmapFactory.decodeStream(input, null, decodeOptions)
            } ?: run {
                AppLog.warn(TAG, "decodeStream returned null for $sourceUri")
                return@withContext null
            }

            // Step 4: 精确缩放到 TARGET_LONG_EDGE_PX
            val scaled = scaleToTarget(sampled, TARGET_LONG_EDGE_PX)
            if (scaled !== sampled) sampled.recycle()

            // Step 5: 原子写入
            val tmp = File(targetFile.parentFile, targetFile.name + ".tmp")
            FileOutputStream(tmp).use { os ->
                @Suppress("DEPRECATION")
                val format = if (android.os.Build.VERSION.SDK_INT >= 30) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    Bitmap.CompressFormat.WEBP
                }
                scaled.compress(format, WEBP_QUALITY, os)
                os.flush()
            }
            scaled.recycle()

            if (targetFile.exists()) targetFile.delete()
            if (!tmp.renameTo(targetFile)) {
                AppLog.error(TAG, "rename tmp → target failed: $tmp -> $targetFile")
                tmp.delete()
                return@withContext null
            }

            val resultUri = Uri.fromFile(targetFile).toString()
            AppLog.info(
                TAG,
                "saved cover kind=$kind owner=$ownerId" +
                    " from ${bounds.outWidth}x${bounds.outHeight}" +
                    " sample=${decodeOptions.inSampleSize}" +
                    " size=${targetFile.length()}B → $resultUri",
            )
            resultUri
        } catch (e: Throwable) {
            AppLog.error(TAG, "saveCover failed: ${e.message}", e)
            null
        }
    }

    override suspend fun deleteCover(kind: CoverKind, ownerId: String) = withContext(Dispatchers.IO) {
        runCatching {
            val f = fileFor(kind, ownerId)
            if (f.exists()) {
                f.delete()
                AppLog.info(TAG, "deleted cover kind=$kind owner=$ownerId")
            }
        }
        Unit
    }

    override fun getCoverFile(kind: CoverKind, ownerId: String): File? {
        val f = fileFor(kind, ownerId)
        return if (f.exists()) f else null
    }

    override fun getCoversRoot(): File = File(context.filesDir, "covers").apply { mkdirs() }

    // ── private helpers ──

    private fun fileFor(kind: CoverKind, ownerId: String): File =
        File(File(getCoversRoot(), kind.dirName), sanitizeFileName(ownerId) + ".webp")

    /**
     * 文件名安全化：ownerId 可能含特殊字符（URL / 斜杠 / 问号），用 hash 做稳定映射。
     * 32 位十进制足够区分，不会因 ID 太长撑爆路径。
     */
    private fun sanitizeFileName(raw: String): String {
        // 保留可读性：短 ID 原样用；长 / 含特殊字符的走 hash
        val safe = raw.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        return if (safe.length == raw.length && safe.length <= 64) safe
        else "h_${raw.hashCode().toLong() and 0xFFFFFFFFL}"
    }

    private fun calcSampleSize(srcW: Int, srcH: Int, targetLongEdge: Int): Int {
        val longEdge = maxOf(srcW, srcH)
        if (longEdge <= targetLongEdge) return 1
        var sample = 1
        while (longEdge / (sample * 2) >= targetLongEdge) sample *= 2
        return sample
    }

    private fun scaleToTarget(src: Bitmap, targetLongEdge: Int): Bitmap {
        val longEdge = maxOf(src.width, src.height)
        if (longEdge <= targetLongEdge) return src
        val ratio = targetLongEdge.toFloat() / longEdge
        val newW = (src.width * ratio).toInt().coerceAtLeast(1)
        val newH = (src.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, newW, newH, /* filter = */ true)
    }
}
