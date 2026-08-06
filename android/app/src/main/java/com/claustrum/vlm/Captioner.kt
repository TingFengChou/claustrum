package com.claustrum.vlm

import com.claustrum.core.NativeCore

/**
 * L1 scene-description boundary (Kotlin side — L1 runs here now that the engine
 * is Google AI Edge / LiteRT, per ADR-0009). The analyzer depends only on this
 * interface, so the L0→L1 trigger logic is unit-testable with a fake and the real
 * `LiteRtCaptioner` (LiteRT-LM) can slot in without touching the pipeline.
 *
 * Contract: called ONLY on an L0-admitted frame ("wake the VLM only on change").
 * Must return objective description only — risk/event judgement is L2's job and
 * needs visible evidence (ADR-0006); L1 never speculates.
 */
interface Captioner {
    /** Short objective description of an admitted frame's luma. */
    fun describe(luma: ByteArray, width: Int, height: Int): String

    /** Backend name shown in the UI so the live L1 engine is unambiguous. */
    val backend: String
}

/**
 * Interim backend: delegates to the Rust diagnostic placeholder
 * ([NativeCore.describe]) — honest frame stats, no faked understanding. Used
 * until [LiteRtCaptioner] (real multimodal Gemma) is wired and a model is present.
 */
object PlaceholderCaptioner : Captioner {
    override fun describe(luma: ByteArray, width: Int, height: Int): String =
        NativeCore.describe(luma, width, height) ?: "L1 佔位:描述失敗"

    override val backend: String = "placeholder(Rust 診斷)"
}
