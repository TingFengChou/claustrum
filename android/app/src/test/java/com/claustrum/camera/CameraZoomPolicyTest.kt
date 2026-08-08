package com.claustrum.camera

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CameraZoomPolicyTest {
    @Test
    fun clamp_respectsDeviceRange() {
        assertThat(CameraZoomPolicy.clamp(0.5f, 1f, 8f)).isEqualTo(1f)
        assertThat(CameraZoomPolicy.clamp(12f, 1f, 8f)).isEqualTo(8f)
        assertThat(CameraZoomPolicy.clamp(2.5f, 1f, 8f)).isEqualTo(2.5f)
    }

    @Test
    fun clamp_rejectsNonFiniteRequestAndInvalidRange() {
        assertThat(CameraZoomPolicy.clamp(Float.NaN, 1f, 8f, fallback = 3f)).isEqualTo(3f)
        assertThat(CameraZoomPolicy.clamp(2f, 8f, 1f, fallback = 3f)).isEqualTo(3f)
    }

    @Test
    fun next_usesHalfStepAndStopsAtDeviceLimits() {
        assertThat(CameraZoomPolicy.next(1f, increase = true, min = 1f, max = 1.3f))
            .isEqualTo(1.3f)
        assertThat(CameraZoomPolicy.next(1f, increase = false, min = 1f, max = 8f))
            .isEqualTo(1f)
        assertThat(CameraZoomPolicy.next(2f, increase = true, min = 1f, max = 8f))
            .isEqualTo(2.5f)
    }
}
