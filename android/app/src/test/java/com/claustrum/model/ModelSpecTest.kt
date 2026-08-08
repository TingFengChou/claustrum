package com.claustrum.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests for the model catalog — no Android, no hardware. */
class ModelSpecTest {

    @Test fun resolveUrlIsHuggingFaceResolvePath() {
        val s = ModelSpec.GEMMA_3N_E2B_VISION
        assertEquals(
            "https://huggingface.co/google/gemma-3n-E2B-it-litert-lm/resolve/main/gemma-3n-E2B-it-int4.litertlm",
            s.resolveUrl(),
        )
    }

    @Test fun visionModelsSupportImage() {
        assertTrue(ModelSpec.GEMMA_3N_E2B_VISION.supportsImage)
        assertTrue(Capability.ASK_IMAGE in ModelSpec.GEMMA_3N_E2B_VISION.capabilities)
    }

    @Test fun textModelDoesNotSupportImage() {
        assertFalse(ModelSpec.GEMMA3_1B_TEXT.supportsImage)
    }

    @Test fun l1CandidatesAreVisionOnly() {
        val candidates = ModelSpec.l1Candidates()
        assertTrue(candidates.isNotEmpty())
        assertTrue(candidates.all { it.supportsImage })
        assertFalse(candidates.contains(ModelSpec.GEMMA3_1B_TEXT))
    }

    @Test fun defaultL1IsVisionCapable() {
        assertTrue(ModelSpec.DEFAULT_L1.supportsImage)
    }

    @Test fun catalogHasNoDuplicateFiles() {
        val files = ModelSpec.CATALOG.map { "${it.modelId}/${it.fileName}" }
        assertEquals(files.size, files.toSet().size)
    }

    @Test fun objectDetectorUsesPinnedOfficialModelAndChecksum() {
        val spec = ModelSpec.EFFICIENTDET_LITE2_OBJECTS

        assertTrue(Capability.DETECT_OBJECTS in spec.capabilities)
        assertFalse(spec.gated)
        assertEquals(
            "https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite2/int8/1/efficientdet_lite2.tflite",
            spec.resolveUrl(),
        )
        assertEquals(64, spec.sha256?.length)
    }
}
