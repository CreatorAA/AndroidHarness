package com.androidharness.app.ui

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Start the target as its launcher would before opening a private ActivityScenario host.
 * Starting instrumentation itself leaves the target in the background; some devices reject
 * its first activity launch there, even when AndroidX holds background-launch permission.
 */
internal fun bringTestTargetToForeground() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val target = instrumentation.targetContext
    val launcher = requireNotNull(target.packageManager.getLaunchIntentForPackage(target.packageName)?.component)
    val result = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
        "am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n ${launcher.flattenToShortString()}"
    )).bufferedReader().use { it.readText() }
    check(result.lineSequence().any { it.trim() == "Status: ok" }) { "Could not open the test target: $result" }
    instrumentation.waitForIdleSync()
}
