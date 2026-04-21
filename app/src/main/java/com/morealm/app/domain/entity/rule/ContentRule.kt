package com.morealm.app.domain.entity.rule

import kotlinx.serialization.Serializable

/**
 * 正文处理规则
 */
@Serializable
data class ContentRule(
    var content: String? = null,
    var title: String? = null,
    var nextContentUrl: String? = null,
    var webJs: String? = null,
    var sourceRegex: String? = null,
    var replaceRegex: String? = null,
    var imageStyle: String? = null,
    var imageDecode: String? = null,
    var payAction: String? = null,
)
