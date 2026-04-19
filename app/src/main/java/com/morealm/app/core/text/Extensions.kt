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

fun todayString(): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

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
