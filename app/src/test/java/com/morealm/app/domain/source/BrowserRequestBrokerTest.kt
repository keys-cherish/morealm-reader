package com.morealm.app.domain.source

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BrowserRequestBrokerTest {
    @Test fun `response during launch is retained`() = runTest {
        val broker = BrowserRequestBroker()
        assertEquals("done", broker.request { assertTrue(broker.complete(it, "done")) })
    }

    @Test fun `concurrent launches serialize and stale replies cannot reach the next request`() = runTest {
        val broker = BrowserRequestBroker()
        val launched = mutableListOf<Long>()
        val first = async { broker.request { launched += it } }
        val second = async { broker.request { launched += it } }
        runCurrent()
        assertEquals(1, launched.size)
        val oldId = launched.single()
        first.cancelAndJoin()
        runCurrent()
        assertEquals(2, launched.size)
        assertFalse(broker.complete(oldId, "stale"))
        assertTrue(broker.complete(launched.last(), "correct"))
        assertEquals("correct", second.await())
    }
}
