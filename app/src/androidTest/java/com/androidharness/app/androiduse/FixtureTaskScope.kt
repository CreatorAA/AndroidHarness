package com.androidharness.app.androiduse

import kotlinx.serialization.json.*

/** Wire payload produced by a scripted fixture provider on its first device read. */
internal fun fixtureTaskScope(
    access: String, vararg effects: String, apps: List<String> = emptyList(), capture: Boolean = false,
) = buildJsonObject {
    put("access", access)
    putJsonArray("effects") { effects.forEach { add(it) } }
    putJsonArray("apps") { apps.forEach { add(it) } }
    put("capture", capture)
}
