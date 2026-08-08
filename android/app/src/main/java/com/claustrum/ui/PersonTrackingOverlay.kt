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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.claustrum.ui.theme.ClaustrumTheme

/** Body landmarks used only for the anonymous, device-local preview overlay. */
enum class TrackedJoint {
    LEFT_SHOULDER,
    RIGHT_SHOULDER,
    LEFT_HIP,
    RIGHT_HIP,
    LEFT_KNEE,
    RIGHT_KNEE,
    LEFT_ANKLE,
    RIGHT_ANKLE,
}

/** Coordinates normalized to the visible PreviewView, not to the camera buffer. */
@Immutable
data class PreviewPoint(
    val x: Float,
    val y: Float,
    val likelihood: Float,
)

/**
 * A short-lived role slot in the current camera stream. [slot] is not an identity and
 * must never be persisted or used for cross-session re-identification.
 */
@Immutable
data class TrackedPersonUi(
    val slot: Int,
    val points: Map<TrackedJoint, PreviewPoint>,
    val subjectHeightPx: Int? = null,
)

@Immutable
data class OverlayBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/** Pure geometry so bounds and confidence filtering stay host-testable. */
object PersonOverlayGeometry {
    const val MIN_LIKELIHOOD = 0.65f

    fun bounds(person: TrackedPersonUi): OverlayBounds? {
        val reliable = person.points.values.filter(::isReliable)
        if (reliable.size < 4) return null

        val left = reliable.minOf(PreviewPoint::x)
        val right = reliable.maxOf(PreviewPoint::x)
        val rawTop = reliable.minOf(PreviewPoint::y)
        val bottom = reliable.maxOf(PreviewPoint::y)
        val bodyHeight = bottom - rawTop
        val bodyWidth = right - left
        if (bodyHeight <= 0f || bodyWidth <= 0f) return null

        // The pose fast path intentionally excludes face landmarks. Add conservative
        // head/side room so the box marks a person rather than only their torso/legs.
        val sidePadding = maxOf(bodyWidth * 0.12f, 0.015f)
        val headPadding = maxOf(bodyHeight * 0.18f, 0.025f)
        val footPadding = maxOf(bodyHeight * 0.04f, 0.01f)
        return OverlayBounds(
            left = (left - sidePadding).coerceIn(0f, 1f),
            top = (rawTop - headPadding).coerceIn(0f, 1f),
            right = (right + sidePadding).coerceIn(0f, 1f),
            bottom = (bottom + footPadding).coerceIn(0f, 1f),
        )
    }

    fun isReliable(point: PreviewPoint): Boolean =
        point.x.isFinite() && point.y.isFinite() && point.likelihood.isFinite() &&
            point.likelihood >= MIN_LIKELIHOOD
}

/**
 * Draws only an anonymous person region. Pose landmarks remain an internal fall feature;
 * exposing a skeleton would add visual noise without adding an independently verified event.
 */
@Composable
fun PersonTrackingOverlay(people: List<TrackedPersonUi>, modifier: Modifier = Modifier) {
    if (people.isEmpty()) return
    val c = ClaustrumTheme.colors
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            people.forEach { person ->
                val bounds = PersonOverlayGeometry.bounds(person) ?: return@forEach
                val color = c.steel
                val stroke = size.minDimension * 0.006f
                drawRoundRect(
                    color = color,
                    topLeft = Offset(bounds.left * size.width, bounds.top * size.height),
                    size = Size(
                        (bounds.right - bounds.left) * size.width,
                        (bounds.bottom - bounds.top) * size.height,
                    ),
                    cornerRadius = CornerRadius(stroke * 3f),
                    style = Stroke(width = stroke),
                )
            }
        }
        Text(
            text = overlayLabel(people),
            color = c.ink,
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.BottomStart).padding(10.dp)
                .background(Color.Black.copy(alpha = 0.68f), RoundedCornerShape(5.dp))
                .padding(horizontal = 7.dp, vertical = 4.dp),
        )
    }
}

internal fun overlayLabel(people: List<TrackedPersonUi>): String {
    if (people.size != 1) return "${people.size} 個人體姿態候選 · 匿名框"
    val height = people.single().subjectHeightPx
    return when {
        height == null -> "人體姿態候選 · 匿名框"
        height < RECOMMENDED_SUBJECT_HEIGHT_PX -> "人體姿態候選 · 高約 ${height}px · 建議放大"
        else -> "人體姿態候選 · 高約 ${height}px"
    }
}

/** ML Kit recommends roughly 256 px of subject image data for best pose quality. */
private const val RECOMMENDED_SUBJECT_HEIGHT_PX = 256
