package com.claustrum.ui

import android.view.View
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.Text
import com.claustrum.model.CaptionLog
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.rememberLottieComposition
import com.claustrum.R
import com.claustrum.ui.theme.ClaustrumTheme
import com.claustrum.ui.theme.Mono

/** Immutable snapshot the Activity pushes to the screen each frame. */
data class MonitorUi(
    val backend: String = "…",
    val resolution: String = "—",
    val distance: Int = 0,
    val threshold: Int = 8,
    val admitted: Boolean = false,
    val admittedCount: Long = 0,
    val total: Long = 0,
    val savedPct: Double = 0.0,
    val caption: String = "（尚無:等待第一個放行幀）",
    val guarding: Boolean = false,
    // Audio modality isn't captured yet — say so honestly; never claim "all clear"
    // (no RECORD_AUDIO / pipeline). See ADR-0006 (no false assurance).
    val audio: String = "音訊模態尚未啟用(規劃中)——目前不監聽聲音,不代表無聲響事件。",
)

/**
 * Main screen — 即時守護 · 機器之眼. The camera ([previewView]) is framed as the
 * robot's eye (Optimus-style helmet + dark visor with a scan line); what it
 * sees (L1) and hears reads out below. Design: docs/design/ui.
 */
@Composable
fun LiveMonitorScreen(ui: MonitorUi, previewView: View, active: Boolean, onActivate: () -> Unit, dev: DevUi = DevUi()) {
    val c = ClaustrumTheme.colors
    val videoMode = dev.videoFrame != null
    Column(
        Modifier.fillMaxSize().background(c.ground).statusBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(14.dp))
        AppBar(active = active || videoMode, guarding = ui.guarding || videoMode)
        Spacer(Modifier.height(12.dp))
        RobotEye(previewView, resolution = ui.resolution, active = active, onActivate = onActivate, videoFrame = dev.videoFrame)
        Text(
            when {
                videoMode -> "機器之眼 · 測試影片 · 過 L0→L1 管線"
                !active -> "機器之眼 · 待命 · 點擊上方開啟"
                ui.guarding -> "機器之眼 · 守護中 · 影格不離裝置"
                else -> "機器之眼 · 啟動中… · 影格不離裝置"
            },
            color = c.faint, style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp),
        )
        if (dev.enabled) {
            DevControls(dev)
            Spacer(Modifier.height(10.dp))
        }
        SenseCard(
            label = "看到 · L1 ${ui.backend}",
            body = if (active || videoMode) ui.caption else "待命中──尚未開始守護。點擊上方「啟動守護」開啟機器之眼。",
            eye = true,
        )
        Spacer(Modifier.height(8.dp))
        SenseCard(label = "聽到 · 音訊(規劃)", body = ui.audio, eye = false)
        Spacer(Modifier.height(12.dp))
        TelemetryRow(ui, active = active || videoMode)
        Spacer(Modifier.height(14.dp))
        DescriptionStream()
        Spacer(Modifier.height(20.dp))
    }
}

/** Row-by-row L1 descriptions with timestamps (newest first) — the recent window;
 *  the full 100-record log lives in the 事件 tab. */
@Composable
private fun DescriptionStream(maxRows: Int = 10) {
    val c = ClaustrumTheme.colors
    val entries = CaptionLog.entries
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("描述串流", color = c.steel, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
        Spacer(Modifier.weight(1f))
        Text(
            if (entries.isEmpty()) "尚無" else "最近 ${minOf(entries.size, maxRows)} / 共 ${entries.size}(上限 ${CaptionLog.MAX})· 完整見事件頁",
            color = c.faint, fontFamily = Mono, fontSize = 10.sp,
        )
    }
    Spacer(Modifier.height(6.dp))
    if (entries.isEmpty()) {
        Text("啟動守護或跑測試影片後,L1 每筆描述會逐列記錄於此(含時間)。",
            color = c.faint, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
    } else {
        entries.take(maxRows).forEach { e ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 5.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(hhmmss(e.tsMillis), color = c.steel, fontFamily = Mono, fontSize = 11.sp,
                    modifier = Modifier.padding(end = 8.dp))
                Text(e.text, color = c.ink, fontSize = 13.sp, modifier = Modifier.weight(1f))
            }
        }
    }
}

private fun hhmmss(ts: Long): String =
    java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.TAIWAN).format(java.util.Date(ts))

