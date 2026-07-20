package com.morealm.app.domain.repository

import android.content.Context
import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.http.okHttpClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class DailyQuote(
    val text: String,
    val source: String,
)

@Singleton
class DailyQuoteRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences("home_daily_quote", Context.MODE_PRIVATE)

    suspend fun getToday(): DailyQuote = withContext(Dispatchers.IO) {
        val day = dayKey()
        val fallback = fallbackForDay(day)
        val cachedText = preferences.getString(KEY_TEXT, null)
        val cachedSource = preferences.getString(KEY_SOURCE, null)
        if (preferences.getString(KEY_DAY, null) == day && !cachedText.isNullOrBlank()) {
            return@withContext DailyQuote(cachedText, cachedSource.orEmpty().ifBlank { fallback.source })
        }

        runCatching {
            val request = Request.Builder()
                .url("https://v1.hitokoto.cn/?c=d&c=i&encode=json")
                .header("User-Agent", "MoRealm/${day}")
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "HTTP ${response.code}" }
                val body = response.body?.string().orEmpty()
                val json = Json.parseToJsonElement(body).jsonObject
                val text = json["hitokoto"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
                val source = formatSource(
                    author = json["from_who"]?.jsonPrimitive?.contentOrNull,
                    work = json["from"]?.jsonPrimitive?.contentOrNull,
                )
                check(text.isNotEmpty()) { "empty quote" }
                DailyQuote(text, source.ifBlank { fallback.source })
            }
        }.onSuccess { quote ->
            preferences.edit()
                .putString(KEY_DAY, day)
                .putString(KEY_TEXT, quote.text)
                .putString(KEY_SOURCE, quote.source)
                .apply()
        }.onFailure { error ->
            AppLog.warn("DailyQuote", "load failed: ${error.message}")
        }.getOrElse { fallback }
    }

    companion object {
        /**
         * 从一言文学/诗词分类提前取得并去重的离线句库。
         * 句子随 APK 分发，确保首次启动且完全断网时仍有完整内容可展示。
         */
        private val FALLBACK_QUOTES = listOf(
            DailyQuote("春蚕到死丝方尽，蜡炬成灰泪始干。", "李商隐 · 无题·相见时难别亦难"),
            DailyQuote("当两颗卫星的轨道偶尔交叉时，我们便这样相会了。", "斯普特尼克恋人"),
            DailyQuote("直道相思了无益，未妨惆怅是清狂。", "李商隐 · 无题"),
            DailyQuote("不知几人真得鹿，毕竟终日梦为鱼。", "黄庭坚 · 杂诗七首其一"),
            DailyQuote("草木有本心，何求美人折！", "张九龄 · 感遇十二首·其一"),
            DailyQuote("得来不易的机会，会让所有的动物去做原来不喜欢做的事。", "夏目漱石 · 我是猫"),
            DailyQuote("东边日出西边雨，道是无晴却有晴。", "刘禹锡 · 竹枝词二首·其一"),
            DailyQuote("多情自古伤离别，更那堪冷落清秋节！", "柳永 · 雨霖铃·寒蝉凄切"),
            DailyQuote("海日生残夜，江春入旧年。", "王湾 · 次北固山下"),
            DailyQuote("何处望神州？满眼风光北固楼。", "辛弃疾 · 南乡子·登京口北固亭有怀"),
            DailyQuote("花无人戴，酒无人劝，醉也无人管。", "黄公绍 · 青玉案·年年社日停针线"),
            DailyQuote("话到此处，已然兴尽。再无言之欲也。", "冯骥才 · 俗世奇人"),
            DailyQuote("接天莲叶无穷碧，映日荷花别样红。", "杨万里 · 晓出净慈寺送林子方"),
            DailyQuote("锦瑟无端五十弦，一弦一柱思华年。", "李商隐 · 锦瑟"),
            DailyQuote("酒入愁肠，化作相思泪。", "范仲淹 · 苏幕遮·怀旧"),
            DailyQuote("你要悄悄努力，然后惊艳所有人。", "佚名"),
        )

        private const val KEY_DAY = "day"
        private const val KEY_TEXT = "text"
        private const val KEY_SOURCE = "source"

        fun fallbackForDay(day: String = dayKey()): DailyQuote =
            FALLBACK_QUOTES[Math.floorMod(day.hashCode(), FALLBACK_QUOTES.size)]

        fun dayKey(now: Date = Date()): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(now)

        internal fun formatSource(author: String?, work: String?): String =
            listOfNotNull(
                author?.trim()?.takeIf { it.isNotEmpty() },
                work?.trim()?.takeIf { it.isNotEmpty() },
            ).distinct().joinToString(" · ").ifBlank { "佚名" }
    }
}
