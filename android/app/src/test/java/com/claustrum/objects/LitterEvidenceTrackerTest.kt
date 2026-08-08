package com.claustrum.objects

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LitterEvidenceTrackerTest {
    @Test
    fun missingPersonDetectionCannotCreateSeparation() {
        val evidence = LitterEvidenceTracker()
        evidence.update(frame(0L, person(1, 0.10f), bottle(ObjectMotion.UNKNOWN, 0L)))
        val carried = evidence.update(frame(250L, person(1, 0.10f), bottle(ObjectMotion.MOVING, 250L)))

        val afterMiss = evidence.update(frame(500L, bottle(ObjectMotion.MOVING, 500L)))

        assertThat(carried.single().stage).isEqualTo(LitterEvidenceStage.PERSON_ASSOCIATED)
        assertThat(afterMiss.single().stage).isEqualTo(LitterEvidenceStage.PERSON_ASSOCIATED)
    }

    @Test
    fun preexistingStationaryObjectNeverBecomesLitterEvidence() {
        val evidence = LitterEvidenceTracker()

        var result = evidence.update(frame(0L, bottle(ObjectMotion.UNKNOWN, 0L)))
        for (atMs in 2_000L..40_000L step 2_000L) {
            result = evidence.update(frame(atMs, bottle(ObjectMotion.STATIONARY, atMs)))
        }

        assertThat(result.single().stage).isEqualTo(LitterEvidenceStage.OBSERVED)
        assertThat(result.single().associatedPersonSlot).isNull()
    }

    @Test
    fun personAssociationSamplesMustBeConsecutive() {
        val evidence = LitterEvidenceTracker()
        evidence.update(frame(0L, person(1, 0.10f), bottle(ObjectMotion.UNKNOWN, 0L)))
        evidence.update(frame(250L, bottle(ObjectMotion.MOVING, 250L)))

        val seenAgain = evidence.update(
            frame(500L, person(1, 0.10f), bottle(ObjectMotion.MOVING, 500L)),
        )

        assertThat(seenAgain.single().stage).isEqualTo(LitterEvidenceStage.OBSERVED)
    }

    @Test
    fun completeVisibleSequenceOnlyReachesReviewPendingAfterDwellAndDeparture() {
        val evidence = LitterEvidenceTracker()
        evidence.update(frame(0L, person(1, 0.10f), bottle(ObjectMotion.UNKNOWN, 0L)))
        evidence.update(frame(250L, person(1, 0.10f), bottle(ObjectMotion.MOVING, 250L)))
        evidence.update(frame(500L, person(1, 0.30f), bottle(ObjectMotion.MOVING, 500L)))
        evidence.update(frame(750L, person(1, 0.34f), bottle(ObjectMotion.STATIONARY, 750L)))
        var result = evidence.update(
            frame(1_000L, person(1, 0.38f), bottle(ObjectMotion.STATIONARY, 1_000L)),
        )
        result = evidence.update(frame(3_000L, bottle(ObjectMotion.STATIONARY, 3_000L)))

        assertThat(result.single().stage).isEqualTo(LitterEvidenceStage.STATIONARY_AFTER_SEPARATION)
        for (atMs in 5_000L..31_000L step 2_000L) {
            result = evidence.update(frame(atMs, bottle(ObjectMotion.STATIONARY, atMs)))
        }

        assertThat(result.single().stage)
            .isEqualTo(LitterEvidenceStage.PERSON_LEFT_PENDING_REVIEW)
        assertThat(result.single().associatedPersonSlot).isEqualTo(1)
    }

    @Test
    fun nearbyPersonPickupRequiresFreshAssociationAndDoesNotConfirm() {
        val evidence = LitterEvidenceTracker()
        evidence.update(frame(0L, person(1, 0.10f), bottle(ObjectMotion.UNKNOWN, 0L)))
        evidence.update(frame(250L, person(1, 0.10f), bottle(ObjectMotion.MOVING, 250L)))
        evidence.update(frame(500L, person(1, 0.30f), bottle(ObjectMotion.MOVING, 500L)))
        evidence.update(frame(750L, person(1, 0.34f), bottle(ObjectMotion.STATIONARY, 750L)))
        evidence.update(
            frame(1_000L, person(1, 0.38f), bottle(ObjectMotion.STATIONARY, 1_000L)),
        )

        val firstPickupFrame = evidence.update(
            frame(1_250L, person(2, 0.10f), bottle(ObjectMotion.MOVING, 1_250L)),
        )
        val pickedUp = evidence.update(
            frame(1_500L, person(2, 0.10f), bottle(ObjectMotion.MOVING, 1_500L)),
        )

        assertThat(firstPickupFrame.single().stage).isEqualTo(LitterEvidenceStage.OBSERVED)
        assertThat(pickedUp.single().stage).isEqualTo(LitterEvidenceStage.PERSON_ASSOCIATED)
        assertThat(pickedUp.single().associatedPersonSlot).isEqualTo(2)
    }

    @Test
    fun staleObjectTrackDropsTemporalEvidence() {
        val evidence = LitterEvidenceTracker()
        evidence.update(frame(0L, person(1, 0.10f), bottle(ObjectMotion.UNKNOWN, 0L)))
        evidence.update(frame(250L, person(1, 0.10f), bottle(ObjectMotion.MOVING, 250L)))

        val restarted = evidence.update(frame(4_000L, bottle(ObjectMotion.UNKNOWN, 4_000L)))

        assertThat(restarted.single().stage).isEqualTo(LitterEvidenceStage.OBSERVED)
        assertThat(restarted.single().associatedPersonSlot).isNull()
    }

    private fun frame(atMs: Long, vararg tracks: TrackedObjectCandidate) =
        ObjectTrackingFrame(atMs, tracks.toList())

    private fun person(slot: Int, left: Float) = TrackedObjectCandidate(
        slot = slot,
        kind = ObjectTrackKind.PERSON,
        category = "person",
        score = 0.9f,
        bounds = NormalizedObjectBounds(left, 0.05f, left + 0.25f, 0.75f),
        motion = ObjectMotion.MOVING,
        observationCount = 3,
        atMs = 0L,
    )

    private fun bottle(motion: ObjectMotion, atMs: Long) = TrackedObjectCandidate(
        slot = 1,
        kind = ObjectTrackKind.PORTABLE_OBJECT,
        category = "bottle",
        score = 0.8f,
        bounds = NormalizedObjectBounds(0.18f, 0.30f, 0.24f, 0.48f),
        motion = motion,
        observationCount = 3,
        atMs = atMs,
    )
}
