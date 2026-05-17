package com.morealm.app.domain.parser.mobi.entities

data class PalmDocHeader(
    val compression: Int,
    val numTextRecords: Int,
    val recordSize: Int,
    val encryption: Int
)
