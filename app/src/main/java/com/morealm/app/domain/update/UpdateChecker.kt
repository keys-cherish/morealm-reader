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
 * 双源更新检查器。
 *
 * ## 检查源策略（顺序 fallback）
 *
 *   1. **主源**：jsdelivr CDN 拉仓库根 [MANIFEST_URL]。
 *      - 全球 CDN 加速 + 永不限流；缓存 12h（jsdelivr 默认），App 拉取 < 200ms
 *      - manifest 自带 [Pan123Info] 字段，UpdateDialog 能直接拿到 123 网盘备用下载入口
 *      - 维护成本：发版后需要改根 [update.json](../../../../../../../../update.json) 并 push 到 main
 *
 *   2. **备源**：[GITHUB_API_URL_TEMPLATE] GitHub Releases API。
 *      - 仅在主源彻底拉不到（jsdelivr 全球宕机 / 用户 DNS 污染）时回退
 *      - 拿不到 [Pan123Info]（只能用 GitHub release htmlUrl）
 *      - 匿名速率 60/h，对单设备「检查更新」够用，但若主源经常失败会被限流
 *
 * 之前是单源 GitHub API —— GitHub 匿名 60/h 在用户基数稍大或被 NAT 共享出口时容易 403。
 * 双源 fallback 解决这个痛点。
 *
 * ## 设计取舍
 *
 *  - 不接 Retrofit / 不放共享 OkHttp：与 [com.morealm.app.domain.sync.WebDavClient] 一致地
 *    "自带 OkHttp"。AppModule 当前没有共享 client provider，且检查更新调用稀疏（用户手动），
 *    短超时独占 client 反而隔离更好，不会被书源 / WebDav 的长连接拖到。
 *  - 不引 markdown 渲染：UI 暂以纯文本展示 release body。release notes 是开发者写的，
 *    可控，不至于把行内代码块当字面渲染太难看；要美化是后续独立任务。
 *  - SemVer 比较自带 [compareSemVer]，不引第三方依赖。
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
     * 检查是否有新版本。先走 jsdelivr manifest，失败再 fallback GitHub API。
     *
     * @param currentVersion 当前 versionName，通常传 `BuildConfig.VERSION_NAME`。
     */
    suspend fun check(currentVersion: String): UpdateResult = withContext(Dispatchers.IO) {
        val primary = checkViaManifest(currentVersion)
        // 主源拿到任何确定结果（最新 / 有更新）都直接返回，仅 [UpdateResult.Failed] 时走备源。
        // 这样 jsdelivr 网络抖动一次也能从 GitHub API 兜回来。
        if (primary !is UpdateResult.Failed) return@withContext primary
        AppLog.warn(
            "UpdateChecker",
            "manifest source failed (${primary.reason}); fallback to GitHub API",
        )
        checkViaGithubApi(currentVersion)
    }

    /**
     * 主源：jsdelivr CDN 拉 update.json。
     *
     * 拿到的 [UpdateManifest] 结构 == 仓库根 update.json，UI 端能直接消费 [Pan123Info]。
     */
    private suspend fun checkViaManifest(currentVersion: String): UpdateResult {
        val url = MANIFEST_URL_TEMPLATE.format(owner, repo)
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "MoRealm-UpdateChecker")
            .get()
            .build()
        return try {
            client.newCall(request).await().use { response ->
                if (!response.isSuccessful) {
                    return UpdateResult.Failed("manifest HTTP ${response.code}")
                }
                val raw = response.body?.text().orEmpty()
                if (raw.isBlank()) {
                    return UpdateResult.Failed("manifest 响应为空")
                }
                val manifest = json.decodeFromString(UpdateManifest.serializer(), raw)
                val latest = manifest.latestVersion.removePrefix("v").trim()
                val cmp = compareSemVer(currentVersion, latest)
                if (cmp >= 0) {
                    UpdateResult.UpToDate
                } else {
                    UpdateResult.Available(
                        latestVersion = latest,
                        title = manifest.title.ifBlank { "v${manifest.tagName}" },
                        body = manifest.body,
                        htmlUrl = manifest.githubUrl,
                        isPrerelease = manifest.isPrerelease,
                        pan123 = manifest.pan123?.takeIf { it.shareUrl.isNotBlank() },
                    )
                }
            }
        } catch (e: java.io.IOException) {
            AppLog.warn("UpdateChecker", "manifest 网络错误：${e.message}")
            UpdateResult.Failed("manifest 网络错误：${e.message ?: e.javaClass.simpleName}")
        } catch (e: Exception) {
            AppLog.warn("UpdateChecker", "manifest 解析失败：${e.message}")
            UpdateResult.Failed("manifest 解析失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * 备源：GitHub Releases API。manifest 拉不到时兜底使用，拿不到 [Pan123Info]。
     */
    private suspend fun checkViaGithubApi(currentVersion: String): UpdateResult {
        val url = GITHUB_API_URL_TEMPLATE.format(owner, repo)
        val request = Request.Builder()
            .url(url)
            // GitHub 推荐用 application/vnd.github+json；不带 token 走匿名速率（60/h），
            // 对个人用户「检查更新」完全够用。
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "MoRealm-UpdateChecker")
            .get()
            .build()
        return try {
            client.newCall(request).await().use { response ->
                if (!response.isSuccessful) {
                    val code = response.code
                    return UpdateResult.Failed(
                        when (code) {
                            404 -> "仓库尚未发布任何 release"
                            403 -> "已达到 GitHub API 速率上限，请稍后再试"
                            else -> "HTTP $code"
                        }
                    )
                }
                val raw = response.body?.text().orEmpty()
                if (raw.isBlank()) {
                    return UpdateResult.Failed("响应为空")
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
                        pan123 = null,
                    )
                }
            }
        } catch (e: java.io.IOException) {
            AppLog.warn("UpdateChecker", "GitHub API 网络错误：${e.message}")
            UpdateResult.Failed("网络错误：${e.message ?: e.javaClass.simpleName}")
        } catch (e: Exception) {
            AppLog.warn("UpdateChecker", "GitHub API 解析失败：${e.message}")
            UpdateResult.Failed("解析失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    companion object {
        const val DEFAULT_OWNER = "keys-cherish"
        const val DEFAULT_REPO = "morealm-reader"

        /**
         * jsdelivr CDN 拉 GitHub raw 的格式。`@main` 锁定主分支。
         * 缓存约 12h（jsdelivr 边缘节点）；推 main 后想立即生效可以临时切到
         * 带 commit hash 的 URL（如 `@1060080`），但常规版本以 `@main` 即可。
         */
        const val MANIFEST_URL_TEMPLATE = "https://cdn.jsdelivr.net/gh/%s/%s@main/update.json"

        const val GITHUB_API_URL_TEMPLATE = "https://api.github.com/repos/%s/%s/releases/latest"

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

/**
 * 仓库根 [update.json](../../../../../../../../update.json) 的 schema。
 *
 * 维护规则：
 *   1. 每次发版前更新 [latestVersion] / [tagName] / [title] / [body] / [githubUrl]
 *   2. 上传 APK 到 123 网盘后把 share URL 填到 [Pan123Info.shareUrl]
 *   3. commit + push 到 main，jsdelivr CDN 12h 内缓存自动刷新
 *   4. 想强制立即生效：把代码里的 `@main` 临时改成 `@<commit-hash>`，发布后改回
 */
@Serializable
internal data class UpdateManifest(
    @SerialName("latestVersion") val latestVersion: String,
    @SerialName("tagName") val tagName: String,
    @SerialName("title") val title: String = "",
    @SerialName("body") val body: String = "",
    @SerialName("githubUrl") val githubUrl: String,
    @SerialName("isPrerelease") val isPrerelease: Boolean = false,
    @SerialName("publishedAt") val publishedAt: String = "",
    @SerialName("pan123") val pan123: Pan123Info? = null,
)

/** 123 网盘备用下载入口。无密码时 [password] 留空。 */
@Serializable
data class Pan123Info(
    @SerialName("shareUrl") val shareUrl: String,
    @SerialName("password") val password: String = "",
    @SerialName("label") val label: String = "123 网盘",
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
        /**
         * 来自 manifest 的 123 网盘备用下载入口；走 GitHub API 兜底路径时为 null
         * （UpdateDialog 仅在非 null 时显示「123 网盘下载」按钮）。
         */
        val pan123: Pan123Info? = null,
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
