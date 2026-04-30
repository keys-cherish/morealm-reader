package com.morealm.app.domain.repository

import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.entity.BookFormat
import com.morealm.app.domain.entity.BookGroup
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Auto-classify a book into one of the user's BookGroups.
 *
 * Resolution order (first hit wins):
 *   1. **User-keyword match** — group with `autoKeywords` matching the book's text
 *   2. **Genre heuristic** — title/kind/category/description contains genre keywords
 *      (玄幻/都市/历史/...) AND the user has a group named like the genre
 *   3. **Format fallback** — for local files, match a group named like "EPUB" / "TXT"
 *   4. **Source fallback** — for online books, match a group named after the source
 *
 * Returns null when nothing matches — caller leaves book in the default "全部" view.
 *
 * We deliberately don't auto-create groups: that would surprise users who curated
 * their shelf manually. If a user wants automatic genre buckets, they create a group
 * named "玄幻" (or any of the listed names) and books with玄幻 keywords will flow in.
 */
@Singleton
class AutoGroupClassifier @Inject constructor(
    private val groupRepo: BookGroupRepository,
) {
    suspend fun classify(book: Book): String? {
        if (book.folderId != null) return book.folderId
        val groups = groupRepo.getAllGroupsSync()
        if (groups.isEmpty()) return null
        // 1) user-keyword
        matchGroup(book, groups)?.let { return it.id }
        // 2) genre heuristic
        classifyByGenre(book, groups)?.let { return it.id }
        // 3) format / source fallback
        classifyByFormatOrSource(book, groups)?.let { return it.id }
        return null
    }

    /** User-keyword match (preserved from earlier behavior). */
    fun matchGroup(book: Book, groups: List<BookGroup>): BookGroup? {
        val haystack = buildSearchText(book)
        if (haystack.isBlank()) return null
        return groups
            .filter { it.autoKeywords.isNotBlank() }
            .sortedWith(compareByDescending<BookGroup> { keywordList(it.autoKeywords).size }.thenBy { it.sortOrder })
            .firstOrNull { group ->
                keywordList(group.autoKeywords).any { keyword ->
                    haystack.contains(keyword.lowercase())
                }
            }
    }

    /**
     * Heuristic: scan the book's text for genre keywords. If matched, look for a user
     * group whose name contains the genre tag (e.g. user group "玄幻奇幻" matches "玄幻").
     */
    private fun classifyByGenre(book: Book, groups: List<BookGroup>): BookGroup? {
        val text = buildSearchText(book)
        if (text.isBlank()) return null
        for ((tag, keywords) in GENRE_KEYWORDS) {
            if (keywords.any { text.contains(it) }) {
                groups.firstOrNull { g -> g.name.contains(tag) }?.let { return it }
            }
        }
        return null
    }

    /**
     * For local files: match a group whose name contains the file format ("EPUB" / "TXT").
     * For online books: match a group whose name contains the source name (e.g. "起点").
     */
    private fun classifyByFormatOrSource(book: Book, groups: List<BookGroup>): BookGroup? {
        // local file format
        when (book.format) {
            BookFormat.EPUB, BookFormat.TXT, BookFormat.PDF -> {
                val token = book.format.name
                groups.firstOrNull { it.name.contains(token, ignoreCase = true) }?.let { return it }
            }
            BookFormat.WEB -> {
                val origin = book.originName.ifBlank { return null }
                groups.firstOrNull {
                    it.name.contains(origin, ignoreCase = true) ||
                        origin.contains(it.name, ignoreCase = true)
                }?.let { return it }
            }
            else -> Unit
        }
        return null
    }

    private fun buildSearchText(book: Book): String = listOfNotNull(
        book.title,
        book.author,
        book.description,
        book.category,
        book.kind,
        book.customTag,
        book.wordCount,
        book.localPath,
        book.originName,
    ).joinToString("\n") { it.lowercase() }

    private fun keywordList(value: String): List<String> = value
        .split(',', '，', ';', '；', '\n', '|', '/', '、')
        .map { it.trim().lowercase() }
        .filter { it.isNotBlank() }
        .distinct()

    companion object {
        /**
         * Genre tag → list of substring matches against the book's text.
         * Order matters: more specific tags first (so "修真" wins over "玄幻"
         * when both could match a 修真 novel).
         */
        private val GENRE_KEYWORDS: List<Pair<String, List<String>>> = listOf(
            "修真" to listOf("修真", "仙侠", "修仙", "渡劫", "金丹", "元婴"),
            "玄幻" to listOf("玄幻", "魔法", "异界", "斗气", "斗破", "斗罗", "魔兽", "战神"),
            "武侠" to listOf("武侠", "江湖", "剑客", "金庸", "古龙", "梁羽生"),
            "都市" to listOf("都市", "都市异能", "都市修真", "都市重生"),
            "历史" to listOf("历史", "穿越历史", "三国", "明朝", "宋朝", "汉朝", "唐朝", "春秋", "战国"),
            "军事" to listOf("军事", "抗战", "兵王", "特种"),
            "科幻" to listOf("科幻", "末世", "星际", "机甲", "未来", "异能", "超能"),
            "网游" to listOf("网游", "游戏世界", "电竞", "竞技"),
            "悬疑" to listOf("悬疑", "推理", "侦探", "刑侦"),
            "灵异" to listOf("灵异", "诡异", "鬼故事", "盗墓", "鬼吹"),
            "恐怖" to listOf("恐怖", "惊悚"),
            "言情" to listOf("言情", "总裁", "豪门", "女频", "宠妻", "婚恋", "霸总"),
            "同人" to listOf("同人"),
            "二次元" to listOf("二次元", "动漫", "轻小说"),
            "短篇" to listOf("短篇", "故事集"),
        )
    }
}
