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
)

@Serializable
enum class BookFormat {
    TXT, EPUB, PDF, MOBI, AZW3, CBZ, UMD, WEB, UNKNOWN
}
