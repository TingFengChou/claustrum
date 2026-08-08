package com.claustrum.objects

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ObjectCandidateGateTest {
    @Test
    fun firstFrameAndPeriodicProbeAreAdmitted() {
        val gate = ObjectCandidateGate(periodicProbeMs = 2_000L)

        assertThat(gate.shouldAnalyze(0L, 0L)).isTrue()
        assertThat(gate.shouldAnalyze(0L, 1_999L)).isFalse()
        assertThat(gate.shouldAnalyze(0L, 2_000L)).isTrue()
    }

    @Test
    fun realChangeStartsBoundedActiveSampling() {
        val gate = ObjectCandidateGate(
            changeThreshold = 4,
            minIntervalMs = 250L,
            activeWindowMs = 1_000L,
            periodicProbeMs = 2_000L,
        )
        gate.shouldAnalyze(0L, 0L)

        assertThat(gate.shouldAnalyze(0b1111L, 100L)).isFalse()
        assertThat(gate.shouldAnalyze(0b1111L, 250L)).isTrue()
        assertThat(gate.shouldAnalyze(0b1111L, 500L)).isTrue()
        assertThat(gate.shouldAnalyze(0b1111L, 1_500L)).isFalse()
    }

    @Test
    fun resetAndOutOfOrderFramesCannotCorruptSchedule() {
        val gate = ObjectCandidateGate()
        gate.shouldAnalyze(0L, 1_000L)

        assertThat(gate.shouldAnalyze(-1L, 999L)).isFalse()
        gate.reset()
        assertThat(gate.shouldAnalyze(5L, 10L)).isTrue()
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeTimestampIsRejected() {
        ObjectCandidateGate().shouldAnalyze(0L, -1L)
    }
}
