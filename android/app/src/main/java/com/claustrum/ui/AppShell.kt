package com.claustrum.ui

import android.view.View
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import com.claustrum.model.ModelsController
import com.claustrum.ui.theme.ClaustrumTheme

enum class ClaustrumTab(val label: String) { GUARD("守護"), EVENTS("事件"), MODELS("模型"), SETTINGS("設定") }

/** Developer-mode state + callbacks, surfaced only when [enabled]. */
data class DevUi(
    val enabled: Boolean = false,
    val onToggle: (Boolean) -> Unit = {},
    val evalRunning: Boolean = false,
    val evalSummary: com.claustrum.vlm.ModelEval.Summary? = null,
    val onRunEval: () -> Unit = {},
    val videoPlaying: Boolean = false,
    val videoFrame: android.graphics.Bitmap? = null,
    val onPlayVideo: () -> Unit = {},
)

/**
 * App shell: the four home destinations behind a bottom nav (守護 / 事件 / 模型 /
 * 設定). Single-Activity, tab-switched — no Activity transitions, no dead-ends.
 */
@Composable
fun AppShell(
    monitorUi: MonitorUi,
    previewView: View,
    models: ModelsController,
    onActivate: () -> Unit,
    dev: DevUi = DevUi(),
) {
    val c = ClaustrumTheme.colors
    var tab by remember { mutableStateOf(ClaustrumTab.GUARD) }

    Column(Modifier.fillMaxSize().background(c.ground)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                ClaustrumTab.GUARD -> LiveMonitorScreen(monitorUi, previewView, onActivate = onActivate, dev = dev)
                ClaustrumTab.EVENTS -> EventsScreen()
                ClaustrumTab.MODELS -> ModelsScreen(models)
                ClaustrumTab.SETTINGS -> SettingsScreen(monitorUi.backend, dev = dev)
            }
        }
        BottomNav(tab) { selected ->
            if (selected == ClaustrumTab.MODELS) models.refreshPresence()
            tab = selected
        }
    }
}

@Composable
private fun BottomNav(selected: ClaustrumTab, onSelect: (ClaustrumTab) -> Unit) {
    val c = ClaustrumTheme.colors
    Row(
        Modifier.fillMaxWidth().background(c.surface)
            .navigationBarsPadding().height(58.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // top hairline
        ClaustrumTab.entries.forEach { t ->
            val active = t == selected
            Column(
                Modifier.weight(1f).fillMaxSize().clickable { onSelect(t) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(9.dp))
                NavIcon(t, active)
                Spacer(Modifier.height(3.dp))
                Text(
                    t.label,
                    color = if (active) c.accent else c.faint,
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun NavIcon(tab: ClaustrumTab, active: Boolean) {
    val c = ClaustrumTheme.colors
    val tint: Color = if (active) c.accent else c.faint
    when (tab) {
        ClaustrumTab.GUARD -> ClaustrumMark(size = 20.dp, active = active)
        ClaustrumTab.EVENTS -> Canvas(Modifier.size(20.dp)) {
            val w = size.width; val y0 = size.height * 0.28f; val gap = size.height * 0.22f
            repeat(3) { i ->
                drawLine(tint, Offset(w * 0.16f, y0 + gap * i), Offset(w * 0.84f, y0 + gap * i), strokeWidth = size.height * 0.09f)
            }
        }
        ClaustrumTab.MODELS -> Canvas(Modifier.size(20.dp)) {
            drawRoundRect(
                color = tint, style = Stroke(width = size.height * 0.10f),
                topLeft = Offset(size.width * 0.16f, size.height * 0.16f),
                size = Size(size.width * 0.68f, size.height * 0.68f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height * 0.16f, size.height * 0.16f),
            )
            drawCircle(tint, radius = size.height * 0.10f, center = Offset(size.width * 0.5f, size.height * 0.5f))
        }
        ClaustrumTab.SETTINGS -> Canvas(Modifier.size(20.dp)) {
            // sliders
            val w = size.width
            listOf(0.3f, 0.5f, 0.7f).forEachIndexed { i, fy ->
                val y = size.height * fy
                drawLine(tint, Offset(w * 0.16f, y), Offset(w * 0.84f, y), strokeWidth = size.height * 0.08f)
                drawCircle(tint, radius = size.height * 0.09f, center = Offset(w * (0.30f + 0.2f * i), y))
            }
        }
    }
}
