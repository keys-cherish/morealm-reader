package com.morealm.app.domain.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import com.morealm.app.core.log.AppLog
import java.io.File

/**
 * "全文件访问" (MANAGE_EXTERNAL_STORAGE) 判定 + SAF tree URI → File path 转换。
 *
 * ## 为什么需要它
 *
 * SAF DocumentFile.listFiles() 在 1000+ 文件目录下耗时 10-30s（每 entry 都过
 * ContentProvider），不能用来做大文件夹扫描。Manifest 已声明
 * MANAGE_EXTERNAL_STORAGE 权限，运行时拿到这个权限后可以走 java.io.File 路径
 * 直接扫文件系统，1000 文件 < 500ms。
 *
 * ## 双路径策略
 *
 * - **有权限**：[treeUriToFilePath] 把 SAF tree URI 转成真实文件系统路径，
 *   交给 [FastFileScanner] 用 [File.walkTopDown] 高速遍历。
 * - **无权限**：回退到优化后的 SAF 路径（[DocumentsContract.query] 替代
 *   [androidx.documentfile.provider.DocumentFile.listFiles]，性能差 3-5x 但
 *   比原递归仍快几倍）。
 *
 * ## 上架风险提示
 *
 * Google Play 对阅读器类应用申请 MANAGE_EXTERNAL_STORAGE **大概率拒**（违反
 * "All Files Access" 限制条款）。如果走 Google Play，应该在 build flavor 里去
 * 这个权限并强制 SAF 路径。当前 manifest 保留权限声明 → 适用 F-Droid / 自有
 * 渠道 / 国内应用市场。
 */
object StorageAccessHelper {

    private const val TAG = "StorageAccess"

    /**
     * 是否拿到 "全文件访问" 权限。Android 11 (API 30) 引入；低版本返回 true
     * （旧权限模型 READ_EXTERNAL_STORAGE 已经包含全盘读）。
     */
    fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true  // API < 30 旧权限模型可读外部存储
        }
    }

    /**
     * 跳转到系统「所有文件访问」授权页的 Intent。Android 11+ 唯一入口。
     * 调用方应该用 ActivityResultLauncher 起这个 Intent，回来后再调
     * [hasAllFilesAccess] 验证。
     */
    fun requestAllFilesAccessIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        } else {
            // 低版本：跳应用详情让用户手动开权限
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        }
    }

    /**
     * 把 SAF tree URI 解码成真实文件系统路径。仅在 [hasAllFilesAccess] 为 true
     * 时调用结果才安全；无权限时即便能算出 path，File API 也读不到。
     *
     * Tree URI docId 格式：`"primary:Books/foo"` 或 `"0000-0000:Path"`（SD 卡）。
     * "primary" → 主用户主存储；其他卷符 → /storage/{vol}。
     *
     * 返回 null 表示无法解析（异常 URI、非主存储且 mount 点不存在）。
     */
    fun treeUriToFilePath(treeUri: Uri): String? {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val parts = docId.split(':', limit = 2)
            if (parts.isEmpty()) return null
            val volume = parts[0]
            val relativePath = parts.getOrNull(1).orEmpty()
            val root = when (volume) {
                "primary" -> Environment.getExternalStorageDirectory().absolutePath
                else -> "/storage/$volume"
            }
            val rootFile = File(root)
            if (!rootFile.exists()) {
                AppLog.warn(TAG, "treeUriToFilePath: root $root does not exist")
                return null
            }
            val resolved = if (relativePath.isEmpty()) root else "$root/$relativePath"
            if (!File(resolved).exists()) {
                AppLog.warn(TAG, "treeUriToFilePath: resolved $resolved does not exist")
                return null
            }
            resolved
        } catch (e: Exception) {
            AppLog.warn(TAG, "treeUriToFilePath failed: ${e.message}")
            null
        }
    }

    /**
     * SingleUri 转 file path（用于单文件 import）。同样需 All-Files-Access。
     * 失败返回 null，调用方应 fallback 走 SAF.openInputStream。
     */
    fun singleUriToFilePath(uri: Uri): String? {
        return try {
            val docId = DocumentsContract.getDocumentId(uri)
            val parts = docId.split(':', limit = 2)
            if (parts.size != 2) return null
            val (volume, relativePath) = parts
            val root = when (volume) {
                "primary" -> Environment.getExternalStorageDirectory().absolutePath
                else -> "/storage/$volume"
            }
            val resolved = "$root/$relativePath"
            if (File(resolved).exists()) resolved else null
        } catch (_: Exception) {
            null
        }
    }
}
