package com.morealm.app.domain.contributor

import android.content.Context
import com.morealm.app.core.log.AppLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 从 `assets/contributors.json` 加载贡献者名单。
 *
 * 单例缓存：第一次 [load] 解析后结果在内存里复用，整个进程内不会重复读 assets。
 * 进程级缓存够用 —— contributors.json 改动需要发版，没必要做 Flow 监听。
 *
 * 兜底策略：JSON 文件缺失 / 损坏 / schema 不匹配 → 返回空列表 + warn 日志。
 * 调用方拿到空列表时 UI 会显示「暂无贡献者」占位，不让用户看到 crash。
 */
@Singleton
class ContributorRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Volatile private var cached: List<Contributor>? = null

    suspend fun load(): List<Contributor> {
        cached?.let { return it }
        val parsed = withContext(Dispatchers.IO) { readFromAssets() }
        cached = parsed
        return parsed
    }

    private fun readFromAssets(): List<Contributor> {
        return try {
            val raw = context.assets.open(ASSET_PATH).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val file = json.decodeFromString(ContributorsFile.serializer(), raw)
            file.contributors
        } catch (t: Throwable) {
            AppLog.warn(TAG, "load contributors.json failed", t)
            emptyList()
        }
    }

    private companion object {
        const val TAG = "ContributorRepo"
        const val ASSET_PATH = "contributors.json"
    }
}
