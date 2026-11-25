package com.androidharness.app.androiduse

import android.Manifest
import android.app.UiAutomation
import android.content.ComponentName
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Instrumentation restarts its target. API 37 can leave the old, user-enabled accessibility binding dead. */
internal suspend fun awaitFixturePhoneService() {
    val ins = InstrumentationRegistry.getInstrumentation()
    val ctx = ins.targetContext
    val ui = ins.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    val key = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    val own = ComponentName(ctx, PhoneAccessibilityService::class.java)
    fun enabled() = Settings.Secure.getString(ctx.contentResolver, key).orEmpty().split(':').filter(String::isNotBlank)
    val original = enabled().singleOrNull { ComponentName.unflattenFromString(it) == own }
        ?: error("Enable Harness phone controls in Android Accessibility settings before running the device fixture.")
    suspend fun connected(ms: Long) = withTimeoutOrNull(ms) {
        while (PhoneAccessibilityService.current == null) delay(100)
        true
    } == true
    if (connected(1_500)) return
    check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "An enabled service did not reconnect; automatic rebind is for the test emulator only." }
    withContext(Dispatchers.IO) {
        ui.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS)
        try {
            check(Settings.Secure.putString(ctx.contentResolver, key, enabled().filterNot { it == original }.joinToString(":")))
            // Wait for the system to release the old service before restoring the user's exact component.
            val released = withTimeoutOrNull(3_000) {
                while (true) {
                    val binding = ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand("dumpsys accessibility"))
                        .bufferedReader().useLines { ls -> ls.filter { "Binding services:" in it || "Enabled services:" in it }.joinToString() }
                    if (own.className !in binding) break
                    delay(100)
                }
                true
            } == true
            check(released) { "The system did not release the stale fixture accessibility binding." }
        } finally {
            try { check(Settings.Secure.putString(ctx.contentResolver, key, (enabled()+original).distinct().joinToString(":"))) }
            finally { ui.dropShellPermissionIdentity() }
        }
    }
    check(connected(10_000)) { "The user-enabled service did not bind to the instrumentation target process." }
}
