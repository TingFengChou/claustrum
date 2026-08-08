package com.claustrum.model

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * Developer mode — off by default. When on, the app exposes validation tooling
 * (test-video playback through the pipeline, model-eval harness). Persisted so it
 * survives restarts. Never gates any production behaviour.
 */
object DevMode {
    val enabled = mutableStateOf(false)

    private const val PREFS = "claustrum.prefs"
    private const val KEY = "dev_mode"

    fun load(context: Context) {
        enabled.value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
    }

    fun set(context: Context, value: Boolean) {
        enabled.value = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, value).apply()
    }
}
