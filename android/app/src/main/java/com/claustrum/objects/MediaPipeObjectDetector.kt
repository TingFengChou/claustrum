package com.claustrum.objects

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

internal data class DetectedObject(
    val category: String,
    val score: Float,
    val leftPx: Float,
    val topPx: Float,
    val rightPx: Float,
    val bottomPx: Float,
)

/** Android/MediaPipe boundary. Temporal association remains outside this adapter. */
internal class MediaPipeObjectDetector(
    context: Context,
    modelFile: File,
) : Closeable {
    private val modelBuffer: MappedByteBuffer = FileInputStream(modelFile).channel.use { channel ->
        channel.map(FileChannel.MapMode.READ_ONLY, 0L, channel.size())
    }
    private val detector = ObjectDetector.createFromOptions(
        context,
        ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(modelBuffer).build())
            .setRunningMode(RunningMode.VIDEO)
            .setScoreThreshold(MIN_SCORE)
            .setMaxResults(MAX_RESULTS)
            .setCategoryAllowlist(ALLOWED_CATEGORIES)
            .build(),
    )

    fun detect(bitmap: Bitmap, timestampMs: Long): List<DetectedObject> {
        val image = BitmapImageBuilder(bitmap).build()
        return try {
            detector.detectForVideo(image, timestampMs).detections().mapNotNull { detection ->
                val category = detection.categories().maxByOrNull { it.score() } ?: return@mapNotNull null
                val box = detection.boundingBox()
                DetectedObject(
                    category = category.categoryName(),
                    score = category.score(),
                    leftPx = box.left,
                    topPx = box.top,
                    rightPx = box.right,
                    bottomPx = box.bottom,
                )
            }
        } finally {
            image.close()
        }
    }

    override fun close() = detector.close()

    companion object {
        private const val MIN_SCORE = 0.35f
        private const val MAX_RESULTS = 10

        /** Concrete portable-object candidates in COCO; none of these means "litter". */
        val ALLOWED_CATEGORIES = listOf(
            "person",
            "backpack",
            "umbrella",
            "handbag",
            "suitcase",
            "bottle",
            "cup",
            "banana",
            "apple",
            "sandwich",
            "orange",
            "book",
            "cell phone",
        )
    }
}
