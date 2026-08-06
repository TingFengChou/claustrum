package com.claustrum.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.claustrum.ui.theme.ClaustrumTheme

/**
 * 事件 tab — timeline of detected events (fall / violence). Honest empty state
 * until the L2 event engine (P3) lands; we never fabricate events (ADR-0006).
 */
@Composable
fun EventsScreen() {
    val c = ClaustrumTheme.colors
    Column(
        Modifier.fillMaxSize().background(c.ground).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("事件記錄", color = c.ink, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
        Spacer(Modifier.height(12.dp))
        Text(
            "尚無事件。",
            color = c.muted, fontSize = 15.sp, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "守護中若偵測到跌倒、暴力等安全事件,會在此依嚴重度記錄(附畫面內可見證據與所用模型)。L2 事件引擎為 P3。",
            color = c.faint, style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
    }
}
