package com.morealm.app.domain.source

import kotlinx.coroutines.withTimeoutOrNull

/** Application-facing broker; the UI adapter supplies launch and returns a matching request ID. */
object ShowBrowserSession {
    private val broker = BrowserRequestBroker()

    suspend fun request(launch: (Long) -> Unit): String? =
        withTimeoutOrNull(5 * 60_000L) { broker.request(launch) }

    fun emit(requestId: Long, html: String?): Boolean = broker.complete(requestId, html)
}
