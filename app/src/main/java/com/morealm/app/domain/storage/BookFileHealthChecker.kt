package com.morealm.app.domain.storage

import android.content.Context
import android.net.Uri
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.entity.BookFormat

/**
 * 书籍文件健康检查 —— 在导入 / 打开两个入口共享。
 *
 * 失败场景（实测有用户中招）：
 * - 0 字节占位文件（下载残缺 / 备份恢复未完成 / 微信传输断了）
 * - .epub 后缀但内容是别的格式（用户改后缀）
 * - 文件已被系统清理但 SAF URI 仍存在（导致 openInputStream 失败）
 *
 * 之前 ShelfImportController 自带 isValidFileHeader 只在**新导入**路径生效，
 * 已入库的占位条目下次打开还是可能崩。这里抽出公共 helper，让 reader 路由的
 * [com.morealm.app.presentation.reader.BookFormatProbeViewModel] 也做同款校验，
 * 让坏书在路由层就被拦住，不进 reader。
 */
object BookFileHealthChecker {

    private const val TAG = "BookHealth"

    sealed interface Health {
        /** 文件健康，可以加载。 */
        data object Ok : Health

        /** 文件不可读 / 不存在 / IO 错。message 用于 UI 显示。 */
        data class Invalid(val reason: String) : Health
    }

    /**
     * 按 [format] 校验 [uri] 指向的文件头。耗时 < 5ms。
     *
     * - EPUB/CBZ：前 4 字节是 ZIP local header `PK\x03\x04`
     * - PDF：前 4 字节是 `%PDF`
     * - MOBI/AZW3：PDB type 字段 (offset 60-63) 是 `BOOK`
     * - TXT/UMD：只校验非空（任意内容都合法）
     * - 其他：默认放行
     */
    fun check(context: Context, uri: Uri, format: BookFormat): Health {
        val needed = expectedHeaderBytesFor(format)
        val bytes = readMagic(context, uri, needed)
            ?: return Health.Invalid("无法读取文件（可能已被删除或权限失效）")
        if (bytes.isEmpty()) return Health.Invalid("文件为空")
        return when (format) {
            BookFormat.EPUB, BookFormat.CBZ ->
                if (isZipMagic(bytes)) Health.Ok else Health.Invalid("不是有效的 EPUB/ZIP 文件（文件可能损坏或下载未完成）")
            BookFormat.PDF ->
                if (isPdfMagic(bytes)) Health.Ok else Health.Invalid("不是有效的 PDF 文件")
            BookFormat.MOBI, BookFormat.AZW3 -> {
                // 仅校验 PDB 容器（type=BOOK）。原本还检查 record0+16 必须是 "MOBI"
                // magic 用于挡 DRM 加密 / Topaz —— 但实测会误伤老格式 PalmDOC
                // 或没显式 MOBI header 的合法文件。改为放行：实际加密/损坏 mobi
                // 由 reader 阶段（[MobiParser.parseChapters] + 参照实现 MobiReader）
                // 自己 catch 返回空章节，再通过空章节占位提示用户。
                if (isMobiPdbMagic(bytes)) Health.Ok
                else Health.Invalid("不是有效的 MOBI/AZW3 容器")
            }
            BookFormat.TXT, BookFormat.UMD -> Health.Ok
            else -> Health.Ok
        }
    }

    /** 仅返回布尔，给不需要错误细节的 caller（如导入路径）使用。 */
    fun isValid(context: Context, uri: Uri, format: BookFormat): Boolean =
        check(context, uri, format) is Health.Ok

    private fun expectedHeaderBytesFor(format: BookFormat): Int = when (format) {
        BookFormat.EPUB, BookFormat.CBZ -> 4
        BookFormat.PDF -> 4
        // MOBI/AZW3 需要 PDB 头 (78B) + record0 offset (4B) + record0+16 magic (4B) ——
        // record0 通常在 4-12KB，读 16KB 覆盖绝大多数 case
        BookFormat.MOBI, BookFormat.AZW3 -> 16384
        BookFormat.TXT, BookFormat.UMD -> 1
        else -> 4
    }

    private fun readMagic(context: Context, uri: Uri, n: Int): ByteArray? = try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val buf = ByteArray(n)
            var total = 0
            while (total < n) {
                val r = stream.read(buf, total, n - total)
                if (r <= 0) break
                total += r
            }
            // total = 0 表示空文件；< n 表示文件比预期短（也算坏文件）
            if (total >= n) buf else ByteArray(0)
        }
    } catch (e: Exception) {
        AppLog.warn(TAG, "readMagic IO failed: ${e.message}")
        null
    }

    private fun isZipMagic(b: ByteArray): Boolean =
        b.size >= 4 && b[0] == 0x50.toByte() && b[1] == 0x4B.toByte() &&
            b[2] == 0x03.toByte() && b[3] == 0x04.toByte()

    private fun isPdfMagic(b: ByteArray): Boolean =
        b.size >= 4 && b[0] == 0x25.toByte() && b[1] == 0x50.toByte() &&  // "%P"
            b[2] == 0x44.toByte() && b[3] == 0x46.toByte()                // "DF"

    /** PDB type at offset 60-63 == "BOOK" (MOBI/AZW3 共用 PDB 容器)。 */
    private fun isMobiPdbMagic(b: ByteArray): Boolean =
        b.size >= 64 && b[60] == 'B'.code.toByte() && b[61] == 'O'.code.toByte() &&
            b[62] == 'O'.code.toByte() && b[63] == 'K'.code.toByte()

    /**
     * MOBI/AZW3 第一个 record 内偏移 16 处的 "MOBI" magic。
     *
     * 用途：区分明文 MOBI/AZW3 vs DRM 加密 / Topaz 容器。两者 PDB 头都是 "BOOK"，
     * 但 DRM 加密的 record0 内容被加密，offset+16 不再是 ASCII "MOBI"，本应用解析
     * 不了 —— 拒绝入库 / 拒绝打开比让 reader 崩溃好。
     *
     * 「解忧杂货店.azw3」实测：head 32 字节是 base32 密文 (`CR!2TG72...`) 而非
     * ASCII 书名 → 这是 Amazon DRM 加密版 azw3，本函数返回 false 拦住。
     */
    private fun hasMobiRecord0Magic(b: ByteArray): Boolean {
        if (b.size < 82) return false
        // PDB record 0 offset 在 bytes[78..81]（big-endian U32）
        val rec0 = ((b[78].toInt() and 0xFF) shl 24) or
            ((b[79].toInt() and 0xFF) shl 16) or
            ((b[80].toInt() and 0xFF) shl 8) or
            (b[81].toInt() and 0xFF)
        if (rec0 < 0 || rec0 + 20 > b.size) return false
        return b[rec0 + 16] == 'M'.code.toByte() &&
            b[rec0 + 17] == 'O'.code.toByte() &&
            b[rec0 + 18] == 'B'.code.toByte() &&
            b[rec0 + 19] == 'I'.code.toByte()
    }
}
