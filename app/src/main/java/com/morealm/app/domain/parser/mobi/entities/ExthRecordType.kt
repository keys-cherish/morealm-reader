package com.morealm.app.domain.parser.mobi.entities

data class ExthRecordType(
    val name: String,
    val type: String = "string",
    val many: Boolean = false
)
