package com.claustrum.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.claustrum.model.CaptionLog
import com.claustrum.ui.theme.ClaustrumTheme
import com.claustrum.ui.theme.Mono

/**
 * 事件 tab — for now, a time-ordered log of L1 scene descriptions (最近 100),
 * for **validation**. Confirmed safety events (跌倒/暴力) are L2's job (P3) with
 * visible evidence (ADR-0006); we never fabricate them, so until L2 lands this
 * shows raw L1 output, clearly labelled as descriptions, not verified events.
 */
@Composable
fun EventsScreen() {
    val c = ClaustrumTheme.colors
    val entries = CaptionLog.entries
    Column(Modifier.fillMaxSize().background(c.ground).statusBarsPadding().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("描述記錄", color = c.ink, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
            Spacer(Modifier.weight(1f))
            Text("L1 · 最近 ${CaptionLog.MAX}", color = c.faint, fontFamily = Mono, fontSize = 11.sp)
        }
        Text(
            "以下為 L1 逐筆場景描述(時間序,供驗證)。跌倒/暴力等「事件」由 L2 判定(P3,需畫面內可見證據),尚未啟用。",
            color = c.faint, style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
        )
        if (entries.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(bottom = 40.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("尚無描述記錄。", color = c.muted, fontSize = 15.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Text(
                    "到「守護」頁啟動守護,或(開發者模式)跑測試影片/模型驗證,描述會即時記錄於此。",
                    color = c.faint, style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn {
                items(entries) { e -> CaptionRow(e) }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun CaptionRow(e: CaptionLog.Entry) {
    val c = ClaustrumTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(12.dp))
            .background(c.surface).border(1.dp, c.line, RoundedCornerShape(12.dp)).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(hhmmss(e.tsMillis), color = c.steel, fontFamily = Mono, fontSize = 11.sp)
            Spacer(Modifier.width(8.dp))
            Text(e.source, color = c.muted, fontFamily = Mono, fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            if (e.latencyMs >= 0) Text("${e.latencyMs}ms", color = c.faint, fontFamily = Mono, fontSize = 11.sp)
        }
        Spacer(Modifier.height(4.dp))
        Text(e.text, color = c.ink, fontSize = 14.sp)
    }
}

private fun hhmmss(ts: Long): String {
    val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.TAIWAN)
    return fmt.format(java.util.Date(ts))
}
