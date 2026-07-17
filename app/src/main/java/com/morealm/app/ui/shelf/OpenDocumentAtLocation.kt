package com.morealm.app.ui.shelf

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts

internal data class OpenDocumentRequest(
    val mimeTypes: Array<String>,
    val initialUri: Uri?,
)

/**
 * 在 AndroidX [ActivityResultContracts.OpenDocument] 的完整行为上只追加初始位置。
 *
 * 官方契约允许传文档 URI 或目录 tree URI；若是普通文件，系统 DocumentsUI 会尝试
 * 打开它的父目录。这样不需要解析各厂商私有 documentId，也不会把 SAF 降级成路径猜测。
 */
internal class OpenDocumentAtLocation : ActivityResultContract<OpenDocumentRequest, Uri?>() {
    private val delegate = ActivityResultContracts.OpenDocument()

    override fun createIntent(context: Context, input: OpenDocumentRequest): Intent =
        delegate.createIntent(context, input.mimeTypes).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && input.initialUri != null) {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, input.initialUri)
            }
        }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        delegate.parseResult(resultCode, intent)
}
