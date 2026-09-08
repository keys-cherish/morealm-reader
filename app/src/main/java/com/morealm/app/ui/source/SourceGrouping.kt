package com.morealm.app.ui.source

import com.morealm.app.domain.entity.BookSource

/**
 * 列表分组方式。`key` 字段用于 DataStore 持久化，[label] 用于 chip UI 显示。
 *
 * 为什么用 `String` 而不是 `enum.ordinal`？序列化稳定性：今后增删枚举值（比如加
 * "按可用性"、"按响应时间"分组）时，旧用户的持久化值依旧能正确解析或安全 fallback；
 * 用 ordinal 需要小心增减位置。
 */
internal enum class SourceGroupMode(val key: String, val label: String) {
    NONE("none", "不分组"),
    GROUP_NAME("group_name", "按分组"),
    DOMAIN("domain", "按域名"),
    TYPE("type", "按类型"),
    ;

    companion object {
        fun fromKey(k: String): SourceGroupMode =
            entries.firstOrNull { it.key == k } ?: NONE
    }
}
/**
 * BookSource.bookSourceType → 中文 label。
 *
 * 当前 schema（见 BookSource.kt 注释）只定义了 0..3 四类；超出范围的值（旧版本写脏数据
 * 或参照实现备份带来非标准类型）一律落到「其他」组而不是丢失/崩溃。
 */
private fun typeLabel(t: Int): String = when (t) {
    0 -> "文本"
    1 -> "音频"
    2 -> "图片"
    3 -> "文件"
    else -> "其他"
}

/**
 * 从 `bookSourceUrl` 提取主机名。`java.net.URI` 解析失败（用户在 url 里写了协议外的
 * 奇形怪状字符）时降级为整段 URL —— 比抛异常或留空都友好：用户至少能看到一个唯一的
 * 分组，自己识别去补全。
 */
private fun extractDomain(url: String): String =
    runCatching {
        val uri = java.net.URI(url.trim())
        uri.host?.takeIf { it.isNotBlank() } ?: url
    }.getOrElse { url }

/**
 * 把一组书源按 [mode] 划分成 `(label, sources)` 列表。
 *
 * - NONE：返回空（caller 自己用 items 平铺）；
 * - GROUP_NAME：以 `bookSourceGroup` trim 后的全字符串为 key；空字符串归到「未分组」；
 *               不拆分逗号分隔（与参照实现一致：多 tag 是同一个组）；
 * - DOMAIN：以 [extractDomain] 结果为 key，按字典序输出；
 * - TYPE：以 [typeLabel] 结果为 key，输出顺序固定为「文本→音频→图片→文件→其他」，
 *         比字典序更符合用户期望（用户最常用的"文本"永远排第一）。
 */
internal fun groupSources(
    sources: List<BookSource>,
    mode: SourceGroupMode,
): List<Pair<String, List<BookSource>>> = when (mode) {
    SourceGroupMode.NONE -> emptyList()
    SourceGroupMode.GROUP_NAME -> sources
        .groupBy { (it.bookSourceGroup ?: "").trim().ifBlank { "未分组" } }
        .toSortedMap()
        .map { (k, v) -> k to v }
    SourceGroupMode.DOMAIN -> sources
        .groupBy { extractDomain(it.bookSourceUrl) }
        .toSortedMap()
        .map { (k, v) -> k to v }
    SourceGroupMode.TYPE -> {
        val groups = sources.groupBy { typeLabel(it.bookSourceType) }
        // 固定顺序：文本/音频/图片/文件/其他。空组不出现（mapNotNull）。
        listOf("文本", "音频", "图片", "文件", "其他")
            .mapNotNull { name -> groups[name]?.let { name to it } }
    }
}
