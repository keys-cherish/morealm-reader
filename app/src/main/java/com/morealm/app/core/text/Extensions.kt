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

/**
 * 把章节正文规整成「与渲染层 stringBuilder 大致同坐标系」的形式，专给 TTS 用。
 *
 * 背景：ChapterProvider 渲染时遇到 `<img src="...">` 会调 `setTypeImage`，
 * 在 stringBuilder 里只占 1 个空格；但 jsoup 在没有可用 src 时 `removeAttr("src")`
 * 留下的裸 `<img>`（无属性）不匹配 `imgSrcPattern`（要求 src），ChapterProvider
 * 兜底走 `setTypeText`，把 `<img>` 当 5 字节文本压进 stringBuilder。结果：
 *   - 渲染 paragraphPositions 视裸 `<img>` 为 5 字节
 *   - chapter.chapterContent.value 仍是原始 HTML，含完整 `<img>` 标签
 *   - TtsEngineHost.paragraphsFromPositions 用 paragraphPositions 切原始内容，
 *     就有概率切到 `<img>` 标签中间，留下 `<img` 这种 4 字节 HTML 碎片，所有
 *     `htmlImgRegex` / `htmlTagRegex` 都需要 `>` 收尾才匹配 → 漏过 → 当文本读
 *
 * 这个函数把完整的 `<img...>` 替换成单空格，让 TTS 内容最大程度贴近渲染层
 * stringBuilder 的字节布局；剩余的 `<svg>`、`<br>`、`<div>` 等也按渲染同款顺序
 * 处理。注意：`paragraphPositions` 仍在渲染层坐标系里，本函数只能减小漂移、
 * 不能消除（裸 `<img>` 渲染走文本路径仍然占 5 字节，本函数会在这里也只占 1 个
 * 空格 —— 这是已知偏差，由调用侧的兜底（slice 内残留 `<` 碎片裁断）兜住）。
 */
fun String.cleanContentForTts(): String {
    return this
        .replace(AppPattern.htmlImgRegex, " ")
        .replace(AppPattern.htmlSvgRegex, " ")
        .replace(AppPattern.htmlDivCloseRegex, "\n")
        .replace(AppPattern.htmlBrRegex, "\n")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&nbsp;", " ")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
}

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
