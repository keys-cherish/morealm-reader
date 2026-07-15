package com.morealm.app.ui.reader.renderer.scroll

import androidx.compose.ui.graphics.toArgb
import com.morealm.epub.render.ScrollPage
import java.util.Locale

/** 页信息槽位解析后的稳定绘制值，Compose 与离屏 Canvas 共用同一份语义。 */
internal sealed interface PageInfoSlotValue {
    data object None : PageInfoSlotValue

    data class Text(val value: String) : PageInfoSlotValue

    data class Battery(
        val level: Int,
        val charging: Boolean,
    ) : PageInfoSlotValue

    data class TimeBattery(
        val time: String,
        val level: Int,
        val charging: Boolean,
        val batteryFirst: Boolean,
    ) : PageInfoSlotValue
}

internal data class PageInfoSlotContext(
    val chapterTitle: String,
    val pageIndex: Int,
    val pageCount: Int,
    val chapterIndex: Int,
    val chaptersSize: Int,
    val batteryLevel: Int,
    val batteryCharging: Boolean,
    val currentTime: String,
    val scrollPercentOverride: Float? = null,
    val readProgress: String? = null,
    val bookName: String = "MoRealm",
)

/**
 * 集中维护 slot 到最终内容的映射，避免 Compose 页栏与仿真位图各自格式化后产生差异。
 */
internal fun resolvePageInfoSlot(
    slot: String,
    context: PageInfoSlotContext,
): PageInfoSlotValue = when (slot) {
    "chapter" -> PageInfoSlotValue.Text(context.chapterTitle)
    "time" -> PageInfoSlotValue.Text(context.currentTime)
    "battery" -> PageInfoSlotValue.Battery(
        level = context.batteryLevel,
        charging = context.batteryCharging,
    )
    "battery_pct" -> PageInfoSlotValue.Text("${context.batteryLevel}%")
    "page" -> PageInfoSlotValue.Text("${context.pageIndex + 1}/${context.pageCount}")
    "progress" -> PageInfoSlotValue.Text(
        context.scrollPercentOverride?.let(::formatPageInfoPercent)
            ?: context.readProgress?.takeIf { it.isNotBlank() }
            ?: formatPageInfoPercent(
                if (context.pageCount > 1) {
                    context.pageIndex.toFloat() / (context.pageCount - 1) * 100f
                } else {
                    100f
                },
            ),
    )
    "page_progress" -> {
        val progress = context.scrollPercentOverride?.let(::formatPageInfoPercent)
            ?: context.readProgress?.takeIf { it.isNotBlank() }
            ?: "0.0%"
        val prefix = if (context.scrollPercentOverride != null && context.chaptersSize > 0) {
            "${context.chapterIndex + 1}/${context.chaptersSize}  "
        } else {
            "${context.pageIndex + 1}/${context.pageCount}  "
        }
        PageInfoSlotValue.Text(prefix + progress)
    }
    "book_name" -> PageInfoSlotValue.Text(context.bookName)
    "time_battery" -> PageInfoSlotValue.TimeBattery(
        time = context.currentTime,
        level = context.batteryLevel,
        charging = context.batteryCharging,
        batteryFirst = false,
    )
    "battery_time" -> PageInfoSlotValue.TimeBattery(
        time = context.currentTime,
        level = context.batteryLevel,
        charging = context.batteryCharging,
        batteryFirst = true,
    )
    "time_battery_pct" -> PageInfoSlotValue.Text(
        "${context.currentTime}  ${context.batteryLevel}%",
    )
    "chapter_progress" -> if (context.chaptersSize > 0) {
        PageInfoSlotValue.Text("${context.chapterIndex + 1}/${context.chaptersSize}")
    } else {
        PageInfoSlotValue.None
    }
    else -> PageInfoSlotValue.None
}

private fun formatPageInfoPercent(value: Float): String =
    String.format(Locale.getDefault(), "%.1f%%", value)

internal data class PageInfoLineSnapshot(
    val left: PageInfoSlotValue,
    val center: PageInfoSlotValue,
    val right: PageInfoSlotValue,
)

/** 不依赖 Compose 状态的单页信息快照，可安全交给原生 View 的手势生命周期持有。 */
internal data class PageInfoSnapshot(
    val chapterIndex: Int,
    val chapterTitle: String,
    val pageIndex: Int,
    val pageCount: Int,
    val scrollPercent: Float,
    val header: PageInfoLineSnapshot?,
    val footer: PageInfoLineSnapshot?,
    val textColorArgb: Int,
    val backgroundColorArgb: Int,
    val hasBgImage: Boolean,
    val paddingHorizontalPx: Float,
    val topInsetPx: Float,
    val bottomInsetPx: Float,
    val cornerInsetPx: Float,
    val lineHeightPx: Float,
    val textSizePx: Float,
    val density: Float,
    val infoVersion: Long,
)

