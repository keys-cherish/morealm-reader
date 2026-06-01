package com.morealm.app.domain.render

import com.morealm.app.core.log.AppLog
import com.morealm.epub.layout.LayoutLog

/** [LayoutLog] backed by the app logger — production trace sink for the scroll layout engine. */
object AppLogLayoutLog : LayoutLog {
    override fun info(tag: String, message: String) { AppLog.info(tag, message) }
    override fun warn(tag: String, message: String) { AppLog.warn(tag, message) }
}
