package com.claustrum.objects

/** Objective stages only; none of these values is a litter event or proof of intent. */
internal enum class LitterEvidenceStage {
    OBSERVED,
    PERSON_ASSOCIATED,
    VISIBLE_SEPARATION,
    STATIONARY_AFTER_SEPARATION,
    PERSON_LEFT_PENDING_REVIEW,
}

internal data class TemporalObjectCandidate(
    val tracked: TrackedObjectCandidate,
    val stage: LitterEvidenceStage,
    val associatedPersonSlot: Int?,
)

/**
 * Fail-closed person-object temporal evidence. A missing person detection never counts as
 * separation or departure. The final stage remains review-only and cannot emit an Event.
 */
internal class LitterEvidenceTracker(
    private val config: Config = Config(),
) {
    data class Config(
        val associationMarginRatio: Float = 0.35f,
        val associatedSamplesRequired: Int = 2,
        val stationarySamplesRequired: Int = 2,
        val departureSamplesRequired: Int = 2,
        val departureDistance: Float = 0.25f,
        val departureIncrease: Float = 0.03f,
        val personAbsentMs: Long = 5_000L,
        val stationaryDwellMs: Long = 30_000L,
        val objectObservationsForReview: Int = 5,
        val stateStaleMs: Long = 3_000L,
    ) {
        init {
            require(associationMarginRatio >= 0f)
            require(associatedSamplesRequired >= 2)
            require(stationarySamplesRequired >= 2)
            require(departureSamplesRequired >= 2)
            require(departureDistance > 0f)
            require(departureIncrease >= 0f)
            require(personAbsentMs > 0L && stationaryDwellMs > 0L && stateStaleMs > 0L)
            require(objectObservationsForReview >= stationarySamplesRequired)
        }
    }

    private data class State(
        var stage: LitterEvidenceStage = LitterEvidenceStage.OBSERVED,
        var associatedPersonSlot: Int? = null,
        var associationSamples: Int = 0,
        var stationarySamples: Int = 0,
        var stationarySinceMs: Long? = null,
        var observationsSinceStationary: Int = 0,
        var lastObjectSeenAtMs: Long,
        var lastAssociatedPersonSeenAtMs: Long? = null,
        var lastDepartureDistance: Float? = null,
        var departureSamples: Int = 0,
    )

    private val states = mutableMapOf<Int, State>()
    private var lastFrameAtMs = Long.MIN_VALUE

    fun update(frame: ObjectTrackingFrame): List<TemporalObjectCandidate> {
        require(lastFrameAtMs == Long.MIN_VALUE || frame.atMs > lastFrameAtMs) {
            "litter evidence timestamps must increase"
        }
        lastFrameAtMs = frame.atMs
        states.entries.removeAll { frame.atMs - it.value.lastObjectSeenAtMs > config.stateStaleMs }

        val people = frame.current.filter { it.kind == ObjectTrackKind.PERSON }
        return frame.current.filter { it.kind == ObjectTrackKind.PORTABLE_OBJECT }.map { objectTrack ->
            val state = states.getOrPut(objectTrack.slot) { State(lastObjectSeenAtMs = frame.atMs) }
            state.lastObjectSeenAtMs = frame.atMs
            val nearest = people.minByOrNull { it.bounds.centerDistance(objectTrack.bounds) }
            val nearPerson = nearest?.takeIf {
                it.bounds.containsWithMargin(
                    objectTrack.bounds.centerX,
                    objectTrack.bounds.centerY,
                    config.associationMarginRatio,
                )
            }
            advance(state, objectTrack, people, nearPerson, frame.atMs)
            TemporalObjectCandidate(objectTrack, state.stage, state.associatedPersonSlot)
        }
    }

    fun reset() {
        states.clear()
        lastFrameAtMs = Long.MIN_VALUE
    }

    private fun advance(
        state: State,
        objectTrack: TrackedObjectCandidate,
        people: List<TrackedObjectCandidate>,
        nearPerson: TrackedObjectCandidate?,
        atMs: Long,
    ) {
        if (nearPerson != null) {
            val previousSlot = state.associatedPersonSlot
            if (previousSlot != null && previousSlot != nearPerson.slot) {
                // A slot switch is uncertainty, not continuity with a different person.
                state.stage = LitterEvidenceStage.OBSERVED
                clearStationaryEvidence(state)
            }
            if (previousSlot == nearPerson.slot) {
                state.associationSamples += 1
            } else {
                state.associatedPersonSlot = nearPerson.slot
                state.associationSamples = 1
            }
            state.lastAssociatedPersonSeenAtMs = atMs
            state.lastDepartureDistance = null
            state.departureSamples = 0
            if (state.associationSamples >= config.associatedSamplesRequired) {
                state.stage = LitterEvidenceStage.PERSON_ASSOCIATED
                clearStationaryEvidence(state)
            }
            return
        }

        val associatedPerson = state.associatedPersonSlot?.let { slot -> people.find { it.slot == slot } }
        if (state.stage == LitterEvidenceStage.OBSERVED) {
            state.associatedPersonSlot = null
            state.associationSamples = 0
            state.lastAssociatedPersonSeenAtMs = null
            return
        }
        if (associatedPerson != null) {
            state.lastAssociatedPersonSeenAtMs = atMs
            val distance = associatedPerson.bounds.centerDistance(objectTrack.bounds)
            val previousDistance = state.lastDepartureDistance
            state.departureSamples = when {
                distance < config.departureDistance -> 0
                previousDistance == null -> 1
                distance >= previousDistance + config.departureIncrease -> state.departureSamples + 1
                else -> 0
            }
            state.lastDepartureDistance = distance
        }

        when (state.stage) {
            LitterEvidenceStage.OBSERVED -> Unit
            LitterEvidenceStage.PERSON_ASSOCIATED -> {
                // A detector miss is not separation: the associated person must still be visible.
                if (associatedPerson != null) {
                    state.stage = LitterEvidenceStage.VISIBLE_SEPARATION
                    state.associationSamples = 0
                    state.stationarySamples = 0
                }
            }
            LitterEvidenceStage.VISIBLE_SEPARATION -> updateStationary(state, objectTrack, atMs)
            LitterEvidenceStage.STATIONARY_AFTER_SEPARATION,
            LitterEvidenceStage.PERSON_LEFT_PENDING_REVIEW,
            -> {
                if (state.stage == LitterEvidenceStage.PERSON_LEFT_PENDING_REVIEW &&
                    associatedPerson != null
                ) {
                    state.stage = LitterEvidenceStage.STATIONARY_AFTER_SEPARATION
                }
                if (objectTrack.motion == ObjectMotion.MOVING) {
                    state.stage = LitterEvidenceStage.VISIBLE_SEPARATION
                    clearStationaryEvidence(state)
                } else {
                    if (objectTrack.motion == ObjectMotion.STATIONARY) {
                        state.observationsSinceStationary += 1
                    }
                    val personLastSeen = state.lastAssociatedPersonSeenAtMs
                    val stationarySince = state.stationarySinceMs
                    if (state.departureSamples >= config.departureSamplesRequired &&
                        personLastSeen != null && atMs - personLastSeen >= config.personAbsentMs &&
                        stationarySince != null && atMs - stationarySince >= config.stationaryDwellMs &&
                        state.observationsSinceStationary >= config.objectObservationsForReview
                    ) {
                        state.stage = LitterEvidenceStage.PERSON_LEFT_PENDING_REVIEW
                    }
                }
            }
        }
    }

    private fun updateStationary(state: State, objectTrack: TrackedObjectCandidate, atMs: Long) {
        if (objectTrack.motion != ObjectMotion.STATIONARY) {
            state.stationarySamples = 0
            return
        }
        state.stationarySamples += 1
        if (state.stationarySamples >= config.stationarySamplesRequired) {
            state.stage = LitterEvidenceStage.STATIONARY_AFTER_SEPARATION
            state.stationarySinceMs = atMs
            state.observationsSinceStationary = 1
        }
    }

    private fun clearStationaryEvidence(state: State) {
        state.stationarySamples = 0
        state.stationarySinceMs = null
        state.observationsSinceStationary = 0
    }
}
