package com.morealm.app.domain.source

import com.morealm.app.domain.entity.BookSource
import com.morealm.app.domain.entity.rule.BookInfoRule
import com.morealm.app.domain.entity.rule.ContentRule
import com.morealm.app.domain.entity.rule.ExploreRule
import com.morealm.app.domain.entity.rule.SearchRule
import com.morealm.app.domain.entity.rule.TocRule
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 书源导入器 - 解析JSON格式书源并转换为 BookSource 实体
 */
object BookSourceImporter {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun importFromJson(jsonString: String): List<BookSource> {
        return try {
            json.decodeFromString<List<ImportBookSource>>(jsonString).map { it.toBookSource() }
        } catch (_: Exception) {
            try {
                val source = json.decodeFromString<ImportBookSource>(jsonString)
                listOf(source.toBookSource())
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}

/**
 * 导入用的中间数据结构，兼容主流书源JSON格式
 */
@Serializable
data class ImportBookSource(
    val bookSourceUrl: String = "",
    val bookSourceName: String = "",
    val bookSourceGroup: String? = null,
    val bookSourceType: Int = 0,
    val bookSourceComment: String? = null,
    val bookUrlPattern: String? = null,
    val loginUrl: String? = null,
    val loginCheckJs: String? = null,
    val loginUi: String? = null,
    val header: String? = null,
    val concurrentRate: String? = null,
    val coverDecodeJs: String? = null,
    val variableComment: String? = null,
    val searchUrl: String? = null,
    val exploreUrl: String? = null,
    val ruleSearch: ImportSearchRule? = null,
    val ruleExplore: ImportExploreRule? = null,
    val ruleBookInfo: ImportBookInfoRule? = null,
    val ruleToc: ImportTocRule? = null,
    val ruleContent: ImportContentRule? = null,
    val enabled: Boolean = true,
    val enabledExplore: Boolean = true,
    val customOrder: Int = 0,
    val lastUpdateTime: Long = 0,
    val respondTime: Long = 180000L,
    val weight: Int = 0,
) {
    fun toBookSource(): BookSource = BookSource(
        bookSourceUrl = bookSourceUrl,
        bookSourceName = bookSourceName,
        bookSourceGroup = bookSourceGroup,
        bookSourceType = bookSourceType,
        bookUrlPattern = bookUrlPattern,
        customOrder = customOrder,
        enabled = enabled,
        enabledExplore = enabledExplore,
        concurrentRate = concurrentRate,
        header = header,
        loginUrl = loginUrl,
        loginUi = loginUi,
        loginCheckJs = loginCheckJs,
        coverDecodeJs = coverDecodeJs,
        bookSourceComment = bookSourceComment,
        variableComment = variableComment,
        lastUpdateTime = lastUpdateTime,
        respondTime = respondTime,
        weight = weight,
        exploreUrl = exploreUrl,
        searchUrl = searchUrl,
        ruleSearch = ruleSearch?.toSearchRule(),
        ruleExplore = ruleExplore?.toExploreRule(),
        ruleBookInfo = ruleBookInfo?.toBookInfoRule(),
        ruleToc = ruleToc?.toTocRule(),
        ruleContent = ruleContent?.toContentRule(),
    )
}

@Serializable
data class ImportSearchRule(
    val checkKeyWord: String? = null,
    val bookList: String? = null,
    val name: String? = null,
    val author: String? = null,
    val intro: String? = null,
    val kind: String? = null,
    val lastChapter: String? = null,
    val updateTime: String? = null,
    val bookUrl: String? = null,
    val coverUrl: String? = null,
    val wordCount: String? = null,
) {
    fun toSearchRule() = SearchRule(checkKeyWord, bookList, name, author, intro, kind, lastChapter, updateTime, bookUrl, coverUrl, wordCount)
}

@Serializable
data class ImportExploreRule(
    val bookList: String? = null,
    val name: String? = null,
    val author: String? = null,
    val intro: String? = null,
    val kind: String? = null,
    val lastChapter: String? = null,
    val updateTime: String? = null,
    val bookUrl: String? = null,
    val coverUrl: String? = null,
    val wordCount: String? = null,
) {
    fun toExploreRule() = ExploreRule(bookList, name, author, intro, kind, lastChapter, updateTime, bookUrl, coverUrl, wordCount)
}

@Serializable
data class ImportBookInfoRule(
    val init: String? = null,
    val name: String? = null,
    val author: String? = null,
    val intro: String? = null,
    val kind: String? = null,
    val lastChapter: String? = null,
    val updateTime: String? = null,
    val coverUrl: String? = null,
    val tocUrl: String? = null,
    val wordCount: String? = null,
    val canReName: String? = null,
    val downloadUrls: String? = null,
) {
    fun toBookInfoRule() = BookInfoRule(init, name, author, intro, kind, lastChapter, updateTime, coverUrl, tocUrl, wordCount, canReName, downloadUrls)
}

@Serializable
data class ImportTocRule(
    val preUpdateJs: String? = null,
    val chapterList: String? = null,
    val chapterName: String? = null,
    val chapterUrl: String? = null,
    val formatJs: String? = null,
    val isVolume: String? = null,
    val isVip: String? = null,
    val isPay: String? = null,
    val updateTime: String? = null,
    val nextTocUrl: String? = null,
) {
    fun toTocRule() = TocRule(preUpdateJs, chapterList, chapterName, chapterUrl, formatJs, isVolume, isVip, isPay, updateTime, nextTocUrl)
}

@Serializable
data class ImportContentRule(
    val content: String? = null,
    val title: String? = null,
    val nextContentUrl: String? = null,
    val webJs: String? = null,
    val sourceRegex: String? = null,
    val replaceRegex: String? = null,
    val imageStyle: String? = null,
    val imageDecode: String? = null,
    val payAction: String? = null,
) {
    fun toContentRule() = ContentRule(content, title, nextContentUrl, webJs, sourceRegex, replaceRegex, imageStyle, imageDecode, payAction)
}
