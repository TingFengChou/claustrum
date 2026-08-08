package com.claustrum.objects

import kotlin.math.hypot
import kotlin.math.max

internal data class NormalizedObjectBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(listOf(left, top, right, bottom).all(Float::isFinite))
        require(left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f)
        require(right > left && bottom > top)
    }

    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun centerDistance(other: NormalizedObjectBounds): Float =
        hypot(centerX - other.centerX, centerY - other.centerY)

    fun intersectionOverUnion(other: NormalizedObjectBounds): Float {
        val intersectionWidth = (minOf(right, other.right) - maxOf(left, other.left)).coerceAtLeast(0f)
        val intersectionHeight = (minOf(bottom, other.bottom) - maxOf(top, other.top)).coerceAtLeast(0f)
        val intersection = intersectionWidth * intersectionHeight
        if (intersection <= 0f) return 0f
        val union = width * height + other.width * other.height - intersection
        return if (union > 0f) intersection / union else 0f
    }

    fun containsWithMargin(pointX: Float, pointY: Float, marginRatio: Float): Boolean {
        val horizontalMargin = width * marginRatio
        val verticalMargin = height * marginRatio
        return pointX in (left - horizontalMargin)..(right + horizontalMargin) &&
            pointY in (top - verticalMargin)..(bottom + verticalMargin)
    }
}

internal data class ObjectDetectionSample(
    val category: String,
    val score: Float,
    val bounds: NormalizedObjectBounds,
) {
    init {
        require(category.isNotBlank())
        require(score.isFinite())
    }
}

internal enum class ObjectTrackKind { PERSON, PORTABLE_OBJECT }

internal enum class ObjectMotion { UNKNOWN, MOVING, STATIONARY }

/** Session-local role slot. It is deliberately not a person or object identity. */
internal data class TrackedObjectCandidate(
    val slot: Int,
    val kind: ObjectTrackKind,
    val category: String,
    val score: Float,
    val bounds: NormalizedObjectBounds,
    val motion: ObjectMotion,
    val observationCount: Int,
    val atMs: Long,
)

internal data class ObjectTrackingFrame(
    val atMs: Long,
    val current: List<TrackedObjectCandidate>,
)

/**
 * Short-lived geometry association for MediaPipe detections.
 *
 * Slots reset with the guardian session and expire quickly. Matching never crosses category or
 * person/object kind, and does not use appearance, faces, embeddings, or persistent identifiers.
 */
internal class AnonymousObjectTracker(
    private val config: Config = Config(),
) {
    data class Config(
        val associationMaxGapMs: Long = 3_000L,
        val minIntersectionOverUnion: Float = 0.05f,
        val maxCenterDistance: Float = 0.22f,
        val stationarySpeedPerSecond: Float = 0.035f,
        val speedSmoothing: Float = 0.45f,
    ) {
        init {
            require(associationMaxGapMs > 0L)
            require(minIntersectionOverUnion in 0f..1f)
            require(maxCenterDistance > 0f)
            require(stationarySpeedPerSecond > 0f)
            require(speedSmoothing in 0f..1f)
        }
    }

    private class Track(
        val slot: Int,
        val kind: ObjectTrackKind,
        val category: String,
        var bounds: NormalizedObjectBounds,
        var score: Float,
        var lastSeenAtMs: Long,
        var speedPerSecond: Float? = null,
        var observations: Int = 1,
    )

    private data class Match(val track: Track, val detectionIndex: Int, val cost: Float)

    private val tracks = mutableListOf<Track>()
    private var nextPersonSlot = 1
    private var nextObjectSlot = 1
    private var lastFrameAtMs = Long.MIN_VALUE

    fun update(detections: List<ObjectDetectionSample>, atMs: Long): ObjectTrackingFrame {
        require(atMs >= 0L)
        require(lastFrameAtMs == Long.MIN_VALUE || atMs > lastFrameAtMs) {
            "tracking timestamps must increase"
        }
        lastFrameAtMs = atMs
        tracks.removeAll { atMs - it.lastSeenAtMs > config.associationMaxGapMs }

        val possibleMatches = buildList {
            tracks.forEach { track ->
                detections.forEachIndexed { index, detection ->
                    val kind = detection.kind()
                    if (track.kind != kind || track.category != detection.category) return@forEachIndexed
                    val iou = track.bounds.intersectionOverUnion(detection.bounds)
                    val distance = track.bounds.centerDistance(detection.bounds)
                    if (iou >= config.minIntersectionOverUnion || distance <= config.maxCenterDistance) {
                        add(Match(track, index, distance - iou * 0.25f))
                    }
                }
            }
        }.sortedBy(Match::cost)

        val matchedTracks = mutableSetOf<Track>()
        val matchedDetections = mutableSetOf<Int>()
        val resultByDetection = arrayOfNulls<TrackedObjectCandidate>(detections.size)
        possibleMatches.forEach { match ->
            if (!matchedTracks.add(match.track) || !matchedDetections.add(match.detectionIndex)) return@forEach
            val detection = detections[match.detectionIndex]
            val elapsedSeconds = (atMs - match.track.lastSeenAtMs) / 1_000f
            val instantaneousSpeed = match.track.bounds.centerDistance(detection.bounds) / elapsedSeconds
            match.track.speedPerSecond = match.track.speedPerSecond?.let { previous ->
                previous * (1f - config.speedSmoothing) + instantaneousSpeed * config.speedSmoothing
            } ?: instantaneousSpeed
            match.track.bounds = detection.bounds
            match.track.score = detection.score.coerceIn(0f, 1f)
            match.track.lastSeenAtMs = atMs
            match.track.observations += 1
            resultByDetection[match.detectionIndex] = match.track.snapshot(atMs)
        }

        detections.forEachIndexed { index, detection ->
            if (resultByDetection[index] != null) return@forEachIndexed
            val kind = detection.kind()
            val track = Track(
                slot = if (kind == ObjectTrackKind.PERSON) nextPersonSlot++ else nextObjectSlot++,
                kind = kind,
                category = detection.category,
                bounds = detection.bounds,
                score = detection.score.coerceIn(0f, 1f),
                lastSeenAtMs = atMs,
            )
            tracks += track
            resultByDetection[index] = track.snapshot(atMs)
        }

        return ObjectTrackingFrame(atMs, resultByDetection.filterNotNull())
    }

    fun reset() {
        tracks.clear()
        nextPersonSlot = 1
        nextObjectSlot = 1
        lastFrameAtMs = Long.MIN_VALUE
    }

    private fun Track.snapshot(atMs: Long): TrackedObjectCandidate = TrackedObjectCandidate(
        slot = slot,
        kind = kind,
        category = category,
        score = score,
        bounds = bounds,
        motion = when {
            observations < 2 || speedPerSecond == null -> ObjectMotion.UNKNOWN
            speedPerSecond!! <= config.stationarySpeedPerSecond -> ObjectMotion.STATIONARY
            else -> ObjectMotion.MOVING
        },
        observationCount = observations,
        atMs = atMs,
    )

    private fun ObjectDetectionSample.kind(): ObjectTrackKind =
        if (category == "person") ObjectTrackKind.PERSON else ObjectTrackKind.PORTABLE_OBJECT
}
