package com.morealm.app.domain.parser.mobi.entities

data class TagxHeader(
    val magic: String,
    val length: Int,
    val numControlBytes: Int
)
