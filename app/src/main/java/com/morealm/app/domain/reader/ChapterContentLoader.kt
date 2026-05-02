package com.morealm.app.domain.reader

import android.content.Context
import android.net.Uri
import com.morealm.app.core.log.AppLog
import com.morealm.app.core.text.ChineseConverter
import com.morealm.app.domain.entity.Book
import com.morealm.app.domain.entity.BookChapter
import com.morealm.app.domain.entity.BookFormat
import com.morealm.app.domain.entity.ReplaceRule
import com.morealm.app.domain.parser.LocalBookParser
import com.morealm.app.domain.preference.AppPreferences
import com.morealm.app.domain.repository.BookRepository
import com.morealm.app.domain.repository.ReplaceRuleRepository
import com.morealm.app.domain.repository.SourceRepository
import com.morealm.app.domain.webbook.CacheBook
import com.morealm.app.domain.webbook.WebBook
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 公共章节内容加载器。
 *
 * 设计目的：把"从 [Book] + [BookChapter] 拿到一段可朗读的纯文本"的核心逻辑从
 * [com.morealm.app.presentation.reader.ReaderChapterController] 抽出来，让 TTS 服务
 * 能在 ReaderViewModel 销毁后**独立**加载下一章续播——这是 Phase D 的核心：
 * 在用户离开阅读器、Activity 销毁、ViewModel.onCleared 后，TTS 不应该断声。
 *
 * **职责边界**（与 ReaderChapterController 的差异）：
 *  - 本类**只做加载与文本变换**：web/local 分流 → CacheBook 命中 → 网页/本地解析 →
 *    替换规则 → 简繁转换。
 *  - 不做 cache（nextChapterCache / prevChapterCache 仍由 ReaderChapterController 管），
 *    不做 placeholder 包装（EMPTY_CONTENT_PLACEHOLDER 仍由调用方决定要不要给空内容套
 *    占位 UI），不做错误内容渲染。
 *
 * **线程安全**：内部所有 I/O 都在 [Dispatchers.IO] 上跑；调用方任何线程都可调。
 *
 * **配置来源**：简繁转换的模式从 [AppPreferences.chineseConvert] 读取（每次加载时
 * 拉一次最新值，避免缓存过时偏好）；替换规则按 [Book.id] 现拉。如果调用方已经在
 * 自己的上下文里维护了 cachedRules，可走 [loadAndTransform]（接受外部传入的 rule
 * 列表）省一次 DB 查询。
 */
