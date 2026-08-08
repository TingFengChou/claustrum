package com.claustrum.vlm

import android.graphics.Bitmap

/**
 * L1 scene-description boundary. Generic over the frame type [F] so the L0→L1
 * trigger ([PerceptionPipeline]) is host-unit-testable with a fake (F=String),
 * while production uses F=Bitmap (the admitted camera frame).
 *
 * Contract: called ONLY on an L0-admitted frame ("wake the VLM only on change").
 * Must return an objective description only — risk/event judgement is L2's job and
 * needs visible evidence (ADR-0006); L1 never speculates.
 */
interface Captioner<in F> {
    fun describe(frame: F): String

    /** Backend name shown in the UI so the live L1 engine is unambiguous. */
    val backend: String
}

/**
 * Interim backend: an honest diagnostic computed from the bitmap (dimensions +
 * mean brightness) — proves the frame arrived, fabricates no understanding. Used
 * until a vision model is downloaded and [LiteRtCaptioner] takes over.
 */
object PlaceholderCaptioner : Captioner<Bitmap> {
    override fun describe(frame: Bitmap): String {
        val w = frame.width
        val h = frame.height
        // Downscale to a tiny grid for a cheap mean brightness (only runs on admit).
        val s = 16
        val scaled = Bitmap.createScaledBitmap(frame, s, s, false)
        val px = IntArray(s * s)
        scaled.getPixels(px, 0, s, 0, 0, s, s)
        var sum = 0L
        for (p in px) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            sum += (r + g + b) / 3
        }
        if (scaled !== frame) scaled.recycle()
        val meanPct = (sum / (s * s)) * 100 / 255
        return "L1 佔位(未載入 VLM)· ${w}×${h} · 亮度 ${meanPct}%"
    }

    override val backend: String = "placeholder(診斷)"
}
