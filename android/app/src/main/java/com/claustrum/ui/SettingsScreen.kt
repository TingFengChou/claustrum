package com.claustrum.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.claustrum.ui.theme.ClaustrumTheme

/** 設定 tab — read-only info + the developer-mode toggle. */
@Composable
fun SettingsScreen(l1Backend: String, dev: DevUi = DevUi()) {
    val c = ClaustrumTheme.colors
    Column(
        Modifier.fillMaxSize().background(c.ground).statusBarsPadding()
            .verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Text("設定", color = c.ink, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
        Spacer(Modifier.height(14.dp))
        ToggleRow(
            "開發者模式",
            "開啟後,守護頁出現「模型驗證 / 測試影片」工具;用測試影片與標註影格驗證換模型後的正確率與延遲。",
            dev.enabled, dev.onToggle,
        )
        InfoRow("L0 變化閘控閾值", "8 bits(靜態場景省 ~100% 運算)")
        InfoRow("L1 場景描述後端", l1Backend)
        InfoRow("加速", "Tensor G5 GPU(LiteRT delegate)")
        InfoRow("隱私", "影格只在裝置端、用完即刪;只有文字描述/事件可外傳。無人物身分特徵。")
        InfoRow("關於", "claustrum · 主動防護的即時守護者 · edge AI")
    }
}

@Composable
private fun ToggleRow(title: String, desc: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val c = ClaustrumTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(12.dp))
            .background(c.surface).border(1.dp, c.line, RoundedCornerShape(12.dp)).padding(14.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text(desc, color = c.muted, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(12.dp))
        androidx.compose.material3.Switch(
            checked = checked, onCheckedChange = onCheckedChange,
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedThumbColor = c.onAccent, checkedTrackColor = c.accent,
                uncheckedThumbColor = c.muted, uncheckedTrackColor = c.surface2, uncheckedBorderColor = c.line,
            ),
        )
    }
}

@Composable
private fun InfoRow(title: String, value: String) {
    val c = ClaustrumTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(RoundedCornerShape(12.dp))
            .background(c.surface).border(1.dp, c.line, RoundedCornerShape(12.dp)).padding(14.dp),
    ) {
        Text(title, color = c.steel, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(4.dp))
        Text(value, color = c.ink, fontSize = 14.sp)
    }
}
