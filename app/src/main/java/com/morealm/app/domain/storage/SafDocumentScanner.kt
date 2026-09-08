package com.morealm.app.domain.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.storage.FastFileScanner.ScanItem
import com.morealm.app.domain.storage.FastFileScanner.ChildItem

/** Batched provider queries; never interpret an opaque provider document ID as a path. */
internal object SafDocumentScanner {
    private const val TAG = "SafScanner"
    // ── SAF 路径（DocumentsContract.query 替代 DocumentFile.listFiles） ─────────

    fun scan(
        context: Context,
        treeUri: Uri,
        isWanted: (String) -> Boolean,
        maxDepth: Int,
    ): List<ScanItem> {
        val result = ArrayList<ScanItem>(256)
        val rootDocId = LocalDocumentIdentity.documentId(treeUri)
        scanSafRecursive(context, treeUri, rootDocId, isWanted, result, depth = 0, maxDepth, visited = hashSetOf())
        return result.distinctBy { LocalDocumentIdentity.key(it.uri) }
    }

    private fun scanSafRecursive(
        context: Context,
        treeUri: Uri,
        dirDocId: String,
        isWanted: (String) -> Boolean,
        out: MutableList<ScanItem>,
        depth: Int,
        maxDepth: Int,
        visited: MutableSet<String>,
    ) {
        if (depth > maxDepth || !visited.add(dirDocId)) return
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
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
                            scanSafRecursive(context, treeUri, docId, isWanted, out, depth + 1, maxDepth, visited)
                        }
                    } else if (isWanted(name)) {
                        out.add(
                            ScanItem(
                                uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId),
                                name = name,
                                filePath = null,
                                // 同一 cursor 顺手带出，零额外 IPC；provider 不给则 0
                                size = if (cursor.isNull(3)) 0L else cursor.getLong(3),
                                lastModified = if (cursor.isNull(4)) 0L else cursor.getLong(4),
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.warn(TAG, "SAF query failed dir=$dirDocId: ${e.message}")
        }
    }

    fun listChildren(context: Context, treeUri: Uri): List<ChildItem> {
        val result = ArrayList<ChildItem>(64)
        val rootDocId = LocalDocumentIdentity.documentId(treeUri)
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
