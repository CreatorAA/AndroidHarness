package com.androidharness.app.androiduse

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.math.roundToInt

internal data class LaunchableApp(val packageName: String, val label: String)

/** Returns candidates, never a guessed default or apps from another user profile. */
internal fun matchLaunchableApps(query: String, apps: List<LaunchableApp>): JsonObject {
    val term = query.trim()
    require(term.length in 1..200) { "Supply an app name or package (1–200 characters)." }
    fun rank(app: LaunchableApp) = when {
        app.packageName.equals(term, true) -> 0
        app.label.equals(term, true) -> 1
        app.label.startsWith(term, true) -> 2
        app.label.contains(term, true) || app.packageName.contains(term, true) -> 3
        else -> 4
    }
    val candidates = apps.map { rank(it) to it }
        .filter { it.first < 4 }
        .sortedWith(compareBy({ it.first }, { it.second.label }, { it.second.packageName }))
        .distinctBy { it.second.packageName }
    return buildJsonObject {
        putJsonArray("apps") {
            for ((_, app) in candidates.take(12)) add(buildJsonObject {
                put("packageName", app.packageName)
                put("label", app.label)
            })
        }
        put("totalMatches", candidates.size)
        put("truncated", candidates.size > 12)
    }
}

internal fun volumeIndex(percent: Int, min: Int, max: Int): Int {
    require(percent in 0..100) { "Media volume must be a percent from 0 to 100." }
    return min + ((max - min) * percent / 100.0).roundToInt()
}

/** Only hand public web-domain links to an external app, not an intent URI or local service. */
internal fun webHandoffLink(raw: String): HttpUrl {
    val url = raw.trim().toHttpUrlOrNull() ?: error("Only public http(s) links are supported.")
    val host = url.host.trimEnd('.')
    val suffix = host.substringAfterLast('.')
    require('.' in host && (suffix.length >= 2 && suffix.all { it in 'a'..'z' } || suffix.startsWith("xn--")) && ':' !in host &&
        suffix !in setOf("localhost", "local", "internal", "test", "lan", "invalid", "home", "onion") &&
        host != "appassets.androidplatform.net" &&
        !host.endsWith(".home.arpa") && !host.endsWith(".in-addr.arpa") && !host.endsWith(".ip6.arpa") &&
        url.username.isEmpty() && url.password.isEmpty()) {
        "Use a public web domain without embedded credentials; local addresses and IP literals are not supported."
    }
    return url
}
