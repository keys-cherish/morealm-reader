package com.morealm.app.presentation.profile

import java.time.LocalDate

internal fun readingStreak(activeDates: Set<String>, today: LocalDate): Int {
    var cursor = if (today.toString() in activeDates) today else today.minusDays(1)
    var days = 0
    while (cursor.toString() in activeDates) {
        days++
        cursor = cursor.minusDays(1)
    }
    return days
}

internal fun formatReadingDuration(milliseconds: Long): String {
    if (milliseconds <= 0) return "0 分钟"
    val minutes = milliseconds / 60_000
    if (minutes == 0L) return "不足 1 分钟"
    if (minutes < 60) return "$minutes 分钟"
    val hours = minutes / 60
    val rest = minutes % 60
    return if (rest == 0L) "$hours 小时" else "$hours 小时 $rest 分钟"
}
