package com.morealm.app.domain.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.parser.MobiResourceLoader
import java.io.File

/**
 * 阅读器图片弹层「保存图片 / 复制图片源」的落地工具。
 *
 * src 形态（与排版引擎 / [com.morealm.app.domain.render.ImageCache] 同一套协议）：
 *  - `file://{cacheDir}/epub_images/{uriHash}/{书内路径下划线化}` —— EPUB 解包缓存
 *  - `mobi-img://{hash}/{index}` —— MOBI 虚拟协议
 *  - 裸本地路径
 */
object ImageSaver {

    data class ShareData(
        val uri: Uri,
        val mimeType: String,
    )

    /**
     * 「复制图片源」的展示串：取文件名段（即书内路径的下划线化形，如
     * `OEBPS_Images_0012.jpg`），足以在书内唯一定位这张图；mobi 虚拟协议原样返回。
     */
    fun sourceLabel(src: String): String {
        if (src.startsWith("mobi-img://")) return src
        val name = src.removePrefix("file://")
            .replace('\\', '/')
            .substringAfterLast('/')
            .removePrefix("cover_")
        return name.ifBlank { src }
    }

    /**
     * 把图片**原字节**写进系统相册 `Pictures/MoRealm`（不重编码，保留原画质）。
     * API 29+ 走 IS_PENDING 三步；低版本 classic insert（Manifest 已声明
     * WRITE_EXTERNAL_STORAGE，maxSdk 限定由 Manifest 管）。
     *
     * @return true = 成功；false = 读源失败 / 插入失败（调用方 toast）
     */
    fun saveToPictures(context: Context, src: String): Boolean = runCatching {
        val bytes = readBytes(context, src) ?: return false
        val name = sourceLabel(src).substringAfterLast('/')
            .ifBlank { "morealm_${System.currentTimeMillis()}.png" }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mimeFor(name))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MoRealm")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values) ?: return false
        var keepEntry = false
        try {
            val written = resolver.openOutputStream(uri)?.use { it.write(bytes) } != null
            if (!written) return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                if (resolver.update(uri, values, null, null) <= 0) return false
            }
            keepEntry = true
            true
        } finally {
            // 插入后任一步失败都必须删除，否则相册会遗留 pending 或空图片记录。
            if (!keepEntry) {
                runCatching { resolver.delete(uri, null, null) }.onFailure { cleanupError ->
                    AppLog.warn(
                        "ImageSaver",
                        "cleanup failed uri=$uri: ${cleanupError.message}",
                    )
                }
            }
        }
    }.onFailure {
        AppLog.warn("ImageSaver", "saveToPictures failed src=$src: ${it.message}")
    }.getOrDefault(false)

    /**
     * 把原图字节写入应用分享缓存并转换为 FileProvider URI。
     *
     * 分享和保存必须复用同一套图片源读取协议，否则 `mobi-img://` 等虚拟源会在
     * 预览正常的情况下分享失败。缓存文件带时间戳，避免系统分享面板复用旧缩略图。
     */
    fun prepareShare(context: Context, src: String): ShareData? = runCatching {
        val bytes = readBytes(context, src) ?: return null
        val sourceName = sourceLabel(src).substringAfterLast('/')
            .ifBlank { "image.png" }
        val mimeType = mimeFor(sourceName, fallback = "image/*")
        val sourceExtension = sourceName.substringAfterLast('.', "").lowercase()
        val shareExtension = if (mimeType == "image/*") ".img" else ".$sourceExtension"
        val shareDir = File(context.cacheDir, "share").apply { mkdirs() }
        val shareFile = File(
            shareDir,
            "reader_image_${System.currentTimeMillis()}$shareExtension",
        )
        shareFile.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            shareFile,
        )
        ShareData(uri = uri, mimeType = mimeType)
    }.onFailure {
        AppLog.warn("ImageSaver", "prepareShare failed src=$src: ${it.message}")
    }.getOrNull()

    private fun readBytes(context: Context, src: String): ByteArray? = when {
        src.startsWith("mobi-img://") -> {
            val parts = src.removePrefix("mobi-img://").split("/", limit = 2)
            val idx = parts.getOrNull(1)?.toIntOrNull()
            if (parts.size == 2 && idx != null) {
                MobiResourceLoader.readBytes(context, parts[0], idx)
            } else null
        }
        else -> File(src.removePrefix("file://"))
            .takeIf { it.exists() && it.isFile }
            ?.readBytes()
    }

    private fun mimeFor(
        name: String,
        fallback: String = "image/png",
    ): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "svg" -> "image/svg+xml"
        else -> fallback
    }
}
