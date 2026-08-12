package com.morealm.app.domain.entity.rule

import kotlinx.serialization.Serializable

/**
 * 发现分类 chip 的 flex 布局样式（参照实现 FlexChildStyle）。
 *
 * 参照实现在 FlexboxLayout 上直接应用这些参数；MoRealm 的发现页是 Compose FlowRow，
 * 无法逐项映射，但字段全部保留：
 *  - 书源 JSON 里带 style 的 exploreUrl 解析不丢数据（导出/编辑往返保真）；
 *  - [layout_wrapBefore] 在 Compose 侧可映射为"强制换行"（分类分节的常见用法）；
 *  - [layout_flexGrow] > 0 可映射为 chip 占满剩余宽度。
 */
@Serializable
data class FlexChildStyle(
    val layout_flexGrow: Float = 0F,
    val layout_flexShrink: Float = 1F,
    val layout_alignSelf: String = "auto",
    val layout_flexBasisPercent: Float = -1F,
    val layout_wrapBefore: Boolean = false,
) {

    companion object {
        val defaultStyle = FlexChildStyle()
    }
}
