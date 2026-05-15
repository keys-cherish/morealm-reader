package com.morealm.app.domain.parser.mobi.entities

data class TagxTag(
    val tag: Int,
    val numValues: Int,
    val bitmask: Int,
    val controlByte: Int,
)
