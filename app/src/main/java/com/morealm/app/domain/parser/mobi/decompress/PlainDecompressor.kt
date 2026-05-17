package com.morealm.app.domain.parser.mobi.decompress

class PlainDecompressor : Decompressor {

    override fun decompress(data: ByteArray): ByteArray {
        return data
    }

}
