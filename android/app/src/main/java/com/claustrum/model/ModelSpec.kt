package com.claustrum.model

import android.content.Context
import java.io.File

/**
 * A model capability (mirrors AI Edge Gallery's task types, scoped to what we use).
 * L1 scene description needs [ASK_IMAGE] (image+text → text).
 */
enum class Capability(val label: String) {
    ASK_IMAGE("看圖描述"),   // image + text → text (our L1)
    DETECT_OBJECTS("物件偵測"), // category + score + bbox (L2 candidate gate)
    CHAT("純文字對話"),       // text → text
    ASK_AUDIO("聽音描述"),   // audio + text → text (future)
}

/**
 * A downloadable on-device model — our own "allowlist entry", modeled on Google
 * AI Edge Gallery's `Model` / `model_allowlist.json` (Apache-2.0). Kept as data so
 * the app can list a **catalog**, show each model's **capabilities**, and let the
 * user download the one they want — not a single hardcoded model.
 *
 * Files come from Hugging Face at the standard resolve URL:
 *   https://huggingface.co/<modelId>/resolve/main/<fileName>
 * Gated repos (e.g. `google/gemma-3n-*`) need an HF access token — the download
 * worker sends it as `Authorization: Bearer`; `litert-community` mirrors are
 * usually public.
 */
data class ModelSpec(
    val name: String,
    val modelId: String,
    val fileName: String,
    val sizeBytes: Long,
    val capabilities: Set<Capability>,
    val gated: Boolean,
    val description: String = "",
    val version: String = "v1",
    /** Optional pinned non-Hugging-Face source, for example an official MediaPipe model. */
    val downloadUrl: String? = null,
    /** Optional lowercase SHA-256. Required for small public runtime models we control. */
    val sha256: String? = null,
    // LiteRT-LM sampler defaults (see AI Edge Gallery allowlist).
    val topK: Int = 64,
    val topP: Float = 0.95f,
    val temperature: Float = 1.0f,
    val maxTokens: Int = 4096,
) {
    val supportsImage: Boolean get() = Capability.ASK_IMAGE in capabilities

    /** Hugging Face resolve URL for the model file. */
    fun resolveUrl(): String =
        downloadUrl ?: "https://huggingface.co/$modelId/resolve/main/$fileName"

    /** Final on-device path once fully downloaded: <externalFiles>/models/<version>/<file>. */
    fun localFile(context: Context): File =
        File(modelsDir(context), "$version${File.separator}$fileName")

    /** Partial-download temp file (append + HTTP Range resume). */
    fun tempFile(context: Context): File =
        File(modelsDir(context), "$version${File.separator}$fileName.tmp")

    /** Present = fully downloaded and the size matches the expected bytes. */
    fun isPresent(context: Context): Boolean {
        val f = localFile(context)
        return f.exists() && (sizeBytes <= 0 || f.length() == sizeBytes)
    }

    companion object {
        fun modelsDir(context: Context): File =
            File(context.getExternalFilesDir(null), "models")

        // --- Catalog (browseable/downloadable models) ------------------------
        // L1 (scene description) requires ASK_IMAGE. Text-only models are kept
        // for quick download testing and future non-vision tasks.

        // .litertlm-native bundles (litertlm SDK's own format). The MediaPipe .task
        // files decode to only <pad> under litertlm 0.11.0 — see docs/design/vlm/SD.md.
        val GEMMA_3N_E2B_VISION = ModelSpec(
            name = "Gemma-3n-E2B-it-int4",
            modelId = "google/gemma-3n-E2B-it-litert-lm",
            fileName = "gemma-3n-E2B-it-int4.litertlm",
            sizeBytes = 3_655_827_456L,
            capabilities = setOf(Capability.ASK_IMAGE, Capability.CHAT),
            gated = true,
            description = "Gemma 3n E2B(圖+文)· LiteRT-LM 原生 · 目前 L1 預設；能力與取景邊界仍待基準評測",
        )

        val GEMMA_3N_E4B_VISION = ModelSpec(
            name = "Gemma-3n-E4B-it-int4",
            modelId = "google/gemma-3n-E4B-it-litert-lm",
            fileName = "gemma-3n-E4B-it-int4.litertlm",
            sizeBytes = 4_919_541_760L,
            capabilities = setOf(Capability.ASK_IMAGE, Capability.CHAT),
            gated = true,
            description = "Gemma 3n E4B(圖+文)· LiteRT-LM 原生 · 較大；是否優於 E2B 待同組影格基準評測",
        )

        val GEMMA3_1B_TEXT = ModelSpec(
            name = "Gemma3-1B-IT q4",
            modelId = "litert-community/Gemma3-1B-IT",
            fileName = "Gemma3-1B-IT_multi-prefill-seq_q4_ekv2048.task",
            sizeBytes = 554_661_246L,
            capabilities = setOf(Capability.CHAT),
            gated = true, // Gemma 授權條款 → HF gated,下載需存取權杖
            description = "Gemma3 1B(純文字)· 較小,用於快速驗證下載/文字任務(HF gated)",
        )

        val EFFICIENTDET_LITE2_OBJECTS = ModelSpec(
            name = "EfficientDet-Lite2 int8",
            modelId = "google-mediapipe/object-detector-efficientdet-lite2-int8",
            fileName = "efficientdet_lite2.tflite",
            sizeBytes = 7_515_971L,
            capabilities = setOf(Capability.DETECT_OBJECTS),
            gated = false,
            description = "MediaPipe Object Detector 448×448 精度優先候選模型 · COCO 類別/bbox 不等於垃圾事件",
            downloadUrl = "https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite2/int8/1/efficientdet_lite2.tflite",
            sha256 = "b3f50554cb0ea559e90328845f7d9ba4d13c8bff372914d24e06bc8bb72fa896",
        )

        /** Browseable catalog. */
        val CATALOG: List<ModelSpec> =
            listOf(
                EFFICIENTDET_LITE2_OBJECTS,
                GEMMA_3N_E2B_VISION,
                GEMMA_3N_E4B_VISION,
                GEMMA3_1B_TEXT,
            )

        /** Default L1 target (must support ASK_IMAGE). */
        val DEFAULT_L1 = GEMMA_3N_E2B_VISION

        /** Vision-capable models the user can pick for L1. */
        fun l1Candidates(): List<ModelSpec> = CATALOG.filter { it.supportsImage }
    }
}
