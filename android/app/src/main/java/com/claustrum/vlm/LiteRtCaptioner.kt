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
        try {
            conversation.sendMessageAsync(
                Contents.of(
                    listOf(
                        Content.ImageBytes(frame.toPngBytes()),
                        Content.Text(prompt),
                    )
                ),
                object : MessageCallback {
                    override fun onMessage(message: Message) {
                        out.append(message.toString())
                    }

                    override fun onDone() {
                        latch.countDown()
                    }

                    override fun onError(throwable: Throwable) {
                        error = throwable.message
                        latch.countDown()
                    }
                },
                // Disable thinking mode — a scene caption needs a direct answer, not a
                // long chain-of-thought (which otherwise runs for many tokens → timeout).
                mapOf("enable_thinking" to false),
            )
            if (!latch.await(timeoutSec, TimeUnit.SECONDS)) {
                try { conversation.cancelProcess() } catch (_: Exception) {}
                return "L1 逾時"
            }
        } catch (t: Throwable) {
            Log.e(TAG, "describe failed", t)
            return "L1 錯誤:${t.message}"
        } finally {
            try { conversation.close() } catch (_: Exception) {}
        }
        return error?.let { "L1 錯誤:$it" } ?: out.toString().trim().ifEmpty { "（無描述）" }
    }

    override fun close() {
        try { engine.close() } catch (t: Throwable) { Log.e(TAG, "engine close failed", t) }
    }

    private fun Bitmap.toPngBytes(): ByteArray =
        ByteArrayOutputStream().use { compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }

    companion object {
        private const val TAG = "LiteRtCaptioner"
        const val DEFAULT_PROMPT =
            "請客觀描述畫面中可見的人物、姿態與動作,只描述看得到的事實,不要臆測或推論意圖。"
    }
}
