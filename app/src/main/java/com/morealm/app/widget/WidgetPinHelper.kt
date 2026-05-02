package com.morealm.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import com.morealm.app.core.log.AppLog

/**
 * 「一键添加到桌面」入口。封装 [AppWidgetManager.requestPinAppWidget] 的
 * SDK 守卫与异常吞咽。
 *
 * 行为分级：
 *  - **API 26+**：尝试调用系统弹窗，由 Launcher 决定是否真的允许 pin。
 *    返回值仅表示「请求是否成功投递」，不代表用户最终接受 — 这部分由
 *    Launcher 自己 UI 处理，无需 App 兜底。
 *  - **API 23~25**：返回 false。Glance widget 仍可被用户长按桌面手动添加，
 *    UI 层应弹出引导 Dialog 教用户操作。
 *  - **API < 23**：返回 false。受 widget_bools 资源守卫，receiver 已禁用，
 *    入口应灰掉。
 *
 * 不在内部弹任何 Toast / Dialog —— 全部交给调用方决定 UI 表达，便于复用。
 */
object WidgetPinHelper {

    /** 系统是否能直接弹「添加小组件」对话框（API 26+ 且 Launcher 支持）。 */
    fun isSupported(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return runCatching {
            AppWidgetManager.getInstance(context).isRequestPinAppWidgetSupported
        }.getOrDefault(false)
    }

    /**
     * 投递添加请求。返回 true 表示请求成功送出（Launcher 接管），false 表示
     * 不支持或投递抛出异常 —— 调用方应回退到引导 Dialog。
     */
    fun requestPin(context: Context): Boolean {
        if (!isSupported(context)) return false
        return runCatching {
            val provider = ComponentName(context.packageName, ContinueReadingWidgetReceiver::class.java.name)
            val callback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // S+ 要求 PendingIntent 显式声明可变性。这里只是用于 Launcher
                // 回调通知，无需 mutable。
                PendingIntent.getBroadcast(
                    context,
                    /* requestCode = */ 0,
                    Intent(ACTION_WIDGET_PINNED).setPackage(context.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            } else {
                PendingIntent.getBroadcast(
                    context,
                    0,
                    Intent(ACTION_WIDGET_PINNED).setPackage(context.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT,
                )
            }
            AppWidgetManager.getInstance(context)
                .requestPinAppWidget(provider, /* extras = */ null, callback)
        }.getOrElse { e ->
            AppLog.error("Widget", "requestPinAppWidget threw", e)
            false
        }
    }

    private const val ACTION_WIDGET_PINNED = "com.morealm.app.WIDGET_PINNED"
}
