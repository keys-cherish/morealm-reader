package com.morealm.app.domain.entity.rule

import kotlinx.serialization.Serializable

/**
 * 段评规则（保留兼容性）
 */
@Serializable
data class ReviewRule(
    var reviewUrl: String? = null,
    var avatarRule: String? = null,
    var contentRule: String? = null,
    var postTimeRule: String? = null,
    var reviewQuoteUrl: String? = null,
    var voteUpUrl: String? = null,
    var voteDownUrl: String? = null,
    var postReviewUrl: String? = null,
    var postQuoteUrl: String? = null,
    var deleteUrl: String? = null,
)
