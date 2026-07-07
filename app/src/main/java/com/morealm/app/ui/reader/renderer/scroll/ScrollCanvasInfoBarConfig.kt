package com.morealm.app.ui.reader.renderer.scroll

import androidx.compose.ui.graphics.Color

/**
 * V2 滚动 Canvas 阅读器 InfoBar 配置 —— 顶部 / 底部状态栏（章节名 / 时间 / 电池 / 进度等）。
 *
 * 由 [com.morealm.app.ui.reader.ReaderScreen] 从 ViewModel.settings 收集 + 透传给
 * [ScrollCanvasReaderHost]。Host 内部 align TopStart / BottomStart 渲染两条 ReaderInfoBar。
 *
 * 与旧 [com.morealm.app.ui.reader.renderer.CanvasRenderer] SCROLL 分支
 * （line 2078-2147）的 ReaderInfoBar 调用完全等价：5 slot 配置 + 显示开关 + 主题色。
 *
 * pageIndex / pageCount / chapterTitle 由 Host 内部从 [ScrollCanvasReaderState] 推导；
 * batteryLevel / currentTime 由 Host 内部 rememberBatteryStatus + LaunchedEffect 维护。
 */
data class ScrollCanvasInfoBarConfig(
    val chaptersSize: Int,
    val textColor: Color,
    val backgroundColor: Color,
    /** 是否有自定义背景图（true 时 InfoBar 不画渐变背景，避免双重 mask）。 */
    val hasBgImage: Boolean,
    /** 水平 padding（dp） —— InfoBar 内 padding(horizontal = paddingHorizontal.dp)。 */
    val paddingHorizontal: Int,
    /** 章节名显示总开关。false 时章节名相关 slot 强制 "none"。 */
    val showChapterName: Boolean,
    /** 时间 / 电池显示总开关。false 时时间电池相关 slot 强制 "none"。 */
    val showTimeBattery: Boolean,
    /** 5-slot header 配置（chapter / chapter_progress / time / battery / page_progress / none ...）。 */
    val headerLeft: String,
    val headerCenter: String,
    val headerRight: String,
    val footerLeft: String,
    val footerCenter: String,
    val footerRight: String,
)

/**
 * 信息栏 slot 按「内容类型」决定显隐，而非按位置：
 *  - 章节名内容（`chapter`）归 [ScrollCanvasInfoBarConfig.showChapterName]；
 *  - 时间 / 电量内容归 [ScrollCanvasInfoBarConfig.showTimeBattery]；
 *  - 进度 / 页码等其余内容不受这两个开关控制（显隐由 slot 自身配置成 `"none"` 决定）。
 *
 * 替代旧的「按固定位置」门控（如 headerLeft 一律归 showTimeBattery）——位置门控假设
 * slot 内容落在固定位置，与用户可自定义的实际 slot 错位，导致关「时间电量」误藏页脚
 * 进度、关「章节名」误藏页脚时间电量（见本文件 showChapterName/showTimeBattery 注释的
 * 原始意图）。token 集合对齐 PageContentDrawer.infoSlotText / Reader.InfoSlotContent。
 */
internal fun gateInfoSlot(slot: String, showChapterName: Boolean, showTimeBattery: Boolean): String =
    when (slot) {
        "chapter" -> if (showChapterName) slot else "none"
        "time", "battery", "battery_pct", "time_battery", "battery_time", "time_battery_pct" ->
            if (showTimeBattery) slot else "none"
        else -> slot
    }

/**
 * header 三 slot 经 [gateInfoSlot] 门控后是否还有任一内容。
 *
 * 正文排版预留（[com.morealm.app.ui.reader.page.animhorizontal.PageLevelReaderHost]
 * 的 effectivePadTop）与绘制（PagePaneCanvas.PageInfoBars）共用此判定——两侧必须
 * 同口径：预留了不画 = 白空一行（用户关「时间电量」后底部空一截的根因）；画了不留 = 重叠。
 */
internal fun ScrollCanvasInfoBarConfig.headerHasContent(): Boolean =
    gateInfoSlot(headerLeft, showChapterName, showTimeBattery) != "none" ||
        gateInfoSlot(headerCenter, showChapterName, showTimeBattery) != "none" ||
        gateInfoSlot(headerRight, showChapterName, showTimeBattery) != "none"

/** footer 版 [headerHasContent]，语义同上。 */
internal fun ScrollCanvasInfoBarConfig.footerHasContent(): Boolean =
    gateInfoSlot(footerLeft, showChapterName, showTimeBattery) != "none" ||
        gateInfoSlot(footerCenter, showChapterName, showTimeBattery) != "none" ||
        gateInfoSlot(footerRight, showChapterName, showTimeBattery) != "none"