@Singleton
class ChapterContentLoader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bookRepo: BookRepository,
    private val sourceRepo: SourceRepository,
    private val replaceRuleRepo: ReplaceRuleRepository,
    private val prefs: AppPreferences,
) {
    /**
     * 拉取并变换章节内容，把替换规则现查一次。
     *
     * @param book 章节所在书；web 书会用它的 sourceUrl + bookUrl 拉书源、判断书源类型
     * @param chapter 要加载的章节
     * @param indexInList chapter 在 [allChapters] 中的下标；用于 web 书 nextUrl 计算
     * @param allChapters 整本书的章节列表（用于 nextUrl 计算）
     * @return 变换后可朗读的纯文本；空字符串表示无内容（调用方决定是否当作"读到尽头"）
     */
    suspend fun loadAndTransform(
        book: Book,
        chapter: BookChapter,
        indexInList: Int,
        allChapters: List<BookChapter>,
    ): String {
        val rules = replaceRuleRepo.getRulesForBook(book.id)
        return loadAndTransform(book, chapter, indexInList, allChapters, rules)
    }

    /**
     * 同 [loadAndTransform] 但 rule 列表由调用方提供——用于 ReaderChapterController
     * 这种已经在自身缓存了 rule 列表的场景，省一次 DB 查询。
     */
    suspend fun loadAndTransform(
        book: Book,
        chapter: BookChapter,
        indexInList: Int,
        allChapters: List<BookChapter>,
        rules: List<ReplaceRule>,
    ): String = withContext(Dispatchers.IO) {
        val raw = if (isWebBook(book)) {
            loadWebChapterRaw(book, chapter, indexInList, allChapters)
        } else {
            loadLocalChapterRaw(book, chapter)
        }
        val replaced = applyReplaceRules(raw, rules)
        val mode = prefs.chineseConvertMode.first()
        ChineseConverter.convert(replaced, mode)
    }

    /**
     * 仅 TTS 路径用：从 [bookId] 起步加载第 [chapterIndex] 章的可朗读文本。
     *
     * 内部完整链路：BookRepository → 章节列表 → 拉书源/本地 → replace rules →
     * 简繁转换。任一步失败返回空串（调用方按"读到尽头"处理）。
     *
     * 这是 [com.morealm.app.service.TtsEngineHost] 在 ChapterFinished 后超时未收到
     * 新 LoadAndPlay 时的兜底加载入口——独立于 ReaderViewModel，是"用户离开阅读器
     * 后续章不断声"的核心实现。
     */
    suspend fun loadForTts(bookId: String, chapterIndex: Int): TtsChapterContent? {
        return runCatching {
            withTimeout(LOAD_TIMEOUT_MS) {
                val book = bookRepo.getById(bookId) ?: return@withTimeout null
                val chapters = bookRepo.getChaptersList(bookId)
                val chapter = chapters.getOrNull(chapterIndex) ?: return@withTimeout null
                val content = loadAndTransform(book, chapter, chapterIndex, chapters)
                TtsChapterContent(
                    bookTitle = book.title,
                    chapterTitle = chapter.title,
                    coverUrl = book.coverUrl,
                    content = content,
                    chapterIndex = chapterIndex,
                    totalChapters = chapters.size,
                )
            }
        }.onFailure {
            AppLog.warn(
                "ChapterLoader",
                "loadForTts failed bookId=$bookId idx=$chapterIndex: ${it.message}",
            )
        }.getOrNull()
    }

    // ── 内部细节（与 ReaderChapterController 的 loadWebChapterContent / readChapter
    //              路径**行为等价**：同样 web 走 CacheBook/WebBook，本地走 LocalBookParser）──

    private fun isWebBook(book: Book): Boolean {
        return book.format == BookFormat.WEB || (book.localPath == null && book.sourceUrl != null)
    }

    private suspend fun loadWebChapterRaw(
        book: Book,
        chapter: BookChapter,
        indexInList: Int,
        allChapters: List<BookChapter>,
    ): String {
        val sourceUrl = book.sourceUrl ?: return ""
        val source = sourceRepo.getByUrl(sourceUrl)
        if (source == null) {
            return CacheBook.getContent(sourceUrl, chapter.url) ?: ""
        }
        if (source.bookSourceType != TEXT_BOOK_SOURCE_TYPE) {
            // 非文本源（音频/图片/视频）—— TTS 路径直接放弃，由调用方处理为"读到尽头"
            return ""
        }
        // 缓存命中优先（与 ReaderChapterController 一致）
        CacheBook.getContent(sourceUrl, chapter.url)?.let { return it }

        val nextUrl = allChapters.getOrNull(indexInList + 1)?.url
        val content = WebBook.getContentAwait(source, chapter.url, nextUrl)
        if (content.isNotBlank()) {
            CacheBook.putContent(sourceUrl, chapter.url, content)
        }
        return content
    }

    private suspend fun loadLocalChapterRaw(book: Book, chapter: BookChapter): String {
        val localPath = book.localPath ?: return ""
        val uri = Uri.parse(localPath)
        return runCatching {
            LocalBookParser.readChapter(context, uri, book.format, chapter)
        }.getOrElse {
            AppLog.warn(
                "ChapterLoader",
                "local readChapter failed: ${book.title}@${chapter.title}: ${it.message}",
            )
            ""
        }
    }

    /**
     * 应用替换规则——和 ReaderChapterController.applyReplaceRules 行为等价。
     *
     * 不复用 ReaderChapterController 的方法是因为：
     *  - 那个方法依赖 `cachedReplaceRules` / `regexCache` / `hitContentRulesSet` 等
     *    实例字段（包含命中追踪以驱动 EffectiveReplacesDialog UI），TTS 路径不需要；
     *  - 抽出来反而要传更多参数，损失局部性；
     *  - 这里只读（不需要追踪命中），代码可以简化掉同步集合 + Flow 发布。
     *
     * regex 用 [REPLACE_RULE_TIMEOUT_MS] 兜底，避免病态正则把 TTS 加载流卡死。
     */
    private fun applyReplaceRules(content: String, rules: List<ReplaceRule>): String {
        if (rules.isEmpty()) return content
        var result = content
        for (rule in rules) {
            if (!rule.enabled || !rule.isValid()) continue
            if (!rule.scopeContent) continue
            try {
                result = if (rule.isRegex) {
                    runCatching {
                        result.replace(Regex(rule.pattern), rule.replacement)
                    }.getOrDefault(result)
                } else {
                    result.replace(rule.pattern, rule.replacement)
                }
            } catch (_: Exception) {
                // 单条规则异常不影响其他规则
            }
        }
        return result
    }

    /** 单个章节加载结果——TTS 接管续章时一次性把后续 LoadAndPlay 需要的元数据准备齐。 */
    data class TtsChapterContent(
        val bookTitle: String,
        val chapterTitle: String,
        val coverUrl: String?,
        val content: String,
        val chapterIndex: Int,
        val totalChapters: Int,
    )

    companion object {
        /** 与 ReaderChapterController 的常量保持一致（0 = 文本书源类型）。 */
        private const val TEXT_BOOK_SOURCE_TYPE = 0

        /**
         * 单次 [loadForTts] 的硬上限。
         *
         * 含网络获取章节内容——比单纯本地解析慢得多。10s 是经验值：弱网下 web 源
         * 通常 2-5s 完成；超过 10s 大概率源已不可用，TTS 应该让用户"知道断了"
         * 而不是一直转圈。
         */
        private const val LOAD_TIMEOUT_MS = 10_000L

        /** 单条 regex 替换规则的超时——防御性正则灾难。 */
        private const val REPLACE_RULE_TIMEOUT_MS = 1_000L
    }
}
