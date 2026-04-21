package com.morealm.app.domain.entity

import com.morealm.app.domain.analyzeRule.RuleDataInterface
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 搜索结果书籍
 */
@Serializable
data class SearchBook(
    var bookUrl: String = "",
    var origin: String = "",
    var originName: String = "",
    var type: Int = 0,
    var name: String = "",
    var author: String = "",
    var kind: String? = null,
    var coverUrl: String? = null,
    var intro: String? = null,
    var wordCount: String? = null,
    var latestChapterTitle: String? = null,
    var tocUrl: String = "",
    var time: Long = System.currentTimeMillis(),
    var variable: String? = null,
    var originOrder: Int = 0,
) : RuleDataInterface {

    @kotlinx.serialization.Transient
    override val variableMap: HashMap<String, String> by lazy {
        variable?.let {
            try { Json.decodeFromString<HashMap<String, String>>(it) } catch (_: Exception) { null }
        } ?: HashMap()
    }

    @kotlinx.serialization.Transient
    val origins: LinkedHashSet<String> by lazy { linkedSetOf(origin) }

    fun addOrigin(origin: String) { origins.add(origin) }

    override fun putBigVariable(key: String, value: String?) {
        if (value == null) variableMap.remove(key) else variableMap[key] = value
    }

    override fun getBigVariable(key: String): String? = null

    override fun putVariable(key: String, value: String?): Boolean {
        if (super.putVariable(key, value)) {
            variable = Json.encodeToString(variableMap)
        }
        return true
    }

    fun getVariable(): String? {
        if (variableMap.isEmpty()) return null
        return Json.encodeToString(variableMap)
    }

    fun toBook() = Book(
        id = bookUrl,
        title = name,
        author = author,
        kind = kind,
        bookUrl = bookUrl,
        origin = origin,
        originName = originName,
        coverUrl = coverUrl,
        description = intro,
        wordCount = wordCount,
        tocUrl = tocUrl,
        variable = variable,
    )
}
