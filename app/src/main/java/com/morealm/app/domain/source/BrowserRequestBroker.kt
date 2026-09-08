package com.morealm.app.domain.source

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Serializes launch and response together. Late replies can never complete a newer request. */
internal class BrowserRequestBroker {
    private data class Pending(val id: Long, val result: CompletableDeferred<String?>)
    private val mutex = Mutex()
    private val sequence = AtomicLong()
    private val pending = AtomicReference<Pending?>()

    suspend fun request(launch: (Long) -> Unit): String? = mutex.withLock {
        val request = Pending(sequence.incrementAndGet(), CompletableDeferred())
        pending.set(request)
        try {
            launch(request.id)
            request.result.await()
        } finally {
            pending.compareAndSet(request, null)
        }
    }

    fun complete(id: Long, html: String?): Boolean {
        val request = pending.get()?.takeIf { it.id == id } ?: return false
        return request.result.complete(html)
    }
}
