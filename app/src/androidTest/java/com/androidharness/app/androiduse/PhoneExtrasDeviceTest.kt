package com.androidharness.app.androiduse

import android.content.ContextWrapper
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.app.UiAutomation
import android.Manifest
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class PhoneExtrasDeviceTest {
    private val extras get() = PhoneExtras(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test fun runtimeEnabledLauncherAliasIsDiscoverable() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val ins = InstrumentationRegistry.getInstrumentation()
        val pm = ins.targetContext.packageManager
        val alias = ComponentName(ins.context.packageName, "com.androidharness.app.androiduse.AlternateLauncher")
        val ui = ins.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        ui.adoptShellPermissionIdentity(Manifest.permission.CHANGE_COMPONENT_ENABLED_STATE)
        try {
            val old = pm.getComponentEnabledSetting(alias)
            try {
                pm.setComponentEnabledSetting(alias, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
                val matches = extras.findApps("Harness alias fixture").getValue("apps").jsonArray
                assertTrue(matches.any { it.jsonObject.getValue("packageName").jsonPrimitive.content == alias.packageName })
                assertTrue("Harness alias fixture" in LauncherTrip.launchLabels(ins.targetContext, alias.packageName))
            } finally { pm.setComponentEnabledSetting(alias, old, PackageManager.DONT_KILL_APP) }
        } finally { ui.dropShellPermissionIdentity() }
    }

    @Test fun offlineRecognitionReturnsChineseAndLatinAtPreviewCoordinates() = runBlocking {
        val bitmap = Bitmap.createBitmap(760, 260, Bitmap.Config.ARGB_8888)
        val bytes = try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 68f }
            canvas.drawText("欢迎使用手机助手", 28f, 95f, paint)
            canvas.drawText("Android 2026", 28f, 210f, paint)
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        } finally { bitmap.recycle() }
        val lines = withTimeout(30_000) { extras.recognize(PhoneImage(bytes, "image/png", 760, 260)) }
        fun match(needle: String) = lines.single { needle in it.jsonObject.getValue("text").jsonPrimitive.content.replace(" ", "") }.jsonObject
        val chinese = match("手机助手").getValue("bounds").jsonArray.map { it.jsonPrimitive.int }
        val latin = match("Android").getValue("bounds").jsonArray.map { it.jsonPrimitive.int }
        assertTrue(chinese[0] in 0..60 && chinese[2] in 100..760 && chinese[1] < chinese[3])
        assertTrue(latin[1] > chinese[3] && latin[3] <= 260)
    }

    @Test fun currentProfileLauncherQueryAndRealVolumeReadback() = runBlocking {
        val pkg = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        val results = extras.findApps(pkg).getValue("apps").jsonArray
        assertTrue(results.any { it.jsonObject.getValue("packageName").jsonPrimitive.content == pkg })
        val volume = extras.mediaVolume()
        assertEquals("observed", volume.getValue("status").jsonPrimitive.content)
        assertTrue(volume.getValue("index").jsonPrimitive.int in
            volume.getValue("minIndex").jsonPrimitive.int..volume.getValue("maxIndex").jsonPrimitive.int)
        val percent = volume["percent"]?.jsonPrimitive?.int
        if (percent != null && !volume.getValue("fixed").jsonPrimitive.boolean &&
            volumeIndex(percent, volume.getValue("minIndex").jsonPrimitive.int, volume.getValue("maxIndex").jsonPrimitive.int)
                == volume.getValue("index").jsonPrimitive.int) {
            // Exercise set-and-readback without changing the user's sound level.
            assertEquals("verified", extras.mediaVolume(percent).getValue("status").jsonPrimitive.content)
        }
    }

    @Suppress("DEPRECATION")
    @Test fun linkAndShareHandOffOnlyToTheSystemChooser() {
        val chooserTargets = mutableListOf<Intent>()
        val capturing = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun startActivity(intent: Intent) {
                assertEquals(Intent.ACTION_CHOOSER, intent.action)
                assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
                chooserTargets += requireNotNull(intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
            }
        }
        val tools = PhoneExtras(capturing)
        assertEquals("dispatched_unverified", tools.shareText("你好 Android").getValue("status").jsonPrimitive.content)
        assertEquals("dispatched_unverified", tools.openLink("https://example.org/a").getValue("status").jsonPrimitive.content)
        assertEquals(listOf(Intent.ACTION_SEND, Intent.ACTION_VIEW), chooserTargets.map { it.action })
        assertEquals("text/plain", chooserTargets[0].type)
        assertEquals("你好 Android", chooserTargets[0].getStringExtra(Intent.EXTRA_TEXT))
        assertEquals("https://example.org/a", chooserTargets[1].dataString)
        assertNull(chooserTargets[0].component)
        assertNull(chooserTargets[0].`package`)
    }
}
