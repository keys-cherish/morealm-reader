package com.morealm.app.core.text

/**
 * Chinese simplified ↔ traditional converter.
 * Uses quick-transfer-core library (same as Legado).
 *
 * Modes: 0 = off, 1 = simplified→traditional, 2 = traditional→simplified
 */
object ChineseConverter {

    fun convert(content: String, mode: Int): String = when (mode) {
        1 -> try { com.github.liuyueyi.quick.transfer.ChineseUtils.s2t(content) } catch (_: Exception) { content }
        2 -> try { com.github.liuyueyi.quick.transfer.ChineseUtils.t2s(content) } catch (_: Exception) { content }
        else -> content
    }
}
