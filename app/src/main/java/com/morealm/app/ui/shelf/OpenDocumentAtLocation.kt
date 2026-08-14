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
 * 调用方应优先传父 document URI 或目录 tree URI。普通文件 URI 在 AOSP 上可能可用，
 * 但部分 OEM DocumentsUI 会忽略并回根目录；调用方只对可证明为层级路径的 provider
 * 解析父目录，这里保持契约单纯，只负责注入 EXTRA_INITIAL_URI。
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
