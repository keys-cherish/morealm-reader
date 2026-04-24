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

    init {
        upReplaceRules()
    }

    fun upReplaceRules() {
        replaceRuleDao?.let { dao ->
            titleReplaceRules = dao.getEnabledByScope(bookName, bookOrigin)
                .filter { it.scopeTitle }
            contentReplaceRules = dao.getEnabledByScope(bookName, bookOrigin)
                .filter { it.scopeContent }
        }
    }

    fun getTitleReplaceRules(): List<ReplaceRule> = titleReplaceRules

    fun getContentReplaceRules(): List<ReplaceRule> = contentReplaceRules

    /**
     * 处理正文内容：去重标题 + 替换净化
     */
    fun process(
        chapterTitle: String,
        content: String,
        useReplace: Boolean = true,
        includeTitle: Boolean = true,
    ): String {
        if (content == "null" || content.isBlank()) return content
        var mContent = content

        // 去除重复标题
        try {
            val escapedName = Regex.escape(bookName)
            val escapedTitle = Regex.escape(chapterTitle).replace("\\s+".toRegex(), "\\\\s*")
            val titlePattern = "^(\\s|\\p{Punct}|${escapedName})*${escapedTitle}(\\s)*".toRegex()
            titlePattern.find(mContent)?.let {
                mContent = mContent.substring(it.range.last + 1)
            }
        } catch (_: Exception) {}

        // 替换净化
        if (useReplace) {
            mContent = mContent.lines().joinToString("\n") { it.trim() }
            for (rule in contentReplaceRules) {
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

        // 重新添加标题
        if (includeTitle) {
            val displayTitle = applyTitleReplace(chapterTitle, useReplace)
            mContent = "$displayTitle\n$mContent"
        }

        // 段落缩进
        return mContent.split("\n").joinToString("\n") { line ->
            val trimmed = line.trim { it.code <= 0x20 || it == '　' }
            if (trimmed.isEmpty()) "" else "　　$trimmed"
        }
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
