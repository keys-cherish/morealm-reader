package com.morealm.app.domain.entity.rule

import kotlinx.serialization.Serializable

/**
 * 发现分类
 */
@Serializable
data class ExploreKind(
    val title: String = "",
    val url: String? = null,
)
