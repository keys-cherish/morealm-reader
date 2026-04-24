package com.morealm.app.domain.analyzeRule

import kotlinx.serialization.json.Json

/**
 * 自定义Url，用于解析url中的参数
 */
@Suppress("unused")
class CustomUrl(url: String) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val mUrl: String
    private val attribute = hashMapOf<String, Any>()

    init {
        val urlMatcher = AnalyzeUrl.paramPattern.matcher(url)
        mUrl = if (urlMatcher.find()) {
            val attr = url.substring(urlMatcher.end())
            try {
                json.decodeFromString<Map<String, String>>(attr).let {
                    attribute.putAll(it)
                }
            } catch (_: Exception) {
            }
            url.substring(0, urlMatcher.start())
        } else {
            url
        }
    }

    fun putAttribute(key: String, value: Any?): CustomUrl {
        if (value == null) {
            attribute.remove(key)
        } else {
            attribute[key] = value
        }
        return this
    }

    fun getUrl(): String {
        return mUrl
    }

    fun getAttr(): Map<String, Any> {
        return attribute
    }

    override fun toString(): String {
        if (attribute.isEmpty()) {
            return mUrl
        }
        return mUrl + "," + json.encodeToString(
            kotlinx.serialization.serializer<HashMap<String, Any>>(),
            HashMap(attribute)
        )
    }
}
