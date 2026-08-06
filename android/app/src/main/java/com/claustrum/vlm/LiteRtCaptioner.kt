package com.claustrum.vlm

import android.graphics.Bitmap
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Real L1 backend — on-device multimodal Gemma via the LiteRT-LM SDK
 * (`litertlm-android`), API shape per Google AI Edge Gallery's LlmChatModelHelper
 * (ADR-0009). The engine is loaded once and reused; a **fresh conversation per
 * admitted frame** keeps each caption independent (no history accumulation →
 * no context pollution / token blow-up). Called off the CameraX analyzer thread
 * (see MonitorActivity), and only on admitted frames (L0 gate), so PNG-encode +
 * inference cost is paid only on scene change.
 *
 * Prompt is strictly objective — L1 describes only what is visible; risk/event
 * judgement is L2's and needs visible evidence (ADR-0006).
 */
class LiteRtCaptioner(
    modelPath: String,
    cacheDir: String? = null,
    private val prompt: String = DEFAULT_PROMPT,
    private val topK: Int = 64,
    private val topP: Double = 0.95,
    private val temperature: Double = 1.0,
    maxTokens: Int = 1024, // context budget: image (~256 vision tokens) + prompt + output
    private val timeoutSec: Long = 60, // runs off the analyzer thread (single-flight), so fail fast
    // The model streams reliably but rarely emits EOS for a short caption (it keeps
    // generating). We cap output client-side: stop at the first sentence end past
    // [softMinChars], or a hard [maxChars], then cancel — a bounded ~1-sentence caption.
    private val softMinChars: Int = 16,
    private val maxChars: Int = 140,
) : Captioner<Bitmap>, AutoCloseable {

    private val engine = createEngine(modelPath, cacheDir, maxTokens)

    /** Prefer full GPU (fastest); fall back to CPU main, then all-CPU, if GPU init fails. */
    private fun createEngine(modelPath: String, cacheDir: String?, maxTokens: Int): Engine {
        val combos = listOf(
            "GPU/GPU" to (Backend.GPU() to Backend.GPU()),
            "CPU/GPU" to (Backend.CPU() to Backend.GPU()),
            "CPU/CPU" to (Backend.CPU() to Backend.CPU()),
        )
        var last: Throwable? = null
        for ((name, be) in combos) {
            try {
                val e = Engine(
                    EngineConfig(
                        modelPath = modelPath,
                        backend = be.first,
                        visionBackend = be.second,
                        maxNumTokens = maxTokens,
                        // MUST allocate image slots or the vision input is dropped and
                        // the model attends to padding → emits only <pad> tokens.
                        maxNumImages = 1,
                        cacheDir = cacheDir,
                    )
                )
                e.initialize()
                Log.i(TAG, "engine initialized (backend=$name)")
                return e
            } catch (t: Throwable) {
                last = t
                Log.w(TAG, "engine init $name failed; trying next", t)
            }
        }
        throw last ?: IllegalStateException("engine init failed")
    }

    override val backend: String = "Gemma · LiteRT-LM"

    override fun describe(frame: Bitmap): String {
        val conversation = engine.createConversation(
            ConversationConfig(
                samplerConfig = SamplerConfig(topK = topK, topP = topP, temperature = temperature)
            )
        )
        val out = StringBuilder()
        val latch = CountDownLatch(1)
        var error: String? = null
        val modelDone = java.util.concurrent.atomic.AtomicBoolean(false)
        val cappedEarly = java.util.concurrent.atomic.AtomicBoolean(false)
        // Downscale before inference: a phone-GPU vision prefill of a full camera
        // frame is costly; the model re-grids internally anyway.
        val small = frame.downscaled(maxEdge = 768)
        val png = small.toPngBytes()
        val t0 = System.nanoTime()
        fun ms() = (System.nanoTime() - t0) / 1_000_000
        try {
            conversation.sendMessageAsync(
                Contents.of(
                    listOf(
                        Content.ImageBytes(png),
                        Content.Text(prompt),
                    )
                ),
                object : MessageCallback {
                    override fun onMessage(message: Message) {
                        synchronized(out) { out.append(message.text()) }
                        // Stop as soon as we have one complete sentence (or hit the hard
                        // cap): the model won't stop on its own for short captions.
                        if (!cappedEarly.get() && CaptionText.reachedEnd(out, softMinChars, maxChars)) {
                            cappedEarly.set(true)
                            latch.countDown()
                        }
                    }

                    override fun onDone() {
                        modelDone.set(true)
                        latch.countDown()
                    }

                    override fun onError(throwable: Throwable) {
                        if (!cappedEarly.get()) error = throwable.message
                        latch.countDown()
                    }
                },
                // Disable thinking mode — a scene caption needs a direct answer, not a
                // long chain-of-thought (which otherwise runs for many tokens → timeout).
                mapOf("enable_thinking" to false),
            )
            val finished = latch.await(timeoutSec, TimeUnit.SECONDS)
            // We stopped early (or timed out): tell the engine to stop generating.
            if (!modelDone.get()) {
                try { conversation.cancelProcess() } catch (_: Exception) {}
            }
            val text = CaptionText.clean(synchronized(out) { out.toString() })
            Log.i(TAG, "describe @${ms()}ms done=${modelDone.get()} capped=${cappedEarly.get()} finished=$finished len=${text.length}")
            if (!finished && text.isEmpty()) return "L1 逾時"
            return error?.takeIf { text.isEmpty() }?.let { "L1 錯誤:$it" }
                ?: text.ifEmpty { "（無描述）" }
        } catch (t: Throwable) {
            Log.e(TAG, "describe failed", t)
            return "L1 錯誤:${t.message}"
        } finally {
            try { conversation.close() } catch (_: Exception) {}
            if (small !== frame) small.recycle()
        }
    }

    override fun close() {
        try { engine.close() } catch (t: Throwable) { Log.e(TAG, "engine close failed", t) }
    }

    /** Extract just the decoded text from a streamed message (not the raw token dump). */
    private fun Message.text(): String =
        contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }

    private fun Bitmap.toPngBytes(): ByteArray =
        ByteArrayOutputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }

    /** Scale so the longest edge ≤ [maxEdge]; returns the original if already small. */
    private fun Bitmap.downscaled(maxEdge: Int): Bitmap {
        val longEdge = maxOf(width, height)
        if (longEdge <= maxEdge) return this
        val s = maxEdge.toFloat() / longEdge
        return Bitmap.createScaledBitmap(this, (width * s).toInt(), (height * s).toInt(), true)
    }

    companion object {
        private const val TAG = "LiteRtCaptioner"
        const val DEFAULT_PROMPT =
            "請務必用「繁體中文」以一句話(30 字以內)客觀描述畫面中可見的人物、姿態與動作。" +
                "只描述看得到的事實,不要臆測意圖,不要分點或延伸說明。" +
                "禁止使用英文、拼音或表情符號(emoji),句末以句號結束。"
    }
}
