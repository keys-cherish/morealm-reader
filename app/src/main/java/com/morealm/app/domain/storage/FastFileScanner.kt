package com.morealm.app.domain.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.morealm.app.core.log.AppLog
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 文件夹快速扫描器 —— 双路径：File API（有 All-Files-Access）/ SAF
 * [DocumentsContract.query]（无权限时 fallback）。
 *
 * ## 性能对比（1000 文件递归）
 *
 * - 原 `DocumentFile.listFiles()` 递归：10-30s（每 entry 一次 ContentProvider IPC）
 * - SAF [DocumentsContract.query] 递归：2-5s（SQL 批量一层一拉）
 * - File API [File.walkTopDown]：< 500ms
 *
 * ## 设计
 *
 * 入口 [scanFolder] 自动判断走哪条路径。返回 [ScanItem]：始终含 `uri` 给下游做
 * `openInputStream` 等通用 SAF/File 操作；File API 路径下额外有 `filePath`
 * 可供性能敏感场景直接走 [java.io.FileInputStream]（未来 EpubParser 优化点）。
 */
object FastFileScanner {

    private const val TAG = "FastScanner"

    data class ScanItem(
        val uri: Uri,
        val name: String,
        /** File API 路径下的绝对路径；SAF 路径下为 null。 */
        val filePath: String?,
    )

    /**
     * 递归扫描 [treeUri] 下所有匹配 [isWanted] 的文件（按文件名）。
     *
     * @param maxDepth 最大递归深度。漫画压缩包嵌套深度通常 ≤ 5，给 10 容错。
     */
    suspend fun scanFolder(
        context: Context,
        treeUri: Uri,
        isWanted: (name: String) -> Boolean,
        maxDepth: Int = 10,
    ): List<ScanItem> = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()

        // 优先 File API：有 All-Files-Access 且能解出真实路径
        if (StorageAccessHelper.hasAllFilesAccess()) {
            val rootPath = StorageAccessHelper.treeUriToFilePath(treeUri)
            if (rootPath != null) {
                val items = scanByFileApi(rootPath, isWanted, maxDepth)
                AppLog.info(
                    TAG,
                    "FileApi scan: ${items.size} files in ${System.currentTimeMillis() - t0}ms root=$rootPath",
                )
                return@withContext items
            }
        }

        // Fallback：SAF [DocumentsContract.query]（比 DocumentFile.listFiles 快 3-5x）
        val items = scanBySaf(context, treeUri, isWanted, maxDepth)
        AppLog.info(
            TAG,
            "SAF scan: ${items.size} files in ${System.currentTimeMillis() - t0}ms",
        )
        items
    }

    /**
     * 列出 [treeUri] 一层内的文件 + 目录（非递归）。供需要分阶段处理（先扫顶层
     * 再决定每个子目录怎么处理）的 caller 用。
     */
    suspend fun listImmediateChildren(
        context: Context,
        treeUri: Uri,
    ): List<ChildItem> = withContext(Dispatchers.IO) {
        if (StorageAccessHelper.hasAllFilesAccess()) {
            val rootPath = StorageAccessHelper.treeUriToFilePath(treeUri)
            if (rootPath != null) {
                return@withContext File(rootPath).listFiles()?.map { f ->
                    ChildItem(
                        uri = Uri.fromFile(f),
                        name = f.name,
                        isDirectory = f.isDirectory,
                        filePath = f.absolutePath,
                    )
                }.orEmpty()
            }
        }
        listImmediateChildrenSaf(context, treeUri)
    }

    data class ChildItem(
        val uri: Uri,
        val name: String,
        val isDirectory: Boolean,
        val filePath: String?,
    )

    // ── File API 路径 ─────────────────────────────────────────────

    private fun scanByFileApi(
        rootPath: String,
        isWanted: (String) -> Boolean,
        maxDepth: Int,
    ): List<ScanItem> {
        val root = File(rootPath)
        if (!root.exists() || !root.isDirectory) return emptyList()
        val result = ArrayList<ScanItem>(256)
        root.walkTopDown()
            .maxDepth(maxDepth)
            // 跳过 . 开头的隐藏目录（.git / .thumbnails / .trash 等，不进入递归）
            .onEnter { dir -> !dir.name.startsWith(".") }
            .forEach { f ->
                if (f.isFile && isWanted(f.name)) {
                    result.add(
                        ScanItem(
                            uri = Uri.fromFile(f),
                            name = f.name,
                            filePath = f.absolutePath,
                        )
                    )
                }
            }
        return result
    }

    // ── SAF 路径（DocumentsContract.query 替代 DocumentFile.listFiles） ─────────

    private fun scanBySaf(
        context: Context,
        treeUri: Uri,
        isWanted: (String) -> Boolean,
        maxDepth: Int,
    ): List<ScanItem> {
        val result = ArrayList<ScanItem>(256)
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        scanSafRecursive(context, treeUri, rootDocId, isWanted, result, depth = 0, maxDepth)
        return result
    }

    private fun scanSafRecursive(
        context: Context,
        treeUri: Uri,
        dirDocId: String,
        isWanted: (String) -> Boolean,
        out: MutableList<ScanItem>,
        depth: Int,
        maxDepth: Int,
    ) {
        if (depth > maxDepth) return
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        try {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val docId = cursor.getString(0)
                    val name = cursor.getString(1) ?: continue
                    val mime = cursor.getString(2) ?: ""
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        // 跳过 . 开头的隐藏目录（.git / .thumbnails / .trash 等）
                        if (!name.startsWith(".")) {
                            scanSafRecursive(context, treeUri, docId, isWanted, out, depth + 1, maxDepth)
                        }
                    } else if (isWanted(name)) {
                        out.add(
                            ScanItem(
                                uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId),
                                name = name,
                                filePath = null,
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.warn(TAG, "SAF query failed dir=$dirDocId: ${e.message}")
        }
    }

    private fun listImmediateChildrenSaf(context: Context, treeUri: Uri): List<ChildItem> {
        val result = ArrayList<ChildItem>(64)
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        try {
            context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val docId = cursor.getString(0)
                    val name = cursor.getString(1) ?: continue
                    val mime = cursor.getString(2) ?: ""
                    result.add(
                        ChildItem(
                            uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId),
                            name = name,
                            isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                            filePath = null,
                        )
                    )
                }
            }
        } catch (e: Exception) {
            AppLog.warn(TAG, "listImmediateChildren failed: ${e.message}")
        }
        return result
    }
}