/** 把 Host 已建立的 per-page 规格转换为位图绘制使用的不可变快照。 */
class PageInfoSnapshotProvider internal constructor(
    val infoVersion: Long,
    private val density: Float,
    private val fontScale: Float,
    private val specProvider: (ScrollPage) -> PageInfoBarSpec?,
) {
    internal fun snapshotFor(page: ScrollPage): PageInfoSnapshot? {
        val spec = specProvider(page) ?: return null
        val cfg = spec.config
        val context = PageInfoSlotContext(
            chapterTitle = spec.chapterTitle,
            pageIndex = spec.pageIndexInChapter,
            pageCount = spec.pageCountInChapter,
            chapterIndex = spec.chapterIndex,
            chaptersSize = cfg.chaptersSize,
            batteryLevel = spec.batteryLevel,
            batteryCharging = spec.batteryCharging,
            currentTime = spec.currentTime,
            scrollPercentOverride = spec.scrollPercent,
        )
        fun line(left: String, center: String, right: String): PageInfoLineSnapshot =
            PageInfoLineSnapshot(
                left = resolvePageInfoSlot(
                    gateInfoSlot(left, cfg.showChapterName, cfg.showTimeBattery),
                    context,
                ),
                center = resolvePageInfoSlot(
                    gateInfoSlot(center, cfg.showChapterName, cfg.showTimeBattery),
                    context,
                ),
                right = resolvePageInfoSlot(
                    gateInfoSlot(right, cfg.showChapterName, cfg.showTimeBattery),
                    context,
                ),
            )

        return PageInfoSnapshot(
            chapterIndex = spec.chapterIndex,
            chapterTitle = spec.chapterTitle,
            pageIndex = spec.pageIndexInChapter,
            pageCount = spec.pageCountInChapter,
            scrollPercent = spec.scrollPercent,
            header = if (cfg.headerHasContent()) {
                line(cfg.headerLeft, cfg.headerCenter, cfg.headerRight)
            } else {
                null
            },
            footer = if (cfg.footerHasContent()) {
                line(cfg.footerLeft, cfg.footerCenter, cfg.footerRight)
            } else {
                null
            },
            textColorArgb = cfg.textColor.copy(alpha = PAGE_INFO_TEXT_ALPHA).toArgb(),
            backgroundColorArgb = cfg.backgroundColor.toArgb(),
            hasBgImage = cfg.hasBgImage,
            paddingHorizontalPx = cfg.paddingHorizontal * density,
            topInsetPx = spec.topInsetDp.value * density,
            bottomInsetPx = spec.bottomInsetDp.value * density,
            cornerInsetPx = spec.cornerInsetDp.value * density,
            lineHeightPx = PAGED_INFO_BAR_LINE_DP * density,
            textSizePx = PAGE_INFO_TEXT_SP * density * fontScale,
            density = density,
            infoVersion = infoVersion,
        )
    }
}

/**
 * 版本只纳入真正影响页栏像素的全局状态；没有时间/电池槽时不因系统广播重建整屏位图。
 */
internal fun calculatePageInfoVersion(
    config: ScrollCanvasInfoBarConfig?,
    batteryLevel: Int,
    batteryCharging: Boolean,
    currentTime: String,
    topInsetPx: Int,
    bottomInsetPx: Int,
    cornerInsetPx: Int,
    density: Float,
    fontScale: Float,
): Long {
    if (config == null) return 0L
    val slots = listOf(
        config.headerLeft,
        config.headerCenter,
        config.headerRight,
        config.footerLeft,
        config.footerCenter,
        config.footerRight,
    ).map { gateInfoSlot(it, config.showChapterName, config.showTimeBattery) }
    val usesTime = slots.any { it == "time" || it == "time_battery" || it == "battery_time" || it == "time_battery_pct" }
    val usesBattery = slots.any { it == "battery" || it == "battery_pct" || it == "time_battery" || it == "battery_time" || it == "time_battery_pct" }

    var version = 1_125_899_906_842_597L
    fun mix(value: Int) {
        version = version * 31L + value
    }
    mix(config.hashCode())
    mix(topInsetPx)
    mix(bottomInsetPx)
    mix(cornerInsetPx)
    mix(density.toBits())
    mix(fontScale.toBits())
    if (usesTime) mix(currentTime.hashCode())
    if (usesBattery) {
        mix(batteryLevel)
        mix(if (batteryCharging) 1 else 0)
    }
    return version
}

private const val PAGE_INFO_TEXT_ALPHA = 0.4f
private const val PAGE_INFO_TEXT_SP = 10f
