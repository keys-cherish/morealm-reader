package com.morealm.app.presentation.reader

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.entity.BookFormat
import com.morealm.app.domain.parser.LocalBookStorage
import com.morealm.app.domain.repository.BookRepository
import com.morealm.app.domain.storage.BookFileHealthChecker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

/**
 * 外部「打开方式」用 MoRealm 打开文件的导入入口（被 [com.morealm.app.ui.navigation.FileOpenActivity] 调用）。
 *
 * 复用既有本地书链路：[LocalBookStorage.saveAsLocal] 把源 Uri **复制进私有目录** → 稳定 file:// path
 * （根治 QQ/微信 content:// 临时权限：复制后不再依赖原 Uri）+ [BookFileHealthChecker] 校验 +
 * [BookRepository.findByLocalPath] 去重。建书走默认 `inBookshelf=true` → 静默入库即在书架。
 *
 * **原位引用的合法例外**：导入主链路（ImportEngine / ShelfImportController）已改为 localPath
 * 直存原文件 uri、零复制——但外部「打开方式」传来的 content:// grant 是一次性的
 * （不带 FLAG_GRANT_PERSISTABLE，takePersistableUriPermission 会 SecurityException），
 * 进程重启即失效，**必须**复制才能再次打开。此处保留 saveAsLocal 是有意为之。
 *
 * 与 ShelfImportController.importLocalBook 的区别：那条是书架 UI 的 fire-and-forget（靠状态广播），
 * 这条是 **suspend 返回 bookId** 供外部打开拿到后导航阅读器。metadata/cover 不在此同步补，
 * 走 BookFormatProbe / 后续打开兜底，避免阻塞首屏。
 */
@HiltViewModel
class ExternalFileOpenViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bookRepo: BookRepository,
) : ViewModel() {

    sealed interface Result {
        /** 导入成功（或去重命中已有书），可导航到 reader。 */
        data class Ok(val bookId: String) : Result
        /** 扩展名不在支持列表。 */
        data class Unsupported(val ext: String) : Result
        /** 文件头校验未通过（损坏 / 占位空文件）。 */
        data object Corrupted : Result
        /** 读取 / 复制 / 入库失败。 */
        data object Failed : Result
    }

    suspend fun importAndOpen(uri: Uri): Result = withContext(Dispatchers.IO) {
        try {
            val name = resolveDisplayName(uri) ?: "Unknown"
            val ext = name.substringAfterLast('.', "").lowercase()
            val format = detectFormat(ext)
            if (format == BookFormat.UNKNOWN) {
                AppLog.warn("ExtOpen", "Unsupported format: $name")
                return@withContext Result.Unsupported(ext)
            }
            // 文件头校验（按格式 magic）——先验再复制，无效就不浪费整文件复制 IO。
            if (!BookFileHealthChecker.isValid(context, uri, format)) {
                AppLog.warn("ExtOpen", "Invalid $format header: $name")
                return@withContext Result.Corrupted
            }
            // 复制进 filesDir/books/{hash}.{ext} → 稳定 file:// Uri（脱离 content:// 临时权限）。
            val localUri = LocalBookStorage.saveAsLocal(context, uri, ext) ?: run {
                AppLog.error("ExtOpen", "saveAsLocal returned null for $name")
                return@withContext Result.Failed
            }
            // 去重：同文件 hash 复用同 path → 命中已有书直接打开，不重复建。
            bookRepo.findByLocalPath(localUri.toString())?.let {
                AppLog.info("ExtOpen", "Reuse existing book: ${it.title}")
                return@withContext Result.Ok(it.id)
            }
            val title = name.substringBeforeLast('.').trim().ifBlank { name }
            val book = Book(
                id = UUID.randomUUID().toString(),
                title = title,
                localPath = localUri.toString(),
                format = format,
                addedAt = System.currentTimeMillis(),
                // inBookshelf 默认 true → 静默入库即在书架（v35）。
            )
            bookRepo.insert(book)
            AppLog.info("ExtOpen", "Imported & inserted: $title ($format)")
            Result.Ok(book.id)
        } catch (e: Exception) {
            AppLog.error("ExtOpen", "importAndOpen failed: ${e.message}", e)
            Result.Failed
        }
    }

    /** content:// 先 query DISPLAY_NAME（QQ/微信常无扩展名路径）；回退 DocumentFile / lastPathSegment。 */
    private fun resolveDisplayName(uri: Uri): String? {
        if (uri.scheme == "content") {
            runCatching {
                context.contentResolver.query(
                    uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null,
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) c.getString(idx)?.let { return it }
                    }
                }
            }
        }
        return DocumentFile.fromSingleUri(context, uri)?.name ?: uri.lastPathSegment
    }

    /** 扩展名 → BookFormat（与 ShelfImportController.detectFormat 同款白名单）。 */
    private fun detectFormat(ext: String): BookFormat = when (ext) {
        "txt" -> BookFormat.TXT
        "epub" -> BookFormat.EPUB
        "pdf" -> BookFormat.PDF
        "mobi" -> BookFormat.MOBI
        "azw3", "azw" -> BookFormat.AZW3
        "zip" -> BookFormat.CBZ
        "umd" -> BookFormat.UMD
        else -> BookFormat.UNKNOWN
    }
}
