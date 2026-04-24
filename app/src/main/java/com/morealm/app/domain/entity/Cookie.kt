package com.morealm.app.domain.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cookies")
data class Cookie(
    @PrimaryKey
    val url: String = "",
    val cookie: String = "",
)
