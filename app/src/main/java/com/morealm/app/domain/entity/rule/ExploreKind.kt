package com.morealm.app.domain.entity.rule

import kotlinx.serialization.Serializable

/**
 * 发现分类（参照实现 ExploreKind）。
 *
 * [url] 为空时该项是"分节标题"（不可点击）；[style] 见 [FlexChildStyle]。
 */
@Serializable
data class ExploreKind(
    val title: String = "",
    val url: String? = null,
    val style: FlexChildStyle? = null,
) {

    fun style(): FlexChildStyle {
        return style ?: FlexChildStyle.defaultStyle
    }
}
