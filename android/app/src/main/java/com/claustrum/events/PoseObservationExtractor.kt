package com.claustrum.events

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/** Landmarks used by the first single-person fall extractor. */
internal enum class PoseJoint {
    LEFT_SHOULDER,
    RIGHT_SHOULDER,
    LEFT_HIP,
    RIGHT_HIP,
    LEFT_KNEE,
    RIGHT_KNEE,
    LEFT_ANKLE,
    RIGHT_ANKLE,
}

/** A normalized, pixel-free landmark. Coordinates are relative to the upright frame. */
internal data class PosePoint(
    val x: Float,
    val y: Float,
    val likelihood: Float,
)

/** One detector result after the ML Kit boundary has discarded the source image. */
internal data class PoseFrame(
    val atMs: Long,
    val points: Map<PoseJoint, PosePoint>,
)

/**
 * Converts a single-person pose stream into the conservative Rust L2 wire contract.
 *
 * This class intentionally has no Android or ML Kit dependency, so the temporal logic is
 * deterministic in host tests. It never invents impact, contact, strike, zone, or a second
 * person: those require separately calibrated sensors/models. The short-lived actant slot is
 * a stream role, not an identity.
 */
internal class PoseObservationExtractor(
    private val minLikelihood: Float = 0.65f,
    private val maxTrackGapMs: Long = 750L,
    private val maxTrackJumpBodySpans: Float = 1.25f,
) {
    private var previous: TrackedPose? = null
    private var activeSlot = 0
    private var hasSeenTrack = false
    private var needsNewSlot = false

    fun extract(frame: PoseFrame): FastPathObservation {
        require(frame.atMs >= 0) { "atMs must be non-negative Unix epoch milliseconds" }
        val current = trackedPose(frame)
        if (current == null) {
            previous = null
            needsNewSlot = hasSeenTrack
            return unknown(frame.atMs)
        }

        val prior = previous
        if (prior == null) {
            if (needsNewSlot) advanceSlot()
            hasSeenTrack = true
            needsNewSlot = false
            previous = current
            return observation(frame.atMs, current.pose)
        }

        val elapsedMs = frame.atMs - prior.atMs
        if (elapsedMs <= 0) {
            return observation(frame.atMs, current.pose)
        }
        if (elapsedMs > maxTrackGapMs) {
            advanceSlot()
            previous = current
            return observation(frame.atMs, current.pose)
        }

        val trackScale = max(prior.bodyScale, current.bodyScale)
        val centerJump = distance(prior.hipCenter, current.hipCenter) / trackScale
        if (centerJump > maxTrackJumpBodySpans) {
            // STREAM_MODE has no public tracking ID. A large discontinuity may mean the
            // detector switched people. Rotate the anonymous role slot so Rust cannot
            // splice two people into one fall sequence; this is not an identity.
            advanceSlot()
            previous = current
            return observation(frame.atMs, current.pose)
        }

        val elapsedSeconds = elapsedMs / 1_000f
        val downwardBodySpansPerSecond =
            ((current.hipCenter.y - prior.hipCenter.y) / trackScale / elapsedSeconds)
                .coerceAtLeast(0f)
        val rapidDescent = score(
            value = downwardBodySpansPerSecond,
            onset = RAPID_DESCENT_ONSET,
            full = RAPID_DESCENT_FULL,
        )

        val shared = prior.points.keys.intersect(current.points.keys)
        val meanMotion = if (shared.isEmpty()) {
            0f
        } else {
            shared.sumOf { joint ->
                distance(prior.points.getValue(joint), current.points.getValue(joint)).toDouble()
            }.toFloat() / shared.size / trackScale / elapsedSeconds
        }
        val motion = score(meanMotion, MOTION_ONSET, MOTION_FULL)

        previous = current
        return observation(
            atMs = frame.atMs,
            pose = current.pose,
            rapidDescentScore = rapidDescent,
            motionScore = motion,
        )
    }

    fun reset() {
        previous = null
        activeSlot = 0
        hasSeenTrack = false
        needsNewSlot = false
    }

    private fun trackedPose(frame: PoseFrame): TrackedPose? {
        val reliable = frame.points.filterValues { point ->
            point.x.isFinite() && point.y.isFinite() &&
                point.likelihood.isFinite() && point.likelihood >= minLikelihood
        }
        val shoulders = midpoint(reliable, PoseJoint.LEFT_SHOULDER, PoseJoint.RIGHT_SHOULDER)
            ?: return null
        val hips = midpoint(reliable, PoseJoint.LEFT_HIP, PoseJoint.RIGHT_HIP) ?: return null
        val torso = distance(shoulders, hips)
        if (torso < MIN_BODY_SCALE) return null

        val xs = reliable.values.map(PosePoint::x)
        val ys = reliable.values.map(PosePoint::y)
        val bodyScale = max(
            max(xs.maxOrNull()!! - xs.minOrNull()!!, ys.maxOrNull()!! - ys.minOrNull()!!),
            torso,
        ).coerceAtLeast(MIN_BODY_SCALE)

        return TrackedPose(
            atMs = frame.atMs,
            hipCenter = hips,
            bodyScale = bodyScale,
            pose = classify(reliable, shoulders, hips),
            points = reliable,
        )
    }

    private fun classify(
        points: Map<PoseJoint, PosePoint>,
        shoulders: PosePoint,
        hips: PosePoint,
    ): FastPathPose {
        val torsoDx = abs(hips.x - shoulders.x)
        val torsoDy = abs(hips.y - shoulders.y)
        if (torsoDx >= torsoDy * HORIZONTAL_RATIO) return FastPathPose.HORIZONTAL

        val knees = midpoint(points, PoseJoint.LEFT_KNEE, PoseJoint.RIGHT_KNEE)
            ?: return FastPathPose.UNKNOWN
        val ankles = midpoint(points, PoseJoint.LEFT_ANKLE, PoseJoint.RIGHT_ANKLE)
            ?: return FastPathPose.UNKNOWN
        val verticalTorso = torsoDy >= torsoDx * UPRIGHT_RATIO && shoulders.y < hips.y
        if (!verticalTorso) return FastPathPose.UNKNOWN

        val torsoLength = distance(shoulders, hips)
        val thighDx = abs(knees.x - hips.x)
        val thighDy = abs(knees.y - hips.y)
        val thighMostlyHorizontal = thighDx >= thighDy * HORIZONTAL_RATIO &&
            distance(hips, knees) >= torsoLength * MIN_SEATED_THIGH_TORSO_RATIO
        if (thighMostlyHorizontal) return FastPathPose.SEATED

        val legExtendsDown = hips.y < knees.y && knees.y < ankles.y
        val legLength = distance(hips, ankles)
        if (legExtendsDown && legLength >= torsoLength * MIN_LEG_TORSO_RATIO) {
            return FastPathPose.UPRIGHT
        }
        return FastPathPose.UNKNOWN
    }

    private fun observation(
        atMs: Long,
        pose: FastPathPose,
        rapidDescentScore: Float = 0f,
        motionScore: Float = 0f,
    ) = FastPathObservation(
        atMs = atMs,
        actant = activeSlot,
        pose = pose,
        rapidDescentScore = rapidDescentScore,
        // Pose landmarks alone do not establish an impact or violence action.
        impactScore = 0f,
        motionScore = motionScore,
        closeContactScore = 0f,
        strikeScore = 0f,
        visiblePeople = 1,
    )

    private fun unknown(atMs: Long) = FastPathObservation(
        atMs = atMs,
        actant = activeSlot,
        pose = FastPathPose.UNKNOWN,
        visiblePeople = 0,
    )

    private fun midpoint(
        points: Map<PoseJoint, PosePoint>,
        left: PoseJoint,
        right: PoseJoint,
    ): PosePoint? {
        val a = points[left] ?: return null
        val b = points[right] ?: return null
        return PosePoint(
            x = (a.x + b.x) / 2f,
            y = (a.y + b.y) / 2f,
            likelihood = minOf(a.likelihood, b.likelihood),
        )
    }

    private fun distance(a: PosePoint, b: PosePoint): Float = hypot(a.x - b.x, a.y - b.y)

    private fun advanceSlot() {
        activeSlot = (activeSlot + 1) and UShort.MAX_VALUE.toInt()
    }

    private fun score(value: Float, onset: Float, full: Float): Float =
        ((value - onset) / (full - onset)).coerceIn(0f, 1f)

    private data class TrackedPose(
        val atMs: Long,
        val hipCenter: PosePoint,
        val bodyScale: Float,
        val pose: FastPathPose,
        val points: Map<PoseJoint, PosePoint>,
    )

    private companion object {
        const val MIN_BODY_SCALE = 0.04f
        const val HORIZONTAL_RATIO = 1.25f
        const val UPRIGHT_RATIO = 1.6f
        const val MIN_LEG_TORSO_RATIO = 0.9f
        const val MIN_SEATED_THIGH_TORSO_RATIO = 0.45f
        const val RAPID_DESCENT_ONSET = 0.35f
        const val RAPID_DESCENT_FULL = 1.15f
        const val MOTION_ONSET = 0.25f
        const val MOTION_FULL = 2.0f
    }
}
