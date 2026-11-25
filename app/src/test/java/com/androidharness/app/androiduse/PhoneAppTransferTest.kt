package com.androidharness.app.androiduse

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PhoneAppTransferTest {
    private val target = "app.destination"
    private val frame = PhoneFrame(target, "window", 1080, 2400, 0, emptyList())

    @Test fun acceptsOnlyTheRequestedDestinationAfterARealWindowTransition() = runBlocking {
        val allowedToRead = mutableListOf<Set<String>>()
        var clock = 0L
        val transfer = PhoneAppTransfer(target, "one-dispatch")
        val arrived = transfer.await({ allowed ->
            allowedToRead += allowed
            when (allowedToRead.size) {
                1 -> error("Source app has not left the foreground.")
                2 -> error("No readable window in the transition.")
                else -> frame
            }
        }, {}, { clock }, { clock += it })
        assertSame(frame, arrived)
        assertEquals(List(3) { setOf(target) }, allowedToRead)
    }

    @Test fun neverAdoptsAnotherAppOrWaitsForever() = runBlocking {
        var clock = 0L
        assertNull(PhoneAppTransfer(target, "once").await({ frame.copy(packageName = "unrelated") },
            {}, { clock }, { clock += it }))
        assertTrue(clock in 4000..4160)
    }

}
