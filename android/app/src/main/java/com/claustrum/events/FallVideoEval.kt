package com.claustrum.events

import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.roundToLong

internal enum class FallVideoExpected { FALL, NONE }

internal data class FallVideoEvalCase(
    val videoFileName: String,
    val label: String,
    val expected: FallVideoExpected,
    val eventStartMs: Long?,
    val eventEndMs: Long?,
)

/** Strict, anonymous contract for `<externalFiles>/dev_pose_eval/manifest.json`. */
internal object FallVideoEvalManifest {
    private val videoExtensions = setOf("mp4", "webm", "mkv", "3gp")

    fun parse(json: String): List<FallVideoEvalCase> {
        val root = JSONObject(json)
        root.requireOnlyKeys(setOf("version", "cases"), "manifest")
        require(root.getInt("version") == 1) { "manifest.version must be 1" }
        val casesJson = root.getJSONArray("cases")
        require(casesJson.length() > 0) { "manifest.cases must not be empty" }

        val seenVideos = mutableSetOf<String>()
        return List(casesJson.length()) { index ->
            val path = "cases[$index]"
            val item = casesJson.getJSONObject(index)
            item.requireOnlyKeys(
                setOf("video", "label", "expected", "eventStartMs", "eventEndMs"),
                path,
            )
            val video = item.getString("video").trim()
            require(video.isNotEmpty()) { "$path.video must not be blank" }
            require(!video.contains('/') && !video.contains('\\')) {
                "$path.video must be a file name, not a path"
            }
            require(video.substringAfterLast('.', "").lowercase() in videoExtensions) {
                "$path.video has an unsupported extension"
            }
            require(seenVideos.add(video)) { "duplicate video in manifest: $video" }

            val label = item.getString("label").trim()
            require(label.isNotEmpty()) { "$path.label must not be blank" }
            val expected = when (item.getString("expected").trim().lowercase()) {
                "fall" -> FallVideoExpected.FALL
                "none" -> FallVideoExpected.NONE
                else -> throw IllegalArgumentException("$path.expected must be fall or none")
            }
            val start = item.nullableLong("eventStartMs", path)
            val end = item.nullableLong("eventEndMs", path)
            when (expected) {
                FallVideoExpected.FALL -> {
                    require(start != null && end != null) {
                        "$path fall case requires eventStartMs and eventEndMs"
                    }
                    require(start >= 0L && end > start) {
                        "$path fall window must satisfy 0 <= eventStartMs < eventEndMs"
                    }
                }
                FallVideoExpected.NONE -> require(start == null && end == null) {
                    "$path none case must use null eventStartMs and eventEndMs"
                }
            }
            FallVideoEvalCase(video, label, expected, start, end)
        }
    }

    private fun JSONObject.requireOnlyKeys(allowed: Set<String>, path: String) {
        val unknown = keys().asSequence().filterNot(allowed::contains).toList()
        require(unknown.isEmpty()) { "$path contains unknown fields: ${unknown.joinToString()}" }
        val missing = allowed.filterNot(::has)
        require(missing.isEmpty()) { "$path is missing fields: ${missing.joinToString()}" }
    }

    private fun JSONObject.nullableLong(name: String, path: String): Long? {
        if (isNull(name)) return null
        val value = get(name)
        require(value is Number) { "$path.$name must be an integer or null" }
        val asDouble = value.toDouble()
        val asLong = value.toLong()
        require(asDouble.isFinite() && asDouble == asLong.toDouble()) {
            "$path.$name must be an integer or null"
        }
        return asLong
    }
}

/** Pure aggregation for the recorded-video ML Kit → extractor → Rust L2 regression route. */
object FallVideoEval {
    internal data class EventSignals(
        val candidateFallCount: Int,
        val confirmedFallAtMs: List<Long>,
    )

    internal data class CaseResult(
        val label: String,
        val expected: FallVideoExpected,
        val eventStartMs: Long?,
        val eventEndMs: Long?,
        val confirmedFallAtMs: List<Long>,
        val candidateFallCount: Int,
        val sampledFrames: Int,
        val poseVisibleFrames: Int,
        val subjectSpansPx: List<Int>,
        val poseLatencyMs: List<Long>,
    ) {
        init {
            require(label.isNotBlank())
            require(candidateFallCount >= 0 && sampledFrames >= 0)
            require(poseVisibleFrames in 0..sampledFrames)
            require(confirmedFallAtMs.all { it >= 0L })
            require(subjectSpansPx.all { it >= 0 })
            require(subjectSpansPx.size <= poseVisibleFrames)
            require(poseLatencyMs.size == sampledFrames)
            require(poseLatencyMs.all { it >= 0L })
            if (expected == FallVideoExpected.FALL) {
                require(
                    eventStartMs != null && eventEndMs != null &&
                        eventStartMs >= 0L && eventEndMs > eventStartMs,
                )
            } else {
                require(eventStartMs == null && eventEndMs == null)
            }
        }

        val matchedConfirmedCount: Int
            get() = if (expected == FallVideoExpected.FALL && confirmedFallAtMs.any(::isInWindow)) 1 else 0
        val falseConfirmedCount: Int get() = confirmedFallAtMs.size - matchedConfirmedCount
        val positiveMatched: Boolean get() = matchedConfirmedCount == 1
        val negativeFailed: Boolean
            get() = expected == FallVideoExpected.NONE && confirmedFallAtMs.isNotEmpty()

        private fun isInWindow(atMs: Long): Boolean =
            atMs in checkNotNull(eventStartMs)..checkNotNull(eventEndMs)
    }

