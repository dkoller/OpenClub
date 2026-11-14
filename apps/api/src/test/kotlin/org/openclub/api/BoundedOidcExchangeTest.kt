package org.openclub.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class BoundedOidcExchangeTest {
    @Test
    fun concurrentCallbacksShareOneFourWorkerLimit() = runBlocking {
        val exchange = BoundedOidcExchange()
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val completed = coroutineScope {
            (1..16).map {
                async(Dispatchers.Default) {
                    exchange.run {
                        val current = active.incrementAndGet()
                        peak.accumulateAndGet(current) { old, next -> maxOf(old, next) }
                        try {
                            // A blocking token HTTP request occupies one worker slot.
                            Thread.sleep(80)
                            true
                        } finally {
                            active.decrementAndGet()
                        }
                    }
                }
            }.awaitAll()
        }
        assertTrue(completed.all { it })
        assertEquals(0, active.get())
        assertTrue(peak.get() <= 4, "Shared token exchange exceeded four blocking workers: ${peak.get()}")
        assertTrue(peak.get() > 1, "Concurrent requests should be scheduled concurrently")
    }
}
