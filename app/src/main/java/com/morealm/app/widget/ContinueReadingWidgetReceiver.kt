package com.morealm.app.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * BroadcastReceiver glue — 把 [ContinueReadingWidget] 实例暴露给 Glance 框架。
 *
 * 单一职责：让系统 AppWidget framework 通过这个 receiver 找到我们的 GlanceAppWidget。
 * 渲染、数据加载、点击行为都在 [ContinueReadingWidget]；这里只是路由。
 *
 * 拆成独立文件（而不是写在 ContinueReadingWidget.kt 内）是为了满足
 * pre-commit `manifest-class-check` hook 的约定：manifest 里的
 * `android:name=".widget.ContinueReadingWidgetReceiver"` 短名要按
 * "包路径 → 文件名" 直映射到 `widget/ContinueReadingWidgetReceiver.kt`。
 */
class ContinueReadingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ContinueReadingWidget()
}
