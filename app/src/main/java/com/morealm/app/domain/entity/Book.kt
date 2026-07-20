package com.morealm.app.domain.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "books",
    indices = [
        Index("folderId"),
        Index("lastReadAt"),
        Index("sourceId"),
    ]
)
data class Book(
    @PrimaryKey val id: String,
    val title: String,
    val author: String = "",
    val coverUrl: String? = null,
    /**
     * 用户自定义封面（走 CoverStorage，存为 WebP 在 filesDir/covers/BOOK/{id}.webp）。
     * 非 null 时显示优先级高于 coverUrl；null 时退回 coverUrl。
     */
    val customCoverUrl: String? = null,
    val localPath: String? = null,
    val sourceId: String? = null,
    val sourceUrl: String? = null,
    val folderId: String? = null,
    val format: BookFormat = BookFormat.TXT,

    // Reading state
    val lastReadChapter: Int = 0,
    val lastReadPosition: Int = 0,
    val lastReadOffset: Float = 0f,
    val totalChapters: Int = 0,
    val readProgress: Float = 0f,

    // Metadata
    val hasDetail: Boolean = false,
    val description: String? = null,
    val wordCount: String? = null,
    val rating: String? = null,
    val category: String? = null,
    val charset: String? = null,

    // Source-related fields
    val bookUrl: String = "",
    val tocUrl: String? = null,
    val origin: String = "",
    val originName: String = "",
    val kind: String? = null,
    val customTag: String? = null,
    val variable: String? = null,

    // Timestamps
    val addedAt: Long = System.currentTimeMillis(),
    val lastReadAt: Long = 0L,
    val latestChapterTime: Long = 0L,

    // Sort & display
    val pinned: Boolean = false,
    val sortOrder: Int = 0,

    // ── Update tracking (Legado-parity, since v16) ──
    /**
     * Number of new chapters discovered on the most recent toc refresh.
     * Used by the shelf "N 新" badge. Cleared (set to 0) when the user opens
     * the book — not when they finish reading the new chapters, matching
     * Legado's `Book.lastCheckCount` semantics.
     */
    val lastCheckCount: Int = 0,
    /** Wall-clock time (ms) of the most recent toc refresh attempt for this book. */
    val lastCheckTime: Long = 0L,
    /** When false, batch toc-refresh skips this book (user opted out). */
    val canUpdate: Boolean = true,

    // ── Auto-grouping bookkeeping (since v17) ──
    /** AUTO = TagResolver assigned the current folderId; MANUAL = user moved it; HYBRID = mixed. */
    val tagsAssignedBy: String = "AUTO",
    /** When true, TagResolver never overwrites this book's tags or folderId. */
    val groupLocked: Boolean = false,

    // ── Comic mode (since v31) ──
    /**
     * 漫画书标记。导入 MOBI/AZW3/CBZ 时由 [com.morealm.app.domain.parser.ComicBookDetector]
     * 判断（图片资源数 ≥ 阈值 且 文本极少），打开时走独立 [com.morealm.app.ui.reader.comic.ComicReaderScreen]
     * 渲染管线（LazyColumn + Coil，无 padding 无间距，进度按图片数）—— 与小说 ChapterProvider
     * 文本管线完全解耦，便于独立维护演进。
     */
    val isComic: Boolean = false,

    // ── Shelf membership (since v35) ──
    /**
     * 是否在书架显示。true = 正式书架书（默认；v34→v35 迁移时所有老书置 true，保留在架）。
     * false = 「查看 / 试读但未点加入书架」的临时记录——可正常阅读，但不出现在书架 / 文件夹 /
     * 本地搜索 / 批量刷新里。详情页点「加入书架」翻 true；离开仍未加入则按退出提示清除。
     */
    @ColumnInfo(defaultValue = "1")
    val inBookshelf: Boolean = true,

    // ── 本地文件指纹 (since v36) ──
    /**
     * 导入时记录的文件大小（字节）。与 [fileMtime] 一起构成本地书的文件指纹：
     * 打开书时若指纹与当前文件一致 → 章节目录直接用 DB 缓存（chapters 表），
     * 跳过全文件解析（1GB TXT 二次打开秒开的关键）；指纹变化 → 文件被外部
     * 替换/追更 → 重新解析并刷新缓存。0 = 老书 / 未知（首开解析后回填）。
     */
    @ColumnInfo(defaultValue = "0")
    val fileSize: Long = 0L,
    /** 导入时记录的文件 lastModified（ms）。语义见 [fileSize]。SAF 拿不到时为 0（仅按 size 校验）。 */
    @ColumnInfo(defaultValue = "0")
    val fileMtime: Long = 0L,

    // ── 弹性扩展列 (since v37) ──
    /**
     * JSON 弹性字段容器。此后新增「不参与 SQL WHERE/ORDER」的字段一律进这里
     * （Kotlin 侧映射 @Serializable data class + 默认值），加/删字段零 migration；
     * 需要被 SQL 查询/排序时才升级为关系列（那时才写一次 migration）。
     */
    @ColumnInfo(defaultValue = "{}")
    val extras: String = "{}",
) {
    /**
     * 展示用封面：自定义封面优先，空则退回原封面。
     *
     * 所有「显示单本书封面」处统一走它，避免各 UI 各写一遍 `customCoverUrl ?: coverUrl`
     * 造成漂移——列表项 / 继续阅读卡曾因直接读 coverUrl 而漏掉自定义封面即源于此。
     * 计算属性（非构造参数）→ Room 不建列、kotlinx.serialization 不序列化，无迁移影响。
     */
    val displayCoverUrl: String?
        get() = customCoverUrl?.takeIf { it.isNotBlank() } ?: coverUrl
}

@Serializable
enum class BookFormat {
    TXT, EPUB, PDF, MOBI, AZW3, CBZ, UMD, WEB, UNKNOWN
}
