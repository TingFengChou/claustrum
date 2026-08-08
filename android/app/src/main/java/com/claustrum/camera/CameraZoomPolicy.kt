package com.claustrum.camera

/** Pure zoom-range policy shared by CameraX control and Compose UI. */
internal object CameraZoomPolicy {
    const val STEP = 0.5f

    fun clamp(requested: Float, min: Float, max: Float, fallback: Float = 1f): Float {
        if (!min.isFinite() || !max.isFinite() || min > max) return fallback
        val safeFallback = fallback.takeIf(Float::isFinite)?.coerceIn(min, max) ?: min
        return requested.takeIf(Float::isFinite)?.coerceIn(min, max) ?: safeFallback
    }

    fun next(current: Float, increase: Boolean, min: Float, max: Float): Float =
        clamp(
            requested = current + if (increase) STEP else -STEP,
            min = min,
            max = max,
            fallback = current,
        )
}
