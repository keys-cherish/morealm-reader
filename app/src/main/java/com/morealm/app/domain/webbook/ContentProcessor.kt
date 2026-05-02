package com.morealm.app.domain.webbook

import com.morealm.app.domain.db.ReplaceRuleDao
import com.morealm.app.domain.entity.ReplaceRule

/**
 * 正文后处理器 - 去重标题、替换净化、繁简转换
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
        replaceRuleDao?.let { dao ->
            titleReplaceRules = dao.getEnabledByScope(bookName, bookOrigin)
                .filter { it.scopeTitle }
            contentReplaceRules = dao.getEnabledByScope(bookName, bookOrigin)
                .filter { it.scopeContent }
            purifyContentRules = contentReplaceRules.filter { it.kind == ReplaceRule.KIND_PURIFY }
            generalContentRules = contentReplaceRules.filter { it.kind != ReplaceRule.KIND_PURIFY }
        }
    }

    fun getTitleReplaceRules(): List<ReplaceRule> = titleReplaceRules

    fun getContentReplaceRules(): List<ReplaceRule> = contentReplaceRules

    /**
     * 处理正文内容：去重标题 + 替换净化 + 段首缩进
     *
     * Legado-parity behavior:
     * - 标题去重尝试两遍：原标题，再用应用过 titleReplaceRules 的标题
     * - 第一行（章节标题）不加段首缩进；其他段才加 "　　"
     * - 单条替换规则失败不影响其他规则
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
        // 顺序：先 PURIFY 后 GENERAL —— 净化只删不改，先清广告 / 版权声明，
        // 再做语义替换；倒过来会让 GENERAL 误中广告里出现的关键字。
        if (useReplace) {
            mContent = mContent.lines().joinToString("\n") { it.trim() }
            for (rule in purifyContentRules) {
                if (rule.pattern.isEmpty()) continue
                try {
                    mContent = if (rule.isRegex) {
                        mContent.replace(rule.pattern.toRegex(), rule.replacement)
                    } else {
                        mContent.replace(rule.pattern, rule.replacement)
                    }
                } catch (_: Exception) {}
            }
            for (rule in generalContentRules) {
                if (rule.pattern.isEmpty()) continue
                try {
                    mContent = if (rule.isRegex) {
                        mContent.replace(rule.pattern.toRegex(), rule.replacement)
                    } else {
                        mContent.replace(rule.pattern, rule.replacement)
                    }
                } catch (_: Exception) {}
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
            try {
                result = if (rule.isRegex) {
                    result.replace(rule.pattern.toRegex(), rule.replacement)
                } else {
                    result.replace(rule.pattern, rule.replacement)
                }
            } catch (_: Exception) {}
        }
        return result
    }
}
