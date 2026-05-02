package com.morealm.app.presentation.source

import com.morealm.app.domain.entity.BookSource

/**
 * 书源排序维度。与 [com.morealm.app.domain.preference.AppPreferences.sourceSortBy]
 * 持久化字符串一一对应；新增维度时同步更新 [fromKey]。
 *
 * `naturalAscending` 标记该维度的"自然方向"——比如响应时间的自然方向是升序（小=快=好），
 * 权重的自然方向是降序（大=优先）。UI 上的"升序/降序"开关与这个 flag XOR：
 *   - 用户切到 RESPOND_TIME，默认看到的是"快→慢"（naturalAscending=true，asc=true）
 *   - 用户切到 WEIGHT，默认看到的是"大→小"（naturalAscending=false，asc=true）
 *   - 用户主动反转，UI 那头 asc=false，结果方向自然取反
 *
 * 这样默认体验对每种维度都是"用户最常想看的方向"，而切换开关又永远是"反一下"。
 */
enum class SourceSortKey(
    val key: String,
    val label: String,
    val naturalAscending: Boolean,
) {
    /** 自定义顺序——与导入顺序一致；fallback 默认。 */
    CUSTOM("custom", "自定义", true),
    /** 响应时间：值越小越快，自然升序。respondTime 字段单位 ms（默认 180000=超时）。 */
    RESPOND_TIME("respond_time", "响应时间", true),
    /** 权重：用户手动设置让该源优先；自然降序（大优先）。 */
    WEIGHT("weight", "权重", false),
    /** 名称：locale-aware 比较，自然升序。 */
    NAME("name", "名称", true),
    /** URL：纯字符串字典序，自然升序。 */
    URL("url", "URL", true),
    /** 更新时间：自然降序（新优先）。 */
    LAST_UPDATE("last_update", "更新时间", false);

    companion object {
        /** 反查；脏字符串 fallback 到 [CUSTOM]，避免 DataStore 里偶发脏值导致 NPE。 */
        fun fromKey(key: String): SourceSortKey =
            entries.firstOrNull { it.key == key } ?: CUSTOM
    }
}

/**
 * 把一组书源按 [sortKey] + [ascending] 排序。
 *
 * 函数名不用 `sortedBy` —— stdlib 已有 `List<T>.sortedBy(selector)` 扩展，同名调用
 * 会引起 import 二义。`sortedBySourceKey` 让调用点一眼能看出"业务排序"而非"通用排序"。
 *
 * - 单一传入决定方向：实际比较方向 = `ascending == sortKey.naturalAscending`。
 *   即：用户选了"升序" + 维度自然升序 → 实际升序；维度自然降序 → 实际降序。
 *   用户切到"降序" → 整个翻一下。
 * - 二级 tiebreaker 永远是 customOrder ASC + bookSourceName ASC，避免相同响应时间
 *   的源每次刷新位置乱跳。
 * - 名称比较用 [String.compareTo] 配 ignoreCase，对中文 / 英文混排足够；如果未来需要
 *   pinyin 排序，单点改这里。
 */
fun List<BookSource>.sortedBySourceKey(sortKey: SourceSortKey, ascending: Boolean): List<BookSource> {
    if (isEmpty()) return this
    val effectiveAsc = ascending == sortKey.naturalAscending
    val base: Comparator<BookSource> = when (sortKey) {
        SourceSortKey.CUSTOM ->
            compareBy<BookSource> { it.customOrder }.thenBy { it.bookSourceName }
        SourceSortKey.RESPOND_TIME ->
            compareBy<BookSource> { it.respondTime }
        SourceSortKey.WEIGHT ->
            compareBy<BookSource> { it.weight }
        SourceSortKey.NAME ->
            Comparator { a, b -> a.bookSourceName.compareTo(b.bookSourceName, ignoreCase = true) }
        SourceSortKey.URL ->
            compareBy<BookSource> { it.bookSourceUrl }
        SourceSortKey.LAST_UPDATE ->
            compareBy<BookSource> { it.lastUpdateTime }
    }
    val tiebreak = compareBy<BookSource>({ it.customOrder }, { it.bookSourceName })
    val finalCmp = (if (effectiveAsc) base else base.reversed()).then(tiebreak)
    return sortedWith(finalCmp)
}
