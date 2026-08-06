package com.claustrum.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.claustrum.ui.theme.ClaustrumTheme

/**
 * The claustrum brand mark — the "machine eye" (Optimus-style visor + red iris),
 * matching the launcher icon. Drawn so it scales crisply anywhere in the app
 * (app bar, splash, nav). [tint] overrides the iris colour (e.g. for nav states).
 */
@Composable
fun ClaustrumMark(size: Dp = 20.dp, active: Boolean = true) {
    val c = ClaustrumTheme.colors
    val iris = if (active) c.accent else c.faint
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val cx = w / 2f
        val cy = h / 2f
        // Visor shell (rounded stadium) — subtle graphite plate.
        val visorH = h * 0.62f
        val visorW = w * 0.92f
        drawRoundRect(
            color = c.surface2,
            topLeft = Offset(cx - visorW / 2, cy - visorH / 2),
            size = Size(visorW, visorH),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(visorH / 2, visorH / 2),
        )
        // Scan line.
        drawLine(
            color = iris,
            start = Offset(cx - visorW / 2 + 1f, cy),
            end = Offset(cx + visorW / 2 - 1f, cy),
            strokeWidth = h * 0.055f,
        )
        // Iris ring + pupil.
        val r = h * 0.20f
        drawCircle(color = iris, radius = r, center = Offset(cx, cy), style = Stroke(width = h * 0.09f))
        drawCircle(color = c.ground, radius = r * 0.42f, center = Offset(cx, cy))
    }
}
