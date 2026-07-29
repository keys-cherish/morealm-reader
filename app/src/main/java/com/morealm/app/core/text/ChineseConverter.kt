package com.morealm.app.core.text

import com.morealm.app.core.log.AppLog

/**
 * Chinese simplified ↔ traditional converter.
 * Uses quick-transfer-core library (same as Legado).
 *
 * Modes: 0 = off, 1 = simplified→traditional, 2 = traditional→simplified
 */
object ChineseConverter {

    fun convert(content: String, mode: Int): String {
        if (mode == 0 || content.isEmpty()) return content
        return when (mode) {
            1 -> try {
                com.github.liuyueyi.quick.transfer.ChineseUtils.s2t(content)
            } catch (e: Exception) {
                // 静默 fallback 会让用户看到"切了模式但内容没变"——必须打 log，
                // 至少在 logcat 里能定位到底是 quick-transfer 内部抛错还是其他原因。
                // 不抛出：单条转换失败时退到原文比让整页空白好。
                AppLog.warn("ChineseConverter", "s2t failed (len=${content.length}): ${e.message}")
                content
            }
            2 -> try {
                com.github.liuyueyi.quick.transfer.ChineseUtils.t2s(content)
            } catch (e: Exception) {
                AppLog.warn("ChineseConverter", "t2s failed (len=${content.length}): ${e.message}")
                content
            }
            else -> content
        }
    }
}