@Composable
private fun AppBar(active: Boolean, guarding: Boolean) {
    val c = ClaustrumTheme.colors
    val (dot, label, on) = when {
        !active -> Triple(c.faint, "待命", false)
        guarding -> Triple(c.accent, "守護中", true)
        else -> Triple(c.warn, "啟動中…", false)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ClaustrumMark(size = 20.dp, active = active)   // machine-eye brand mark
        Spacer(Modifier.size(9.dp))
        Text("CLAUSTRUM", color = c.ink, fontWeight = FontWeight.ExtraBold, letterSpacing = 3.sp, fontSize = 15.sp)
        Spacer(Modifier.weight(1f))
        // Status pill — reflects whether the camera/pipeline is actually running.
        Row(
            Modifier.clip(RoundedCornerShape(6.dp)).background(c.surface2)
                .border(1.dp, c.line, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.size(6.dp))
            Text(
                label, color = if (on) c.ink else c.muted,
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun RobotEye(previewView: View, resolution: String, active: Boolean, onActivate: () -> Unit, videoFrame: android.graphics.Bitmap? = null) {
    val c = ClaustrumTheme.colors
    val helmet = Brush.verticalGradient(listOf(c.surface2, c.surface, c.ground))
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(64.dp, 64.dp, 24.dp, 24.dp))
            .background(helmet).border(1.dp, c.line, RoundedCornerShape(64.dp, 64.dp, 24.dp, 24.dp))
            .padding(16.dp),
    ) {
        BoxWithConstraints(
            Modifier.fillMaxWidth().aspectRatio(1.5f).clip(RoundedCornerShape(44.dp))
                .background(c.ground).border(1.dp, c.line, RoundedCornerShape(44.dp)),
        ) {
            if (videoFrame != null) {
                // Dev test-video frame fed through the pipeline (not the live camera).
                androidx.compose.foundation.Image(
                    bitmap = videoFrame.asImageBitmap(),
                    contentDescription = "測試影片",
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                val vt = rememberInfiniteTransition(label = "vscan")
                val vfrac by vt.animateFloat(0.08f, 0.9f, infiniteRepeatable(tween(3200), RepeatMode.Reverse), label = "vy")
                Box(
                    Modifier.fillMaxWidth(0.86f).height(2.dp).align(Alignment.TopCenter)
                        .offset(y = maxHeight * vfrac).background(c.accent.copy(alpha = 0.5f)),
                )
                Text(
                    "測試影片 · L0→L1",
                    color = c.steel, style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.TopEnd).padding(10.dp)
                        .clip(RoundedCornerShape(5.dp)).background(c.ground.copy(alpha = 0.7f))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
            } else if (!active) {
                // Standby — the eye is asleep. Camera preview does NOT start until the
                // guardian is deliberately activated (the machine eye "wakes up").
                StandbyEye(onActivate)
            } else {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                // Scan line (animated) — the eye is "alive".
                val t = rememberInfiniteTransition(label = "scan")
                val frac by t.animateFloat(
                    0.08f, 0.9f,
                    infiniteRepeatable(tween(3200), RepeatMode.Reverse), label = "y",
                )
                Box(
                    Modifier.fillMaxWidth(0.86f).height(2.dp).align(Alignment.TopCenter)
                        .offset(y = maxHeight * frac).background(c.accent.copy(alpha = 0.5f)),
                )
                Text(
                    "L0 監看 · $resolution",
                    color = c.steel, style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.TopEnd).padding(10.dp)
                        .clip(RoundedCornerShape(5.dp)).background(c.ground.copy(alpha = 0.7f))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
        }
    }
}

/** Standby content inside the visor: breathing machine-eye + manual "啟動守護". */
@Composable
private fun StandbyEye(onActivate: () -> Unit) {
    val c = ClaustrumTheme.colors
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.machine_eye))
    Column(
        Modifier.fillMaxSize().clickable { onActivate() },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        LottieAnimation(
            composition = composition,
            iterations = LottieConstants.IterateForever,
            modifier = Modifier.size(104.dp),
        )
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.clip(RoundedCornerShape(12.dp)).background(c.accent)
                .clickable { onActivate() }.padding(horizontal = 22.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("▶  啟動守護", color = c.onAccent, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "點擊開啟機器之眼 · 相機此前不啟動",
            color = c.faint, style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
        )
    }
}

/** Dev-mode controls: run model eval / play test video, with the latest eval summary. */
@Composable
private fun DevControls(dev: DevUi) {
    val c = ClaustrumTheme.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface2)
            .border(1.dp, c.warn.copy(alpha = 0.4f), RoundedCornerShape(12.dp)).padding(12.dp),
    ) {
        Text("開發者模式 · 模型驗證", color = c.warn, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DevButton(if (dev.evalRunning) "驗證中…" else "▶ 模型驗證", enabled = !dev.evalRunning && !dev.videoPlaying, modifier = Modifier.weight(1f)) { dev.onRunEval() }
            DevButton(if (dev.videoPlaying) "播放中…" else "▶ 測試影片", enabled = !dev.videoPlaying && !dev.evalRunning, modifier = Modifier.weight(1f)) { dev.onPlayVideo() }
        }
        dev.evalSummary?.let { s ->
            Spacer(Modifier.height(8.dp))
            Text(
                "最近驗證:通過 ${s.passed}/${s.total}(${"%.0f".format(s.passRate)}%) · 平均 ${s.avgLatencyMs}ms · p50 ${s.p50LatencyMs}ms",
                color = c.steel, fontFamily = Mono, fontSize = 11.sp,
            )
        }
        Text(
            "影格放 dev_eval/(檔名 fall__倒臥,跌倒.jpg)· 影片放 dev_videos/",
            color = c.faint, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun DevButton(label: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = ClaustrumTheme.colors
    Box(
        modifier.clip(RoundedCornerShape(10.dp))
            .background(if (enabled) c.surface else c.surface.copy(alpha = 0.5f))
            .border(1.dp, c.line, RoundedCornerShape(10.dp))
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (enabled) c.ink else c.faint, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SenseCard(label: String, body: String, eye: Boolean) {
    val c = ClaustrumTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface2)
            .border(1.dp, c.line, RoundedCornerShape(12.dp)).padding(14.dp),
    ) {
        // Leading icon chip: eye (看到) / ear (聽到).
        Box(
            Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(c.surface)
                .border(1.dp, c.line, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) { if (eye) EyeIcon() else EarIcon() }
        Spacer(Modifier.size(11.dp))
        Column {
            Text(label, color = c.steel, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(6.dp))
            Text(body, color = c.ink, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun EyeIcon() {
    val steel = ClaustrumTheme.colors.steel
    val ground = ClaustrumTheme.colors.ground
    androidx.compose.foundation.Canvas(Modifier.size(16.dp)) {
        val w = size.width; val h = size.height
        // almond eye approximated with an oval + iris + pupil
        drawOval(color = steel.copy(alpha = 0.25f), topLeft = Offset(0f, h * 0.22f), size = Size(w, h * 0.56f))
        drawCircle(steel, radius = h * 0.17f, center = Offset(w / 2, h / 2))
        drawCircle(ground, radius = h * 0.07f, center = Offset(w / 2, h / 2))
    }
}

@Composable
private fun EarIcon() {
    val steel = ClaustrumTheme.colors.steel
    androidx.compose.foundation.Canvas(Modifier.size(16.dp)) {
        val w = size.width; val h = size.height
        // sound waves
        drawCircle(steel, radius = h * 0.12f, center = Offset(w * 0.28f, h / 2))
        repeat(2) { i ->
            drawArc(
                color = steel, startAngle = -55f, sweepAngle = 110f, useCenter = false,
                topLeft = Offset(w * 0.30f - w * (0.18f + 0.16f * i), h / 2 - h * (0.20f + 0.18f * i)),
                size = Size(w * (0.36f + 0.32f * i), h * (0.40f + 0.36f * i)),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = h * 0.08f),
            )
        }
    }
}

@Composable
private fun TelemetryRow(ui: MonitorUi, active: Boolean) {
    val c = ClaustrumTheme.colors
    if (!active) {
        Text("L0 ⏸ 待命 · 尚未取幀", color = c.faint, fontFamily = Mono, fontSize = 11.sp)
        return
    }
    val decision = if (ui.admitted) "▶ 放行" else "⏸ 略過"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("L0 $decision · Δ${ui.distance}/${ui.threshold}", color = c.muted, fontFamily = Mono, fontSize = 11.sp)
        Text("放行 ${ui.admittedCount}/${ui.total} · 省 ${"%.1f".format(ui.savedPct)}%",
            color = c.steel, fontFamily = Mono, fontSize = 11.sp)
    }
}
