package com.morealm.app.core.text

import android.content.Context
import android.net.Uri
import androidx.compose.ui.graphics.Color
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Parse hex color string to Compose Color, handling both #AARRGGBB and #RRGGBB */
fun parseColor(hex: String): Color {
    val clean = hex.removePrefix("#")
    val value = clean.toLongOrNull(16) ?: return Color.Magenta
    return when (clean.length) {
        8 -> Color(value)
        6 -> Color(0xFF000000 or value)
        else -> Color.Magenta
    }
}

// ── ThreadLocal date formatters (avoid per-call allocation) ──

private val DATE_FMT_YMD = ThreadLocal.withInitial {
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
}
private val DATE_FMT_YMDHM = ThreadLocal.withInitial {
    SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault())
}
private val DATE_FMT_HMS = ThreadLocal.withInitial {
    SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
}
private val DATE_FMT_HM = ThreadLocal.withInitial {
    SimpleDateFormat("HH:mm", Locale.getDefault())
}

fun todayString(): String = DATE_FMT_YMD.get()!!.format(Date())
fun timestampString(): String = DATE_FMT_YMDHM.get()!!.format(Date())
fun timeHmsString(): String = DATE_FMT_HMS.get()!!.format(Date())
fun timeHmString(): String = DATE_FMT_HM.get()!!.format(Date())
fun formatDateYmd(time: Long): String = DATE_FMT_YMD.get()!!.format(Date(time))

/**
 * Strip HTML tags and decode common entities → plain text.
 * Uses pre-compiled regex from AppPattern to avoid per-call allocation.
 */
fun String.stripHtml(): String {
    return this
        .replace(AppPattern.htmlBrRegex, "\n")
        .replace(AppPattern.htmlPOpenRegex, "\n")
        .replace(AppPattern.htmlPCloseRegex, "")
        .replace(AppPattern.htmlTagRegex, "")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&nbsp;", " ")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
}

/**
 * Strip HTML tags only (no entity decode). Faster for simple tag removal.
 */
fun String.stripHtmlTags(): String = replace(AppPattern.htmlTagRegex, "")

fun Long.toReadableDuration(): String {
    val hours = this / 3600000
    val minutes = (this % 3600000) / 60000
    return when {
        hours > 0 -> "${hours}小时${minutes}分钟"
        minutes > 0 -> "${minutes}分钟"
        else -> "不到1分钟"
    }
}

fun Uri.getFileName(context: Context): String? {
    return context.contentResolver.query(this, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        cursor.moveToFirst()
        if (nameIndex >= 0) cursor.getString(nameIndex) else null
    }
}
