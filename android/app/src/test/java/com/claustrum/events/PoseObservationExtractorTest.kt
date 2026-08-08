package com.claustrum.events

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class PoseObservationExtractorTest {

    @Test
    fun `full vertical body is an upright anonymous observation`() {
        val observation = PoseObservationExtractor().extract(standing(atMs = 1_000))

        assertThat(observation.pose).isEqualTo(FastPathPose.UPRIGHT)
        assertThat(observation.visiblePeople).isEqualTo(1)
        assertThat(observation.actant).isEqualTo(0)
        assertThat(observation.secondaryActant).isNull()
        assertThat(observation.impactScore).isZero()
        assertThat(observation.closeContactScore).isZero()
        assertThat(observation.strikeScore).isZero()
    }

    @Test
    fun `rapid downward transition to horizontal produces fall features`() {
        val extractor = PoseObservationExtractor()
        extractor.extract(standing(atMs = 1_000))

        val observation = extractor.extract(horizontal(atMs = 1_400, centerY = 0.80f))

        assertThat(observation.pose).isEqualTo(FastPathPose.HORIZONTAL)
        assertThat(observation.rapidDescentScore).isGreaterThan(0.85f)
        assertThat(observation.motionScore).isGreaterThan(0f)
        assertThat(observation.impactScore).isZero()
    }

    @Test
    fun `horizontal torso remains visible evidence when lower body is occluded`() {
        val extractor = PoseObservationExtractor()
        val torsoOnly = horizontal(atMs = 1_000, centerY = 0.70f).copy(
            points = horizontal(atMs = 1_000, centerY = 0.70f).points.filterKeys { joint ->
                joint in setOf(
                    PoseJoint.LEFT_SHOULDER,
                    PoseJoint.RIGHT_SHOULDER,
                    PoseJoint.LEFT_HIP,
                    PoseJoint.RIGHT_HIP,
                )
            },
        )

        val observation = extractor.extract(torsoOnly)

        assertThat(observation.pose).isEqualTo(FastPathPose.HORIZONTAL)
        assertThat(observation.visiblePeople).isEqualTo(1)
    }

    @Test
    fun `low confidence or partial body is unknown and resets track`() {
        val extractor = PoseObservationExtractor()
        extractor.extract(standing(atMs = 1_000))
        val partial = standing(atMs = 1_100).copy(
            points = standing(atMs = 1_100).points - PoseJoint.RIGHT_SHOULDER,
        )

        val missing = extractor.extract(partial)
        val recovered = extractor.extract(horizontal(atMs = 1_200, centerY = 0.80f))

        assertThat(missing.pose).isEqualTo(FastPathPose.UNKNOWN)
        assertThat(missing.visiblePeople).isEqualTo(0)
        assertThat(recovered.rapidDescentScore).isZero()
    }

    @Test
    fun `detector switching to a distant person rotates the anonymous role slot`() {
        val extractor = PoseObservationExtractor(maxTrackJumpBodySpans = 0.5f)
        extractor.extract(standing(atMs = 1_000))

        val switched = extractor.extract(standing(atMs = 1_100, xOffset = 0.45f))
        val continued = extractor.extract(standing(atMs = 1_200, xOffset = 0.45f))

        assertThat(switched.pose).isEqualTo(FastPathPose.UPRIGHT)
        assertThat(switched.visiblePeople).isEqualTo(1)
        assertThat(switched.actant).isEqualTo(1)
        assertThat(continued.pose).isEqualTo(FastPathPose.UPRIGHT)
        assertThat(continued.actant).isEqualTo(1)
        assertThat(continued.rapidDescentScore).isZero()
    }

    @Test
    fun `track recovery after missing landmarks uses a new role slot`() {
        val extractor = PoseObservationExtractor()
        extractor.extract(standing(atMs = 1_000))
        extractor.extract(PoseFrame(atMs = 1_100, points = emptyMap()))

        val recovered = extractor.extract(horizontal(atMs = 1_200, centerY = 0.80f))

        assertThat(recovered.actant).isEqualTo(1)
        assertThat(recovered.pose).isEqualTo(FastPathPose.HORIZONTAL)
        assertThat(recovered.rapidDescentScore).isZero()
    }

    @Test
    fun `seated geometry is not promoted to upright`() {
        val extractor = PoseObservationExtractor()

        val observation = extractor.extract(seated(atMs = 1_000))

        assertThat(observation.pose).isEqualTo(FastPathPose.SEATED)
        assertThat(observation.rapidDescentScore).isZero()
    }

    @Test
    fun `non increasing timestamps cannot create motion scores`() {
        val extractor = PoseObservationExtractor()
        extractor.extract(standing(atMs = 1_000))

        val observation = extractor.extract(horizontal(atMs = 1_000, centerY = 0.80f))

        assertThat(observation.rapidDescentScore).isZero()
        assertThat(observation.motionScore).isZero()
    }

    private fun standing(atMs: Long, xOffset: Float = 0f): PoseFrame = PoseFrame(
        atMs = atMs,
        points = mapOf(
            PoseJoint.LEFT_SHOULDER to point(0.42f + xOffset, 0.20f),
            PoseJoint.RIGHT_SHOULDER to point(0.58f + xOffset, 0.20f),
            PoseJoint.LEFT_HIP to point(0.44f + xOffset, 0.42f),
            PoseJoint.RIGHT_HIP to point(0.56f + xOffset, 0.42f),
            PoseJoint.LEFT_KNEE to point(0.45f + xOffset, 0.65f),
            PoseJoint.RIGHT_KNEE to point(0.55f + xOffset, 0.65f),
            PoseJoint.LEFT_ANKLE to point(0.46f + xOffset, 0.90f),
            PoseJoint.RIGHT_ANKLE to point(0.54f + xOffset, 0.90f),
        ),
    )

    private fun horizontal(atMs: Long, centerY: Float): PoseFrame = PoseFrame(
        atMs = atMs,
        points = mapOf(
            PoseJoint.LEFT_SHOULDER to point(0.16f, centerY - 0.06f),
            PoseJoint.RIGHT_SHOULDER to point(0.16f, centerY + 0.06f),
            PoseJoint.LEFT_HIP to point(0.38f, centerY - 0.05f),
            PoseJoint.RIGHT_HIP to point(0.38f, centerY + 0.05f),
            PoseJoint.LEFT_KNEE to point(0.62f, centerY - 0.04f),
            PoseJoint.RIGHT_KNEE to point(0.62f, centerY + 0.04f),
            PoseJoint.LEFT_ANKLE to point(0.88f, centerY - 0.03f),
            PoseJoint.RIGHT_ANKLE to point(0.88f, centerY + 0.03f),
        ),
    )

    private fun seated(atMs: Long): PoseFrame = PoseFrame(
        atMs = atMs,
        points = mapOf(
            PoseJoint.LEFT_SHOULDER to point(0.42f, 0.20f),
            PoseJoint.RIGHT_SHOULDER to point(0.58f, 0.20f),
            PoseJoint.LEFT_HIP to point(0.44f, 0.45f),
            PoseJoint.RIGHT_HIP to point(0.56f, 0.45f),
            PoseJoint.LEFT_KNEE to point(0.67f, 0.49f),
            PoseJoint.RIGHT_KNEE to point(0.79f, 0.49f),
            PoseJoint.LEFT_ANKLE to point(0.67f, 0.78f),
            PoseJoint.RIGHT_ANKLE to point(0.79f, 0.78f),
        ),
    )

    private fun point(x: Float, y: Float) = PosePoint(x = x, y = y, likelihood = 0.95f)
}
