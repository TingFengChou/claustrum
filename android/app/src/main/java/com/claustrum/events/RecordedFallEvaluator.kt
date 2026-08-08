package com.claustrum.events

import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Android-only recorded-frame boundary; scoring and manifest logic stay in [FallVideoEval]. */
@RequiresApi(Build.VERSION_CODES.P)
internal class RecordedFallEvaluator(
    private val directory: File,
    private val ensureCurrent: () -> Unit,
) {
    fun evaluate(
        cases: List<FallVideoEvalCase>,
        onCaseComplete: (FallVideoEval.CaseResult) -> Unit = {},
    ): List<FallVideoEval.CaseResult> = cases.mapIndexed { index, case ->
        ensureCurrent()
        val video = File(directory, case.videoFileName)
        require(video.isFile) { "找不到標註影片: ${case.videoFileName}" }
        evaluateCase(index, case, video).also(onCaseComplete)
    }

    private fun evaluateCase(
        index: Int,
        case: FallVideoEvalCase,
        video: File,
    ): FallVideoEval.CaseResult {
        val retriever = MediaMetadataRetriever()
        val extractor = PoseObservationExtractor()
        var detector: PoseDetector? = null
        var engine: NativeEventEngine? = null
        val confirmed = mutableListOf<Long>()
        val subjectSpans = mutableListOf<Int>()
        val latencies = mutableListOf<Long>()
        var candidateCount = 0
        var visibleFrames = 0
        try {
            val poseDetector = PoseDetection.getClient(
                PoseDetectorOptions.Builder()
                    .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                    .build(),
            ).also { detector = it }
            val eventEngine = NativeEventEngine("dev_pose_eval_$index").also { engine = it }
            retriever.setDataSource(video.absolutePath)
            val durationMs = retriever.longMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION,
                "影片長度",
                case.videoFileName,
            )
            require(durationMs > 0L) { "影片長度必須大於 0: ${case.videoFileName}" }
            require(durationMs <= Long.MAX_VALUE - EVAL_EPOCH_MS) {
                "影片長度超出可評估時間範圍: ${case.videoFileName}"
            }
            val frameCount = retriever.longMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT,
                "影片 frame count",
                case.videoFileName,
            )
            val sourceWidth = retriever.longMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH,
                "影片寬度",
                case.videoFileName,
            )
            val sourceHeight = retriever.longMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT,
                "影片高度",
                case.videoFileName,
            )
            require(frameCount in 1..Int.MAX_VALUE && sourceWidth > 0L && sourceHeight > 0L) {
                "影片 frame count 與尺寸必須大於 0: ${case.videoFileName}"
            }
            case.eventEndMs?.let {
                require(it <= durationMs) { "${case.videoFileName} 的事件時窗超過影片長度 $durationMs ms" }
            }

            // Decode sequential CFR batches so the retriever does not rebuild its decoder for
            // every sample. One decoded ARGB frame must also fit the total batch pixel budget.
            val pixelsPerFrame = Math.multiplyExact(sourceWidth, sourceHeight)
            require(pixelsPerFrame <= MAX_BATCH_BYTES / Int.SIZE_BYTES) {
                "${case.videoFileName} 單幀超過 48 MiB pixel budget"
            }
            val bytesPerFrame = pixelsPerFrame * Int.SIZE_BYTES
            val batchSize = minOf(
                MAX_BATCH_FRAMES,
                (MAX_BATCH_BYTES / bytesPerFrame).coerceAtLeast(1L).toInt(),
            )
            val frameCountInt = frameCount.toInt()
            val sampleStride = max(
                1,
                (frameCount.toDouble() * SAMPLE_MS / durationMs).roundToInt(),
            )
            var batchStart = 0
            while (batchStart < frameCountInt) {
                ensureCurrent()
                val requested = minOf(batchSize, frameCountInt - batchStart)
                val frames = retriever.getFramesAtIndex(batchStart, requested)
                try {
                    require(frames.size == requested) {
                        "${case.videoFileName} frame batch $batchStart 預期 $requested、實得 ${frames.size}"
                    }
                    frames.forEachIndexed { offset, frame ->
                        val frameIndex = batchStart + offset
                        if (frameIndex % sampleStride == 0) {
                            ensureCurrent()
                            val videoAtMs = (frameIndex.toDouble() * durationMs / frameCount)
                                .roundToLong()
                                .coerceIn(0L, durationMs - 1L)
                            val startedAt = SystemClock.uptimeMillis()
                            val pose = Tasks.await(poseDetector.process(InputImage.fromBitmap(frame, 0)))
                            latencies += SystemClock.uptimeMillis() - startedAt
                            ensureCurrent()
                            val poseFrame = pose.toPoseFrame(
                                EVAL_EPOCH_MS + videoAtMs,
                                frame.width,
                                frame.height,
                            )
                            val observation = extractor.extract(poseFrame)
                            if (observation.visiblePeople > 0) {
                                visibleFrames++
                                poseFrame.estimatedSubjectSpanPx(frame.width, frame.height)
                                    ?.let(subjectSpans::add)
                            }
                            val signals = FallVideoEval.parseEventSignals(eventEngine.process(observation))
                            candidateCount += signals.candidateFallCount
                            confirmed += signals.confirmedFallAtMs.map { detectedAtMs ->
                                (detectedAtMs - EVAL_EPOCH_MS).also { clipAtMs ->
                                    require(clipAtMs in 0 until durationMs) {
                                        "Rust fall timestamp is outside the clip timeline"
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    frames.forEach { if (!it.isRecycled) it.recycle() }
                }
                batchStart += requested
            }
        } finally {
            try { engine?.close() } catch (_: Throwable) {}
            try { detector?.close() } catch (_: Throwable) {}
            try { retriever.release() } catch (_: Throwable) {}
        }
        return FallVideoEval.CaseResult(
            label = case.label,
            expected = case.expected,
            eventStartMs = case.eventStartMs,
            eventEndMs = case.eventEndMs,
            confirmedFallAtMs = confirmed,
            candidateFallCount = candidateCount,
            sampledFrames = latencies.size,
            poseVisibleFrames = visibleFrames,
            subjectSpansPx = subjectSpans,
            poseLatencyMs = latencies,
        )
    }

    private fun MediaMetadataRetriever.longMetadata(key: Int, field: String, fileName: String): Long =
        extractMetadata(key)?.toLongOrNull() ?: error("無法讀取$field: $fileName")

    private companion object {
        const val SAMPLE_MS = 100L
        const val MAX_BATCH_FRAMES = 30
        const val MAX_BATCH_BYTES = 48L * 1024L * 1024L
        const val EVAL_EPOCH_MS = 1_700_000_000_000L
    }
}
