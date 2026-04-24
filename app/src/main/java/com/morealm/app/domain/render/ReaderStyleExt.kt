package com.morealm.app.domain.render

import android.graphics.Color
import com.morealm.app.domain.entity.ReaderStyle

/**
 * Resolves day/night-dependent values from ReaderStyle.
 * No new data class — just extension functions on the existing entity.
 */

fun ReaderStyle.textColorInt(isNight: Boolean): Int =
    parseColorSafe(if (isNight) textColorNight else textColor, if (isNight) 0xFFADADAD.toInt() else 0xFF2D2D2D.toInt())

fun ReaderStyle.bgColorInt(isNight: Boolean): Int =
    parseColorSafe(if (isNight) bgColorNight else bgColor, if (isNight) 0xFF0A0A0F.toInt() else 0xFFFDFBF7.toInt())

fun ReaderStyle.bgImagePath(isNight: Boolean): String? =
    if (isNight) bgImageUriNight else bgImageUri

fun ReaderStyle.effectiveTitleSize(): Int =
    if (titleSize > 0) titleSize else textSize + 4

fun ReaderStyle.indentString(): String =
    paragraphIndent.ifEmpty { "\u3000\u3000" }

fun ReaderStyle.indentCount(): Int =
    paragraphIndent.count { it == '\u3000' }

fun ReaderStyle.isJustify(): Boolean = textAlign == "justify"

private fun parseColorSafe(hex: String, fallback: Int): Int {
    return try {
        val clean = hex.removePrefix("#")
        when (clean.length) {
            6 -> Color.parseColor("#FF$clean")
            8 -> Color.parseColor("#$clean")
            else -> fallback
        }
    } catch (_: Exception) {
        fallback
    }
}
