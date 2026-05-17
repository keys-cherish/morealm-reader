package com.morealm.app.domain.parser.mobi.decompress

interface Decompressor {

    fun decompress(data: ByteArray): ByteArray

}
