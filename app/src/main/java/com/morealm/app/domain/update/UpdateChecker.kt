package com.morealm.app.domain.update

import com.morealm.app.core.log.AppLog
import com.morealm.app.domain.http.addExceptionLoggingInterceptor
import com.morealm.app.domain.http.await
import com.morealm.app.domain.http.installDispatcherExceptionLogger
import com.morealm.app.domain.http.text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 拉取 GitHub Releases /latest 与本地 versionName 比对的更新检查器。
 *
 * 设计取舍：
 *  - 不接 Retrofit / 不放共享 OkHttp：与 [com.morealm.app.domain.sync.WebDavClient] 一致地
 *    "自带 OkHttp"。AppModule 当前没有共享 client provider，且检查更新调用稀疏（用户手动），
 *    短超时独占 client 反而隔离更好，不会被书源 / WebDav 的长连接拖到。
 *  - 不引 markdown 渲染：UI 暂以纯文本展示 release body。release notes 是开发者写的，
 *    可控，不至于把行内代码块当字面渲染太难看；要美化是后续独立任务。
 *  - SemVer 比较自带 [compareSemVer]，不引第三方依赖。规则覆盖项目当前用法
 *    "1.0.0-alpha1 / -alpha2 / -beta1 / 正式版" 即可。
 *  - TODO（本期不做）：启动后 24h 静默自动检查 + 红点角标，需扩 [AppPreferences] 加
 *    lastCheckUpdateMs / hasUpdateBadge 两个字段。等基线稳定后再加。
 *
 * 网络异常一律回 [UpdateResult.Failed]，不抛——上层 ViewModel 不需要 try/catch。
 */
