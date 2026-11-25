package com.androidharness.app.ui.chat

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/** Simulate Android's service callbacks, then give the real packaged decoder *new* Mandarin PCM. */
class PhoneSpeechRoutingDeviceTest {
    private val ins = InstrumentationRegistry.getInstrumentation()
    private val ctx = ins.targetContext.let { it.createConfigurationContext(Configuration(it.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) }) }
    private fun words(s: String) = Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(s)) }
    private class Platform : SystemSpeechInput {
        lateinit var events: RecognitionListener
        var closes = 0
        override fun start(intent: Intent, listener: RecognitionListener) { events = listener; listener.onReadyForSpeech(null) }
        override fun stop() = Unit
        override fun close() { closes++ }
    }

    @Test fun keepsSystemDraftThenContinuesWithNewBundledAudio() = runBlocking<Unit> {
        val wav = ins.context.assets.open("offline-speech/zh-short-pause.wav").use { it.readBytes() }
        val pcm = ByteBuffer.wrap(wav,44,wav.size-44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val fed = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<String>()
        val platform = Platform(); var connections = 0; var handoffs = 0; var microphones = 0
        val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
        val backoff = SpeechCooldown { 500 }
        val partials = mutableListOf<String>()
        val turn = PhoneSpeechSession(ctx,scope,null,{ s, final -> if (final) finished.complete(s) else partials.add(s) },{}, { _,_ -> }, { handoffs++ },
            { error -> if(error != null) finished.completeExceptionally(AssertionError(error)) }, system = { connections++; platform },
            microphone = {
                microphones++
                object: PcmInput {
                    override fun read(buffer: ShortArray, offset: Int, length: Int): Int {
                        val n = minOf(length,pcm.remaining()); pcm.get(buffer,offset,n)
                        if (n == 0) fed.complete(Unit)
                        return n
                    }
                    override fun close() = Unit
                }
            }, backoff = backoff)
        try {
            withContext(Dispatchers.Main) {
                turn.start(false); assertEquals(1,connections); assertEquals(0,microphones)
                platform.events.onPartialResults(words("有三个任务")); assertEquals("有3个任务",partials.last())
                platform.events.onError(SpeechRecognizer.ERROR_NETWORK); assertEquals(1,platform.closes); assertEquals(1,handoffs)
                platform.events.onResults(words("过期结果")); assertFalse(finished.isCompleted)
            }
            withTimeout(15_000) { fed.await() }
            withContext(Dispatchers.Main) { turn.finish() }
            val actual = withTimeout(45_000) { finished.await() }
            assertEquals("有3个任务今天下午3点我们一起去公园散步",actual)
            assertTrue(backoff.recentlyFailed()); assertEquals(1,microphones)
            ins.sendStatus(0,Bundle().apply { putString("continuedTranscript",actual) })
        } finally { withContext(Dispatchers.Main) { turn.cancel() }; scope.cancel() }
    }

    @Test fun systemSuccessAndUserStopOrPermissionFailureNeverOpenAMicrophone() = runBlocking<Unit> {
        withContext(Dispatchers.Main) {
            for (event in listOf("ok","finish","cancel","permission")) {
                val platform=Platform(); var mic=0; var switched=0; var result:String?=null
                var ended:NativeDictation.Failure? = null
                val turn = PhoneSpeechSession(ctx,this,null,{ text, final -> if(final) result=text },{}, { _,_ -> }, { switched++ }, { ended=it },
                    system={platform}, microphone={mic++; error("Unwanted local recording")}, backoff=SpeechCooldown { 100 })
                turn.start(false); platform.events.onPartialResults(words("编号零零二"))
                when(event) {
                    "ok" -> platform.events.onResults(words("编号零零二"))
                    "permission" -> platform.events.onError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
                    "finish" -> { turn.finish(); platform.events.onError(SpeechRecognizer.ERROR_NETWORK) }
                    "cancel" -> { turn.cancel(); platform.events.onError(SpeechRecognizer.ERROR_NETWORK) }
                }
                assertEquals(0,switched); assertEquals(0,mic)
                if(event=="permission") assertEquals(NativeDictation.Failure.PERMISSION,ended)
                assertEquals(if(event=="ok") "编号002" else null,result)
                turn.cancel()
            }
        }
    }

    @Test fun offlineOnlyNeverEvenCreatesTheSystemService() = assertSkipsSystem(true)

    @Test fun disconnectedAutomaticModeAlsoSkipsTheSystemService() {
        org.junit.Assume.assumeTrue("Run in the airplane-mode batch",
            ctx.getSystemService(android.net.ConnectivityManager::class.java).activeNetwork == null)
        assertSkipsSystem(false)
    }

    private fun assertSkipsSystem(force: Boolean) = runBlocking<Unit> {
        val opened = CompletableDeferred<Unit>()
        var systemCalls = 0
        val owner = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
        val turn = PhoneSpeechSession(ctx,owner,null,{_,_ ->},{},{_,_ ->},{},{},
            system={systemCalls++; Platform()}, microphone={
                opened.complete(Unit)
                object:PcmInput {
                    override fun read(buffer:ShortArray,offset:Int,length:Int)=0
                    override fun close()=Unit
                }
            })
        try {
            withContext(Dispatchers.Main) { turn.start(force) }
            withTimeout(8_000) { opened.await() }; assertEquals(0,systemCalls)
        } finally { withContext(Dispatchers.Main) { turn.cancel() }; owner.cancel() }
    }
}
