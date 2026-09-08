package com.morealm.app.core.text

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Decode the original bytes once, before a JSON parser can persist replacement characters. */
object ImportTextDecoder {
    fun decode(bytes: ByteArray): String {
        fun starts(vararg prefix: Int): Boolean = bytes.size >= prefix.size &&
            prefix.indices.all { (bytes[it].toInt() and 0xff) == prefix[it] }
        val (charset, offset) = when {
            starts(0xef, 0xbb, 0xbf) -> Charsets.UTF_8 to 3
            starts(0xff, 0xfe) -> Charsets.UTF_16LE to 2
            starts(0xfe, 0xff) -> Charsets.UTF_16BE to 2
            // JSON starts with ASCII whitespace, '[' or '{'; allow UTF-16 files without BOM.
            bytes.size >= 4 && bytes[1] == 0.toByte() && bytes[3] == 0.toByte() -> Charsets.UTF_16LE to 0
            bytes.size >= 4 && bytes[0] == 0.toByte() && bytes[2] == 0.toByte() -> Charsets.UTF_16BE to 0
            else -> null to 0
        }
        val decoded = if (charset != null) strictDecode(bytes, offset, charset) else {
            try {
                strictDecode(bytes, 0, Charsets.UTF_8)
            } catch (_: CharacterCodingException) {
                strictDecode(bytes, 0, Charset.forName("GB18030"))
            }
        }
        return decoded.removePrefix("\uFEFF").trim()
    }

    private fun strictDecode(bytes: ByteArray, offset: Int, charset: Charset): String =
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()
}
