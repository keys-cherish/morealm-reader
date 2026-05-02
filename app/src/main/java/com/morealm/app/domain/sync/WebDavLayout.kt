package com.morealm.app.domain.sync

/**
 * 集中 WebDav 远端目录公约 — 跟 Legado 的 [AppWebDav] 保持同构：
 *
 * ```
 * <root>/                       ← 用户填的服务器根（如 https://dav.jianguoyun.com/dav/）
 *   <dir>/                      ← AppPreferences.webDavDir，默认 "MoRealm"
 *     backup_*.zip              ← 备份文件（命名规范见 [backupFileName]）
 *     bookProgress/             ← 每本书一个 JSON：bookProgress/<bookId>.json
 *     books/                    ← 导出的 TXT / EPUB 上传到这里（云端书架）
 *     background/               ← 阅读器背景图同步
 * ```
 *
 * 把规约挪进单一对象意味着新增一个子目录（如未来加 `dictionaries/`）只用动这里
 * 一处；之前 `bookProgress/`、`backup_` 前缀都散在 BackupRunner / BookProgressSync
 * 各自的字符串字面量里，加一个新概念非常容易漏改。
 */
object WebDavLayout {

    /** WebDav 根目录下默认子目录名；用户可在 AppPreferences.webDavDir 覆盖。 */
    const val DEFAULT_DIR = "MoRealm"

    /** 备份文件名前缀 — 列表过滤、清理旧备份都靠这个识别。 */
    const val BACKUP_PREFIX = "backup_"

    /** 备份文件扩展名（包括前置点）。 */
    const val BACKUP_SUFFIX = ".zip"

    /** "最新"备份的固定中段名，跟时间戳备份区分；onlyLatest 模式就是只写它。 */
    const val LATEST_TAG = "latest"

    /** 子目录：每本书阅读进度 JSON 仓库。 */
    const val SUBDIR_BOOK_PROGRESS = "bookProgress"

    /** 子目录：导出 TXT / EPUB 的云端书架。 */
    const val SUBDIR_BOOKS = "books"

    /** 子目录：阅读器背景图同步。 */
    const val SUBDIR_BACKGROUND = "background"

    /**
     * 全部需要在 `upConfig()` 阶段创建的子目录列表 — 调用方一次性 makeAsDir。
     * 顺序无关；MKCOL 重复成功（或被 4xx 视为已存在）都不抛。
     */
    val ALL_SUBDIRS: List<String> = listOf(SUBDIR_BOOK_PROGRESS, SUBDIR_BOOKS, SUBDIR_BACKGROUND)

    /**
     * 把用户填的 webDavDir 归一化到本规约：去除前后斜杠 + 空值替默认。
     * 调用方应该统一走这里，避免 "MoRealm" / "/MoRealm/" / "MoRealm/" 三种写法
     * 在不同入口拼出不同最终路径。
     */
    fun normalizeDir(raw: String?): String =
        raw?.trim()?.trim('/').orEmpty().ifEmpty { DEFAULT_DIR }

    /**
     * 拼接子目录路径。例：`subPath("MoRealm", "bookProgress")` → `MoRealm/bookProgress`。
     * 不在前后加斜杠，调用方根据需要再加。
     */
    fun subPath(dir: String, sub: String): String = "$dir/$sub"

    /** 生成"时间戳备份"文件名：`backup_20260501_1832_Pixel.zip`。 */
    fun backupFileName(timestamp: String, deviceSuffix: String): String =
        "$BACKUP_PREFIX$timestamp$deviceSuffix$BACKUP_SUFFIX"

    /** 生成"最新备份"固定文件名：`backup_latest_Pixel.zip`。 */
    fun latestBackupFileName(deviceSuffix: String): String =
        "$BACKUP_PREFIX$LATEST_TAG$deviceSuffix$BACKUP_SUFFIX"

    /**
     * 给设备名做路径安全过滤（去除任何不在 `[A-Za-z0-9_-]` 集合里的字符）；
     * 空设备名返回空串（=不带后缀）。返回值永远以 `_` 开头或为空，方便直接拼。
     */
    fun deviceSuffix(rawDeviceName: String): String {
        val cleaned = rawDeviceName.replace(Regex("[^A-Za-z0-9_-]"), "")
        return if (cleaned.isEmpty()) "" else "_$cleaned"
    }

    /**
     * 判断一个文件名是不是备份文件（区别于 bookProgress 子目录里同名混入的情况）。
     * 列表过滤、清理旧备份都用这个。
     */
    fun isBackupFile(name: String): Boolean =
        name.startsWith(BACKUP_PREFIX) && name.endsWith(BACKUP_SUFFIX)
}
