package com.morealm.app.domain.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.morealm.app.domain.entity.rule.BookInfoRule
import com.morealm.app.domain.entity.rule.ContentRule
import com.morealm.app.domain.entity.rule.ExploreRule
import com.morealm.app.domain.entity.rule.ReviewRule
import com.morealm.app.domain.entity.rule.SearchRule
import com.morealm.app.domain.entity.rule.TocRule
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val jsonParser = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

/**
 * 书源实体 - 完整的在线书源数据结构
 * 使用 TypeConverters 将规则子对象序列化为JSON存储
 */
@Serializable
@TypeConverters(BookSource.Converters::class)
@Entity(
    tableName = "book_sources",
    indices = [(Index(value = ["bookSourceUrl"], unique = false))]
)
data class BookSource(
    @PrimaryKey
    var bookSourceUrl: String = "",
    var bookSourceName: String = "",
    var bookSourceGroup: String? = null,
    var bookSourceType: Int = 0, // 0=text, 1=audio, 2=image, 3=file
    var bookUrlPattern: String? = null,
    @ColumnInfo(defaultValue = "0")
    var customOrder: Int = 0,
    @ColumnInfo(defaultValue = "1")
    var enabled: Boolean = true,
    @ColumnInfo(defaultValue = "1")
    var enabledExplore: Boolean = true,
    var concurrentRate: String? = null,
    var header: String? = null,
    var loginUrl: String? = null,
    var loginUi: String? = null,
    var loginCheckJs: String? = null,
    var coverDecodeJs: String? = null,
    var bookSourceComment: String? = null,
    var variableComment: String? = null,
    var lastUpdateTime: Long = 0,
    var respondTime: Long = 180000L,
    var weight: Int = 0,
    var exploreUrl: String? = null,
    var searchUrl: String? = null,
    var ruleExplore: ExploreRule? = null,
    var ruleSearch: SearchRule? = null,
    var ruleBookInfo: BookInfoRule? = null,
    var ruleToc: TocRule? = null,
    var ruleContent: ContentRule? = null,
    var ruleReview: ReviewRule? = null,
) {
    override fun hashCode(): Int = bookSourceUrl.hashCode()

    override fun equals(other: Any?): Boolean =
        if (other is BookSource) other.bookSourceUrl == bookSourceUrl else false

    fun getSearchRule(): SearchRule {
        ruleSearch?.let { return it }
        val rule = SearchRule(); ruleSearch = rule; return rule
    }

    fun getExploreRule(): ExploreRule {
        ruleExplore?.let { return it }
        val rule = ExploreRule(); ruleExplore = rule; return rule
    }

    fun getBookInfoRule(): BookInfoRule {
        ruleBookInfo?.let { return it }
        val rule = BookInfoRule(); ruleBookInfo = rule; return rule
    }

    fun getTocRule(): TocRule {
        ruleToc?.let { return it }
        val rule = TocRule(); ruleToc = rule; return rule
    }

    fun getContentRule(): ContentRule {
        ruleContent?.let { return it }
        val rule = ContentRule(); ruleContent = rule; return rule
    }

    fun getDisplayNameGroup(): String {
        return if (bookSourceGroup.isNullOrBlank()) bookSourceName
        else String.format("%s (%s)", bookSourceName, bookSourceGroup)
    }

    fun getHeaderMap(): HashMap<String, String> = HashMap<String, String>().apply {
        header?.let {
            try {
                jsonParser.decodeFromString<Map<String, String>>(it).let { map -> putAll(map) }
            } catch (_: Exception) {}
        }
        if (!containsKey("User-Agent")) {
            put("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
        }
    }

    class Converters {
        @TypeConverter
        fun exploreRuleToString(rule: ExploreRule?): String = jsonParser.encodeToString(ExploreRule.serializer().nullable, rule)
        @TypeConverter
        fun stringToExploreRule(json: String?): ExploreRule? =
            json?.let { runCatching { jsonParser.decodeFromString<ExploreRule>(it) }.getOrNull() }

        @TypeConverter
        fun searchRuleToString(rule: SearchRule?): String = jsonParser.encodeToString(SearchRule.serializer().nullable, rule)
        @TypeConverter
        fun stringToSearchRule(json: String?): SearchRule? =
            json?.let { runCatching { jsonParser.decodeFromString<SearchRule>(it) }.getOrNull() }

        @TypeConverter
        fun bookInfoRuleToString(rule: BookInfoRule?): String = jsonParser.encodeToString(BookInfoRule.serializer().nullable, rule)
        @TypeConverter
        fun stringToBookInfoRule(json: String?): BookInfoRule? =
            json?.let { runCatching { jsonParser.decodeFromString<BookInfoRule>(it) }.getOrNull() }

        @TypeConverter
        fun tocRuleToString(rule: TocRule?): String = jsonParser.encodeToString(TocRule.serializer().nullable, rule)
        @TypeConverter
        fun stringToTocRule(json: String?): TocRule? =
            json?.let { runCatching { jsonParser.decodeFromString<TocRule>(it) }.getOrNull() }

        @TypeConverter
        fun contentRuleToString(rule: ContentRule?): String = jsonParser.encodeToString(ContentRule.serializer().nullable, rule)
        @TypeConverter
        fun stringToContentRule(json: String?): ContentRule? =
            json?.let { runCatching { jsonParser.decodeFromString<ContentRule>(it) }.getOrNull() }

        @TypeConverter
        fun reviewRuleToString(rule: ReviewRule?): String = "null"
        @TypeConverter
        fun stringToReviewRule(json: String?): ReviewRule? = null
    }
}
