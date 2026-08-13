package com.morealm.app.domain.storage

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
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
        val written = runCatching {
            resolver.openOutputStream(uri)?.use { it.write(bytes) } != null
        }.getOrDefault(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        if (!written) resolver.delete(uri, null, null)
        written
    }.onFailure {
        AppLog.warn("ImageSaver", "saveToPictures failed src=$src: ${it.message}")
    }.getOrDefault(false)

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

    private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        else -> "image/png"
    }
}