    data class Summary(
        val cases: Int,
        val positiveCases: Int,
        val negativeCases: Int,
        val truePositiveCases: Int,
        val falseNegativeCases: Int,
        val falsePositiveCases: Int,
        val trueNegativeCases: Int,
        val candidateFalls: Int,
        val confirmedFalls: Int,
        val matchedConfirmedFalls: Int,
        val falseConfirmedFalls: Int,
        val sampledFrames: Int,
        val poseVisibleFrames: Int,
        val minSubjectSpanPx: Int?,
        val avgPoseLatencyMs: Long,
        val p50PoseLatencyMs: Long,
        val p95PoseLatencyMs: Long,
        val maxPoseLatencyMs: Long,
    ) {
        val eventPrecision: Double? get() = ratio(matchedConfirmedFalls, confirmedFalls)
        val positiveRecall: Double? get() = ratio(truePositiveCases, positiveCases)
        val negativePassRate: Double? get() = ratio(trueNegativeCases, negativeCases)
        val poseAcquisitionRate: Double? get() = ratio(poseVisibleFrames, sampledFrames)
    }

    internal fun summarize(results: List<CaseResult>): Summary {
        val latencies = results.flatMap(CaseResult::poseLatencyMs).sorted()
        val positives = results.filter { it.expected == FallVideoExpected.FALL }
        val negatives = results.filter { it.expected == FallVideoExpected.NONE }
        val truePositives = positives.count(CaseResult::positiveMatched)
        val falsePositives = negatives.count(CaseResult::negativeFailed)
        return Summary(
            cases = results.size,
            positiveCases = positives.size,
            negativeCases = negatives.size,
            truePositiveCases = truePositives,
            falseNegativeCases = positives.size - truePositives,
            falsePositiveCases = falsePositives,
            trueNegativeCases = negatives.size - falsePositives,
            candidateFalls = results.sumOf(CaseResult::candidateFallCount),
            confirmedFalls = results.sumOf { it.confirmedFallAtMs.size },
            matchedConfirmedFalls = results.sumOf(CaseResult::matchedConfirmedCount),
            falseConfirmedFalls = results.sumOf(CaseResult::falseConfirmedCount),
            sampledFrames = results.sumOf(CaseResult::sampledFrames),
            poseVisibleFrames = results.sumOf(CaseResult::poseVisibleFrames),
            minSubjectSpanPx = results.flatMap(CaseResult::subjectSpansPx).minOrNull(),
            avgPoseLatencyMs = if (latencies.isEmpty()) 0L else latencies.average().roundToLong(),
            p50PoseLatencyMs = percentile(latencies, 0.50),
            p95PoseLatencyMs = percentile(latencies, 0.95),
            maxPoseLatencyMs = latencies.lastOrNull() ?: 0L,
        )
    }

    /** Decode only the fall fields needed from trusted Rust event-schema documents. */
    internal fun parseEventSignals(jsonEvents: List<String>): EventSignals {
        var candidates = 0
        val confirmed = mutableListOf<Long>()
        jsonEvents.forEach { json ->
            val event = JSONObject(json)
            if (event.getString("type") != "fall") return@forEach
            when (event.getString("status")) {
                "candidate" -> candidates++
                "confirmed" -> {
                    val atMs = event.getLong("detected_at_ms")
                    require(atMs >= 0L) { "fall detected_at_ms must be non-negative" }
                    confirmed += atMs
                }
                else -> throw IllegalArgumentException("fall event has unsupported status")
            }
        }
        return EventSignals(candidates, confirmed)
    }

    private fun percentile(sorted: List<Long>, percentile: Double): Long {
        if (sorted.isEmpty()) return 0L
        val rank = ceil(percentile * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    private fun ratio(numerator: Int, denominator: Int): Double? =
        if (denominator == 0) null else numerator.toDouble() / denominator
}
