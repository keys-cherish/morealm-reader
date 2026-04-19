package com.morealm.app.domain.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Book source entity for online book discovery.
 * The [ruleJson] field stores the rule structure for parsing search results and content.
 */
@Serializable
@Entity(tableName = "book_sources")
data class BookSource(
    @PrimaryKey val id: String,
    val name: String,
    val url: String,
    val type: Int = 0,               // 0=text, 1=audio
    val ruleJson: String = "{}",     // Rule JSON for parsing
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
    val groupName: String? = null,
    val subscriptionUrl: String? = null,
    val comment: String? = null,
    val lastUpdateAt: Long = 0L,

    // Import compatibility fields
    val bookSourceUrl: String = "",
    val bookSourceName: String = "",
    val bookSourceGroup: String? = null,
    val bookSourceType: Int = 0,
    val loginUrl: String? = null,
    val loginCheckJs: String? = null,  // JS to check if login is still valid
    val loginUi: String? = null,       // Login UI config JSON
    val header: String? = null,
    val concurrentRate: String? = null,
)
