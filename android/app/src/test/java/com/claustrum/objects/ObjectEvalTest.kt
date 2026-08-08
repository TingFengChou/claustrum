package com.claustrum.objects

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class ObjectEvalTest {
    @Test
    fun perfectMatchProducesPrecisionRecallAndIou() {
        val result = ObjectEval.evaluate(
            label = "day-person",
            imageWidth = 1000,
            imageHeight = 500,
            groundTruth = listOf(annotation("person", 0.1f, 0.2f, 0.3f, 0.8f)),
            predictions = listOf(prediction("person", 0.9f, 0.1f, 0.2f, 0.3f, 0.8f)),
            latencyMs = 100,
        )
        val summary = ObjectEval.summarize(listOf(result))

        assertThat(summary.truePositive).isEqualTo(1)
        assertThat(summary.falsePositive).isEqualTo(0)
        assertThat(summary.falseNegative).isEqualTo(0)
        assertThat(summary.precision).isWithin(0.0001).of(1.0)
        assertThat(summary.recall).isWithin(0.0001).of(1.0)
        assertThat(summary.meanMatchedIou!!).isWithin(0.0001).of(1.0)
        assertThat(summary.minGroundTruthShortSidePx).isEqualTo(200)
        assertThat(summary.minGroundTruthAreaPx).isEqualTo(60_000)
    }

    @Test
    fun duplicatePredictionIsFalsePositive() {
        val groundTruth = listOf(annotation("bottle", 0.1f, 0.1f, 0.2f, 0.4f))
        val predictions = listOf(
            prediction("bottle", 0.9f, 0.1f, 0.1f, 0.2f, 0.4f),
            prediction("bottle", 0.7f, 0.1f, 0.1f, 0.2f, 0.4f),
        )

        val result = ObjectEval.evaluate("duplicate", 100, 100, groundTruth, predictions, 10)

        assertThat(result.truePositive).isEqualTo(1)
        assertThat(result.falsePositive).isEqualTo(1)
        assertThat(result.falseNegative).isEqualTo(0)
    }

    @Test
    fun wrongCategoryCountsAsFalsePositiveAndFalseNegative() {
        val result = ObjectEval.evaluate(
            "wrong-category",
            100,
            100,
            listOf(annotation("bottle", 0.1f, 0.1f, 0.3f, 0.5f)),
            listOf(prediction("cup", 0.8f, 0.1f, 0.1f, 0.3f, 0.5f)),
            10,
        )

        assertThat(result.truePositive).isEqualTo(0)
        assertThat(result.falsePositive).isEqualTo(1)
        assertThat(result.falseNegative).isEqualTo(1)
        assertThat(result.categoryCounts.getValue("bottle").falseNegative).isEqualTo(1)
        assertThat(result.categoryCounts.getValue("cup").falsePositive).isEqualTo(1)
    }

    @Test
    fun belowIouThresholdDoesNotMatch() {
        val result = ObjectEval.evaluate(
            "low-iou",
            100,
            100,
            listOf(annotation("person", 0f, 0f, 0.5f, 0.5f)),
            listOf(prediction("person", 0.9f, 0.25f, 0.25f, 0.75f, 0.75f)),
            10,
        )

        assertThat(result.truePositive).isEqualTo(0)
        assertThat(result.falsePositive).isEqualTo(1)
        assertThat(result.falseNegative).isEqualTo(1)
    }

    @Test
    fun hardNegativeTracksAnyFalseDetection() {
        val clean = ObjectEval.evaluate("empty-clean", 100, 100, emptyList(), emptyList(), 10)
        val failed = ObjectEval.evaluate(
            "empty-failed",
            100,
            100,
            emptyList(),
            listOf(prediction("person", 0.5f, 0.1f, 0.1f, 0.2f, 0.4f)),
            30,
        )
        val summary = ObjectEval.summarize(listOf(clean, failed))

        assertThat(summary.hardNegativeImages).isEqualTo(2)
        assertThat(summary.hardNegativeFailures).isEqualTo(1)
        assertThat(summary.precision).isWithin(0.0001).of(0.0)
        assertThat(summary.recall).isNull()
        assertThat(summary.p50LatencyMs).isEqualTo(10)
        assertThat(summary.p95LatencyMs).isEqualTo(30)
    }

    @Test
    fun summaryAggregatesPerCategory() {
        val first = ObjectEval.evaluate(
            "one",
            100,
            100,
            listOf(annotation("person", 0f, 0f, 0.5f, 1f)),
            listOf(prediction("person", 0.9f, 0f, 0f, 0.5f, 1f)),
            10,
        )
        val second = ObjectEval.evaluate(
            "two",
            100,
            100,
            listOf(annotation("bottle", 0f, 0f, 0.1f, 0.2f)),
            emptyList(),
            20,
        )

        val summary = ObjectEval.summarize(listOf(first, second))

        assertThat(summary.categories.map { it.category }).containsExactly("bottle", "person").inOrder()
        assertThat(summary.categories.first().falseNegative).isEqualTo(1)
        assertThat(summary.categories.first().minGroundTruthShortSidePx).isEqualTo(10)
        assertThat(summary.categories.first().minGroundTruthAreaPx).isEqualTo(200)
        assertThat(summary.avgLatencyMs).isEqualTo(15)
    }

    private fun annotation(
        category: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) = ObjectEvalAnnotation(category, NormalizedObjectBounds(left, top, right, bottom))

    private fun prediction(
        category: String,
        score: Float,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) = ObjectDetectionSample(category, score, NormalizedObjectBounds(left, top, right, bottom))
}
