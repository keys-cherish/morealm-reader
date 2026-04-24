package com.morealm.app.domain.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "caches")
data class Cache(
    @PrimaryKey
    val key: String = "",
    val value: String? = null,
    val deadline: Long = 0L,
)
