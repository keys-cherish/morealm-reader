package com.morealm.app.domain.contributor

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 贡献者维度。决定 ContributorsScreen 上的 chip 颜色，也决定 CONTRIBUTING.md
 * 入榜标准的归类。新增维度时三处同步：
 *   - 这里的 enum
 *   - ContributorsScreen.tagAccentColor 配色映射
 *   - CONTRIBUTING.md 「入榜标准」
 *
 * 兼容性：JSON 用枚举 name 序列化；未识别的 tag 在 Repository 里被忽略而非 crash，
 * 这样老 App 看新版 contributors.json（内含未来新增维度）时不会整张列表挂掉。
 */
@Serializable
enum class ContributorTag {
    Code,
    Design,
    Issues,
    Community,
    Localization,
}

/**
 * 平台链接条目。
 *
 * - [platform] 不用枚举，留字符串以便 JSON 端直接加新平台（如 "bilibili"）
 *   而不需要发版升级；UI 端遇到未识别平台走兜底图标。
 * - [url] 可空 —— QQ 群名片这类没法直接跳转的，UI 端点击只 Snackbar 显示 [handle]。
 */
@Serializable
data class ContributorLink(
    val platform: String,
    val handle: String,
    val url: String? = null,
)

/**
 * 一名贡献者。
 *
 * - [id] 内部 slug，不是 GitHub ID —— 因为不绑死 GitHub。改名后保持 id 不变即可。
 * - [avatar] 为空则 UI 走「首字母彩色圆」fallback，不强求每个人都有图。
 * - [joinedAt] ISO 日期，UI 排序用。新人放尾部，老人在前。
 */
@Serializable
data class Contributor(
    val id: String,
    val name: String,
    val avatar: String? = null,
    val links: List<ContributorLink> = emptyList(),
    val tags: List<ContributorTag> = emptyList(),
    val contribution: String = "",
    val joinedAt: String = "",
)

/** assets/contributors.json 顶层结构。 */
@Serializable
internal data class ContributorsFile(
    @SerialName("contributors")
    val contributors: List<Contributor> = emptyList(),
)
