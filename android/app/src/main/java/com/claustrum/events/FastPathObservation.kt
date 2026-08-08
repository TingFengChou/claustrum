package com.claustrum.events

/** Stable JNI wire values for the Rust `events::Pose` contract. */
enum class FastPathPose(internal val wireValue: Int) {
    UNKNOWN(0),
    UPRIGHT(1),
    SEATED(2),
    CROUCHED(3),
    HORIZONTAL(4),
    PRONE(5),
}

/**
 * Anonymous, pixel-free output from a future on-device pose/action extractor.
 *
 * `actant` is a short-lived tracker slot within one camera session, never a person
 * identity. `atMs` is Unix epoch milliseconds, matching the Event schema. Scores are
 * passed through because the Rust engine owns finite/range normalization and all event
 * thresholds.
 */
data class FastPathObservation(
    val atMs: Long,
    val actant: Int,
    val secondaryActant: Int? = null,
    val pose: FastPathPose = FastPathPose.UNKNOWN,
    val rapidDescentScore: Float = 0f,
    val impactScore: Float = 0f,
    val motionScore: Float = 0f,
    val closeContactScore: Float = 0f,
    val strikeScore: Float = 0f,
    val visiblePeople: Int = 0,
    val zoneExit: Boolean = false,
) {
    init {
        require(atMs >= 0) { "atMs must be non-negative Unix epoch milliseconds" }
        require(actant in ACTANT_RANGE) { "actant must fit the Rust u16 slot" }
        require(secondaryActant == null || secondaryActant in ACTANT_RANGE) {
            "secondaryActant must fit the Rust u16 slot"
        }
        require(visiblePeople in 0..UByte.MAX_VALUE.toInt()) {
            "visiblePeople must fit the Rust u8 count"
        }
    }

    private companion object {
        val ACTANT_RANGE = 0..UShort.MAX_VALUE.toInt()
    }
}
