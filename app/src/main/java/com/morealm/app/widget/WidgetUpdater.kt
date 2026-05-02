package com.morealm.app.widget

import android.content.Context
import android.os.Build
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import com.morealm.app.core.log.AppLog

/**
 * 通用工具，让任何业务代码（ReaderViewModel、TtsService 等）都能用一行调用
 * 把桌面小组件刷新到最新阅读位置。
 *
 * 设计要点：
 *  - **suspend**：[updateAll] 是 suspend，调用方应在 IO 协程里调
 *  - **SDK 守卫**：低于 API 23 直接 noop；和 widget receiver 资源守卫
 *    （res/values/widget_bools.xml）保持一致，避免在低端设备触发 Glance
 *    内部对 androidx.compose.ui.unit 等高版本依赖
 *  - **不抛异常**：日志记录，不向调用方传播 — widget 失败不应影响主功能
 */
object WidgetUpdater {

    suspend fun refresh(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        runCatching {
            val mgr = GlanceAppWidgetManager(context)
            // 没有挂载 widget 时 updateAll 自然 noop，无需先 query GlanceIds
            ContinueReadingWidget().updateAll(context)
            // 也额外尝试一下 manager 的批量刷新，防止 Glance 1.1.x 在某些
            // OEM ROM 上 updateAll 不更新 receiver 关联的实例
            runCatching { mgr.getGlanceIds(ContinueReadingWidget::class.java) }
        }.onFailure { e ->
            AppLog.error("Widget", "refresh failed", e)
        }
    }
}
