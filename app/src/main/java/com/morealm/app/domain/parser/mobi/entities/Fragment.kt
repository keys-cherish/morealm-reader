package com.morealm.app.domain.parser.mobi.entities

data class Fragment(
    val insertOffset: Int,
    val selector: String,
    val index: Int,
    val offset: Int,
    val length: Int
)
