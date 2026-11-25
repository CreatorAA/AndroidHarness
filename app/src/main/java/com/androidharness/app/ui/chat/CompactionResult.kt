package com.androidharness.app.ui.chat

import android.content.Context
import com.androidharness.app.R

/** Stored system messages retain their existing form; only presentation depends on locale. */
internal enum class CompactionResult(private val prefix: String, private val tail: String = "") {
    STOPPED("Compaction stopped.", " History was kept. Retry /compact when ready."),
    SAVED("Chat compacted, but its task settings or context estimate could not refresh: "),
    FAILED("Compaction failed: ", ". History was kept. Retry /compact or choose another model.");

    val canRetry get() = this != SAVED

    fun storedText(detail: String = ""): String = prefix + detail + tail

    fun diagnostic(text: String): String = text.removePrefix(prefix).removeSuffix(tail)

    fun localizedDetails(context: Context, text: String): String = when (this) {
        STOPPED -> context.getString(R.string.chat_compaction_cancelled_notice)
        SAVED -> context.getString(R.string.chat_compaction_refresh_failed, diagnostic(text))
        FAILED -> context.getString(R.string.chat_compaction_failed_notice, diagnostic(text))
    }

    companion object {
        fun fromStoredText(text: String): CompactionResult? = entries.firstOrNull { text.startsWith(it.prefix.trimEnd()) }
    }
}
