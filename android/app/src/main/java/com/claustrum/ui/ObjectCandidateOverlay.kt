package com.claustrum.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import com.claustrum.ui.theme.ClaustrumTheme
import kotlin.math.roundToInt

@Immutable
data class ObjectCandidateUi(
    val category: String,
    val score: Float,
    val bounds: OverlayBounds,
)

object ObjectCandidateGeometry {
    fun normalizedBounds(
        leftPx: Float,
        topPx: Float,
        rightPx: Float,
        bottomPx: Float,
        viewWidth: Int,
        viewHeight: Int,
    ): OverlayBounds? {
        if (viewWidth <= 0 || viewHeight <= 0) return null
        if (!listOf(leftPx, topPx, rightPx, bottomPx).all(Float::isFinite)) return null
        if (rightPx <= leftPx || bottomPx <= topPx) return null
        val left = (leftPx / viewWidth).coerceIn(0f, 1f)
        val top = (topPx / viewHeight).coerceIn(0f, 1f)
        val right = (rightPx / viewWidth).coerceIn(0f, 1f)
        val bottom = (bottomPx / viewHeight).coerceIn(0f, 1f)
        return if (right > left && bottom > top) OverlayBounds(left, top, right, bottom) else null
    }
}

/** Candidate boxes only. Category/score are detector output, not a litter event. */
@Composable
fun ObjectCandidateOverlay(
    candidates: List<ObjectCandidateUi>,
    status: String,
    modifier: Modifier = Modifier,
) {
    val c = ClaustrumTheme.colors
    Box(modifier) {
        if (candidates.isNotEmpty()) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = size.minDimension * 0.0045f
                candidates.forEach { candidate ->
                    val b = candidate.bounds
                    val left = b.left * size.width
                    val top = b.top * size.height
                    drawRoundRect(
                        color = c.warn,
                        topLeft = Offset(left, top),
                        size = Size((b.right - b.left) * size.width, (b.bottom - b.top) * size.height),
                        cornerRadius = CornerRadius(stroke * 2f),
                        style = Stroke(width = stroke),
                    )
                    drawIntoCanvas { canvas ->
                        val label = "${objectCategoryLabel(candidate.category)} ${(candidate.score * 100).roundToInt()}%"
                        val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                            color = android.graphics.Color.WHITE
                            textSize = (size.minDimension * 0.027f).coerceAtLeast(18f)
                            typeface = android.graphics.Typeface.DEFAULT_BOLD
                        }
                        val pad = textPaint.textSize * 0.25f
                        val labelWidth = textPaint.measureText(label) + pad * 2
                        val labelBottom = if (top >= textPaint.textSize + pad * 2) top else {
                            (top + textPaint.textSize + pad * 2).coerceAtMost(size.height)
                        }
                        val labelTop = labelBottom - textPaint.textSize - pad * 2
                        val background = android.graphics.Paint().apply {
                            color = android.graphics.Color.argb(190, 0, 0, 0)
                        }
                        canvas.nativeCanvas.drawRoundRect(
                            left,
                            labelTop,
                            (left + labelWidth).coerceAtMost(size.width),
                            labelBottom,
                            pad,
                            pad,
                            background,
                        )
                        canvas.nativeCanvas.drawText(label, left + pad, labelBottom - pad, textPaint)
                    }
                }
            }
        }
        if (status.isNotBlank()) {
            Text(
                text = status,
                color = c.ink,
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.TopStart).padding(10.dp)
                    .background(Color.Black.copy(alpha = 0.68f), RoundedCornerShape(5.dp))
                    .padding(horizontal = 7.dp, vertical = 4.dp),
            )
        }
    }
}

internal fun objectCandidateSummary(candidates: List<ObjectCandidateUi>, latencyMs: Long): String {
    if (candidates.isEmpty()) return "物件候選 0 · ${latencyMs}ms"
    val counts = candidates.groupingBy(ObjectCandidateUi::category).eachCount()
        .entries.sortedBy { it.key }
        .joinToString(" · ") { (category, count) ->
            val label = objectCategoryLabel(category)
            if (count == 1) label else "$label×$count"
        }
    return "物件候選 ${candidates.size} · $counts · ${latencyMs}ms"
}

internal fun objectCategoryLabel(category: String): String = when (category) {
    "person" -> "人"
    "backpack" -> "背包"
    "umbrella" -> "雨傘"
    "handbag" -> "手提包"
    "suitcase" -> "行李箱"
    "bottle" -> "瓶子"
    "cup" -> "杯子"
    "banana" -> "香蕉"
    "apple" -> "蘋果"
    "sandwich" -> "三明治"
    "orange" -> "橘子"
    "book" -> "書"
    "cell phone" -> "手機"
    else -> category
}
