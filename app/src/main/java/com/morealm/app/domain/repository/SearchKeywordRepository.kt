package com.morealm.app.domain.repository

import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.db.SearchKeywordDao
import com.morealm.app.domain.entity.SearchKeyword
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 搜索历史仓库。封装 [SearchKeywordDao] 的 upsert 累加策略，让 ViewModel
 * 不必自己处理"已存在 → 计数 +1，不存在 → 新建" 的两步逻辑。
 *
 * 设计要点：
 *   - [record] 是「记录一次搜索」的唯一入口。所有"用户按下回车搜了 X"的位置
 *     都应该调它，不要直接走 dao.upsert，否则计数会错。
 *   - 空字符串 / 长度 > MAX_WORD_LEN 的输入会被静默忽略 —— 异常输入不应该污染历史。
 *   - 历史规模上限通过 [trimToMax] 维护：单次写入后超过 200 条就裁掉计数最少 + 时间最旧的。
 *     这避免历史无限增长（粘贴一段长文搜索一次也只占一行）。
 */
@Singleton
class SearchKeywordRepository @Inject constructor(
    private val dao: SearchKeywordDao,
) {
    /** 单条历史最大字符数：超过这个长度的"搜索词"几乎一定是用户误粘贴或日志泄漏，不入库。 */
    private val maxWordLen = 200

    /** 历史总条数上限。Legado 里没有硬上限，但 200 条已经远超普通用户需要。 */
    private val maxHistorySize = 200

    /** 默认下拉显示数。UI 端可以再 take(n) 截短，这里给 50 让长按时也能看到老一点的。 */
    fun observeAll(limit: Int = maxHistorySize): Flow<List<SearchKeyword>> = dao.topAll(limit)

    /**
     * 记录一次搜索。
     *
     * - 输入会先 trim —— 用户复制黏贴常带前后空格；
     * - 空 / 过长 / 全空白都早返回，不污染表；
     * - 已存在 → usage += 1, lastUseTime = now；
     * - 不存在 → 新建 row（usage = 1）。
     *
     * 写完后异步 [trimToMax]，超量删最低优先级的。
     * 失败只打 warn 日志不抛 —— 搜索历史是 nice-to-have，写库失败不应该让用户的搜索流程崩。
     */
    suspend fun record(rawWord: String) {
        val word = rawWord.trim()
        if (word.isEmpty() || word.length > maxWordLen) return
        val now = System.currentTimeMillis()
        try {
            val existing = dao.get(word)
            if (existing != null) {
                dao.upsert(existing.copy(lastUseTime = now, usage = existing.usage + 1))
            } else {
                dao.upsert(SearchKeyword(word = word, lastUseTime = now, usage = 1))
                trimToMax()
            }
        } catch (e: Exception) {
            AppLog.warn("SearchKeyword", "record failed: ${e.message}")
        }
    }

    /**
     * 前缀联想：用户输入到一半时拉同前缀的历史。结果保留 [limit] 条。
     * 空查询会触发 [observeAll] 全量返回 — 调用方不应该传空，但兜个底。
     */
    suspend fun suggestionsForPrefix(prefix: String, limit: Int = 10): List<SearchKeyword> {
        val p = prefix.trim()
        if (p.isEmpty()) return dao.topAllSync(limit)
        return try {
            dao.searchPrefix(p, limit)
        } catch (e: Exception) {
            AppLog.warn("SearchKeyword", "suggestionsForPrefix failed: ${e.message}")
            emptyList()
        }
    }

    suspend fun delete(word: String) {
        try {
            dao.deleteByWord(word)
        } catch (e: Exception) {
            AppLog.warn("SearchKeyword", "delete failed: ${e.message}")
        }
    }

    suspend fun clear() {
        try {
            dao.clear()
            AppLog.info("SearchKeyword", "history cleared")
        } catch (e: Exception) {
            AppLog.warn("SearchKeyword", "clear failed: ${e.message}")
        }
    }

    /**
     * 批量恢复历史。配合 UI 「清空 + Snackbar 撤销」用：UI 在清空前 snapshot 当前
     * 列表，用户点撤销 → 调本方法把整张表的内容写回去。
     *
     * 不复用 [record]：record 会改 lastUseTime / 累加 usage，撤销应当原样还原（包括
     * 计数和时间戳），所以走 dao.upsert 直接 REPLACE。upsert 失败的条目静默跳过 —
     * 撤销是 best-effort，丢一两条历史比抛异常打断 UI 流程更合适。
     */
    suspend fun restoreAll(keywords: List<SearchKeyword>) {
        if (keywords.isEmpty()) return
        var ok = 0
        keywords.forEach { kw ->
            try {
                dao.upsert(kw)
                ok++
            } catch (e: Exception) {
                AppLog.warn("SearchKeyword", "restore one failed: ${e.message}")
            }
        }
        AppLog.info("SearchKeyword", "restored $ok / ${keywords.size} entries")
    }

    /**
     * 超量裁剪 —— 实现策略简单粗暴：把所有历史按 (usage DESC, lastUseTime DESC) 排好，
     * 取出 maxHistorySize 之外的全部 deleteByWord。
     *
     * 之所以不写一条 `DELETE FROM search_keyword WHERE word NOT IN (SELECT ...)`：
     *   - SQLite 子查询和 NOT IN 在 200 行表上没性能优势；
     *   - 这里逐条删能 reuse 现有 [SearchKeywordDao.deleteByWord]，不必加新 SQL；
     *   - 失败时只丢一条历史，不会破坏整张表。
     */
    private suspend fun trimToMax() {
        try {
            val all = dao.topAllSync(Int.MAX_VALUE)
            if (all.size <= maxHistorySize) return
            val toDelete = all.drop(maxHistorySize)
            toDelete.forEach { dao.deleteByWord(it.word) }
            AppLog.info("SearchKeyword", "trimmed ${toDelete.size} entries")
        } catch (e: Exception) {
            AppLog.warn("SearchKeyword", "trim failed: ${e.message}")
        }
    }
}
