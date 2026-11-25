package com.androidharness.app.androiduse

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

/** Operations for an explicitly authorized phone task. The caller owns approval, frame and lease checks. */
internal class PhoneExtras(private val context: Context) {
    @Suppress("DEPRECATION")
    suspend fun findApps(query: String): JsonObject = withContext(Dispatchers.IO) {
        require(query.isNotBlank()) { "Supply an app name or package." }
        val pm = context.packageManager
        val apps = launchableActivities(context).map { result ->
            LaunchableApp(result.activityInfo.packageName, result.loadLabel(pm).toString())
        }
        matchLaunchableApps(query, apps)
    }

    /** Chinese recognition includes Latin script; all bounds are relative to the provided preview at rotation zero. */
    suspend fun recognize(image: PhoneImage): JsonArray = withContext(Dispatchers.Default) {
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(image.bytes, 0, image.bytes.size)) { "Cannot decode the screen image for OCR." }
        if (bitmap.width != image.width || bitmap.height != image.height) {
            bitmap.recycle()
            error("OCR image dimensions no longer match the observation.")
        }
        val recognizer = try { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }
            catch (e: Exception) { bitmap.recycle(); throw e }
        // ML Kit continues after coroutine cancellation. Release its bitmap and recognizer when that task finishes.
        suspendCancellableCoroutine { cont ->
            try {
                recognizer.process(InputImage.fromBitmap(bitmap, 0)).addOnCompleteListener(direct) { task ->
                    try {
                        if (!cont.isActive) return@addOnCompleteListener
                        if (!task.isSuccessful) throw task.exception ?: IllegalStateException("On-device OCR was interrupted.")
                        val output = buildJsonArray {
                            for (line in task.result.textBlocks.asSequence().flatMap { it.lines.asSequence() }.take(140)) {
                                val r = line.boundingBox ?: continue
                                if (line.text.isBlank() || r.isEmpty) continue
                                add(buildJsonObject {
                                    put("text", line.text.take(300))
                                    putJsonArray("bounds") {
                                        add(r.left.coerceIn(0, image.width))
                                        add(r.top.coerceIn(0, image.height))
                                        add(r.right.coerceIn(0, image.width))
                                        add(r.bottom.coerceIn(0, image.height))
                                    }
                                })
                            }
                        }
                        cont.resume(output)
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resumeWithException(e)
                    } finally {
                        bitmap.recycle()
                        recognizer.close()
                    }
                }
            } catch (e: Exception) {
                bitmap.recycle()
                recognizer.close()
                cont.resumeWithException(e)
            }
        }
    }

    fun mediaVolume(percent: Int? = null): JsonObject {
        val audio = context.getSystemService(AudioManager::class.java)
        val stream = AudioManager.STREAM_MUSIC
        val min = if (Build.VERSION.SDK_INT >= 28) audio.getStreamMinVolume(stream) else 0
        val max = audio.getStreamMaxVolume(stream)
        val requested = percent?.let {
            check(!audio.isVolumeFixed) { "This device uses fixed media volume." }
            volumeIndex(it, min, max).also { index -> audio.setStreamVolume(stream, index, 0) }
        }
        val actual = audio.getStreamVolume(stream)
        return buildJsonObject {
            put("status", when { requested == null -> "observed"; actual == requested -> "verified"; else -> "dispatched_unverified" })
            put("index", actual)
            put("minIndex", min)
            put("maxIndex", max)
            if (max > min) put("percent", ((actual - min) * 100.0 / (max - min)).roundToInt())
            put("fixed", audio.isVolumeFixed)
            requested?.let { put("requestedIndex", it) }
        }
    }

    fun openLink(url: String): JsonObject {
        val link = webHandoffLink(url)
        return handoff(Intent(Intent.ACTION_VIEW, Uri.parse(link.toString())).addCategory(Intent.CATEGORY_BROWSABLE))
    }

    fun shareText(text: String): JsonObject {
        require(text.isNotBlank() && text.length <= 8192) { "Share text must contain 1–8192 characters." }
        return handoff(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text))
    }

    private fun handoff(target: Intent): JsonObject {
        context.startActivity(Intent.createChooser(target, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return buildJsonObject {
            put("status", "dispatched_unverified")
            put("message", "Requested Android's app chooser. The user chooses the destination; completion has not been verified.")
        }
    }

    private companion object { val direct = Executor { it.run() } }
}
