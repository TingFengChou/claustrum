package com.claustrum.objects

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class AnonymousObjectTrackerTest {
    @Test
    fun stableGeometryKeepsSessionSlotAndClassifiesMotion() {
        val tracker = AnonymousObjectTracker(
            AnonymousObjectTracker.Config(speedSmoothing = 1f),
        )

        val first = tracker.update(listOf(sample("bottle", 0.10f)), 0L).current.single()
        val moving = tracker.update(listOf(sample("bottle", 0.20f)), 250L).current.single()
        val stationary = tracker.update(listOf(sample("bottle", 0.20f)), 500L).current.single()

        assertThat(first.slot).isEqualTo(1)
        assertThat(first.motion).isEqualTo(ObjectMotion.UNKNOWN)
        assertThat(moving.slot).isEqualTo(first.slot)
        assertThat(moving.motion).isEqualTo(ObjectMotion.MOVING)
        assertThat(stationary.slot).isEqualTo(first.slot)
        assertThat(stationary.motion).isEqualTo(ObjectMotion.STATIONARY)
    }

    @Test
    fun personAndPortableObjectUseSeparateAnonymousSlotNamespaces() {
        val tracker = AnonymousObjectTracker()

        val tracks = tracker.update(
            listOf(sample("person", 0.10f), sample("bottle", 0.15f)),
            0L,
        ).current

        assertThat(tracks.map { it.slot }).containsExactly(1, 1).inOrder()
        assertThat(tracks.map { it.kind }).containsExactly(
            ObjectTrackKind.PERSON,
            ObjectTrackKind.PORTABLE_OBJECT,
        ).inOrder()
    }

    @Test
    fun categoryChangeOrLongGapCannotPretendContinuity() {
        val tracker = AnonymousObjectTracker()

        val bottle = tracker.update(listOf(sample("bottle", 0.10f)), 0L).current.single()
        val cup = tracker.update(listOf(sample("cup", 0.10f)), 250L).current.single()
        val bottleAfterGap = tracker.update(listOf(sample("bottle", 0.10f)), 3_501L).current.single()

        assertThat(cup.slot).isNotEqualTo(bottle.slot)
        assertThat(bottleAfterGap.slot).isNotEqualTo(bottle.slot)
    }

    @Test
    fun resetRemovesAllCrossSessionContinuity() {
        val tracker = AnonymousObjectTracker()
        tracker.update(listOf(sample("bottle", 0.10f)), 5_000L)

        tracker.reset()

        val restarted = tracker.update(listOf(sample("bottle", 0.10f)), 10L).current.single()
        assertThat(restarted.slot).isEqualTo(1)
        assertThat(restarted.observationCount).isEqualTo(1)
    }

    @Test
    fun outOfOrderTimestampIsRejected() {
        val tracker = AnonymousObjectTracker()
        tracker.update(emptyList(), 10L)

        assertThrows(IllegalArgumentException::class.java) { tracker.update(emptyList(), 10L) }
    }

    private fun sample(category: String, left: Float) = ObjectDetectionSample(
        category = category,
        score = 0.8f,
        bounds = NormalizedObjectBounds(left, 0.1f, left + 0.1f, 0.3f),
    )
}
