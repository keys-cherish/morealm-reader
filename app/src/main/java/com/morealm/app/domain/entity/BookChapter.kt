package com.morealm.app.domain.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "book_chapters",
    indices = [Index("bookId")]
)
data class BookChapter(
    @PrimaryKey val id: String,
    val bookId: String,
    val index: Int,
    val title: String,
    val url: String = "",
    val nextUrl: String? = null,
    val startPosition: Long = 0L,
    val endPosition: Long = 0L,
    val isVolume: Boolean = false,
    val variable: String? = null,
)

/**
 * 「无目录 TXT」自动分节标题的格式 —— 由
 * [com.morealm.app.domain.parser.LocalBookParser.parseWithoutToc] 生成。
 *
 * 这种「第 N 节」其实不是真章节，只是按 10KB 切片做的 OOM 防御产物，对用户而言是
 * 「同一篇内容被莫名其妙切开」。匹配此模式的标题在 UI 层应该被合并显示为书名。
 *
 * 严格 `^第\d+节$` 不带空格、不带其他字符，避免误伤真正章节里 "第 22 节 风雪夜归人"
 * 这种带正文的章名。
 */
private val AUTO_SPLIT_TITLE_PATTERN = Regex("^第\\d+节$")

/**
 * 字符串级判断：传入的标题文本是否长得像 [parseWithoutToc] 自动生成的伪章名。
 *
 * 给那些拿不到 BookChapter / Book 上下文、只有标题字符串的渲染层用
 * （例如 [PageContentDrawer] 在画 page info overlay 时只有 `page.title`）。
 *
 * 误伤面：极少数真章节标题恰好是 `第\d+节` 或 `正文`。但 `parseWithTocPattern` 路径
 * 会保留章节正文里的额外字符（如 "第 22 节 风雪夜归人"），不会撞这条规则。
 */
fun String.looksLikeAutoSplitTitle(): Boolean {
    return AUTO_SPLIT_TITLE_PATTERN.matches(this) || this == "正文"
}

/**
 * 判断该章是否是 TXT 无目录解析时的自动分节产物。
 *
 * 触发条件：标题是 [AUTO_SPLIT_TITLE_PATTERN] 命中或纯 "正文"。
 * **注意**：返回 true 不代表书一定是本地 TXT；调用 [displayTitle] 时再附加 book
 * 来源校验，避免误伤其他来源的同名章节。
 */
fun BookChapter.isAutoSplitChapter(): Boolean = title.looksLikeAutoSplitTitle()

/**
 * 给 UI 显示用的章节标题。
 *
 * 自动分节（[isAutoSplitChapter] = true）+ 本地 TXT 时回退到书名；其余情况返回原标题。
 * 这样：
 *
 * - 阅读器顶栏：用户不会看到「第 22 节」这种伪标题，看到的是书名
 * - 章节列表：仍然按 chapter idx 显示（List 形态），但标题文本是书名
 * - 网络书源 / EPUB / MOBI 等：完全不受影响（解析流程不会产生 [isAutoSplitChapter] 命中）
 *
 * @param book 章节所属书。null 时返回原标题（兜底）。
 */
fun BookChapter.displayTitle(book: Book?): String {
    if (book == null) return title
    if (book.localPath.isNullOrEmpty()) return title
    if (book.format != BookFormat.TXT) return title
    return if (isAutoSplitChapter()) {
        book.title.ifBlank { "正文" }
    } else {
        title
    }
}
