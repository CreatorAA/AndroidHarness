package com.androidharness.app.data.audio

import java.io.Closeable

/** One dictation. Both partials and the final result cover the entire recording. */
internal interface PcmTextDecoder : Closeable {
    fun accept(samples: ShortArray, length: Int): String
    fun finish(): String
}
