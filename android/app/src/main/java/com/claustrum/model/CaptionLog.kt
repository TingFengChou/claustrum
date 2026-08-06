package com.claustrum.model

import androidx.compose.runtime.mutableStateListOf

/**
 * Rolling, time-ordered log of L1 caption outputs (newest first), capped at [MAX].
 * In-memory only (cleared on process death) — for **validation** in both dev and
 * prod: eyeball what the model produced, and diff before/after a model swap.
 */
object CaptionLog {
    data class Entry(val tsMillis: Long, val text: String, val source: String, val latencyMs: Long)

    const val MAX = 100

    /** Newest first; Compose-observable. */
    val entries = mutableStateListOf<Entry>()

    @Synchronized
    fun add(tsMillis: Long, text: String, source: String, latencyMs: Long = -1) {
        entries.add(0, Entry(tsMillis, text, source, latencyMs))
        while (entries.size > MAX) entries.removeAt(entries.lastIndex)
    }

    @Synchronized
    fun clear() = entries.clear()
}
