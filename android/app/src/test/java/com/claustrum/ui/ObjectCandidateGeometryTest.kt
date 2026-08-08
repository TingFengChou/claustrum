package com.claustrum.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ObjectCandidateGeometryTest {
    @Test
    fun boundsAreNormalizedAndClampedToPreview() {
        val bounds = ObjectCandidateGeometry.normalizedBounds(
            leftPx = -20f,
            topPx = 10f,
            rightPx = 120f,
            bottomPx = 220f,
            viewWidth = 100,
            viewHeight = 200,
        )

        assertThat(bounds).isEqualTo(OverlayBounds(0f, 0.05f, 1f, 1f))
    }

    @Test
    fun invalidOrOffscreenBoundsAreRejected() {
        assertThat(ObjectCandidateGeometry.normalizedBounds(5f, 5f, 4f, 8f, 100, 100)).isNull()
        assertThat(ObjectCandidateGeometry.normalizedBounds(Float.NaN, 5f, 8f, 9f, 100, 100)).isNull()
        assertThat(ObjectCandidateGeometry.normalizedBounds(110f, 5f, 120f, 9f, 100, 100)).isNull()
    }

    @Test
    fun summaryUsesObjectiveCategoriesOnly() {
        val candidates = listOf(
            ObjectCandidateUi("bottle", 0.8f, OverlayBounds(0f, 0f, 0.2f, 0.3f)),
            ObjectCandidateUi("bottle", 0.7f, OverlayBounds(0.3f, 0f, 0.5f, 0.3f)),
            ObjectCandidateUi("person", 0.9f, OverlayBounds(0.5f, 0f, 1f, 1f)),
        )

        assertThat(objectCandidateSummary(candidates, latencyMs = 42L))
            .isEqualTo("物件候選 3 · 瓶子×2 · 人 · 42ms")
    }

    @Test
    fun knownCategoryGetsDisplayLabelButUnknownStaysObjective() {
        assertThat(objectCategoryLabel("cell phone")).isEqualTo("手機")
        assertThat(objectCategoryLabel("traffic cone")).isEqualTo("traffic cone")
    }
}
