package com.claustrum.events

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FallVideoEvalTest {
    @Test
    fun summarizesWindowedEventsAndNegativeClips() {
        val summary = FallVideoEval.summarize(
            listOf(
                result("hit", FallVideoExpected.FALL, 1_000, 5_000, listOf(3_000), 1, listOf(10, 20)),
                result("late", FallVideoExpected.FALL, 1_000, 5_000, listOf(5_001), 1, listOf(30)),
                result("clean", FallVideoExpected.NONE, null, null, emptyList(), 0, listOf(40)),
                result("false", FallVideoExpected.NONE, null, null, listOf(2_000), 1, listOf(50)),
            ),
        )

        assertThat(summary.truePositiveCases).isEqualTo(1)
        assertThat(summary.falseNegativeCases).isEqualTo(1)
        assertThat(summary.falsePositiveCases).isEqualTo(1)
        assertThat(summary.trueNegativeCases).isEqualTo(1)
        assertThat(summary.matchedConfirmedFalls).isEqualTo(1)
        assertThat(summary.falseConfirmedFalls).isEqualTo(2)
        assertThat(summary.eventPrecision!!).isWithin(0.0001).of(1.0 / 3.0)
        assertThat(summary.positiveRecall!!).isWithin(0.0001).of(0.5)
        assertThat(summary.negativePassRate!!).isWithin(0.0001).of(0.5)
        assertThat(summary.poseAcquisitionRate!!).isWithin(0.0001).of(1.0)
        assertThat(summary.p50PoseLatencyMs).isEqualTo(30)
        assertThat(summary.p95PoseLatencyMs).isEqualTo(50)
        assertThat(summary.minSubjectSpanPx).isEqualTo(80)
    }

    @Test
    fun oneInWindowEventMatchesAndDuplicatesRemainFalseConfirmed() {
        val summary = FallVideoEval.summarize(
            listOf(result("duplicate", FallVideoExpected.FALL, 100, 200, listOf(90, 150, 170), 1)),
        )

        assertThat(summary.truePositiveCases).isEqualTo(1)
        assertThat(summary.confirmedFalls).isEqualTo(3)
        assertThat(summary.matchedConfirmedFalls).isEqualTo(1)
        assertThat(summary.falseConfirmedFalls).isEqualTo(2)
    }

    @Test
    fun emptySummaryDoesNotInventRatesOrLatency() {
        val summary = FallVideoEval.summarize(emptyList())

        assertThat(summary.eventPrecision).isNull()
        assertThat(summary.positiveRecall).isNull()
        assertThat(summary.negativePassRate).isNull()
        assertThat(summary.poseAcquisitionRate).isNull()
        assertThat(summary.p95PoseLatencyMs).isEqualTo(0)
        assertThat(summary.minSubjectSpanPx).isNull()
    }

    @Test
    fun parsesOnlyFallSignalsFromRustEvents() {
        val signals = FallVideoEval.parseEventSignals(
            listOf(
                """{"type":"fall","status":"candidate","detected_at_ms":100}""",
                """{"type":"fall","status":"confirmed","detected_at_ms":2100}""",
                """{"type":"zone_exit","status":"confirmed","detected_at_ms":2200}""",
            ),
        )

        assertThat(signals.candidateFallCount).isEqualTo(1)
        assertThat(signals.confirmedFallAtMs).containsExactly(2100L)
    }

    private fun result(
        label: String,
        expected: FallVideoExpected,
        start: Long?,
        end: Long?,
        confirmed: List<Long>,
        candidateCount: Int,
        latencies: List<Long> = listOf(10L),
    ) = FallVideoEval.CaseResult(
        label = label,
        expected = expected,
        eventStartMs = start,
        eventEndMs = end,
        confirmedFallAtMs = confirmed,
        candidateFallCount = candidateCount,
        sampledFrames = latencies.size,
        poseVisibleFrames = latencies.size,
        subjectSpansPx = List(latencies.size) { if (it == 0) 80 else 120 },
        poseLatencyMs = latencies,
    )
}
