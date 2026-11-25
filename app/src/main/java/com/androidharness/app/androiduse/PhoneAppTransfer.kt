package com.androidharness.app.androiduse

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Waiting for one already-issued package switch never launches again or reads another app's UI. */
internal class PhoneAppTransfer(val destination: String, val actionId: String) {
    suspend fun await(
        read: suspend (Set<String>) -> PhoneFrame,
        allowed: suspend () -> Unit,
        now: () -> Long = { System.nanoTime() / 1_000_000 },
        pause: suspend (Long) -> Unit = { delay(it) },
    ): PhoneFrame? {
        val target = setOf(destination)
        val deadline = now() + 4_000
        do {
            allowed()
            val frame = try { read(target) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { null } // Android can transiently show the source, launcher or no window.
            allowed()
            if (frame?.packageName == destination) return frame
            if (now() >= deadline) break
            pause(160)
        } while (true)
        return null
    }
}
