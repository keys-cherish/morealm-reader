package com.morealm.app.domain.render

import com.morealm.app.core.log.AppLog
import com.morealm.app.core.log.RenderDiag
import com.morealm.epub.layout.LayoutLog

/** [LayoutLog] backed by the app logger — production trace sink for the scroll layout engine. */
object AppLogLayoutLog : LayoutLog {
    /** 引擎逐行 trace 的调用点门控 —— 透传全局渲染诊断开关（默认关，省字符串构建）。 */
    override val verbose: Boolean get() = RenderDiag.verbose
    override fun info(tag: String, message: String) { AppLog.info(tag, message) }
    override fun warn(tag: String, message: String) { AppLog.warn(tag, message) }
}
