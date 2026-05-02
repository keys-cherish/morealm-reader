package com.morealm.app.domain.preference

import com.morealm.app.core.log.AppLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * DataStore 偏好备份 / 恢复格式。
 *
 * **为什么需要类型标签 (`t`) 而不直接序列化原始 Map**
 * ------------------------------------------------
 * `androidx.datastore.preferences` 的 [androidx.datastore.preferences.core.Preferences.Key]
 * 在 6 种值类型间区分（Bool/Int/Long/Float/Double/String/StringSet），
 * 而 JSON 数字字面值 `42` 既能解为 Int 也能解为 Long / Float。直接做
 * `Map<String, JsonPrimitive>` round-trip 会丢失这个区别 —— 把 `Long` 写回成
 * `Int` 会让 DataStore 在 *读取* 时类型不匹配抛异常（`Preferences[longKey]` 看
 * 不到那条记录），用户感觉「设置消失了」。所以 dump 时显式存类型标签。
 *
 * **格式**（JSON）：
 * ```
 * {
 *   "reader_font_size": { "t": "f", "v": 18.0 },
 *   "page_anim":        { "t": "s", "v": "cover" },
 *   "auto_folder_ignored": { "t": "ss", "v": ["xuanhuan", "junshi"] }
 * }
 * ```
 *
 * 类型缩写：`b` Bool / `i` Int / `l` Long / `f` Float / `d` Double / `s` String / `ss` StringSet。
 *
 * **黑名单**（[IGNORED_KEYS]）默认排除以下 key 不参与备份/恢复：
 *  - `webdav_pass` / `backup_password` —— 敏感凭据，跨设备同步会扩大泄露面
 *  - `last_auto_backup` —— 恢复后让自动备份调度立即触发反而是 bug
 *  - `disclaimer_accepted` —— 新设备应该让用户重新看一次免责声明
 *
 * 黑名单是类常量、固定。如果需要"可配置"，由调用方传 `extraIgnored` 合并 ——
 * 例如「跨设备时跳过 active_theme_id（保留本机主题）」可以由 RestoreOptions 决定。
 */
object PreferenceSnapshot {

    /**
     * 默认不参与备份的 key.name 黑名单。详见类注释。
     *
     * **不要**把所有"用户可能不想跨设备同步的 key"都堆这里 —— 那会让用户失去
     * 选择权。有明确单设备语义的（last_*、disclaimer_accepted）才放黑名单；
     * 其他可选（active_theme_id）走 RestoreOptions 的可配置开关。
     */
    val IGNORED_KEYS: Set<String> = setOf(
        "webdav_pass",
        "backup_password",
        "last_auto_backup",
        "disclaimer_accepted",
    )

    private val json = Json {
        prettyPrint = false  // 备份内嵌字段用紧凑格式省空间，外层 BackupData 会再 prettyPrint
        ignoreUnknownKeys = true
        allowSpecialFloatingPointValues = true
    }

    @Serializable
    private data class TaggedValue(val t: String, val v: JsonElement)

    /**
     * 把当前 DataStore 全量快照转成 JSON 字符串。空 prefs（首次安装从未写过任何
     * 设置）返回 `"{}"`。
     *
     * @param prefs 调 [AppPreferences.snapshotAllRaw] 取的 raw map（key name → kotlin value）
     */
    fun dump(prefs: Map<String, Any>): String {
        val tagged: Map<String, TaggedValue> = prefs.mapNotNull { (name, value) ->
            val tagged = encodeOne(value) ?: return@mapNotNull null
            name to tagged
        }.toMap()
        return json.encodeToString(
            kotlinx.serialization.builtins.MapSerializer(
                kotlinx.serialization.serializer<String>(),
                TaggedValue.serializer(),
            ),
            tagged,
        )
    }

    /**
     * 把 dump 出来的 JSON 字符串解回 raw map（key name → kotlin value），可直接
     * 喂给 [AppPreferences.applySnapshotRaw]。
     *
     * 解析出错（坏 JSON / 未识别 type tag）：返回的 map 中跳过坏条目，不抛
     * 异常 —— 上层不应该因为某个未来版本写入的新类型项导致整体恢复失败。
     */
    fun load(jsonStr: String): Map<String, Any> {
        if (jsonStr.isBlank()) return emptyMap()
        return runCatching {
            val obj = json.decodeFromString<Map<String, TaggedValue>>(jsonStr)
            obj.mapNotNull { (name, tv) ->
                val raw = decodeOne(tv) ?: return@mapNotNull null
                name to raw
            }.toMap()
        }.getOrElse {
            AppLog.warn("PrefSnapshot", "load failed, treating as empty: ${it.message}")
            emptyMap()
        }
    }

    /** 把单个 raw value 编成 TaggedValue；不识别类型返回 null（备份时跳过）。 */
    private fun encodeOne(value: Any): TaggedValue? = when (value) {
        is Boolean -> TaggedValue("b", JsonPrimitive(value))
        is Int -> TaggedValue("i", JsonPrimitive(value))
        is Long -> TaggedValue("l", JsonPrimitive(value))
        is Float -> TaggedValue("f", JsonPrimitive(value))
        is Double -> TaggedValue("d", JsonPrimitive(value))
        is String -> TaggedValue("s", JsonPrimitive(value))
        is Set<*> -> {
            val strs = value.filterIsInstance<String>()
            if (strs.size != value.size) {
                // DataStore 只支持 Set<String>；混入其它类型说明上游写错了
                AppLog.warn("PrefSnapshot", "encodeOne: skipping non-String in Set ($value)")
                null
            } else {
                TaggedValue("ss", JsonArray(strs.map { JsonPrimitive(it) }))
            }
        }
        else -> {
            AppLog.warn("PrefSnapshot", "encodeOne: unsupported type ${value::class.simpleName}")
            null
        }
    }

    /** 把 TaggedValue 解回 raw kotlin value；type tag 不识别返回 null。 */
    private fun decodeOne(tv: TaggedValue): Any? = runCatching {
        when (tv.t) {
            "b" -> tv.v.jsonPrimitive.boolean
            "i" -> tv.v.jsonPrimitive.int
            "l" -> tv.v.jsonPrimitive.long
            "f" -> tv.v.jsonPrimitive.float
            "d" -> tv.v.jsonPrimitive.double
            "s" -> tv.v.jsonPrimitive.contentOrNull ?: ""
            "ss" -> tv.v.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }.toSet()
            else -> {
                AppLog.warn("PrefSnapshot", "decodeOne: unknown type tag '${tv.t}'")
                null
            }
        }
    }.getOrElse {
        AppLog.warn("PrefSnapshot", "decodeOne(${tv.t}) failed: ${it.message}")
        null
    }
}
