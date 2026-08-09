package com.morealm.app.domain.webbook

import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.db.ReplaceRuleDao
import com.morealm.app.domain.entity.ReplaceRule
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 正文后处理器 - 去重标题、替换净化、繁简转换
 *
 * 参照实现对齐（v1.2 对齐）：
 * - **Title / Content 规则分别查 DB** —— 走 [ReplaceRuleDao.findEnabledByTitleScope] /
 *   [ReplaceRuleDao.findEnabledByContentScope]，SQL 已经 filter scopeTitle/scopeContent
 *   + scope LIKE + excludeScope 反向排除，Kotlin 侧不再做二次 filter。
 * - **`<usehtml>` 占位** —— 替换循环前把 `<usehtml>...</usehtml>` 段替换成占位符，
 *   循环结束后再还原。让用户的"我这段不要被规则改"的明确声明真正生效。
 * - **regex 超时保护** —— 单条规则跑超过 `timeoutMs` 会被 [regexExecutor] cancel，
 *   自动 disable 该规则并写回 DB，避免 catastrophic backtracking 永久卡死阅读器。
 */
class ContentProcessor(
    private val bookName: String,
    private val bookOrigin: String,
    private val replaceRuleDao: ReplaceRuleDao?,
) {

    private var titleReplaceRules: List<ReplaceRule> = emptyList()
    private var contentReplaceRules: List<ReplaceRule> = emptyList()

    /**
     * 缓存按 kind 拆分后的列表，避免每次 process() 都遍历 contentReplaceRules
     * 重新分组。规则数量改动经过 [upReplaceRules] 时同步刷新。
     *
     * 顺序约定：先 PURIFY 后 GENERAL —— 净化只删不改，先把广告 / 版权声明清掉
     * 能避免 GENERAL 的语义替换误中广告里出现的关键字。
     */
    private var purifyContentRules: List<ReplaceRule> = emptyList()
    private var generalContentRules: List<ReplaceRule> = emptyList()

    init {
        upReplaceRules()
    }

    fun upReplaceRules() {
        val dao = replaceRuleDao ?: return
        // SQL 直接按 (scope LIKE + excludeScope NOT LIKE + scopeTitle/Content) 过滤 ——
        // Kotlin 侧不再 .filter { it.scopeTitle / it.scopeContent }，避免和 SQL 双重过滤。
        titleReplaceRules = dao.findEnabledByTitleScope(bookName, bookOrigin)
        contentReplaceRules = dao.findEnabledByContentScope(bookName, bookOrigin)
        purifyContentRules = contentReplaceRules.filter { it.kind == ReplaceRule.KIND_PURIFY }
        generalContentRules = contentReplaceRules.filter { it.kind != ReplaceRule.KIND_PURIFY }
    }

    fun getTitleReplaceRules(): List<ReplaceRule> = titleReplaceRules

    fun getContentReplaceRules(): List<ReplaceRule> = contentReplaceRules

    /**
     * 处理正文内容：去重标题 + 替换净化 + 段首缩进
     *
     * 参照实现对齐 behavior:
     * - 标题去重尝试两遍：原标题，再用应用过 titleReplaceRules 的标题
     * - 第一行（章节标题）不加段首缩进；其他段才加 "　　"
     * - 单条替换规则失败 / 超时不影响其他规则
     * - `<usehtml>...</usehtml>` 段在替换循环前占位、循环后还原 —— 让用户显式声明
     *   "这段保留原文不要净化"真正起作用（对齐参照实现 adaptSpecialStyle）
     */
    fun process(
        chapterTitle: String,
        content: String,
        useReplace: Boolean = true,
        includeTitle: Boolean = true,
    ): String {
        if (content == "null" || content.isBlank()) return content
        var mContent = content
        val displayTitle = applyTitleReplace(chapterTitle, useReplace)

        // 去除重复标题：先用原标题匹配；不命中再尝试替换后标题
        try {
            val escapedName = Regex.escape(bookName)
            for (candidateTitle in listOf(chapterTitle, displayTitle).distinct()) {
                if (candidateTitle.isBlank()) continue
                val escapedTitle = Regex.escape(candidateTitle).replace("\\s+".toRegex(), "\\\\s*")
                val titlePattern = "^(\\s|\\p{Punct}|${escapedName})*${escapedTitle}(\\s)*".toRegex()
                val match = titlePattern.find(mContent)
                if (match != null) {
                    mContent = mContent.substring(match.range.last + 1)
                    break
                }
            }
        } catch (_: Exception) {}

        // 替换净化（按行 trim 后再做规则替换）
        // 顺序：<usehtml> 占位 → trim → PURIFY → GENERAL → 还原 <usehtml>
        //   - PURIFY 先于 GENERAL：净化只删不改，先清广告 / 版权声明，
        //     再做语义替换；倒过来会让 GENERAL 误中广告里出现的关键字。
        //   - <usehtml> 占位让用户显式声明的"这段保留原文"段不被任何规则误伤；
        //     占位字符必须是规则极不可能匹配的形式（HTML 元字符不出现 + 数字下标），
        //     对齐参照实现 `adaptSpecialStyle` 块。
        if (useReplace) {
            val useHtmlMap = mutableMapOf<String, String>()
            if (USEHTML_HINT in mContent) {
                mContent = USEHTML_REGEX.replace(mContent) { match ->
                    val placeholder = "${USEHTML_PLACEHOLDER_PREFIX}${useHtmlMap.size}${USEHTML_PLACEHOLDER_SUFFIX}"
                    useHtmlMap[placeholder] = match.value
                    placeholder
                }
            }
            mContent = mContent.lines().joinToString("\n") { it.trim() }
            for (rule in purifyContentRules) {
                if (rule.pattern.isEmpty()) continue
                mContent = applyReplaceWithTimeout(mContent, rule)
            }
            for (rule in generalContentRules) {
                if (rule.pattern.isEmpty()) continue
                mContent = applyReplaceWithTimeout(mContent, rule)
            }
            // 占位还原（即使占位 0 个也安全 —— forEach 空 map noop）
            useHtmlMap.forEach { (placeholder, original) ->
                mContent = mContent.replace(placeholder, original)
            }
        }

        // 段落缩进：标题（第一行）不缩进；其余段加全角空格
        val paragraphs = ArrayList<String>()
        if (includeTitle) {
            paragraphs.add(displayTitle)
        }
        for (line in mContent.split("\n")) {
            val trimmed = line.trim { it.code <= 0x20 || it == '　' }
            if (trimmed.isEmpty()) continue
            paragraphs.add("　　$trimmed")
        }
        return paragraphs.joinToString("\n")
    }

    private fun applyTitleReplace(title: String, useReplace: Boolean): String {
        if (!useReplace) return title
        var result = title
        for (rule in titleReplaceRules) {
            if (rule.pattern.isEmpty()) continue
            result = applyReplaceWithTimeout(result, rule)
        }
        return result
    }

    /**
     * 单条规则的替换 + 超时保护。
     *
     * 用 [regexExecutor] submit 任务，主线程 `get(timeout)` 等待结果：
     *  - 正常完成 → 返回替换后的字符串
     *  - 抛异常（pattern 语法错 / replacement `$` 转义错）→ 静默返回原文，规则跳过
     *  - 超过 [ReplaceRule.timeoutMs] → cancel future，记 warn，自动写回 DB 把规则
     *    enabled = false（对齐参照实现 RegexTimeoutException → `item.isEnabled = false`），
     *    避免下次进入这一章再被卡住。
     *
     * 注意 Java regex 在 catastrophic backtracking 下**不响应 Thread.interrupt**，
     * 所以 future.cancel(true) 后线程会继续跑直到自然结束。这是 trade-off：
     * 我们接受单次后台 CPU 浪费，换 UI 线程立即返回。线程自然结束后 GC 回收，
     * 不会累积，因为我们用了固定的单线程 executor + auto-disable 防复发。
     */
    private fun applyReplaceWithTimeout(content: String, rule: ReplaceRule): String {
        val timeoutMs = rule.timeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS).toLong()
        val future = regexExecutor.submit<String> {
            if (rule.isRegex) {
                content.replace(rule.pattern.toRegex(), rule.replacement)
            } else {
                content.replace(rule.pattern, rule.replacement)
            }
        }
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            AppLog.warn(
                TAG,
                "Replace timeout: rule='${rule.name}' pattern='${rule.pattern.take(80)}…' " +
                    "after ${timeoutMs}ms — auto-disabling to avoid blocking next read",
            )
            disableRuleAsync(rule)
            content
        } catch (e: Exception) {
            // ExecutionException 包了 PatternSyntaxException / IllegalArgumentException 等
            AppLog.debug(
                TAG,
                "Replace failed: rule='${rule.name}' ${e.cause?.javaClass?.simpleName ?: e.javaClass.simpleName}: " +
                    "${(e.cause?.message ?: e.message)?.take(120)}",
            )
            content
        }
    }

    /**
     * 把超时规则写回 DB 为 disabled。fire-and-forget：失败也无所谓 —— 主流程已经
     * 跳过这条规则，下次最坏再触发一次超时；用户感知是「这条规则用不了」而非「阅读器死了」。
     */
    private fun disableRuleAsync(rule: ReplaceRule) {
        val dao = replaceRuleDao ?: return
        regexExecutor.execute {
            try {
                kotlinx.coroutines.runBlocking {
                    dao.insert(rule.copy(enabled = false))
                }
            } catch (e: Exception) {
                AppLog.debug(TAG, "disableRuleAsync failed for '${rule.name}': ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "ContentProcessor"

        /** 单条规则替换的下限超时（500ms），防御用户配置 0 / 负数。 */
        private const val MIN_TIMEOUT_MS = 500

        /** 单条规则替换的上限超时（30s），catastrophic backtracking 兜底。参照实现默认 3s。 */
        private const val MAX_TIMEOUT_MS = 30_000

        /** 快速判断 content 是否含 `<usehtml>` 段，避免 regex 调度开销。 */
        private const val USEHTML_HINT = "<usehtml>"

        private val USEHTML_REGEX = Regex("<usehtml>.*?</usehtml>", RegexOption.DOT_MATCHES_ALL)

        /** 占位符前缀/后缀刻意用「正则不可能匹配的形式」+ 中文，最大限度避免与规则冲突。 */
        private const val USEHTML_PLACEHOLDER_PREFIX = "「莫境占位usehtml#"
        private const val USEHTML_PLACEHOLDER_SUFFIX = "」"

        /**
         * 单线程后台 executor，专供 [applyReplaceWithTimeout] 使用。
         *
         * - **单线程**：替换是顺序的，没必要并行；同一进程同时只跑一条规则的替换。
         * - **daemon 线程**：JVM 退出时不阻塞，对单测和重启场景友好。
         * - **lazy**：单测里没规则 → 不会创建线程，零开销。
         */
        private val regexExecutor by lazy {
            Executors.newSingleThreadExecutor { r ->
                Thread(r, "MoRealm-RegexReplace").apply { isDaemon = true }
            }
        }
    }
}