class UpdateChecker(
    private val owner: String = DEFAULT_OWNER,
    private val repo: String = DEFAULT_REPO,
    client: OkHttpClient = defaultClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val client: OkHttpClient = client

    /**
     * 检查是否有新版本。
     *
     * @param currentVersion 当前 versionName，通常传 `BuildConfig.VERSION_NAME`。
     * @return [UpdateResult.UpToDate] / [UpdateResult.Available] / [UpdateResult.Failed]。
     */
    suspend fun check(currentVersion: String): UpdateResult = withContext(Dispatchers.IO) {
        val url = "https://api.github.com/repos/$owner/$repo/releases/latest"
        val request = Request.Builder()
            .url(url)
            // GitHub 推荐用 application/vnd.github+json；不带 token 走匿名速率（60/h），
            // 对个人用户「检查更新」完全够用。
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "MoRealm-UpdateChecker")
            .get()
            .build()
        try {
            client.newCall(request).await().use { response ->
                if (!response.isSuccessful) {
                    val code = response.code
                    return@withContext UpdateResult.Failed(
                        when (code) {
                            404 -> "仓库尚未发布任何 release"
                            403 -> "已达到 GitHub API 速率上限，请稍后再试"
                            else -> "HTTP $code"
                        }
                    )
                }
                val raw = response.body?.text().orEmpty()
                if (raw.isBlank()) {
                    return@withContext UpdateResult.Failed("响应为空")
                }
                val release = json.decodeFromString(GithubRelease.serializer(), raw)
                val latest = release.tagName.removePrefix("v").trim()
                val cmp = compareSemVer(currentVersion, latest)
                if (cmp >= 0) {
                    UpdateResult.UpToDate
                } else {
                    UpdateResult.Available(
                        latestVersion = latest,
                        title = release.name?.takeIf { it.isNotBlank() } ?: release.tagName,
                        body = release.body.orEmpty(),
                        htmlUrl = release.htmlUrl,
                        isPrerelease = release.prerelease,
                    )
                }
            }
        } catch (e: java.io.IOException) {
            AppLog.warn("UpdateChecker", "网络错误：${e.message}")
            UpdateResult.Failed("网络错误：${e.message ?: e.javaClass.simpleName}")
        } catch (e: Exception) {
            AppLog.warn("UpdateChecker", "解析失败：${e.message}")
            UpdateResult.Failed("解析失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    companion object {
        const val DEFAULT_OWNER = "keys-cherish"
        const val DEFAULT_REPO = "morealm-reader"

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .addExceptionLoggingInterceptor("UpdateChecker")
            .build()
            .apply { installDispatcherExceptionLogger("UpdateChecker") }
    }
}

/**
 * GitHub Release JSON 子集——只挑用得到的字段。
 * `ignoreUnknownKeys = true` 让上游加字段不会破坏解析。
 */
@Serializable
internal data class GithubRelease(
    @SerialName("tag_name") val tagName: String,
    @SerialName("html_url") val htmlUrl: String,
    @SerialName("name") val name: String? = null,
    @SerialName("body") val body: String? = null,
    @SerialName("prerelease") val prerelease: Boolean = false,
    @SerialName("draft") val draft: Boolean = false,
    @SerialName("published_at") val publishedAt: String? = null,
)

/** 检查更新的三态结果。所有 IO 错误都包成 [Failed]，UI 不必 try/catch。 */
sealed interface UpdateResult {
    /** 当前版本不低于远端 latest。 */
    data object UpToDate : UpdateResult

    /** 远端有更新版本，可引导用户去下载。 */
    data class Available(
        val latestVersion: String,
        val title: String,
        val body: String,
        val htmlUrl: String,
        val isPrerelease: Boolean,
    ) : UpdateResult

    /** 网络 / 解析 / 限流等失败，[reason] 已经做成「人话」可直接 Snackbar。 */
    data class Failed(val reason: String) : UpdateResult
}

/**
 * 轻量 SemVer 比较，无外部依赖。
 *
 * 规则（与 semver.org 子集对齐）：
 *  1. 去掉前导 `v` / `V`。
 *  2. 按首个 `-` 切分 `main` 与 `preRelease`。
 *  3. `main` 部分按点切分，每段尽力转 Int（非数字段按 0），不足三段补 0。
 *  4. main 相等时：
 *     - **无 preRelease 的版本 > 有 preRelease 的版本**（正式版 > 预发布）。
 *     - 都有 preRelease 时按 ASCII 字典序——"alpha1" < "alpha2" < "alpha9" < "alpha10"
 *       (注意字典序在 alpha9 vs alpha10 处不严格，但目前项目用法是 -alpha1/-alpha2/-beta1，
 *       两位数 alpha 的概率极低；真到 -alpha10 之前，先升 -beta1 即可避雷)。
 *  5. 输入完全无法解析时返回 0（不抛异常），调用方会回退到「认为是最新」，安全选择。
 *
 * @return 负数表示 a<b，0 表示相等，正数表示 a>b。
 */
internal fun compareSemVer(a: String, b: String): Int {
    val (aMain, aPre) = splitVersion(a)
    val (bMain, bPre) = splitVersion(b)

    val aNums = aMain.split('.').map { it.toIntOrNull() ?: 0 }
    val bNums = bMain.split('.').map { it.toIntOrNull() ?: 0 }
    val maxLen = maxOf(aNums.size, bNums.size, 3)
    for (i in 0 until maxLen) {
        val av = aNums.getOrElse(i) { 0 }
        val bv = bNums.getOrElse(i) { 0 }
        if (av != bv) return av.compareTo(bv)
    }

    // main 相等 → 看 preRelease：空 > 非空
    return when {
        aPre.isEmpty() && bPre.isEmpty() -> 0
        aPre.isEmpty() -> 1
        bPre.isEmpty() -> -1
        else -> aPre.compareTo(bPre)
    }
}

private fun splitVersion(raw: String): Pair<String, String> {
    val cleaned = raw.trim().removePrefix("v").removePrefix("V")
    val dash = cleaned.indexOf('-')
    return if (dash < 0) cleaned to ""
    else cleaned.substring(0, dash) to cleaned.substring(dash + 1)
}
