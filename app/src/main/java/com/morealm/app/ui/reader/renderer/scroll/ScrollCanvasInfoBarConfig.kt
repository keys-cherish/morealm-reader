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
