package com.claustrum.model

import android.content.Context

/** Explicit opt-in required by the MediaPipe Tasks privacy notice (2026-06-05). */
object MediaPipeMetricsConsent {
    fun isGranted(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_GRANTED, false)

    fun setGranted(context: Context, granted: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_GRANTED, granted)
            .apply()
    }

    private const val PREFS = "claustrum.privacy"
    private const val KEY_GRANTED = "mediapipe.metrics.consent"
}
