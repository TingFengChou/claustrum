package com.claustrum.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PersonOverlayGeometryTest {
    @Test
    fun bounds_includeHeadRoomAndClampToPreview() {
        val bounds = PersonOverlayGeometry.bounds(person(
            PreviewPoint(0.02f, 0.08f, 0.9f),
            PreviewPoint(0.25f, 0.99f, 0.9f),
        ))

        assertThat(bounds).isNotNull()
        assertThat(bounds!!.left).isEqualTo(0f)
        assertThat(bounds.top).isEqualTo(0f)
        assertThat(bounds.right).isGreaterThan(0.25f)
        assertThat(bounds.bottom).isEqualTo(1f)
    }

    @Test
    fun bounds_ignoreLowConfidenceAndNonFinitePoints() {
        assertThat(PersonOverlayGeometry.isReliable(PreviewPoint(Float.NaN, 0.5f, 0.99f))).isFalse()
        assertThat(PersonOverlayGeometry.isReliable(PreviewPoint(0.5f, 0.5f, 0.2f))).isFalse()
        assertThat(PersonOverlayGeometry.isReliable(PreviewPoint(0.5f, 0.5f, 0.9f))).isTrue()
    }

    @Test
    fun bounds_requireEnoughReliableEvidence() {
        val sparse = TrackedPersonUi(slot = 0, points = mapOf(
            TrackedJoint.LEFT_SHOULDER to PreviewPoint(0.3f, 0.2f, 0.9f),
            TrackedJoint.RIGHT_SHOULDER to PreviewPoint(0.6f, 0.2f, 0.9f),
            TrackedJoint.LEFT_HIP to PreviewPoint(0.35f, 0.5f, 0.9f),
        ))

        assertThat(PersonOverlayGeometry.bounds(sparse)).isNull()
    }

    @Test
    fun uiContract_acceptsMultipleAnonymousSlots() {
        val people = listOf(
            person(PreviewPoint(0.1f, 0.2f, 0.9f), PreviewPoint(0.4f, 0.8f, 0.9f), slot = 2),
            person(PreviewPoint(0.6f, 0.2f, 0.9f), PreviewPoint(0.9f, 0.8f, 0.9f), slot = 7),
        )

        assertThat(people.map(TrackedPersonUi::slot)).containsExactly(2, 7).inOrder()
        assertThat(people.mapNotNull(PersonOverlayGeometry::bounds)).hasSize(2)
    }

    @Test
    fun label_reportsCommissioningSizeWithoutExposingJointsOrIdentity() {
        val small = person(
            PreviewPoint(0.1f, 0.2f, 0.9f),
            PreviewPoint(0.4f, 0.8f, 0.9f),
        ).copy(subjectHeightPx = 180)

        assertThat(overlayLabel(listOf(small)))
            .isEqualTo("人體姿態候選 · 高約 180px · 建議放大")
        assertThat(overlayLabel(listOf(small, small.copy(slot = 1))))
            .isEqualTo("2 個人體姿態候選 · 匿名框")
    }

    private fun person(a: PreviewPoint, b: PreviewPoint, slot: Int = 0): TrackedPersonUi =
        TrackedPersonUi(slot = slot, points = mapOf(
            TrackedJoint.LEFT_SHOULDER to a,
            TrackedJoint.RIGHT_SHOULDER to a.copy(x = a.x + 0.08f),
            TrackedJoint.LEFT_HIP to b.copy(y = (a.y + b.y) / 2f),
            TrackedJoint.RIGHT_HIP to b.copy(x = b.x - 0.08f, y = (a.y + b.y) / 2f),
            TrackedJoint.LEFT_KNEE to b.copy(y = b.y - 0.12f),
            TrackedJoint.RIGHT_KNEE to b.copy(x = b.x - 0.08f, y = b.y - 0.12f),
            TrackedJoint.LEFT_ANKLE to b,
            TrackedJoint.RIGHT_ANKLE to b.copy(x = b.x - 0.08f),
        ))
}
