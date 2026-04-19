package com.morealm.app.domain.source

import com.morealm.app.domain.entity.BookSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Legado-compatible book source importer.
 * Parses Legado JSON book source format and converts to MoRealm entities.
 */
object BookSourceImporter {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun importFromJson(jsonString: String): List<BookSource> {
        return try {
            // Try array first
            val legadoSources = json.decodeFromString<List<LegadoBookSource>>(jsonString)
            legadoSources.map { it.toBookSource() }
        } catch (e: Exception) {
            try {
                // Try single object
                val source = json.decodeFromString<LegadoBookSource>(jsonString)
                listOf(source.toBookSource())
            } catch (e2: Exception) {
                emptyList()
            }
        }
    }
}

@Serializable
data class LegadoBookSource(
    val bookSourceUrl: String = "",
    val bookSourceName: String = "",
    val bookSourceGroup: String? = null,
    val bookSourceType: Int = 0,
    val bookSourceComment: String? = null,
    val loginUrl: String? = null,
    val loginCheckJs: String? = null,
    val loginUi: String? = null,
    val header: String? = null,
    val concurrentRate: String? = null,
    val searchUrl: String? = null,
    val exploreUrl: String? = null,
    val ruleSearch: RuleSearch? = null,
    val ruleBookInfo: RuleBookInfo? = null,
    val ruleToc: RuleToc? = null,
    val ruleContent: RuleContent? = null,
    val enabled: Boolean = true,
    val enabledExplore: Boolean = true,
    val customOrder: Int = 0,
) {
    fun toBookSource(): BookSource = BookSource(
        id = bookSourceUrl,
        name = bookSourceName,
        url = bookSourceUrl,
        type = bookSourceType,
        ruleJson = Json.encodeToString(serializer(), this),
        enabled = enabled,
        sortOrder = customOrder,
        groupName = bookSourceGroup,
        comment = bookSourceComment,
        bookSourceUrl = bookSourceUrl,
        bookSourceName = bookSourceName,
        bookSourceGroup = bookSourceGroup,
        bookSourceType = bookSourceType,
        loginUrl = loginUrl,
        loginCheckJs = loginCheckJs,
        loginUi = loginUi,
        header = header,
        concurrentRate = concurrentRate,
    )
}

@Serializable
data class RuleSearch(
    val bookList: String? = null,
    val name: String? = null,
    val author: String? = null,
    val intro: String? = null,
    val kind: String? = null,
    val bookUrl: String? = null,
    val coverUrl: String? = null,
    val wordCount: String? = null,
)

@Serializable
data class RuleBookInfo(
    val name: String? = null,
    val author: String? = null,
    val intro: String? = null,
    val coverUrl: String? = null,
    val tocUrl: String? = null,
    val wordCount: String? = null,
)

@Serializable
data class RuleToc(
    val chapterList: String? = null,
    val chapterName: String? = null,
    val chapterUrl: String? = null,
    val nextTocUrl: String? = null,
)

@Serializable
data class RuleContent(
    val content: String? = null,
    val nextContentUrl: String? = null,
    val replaceRegex: String? = null,
)
