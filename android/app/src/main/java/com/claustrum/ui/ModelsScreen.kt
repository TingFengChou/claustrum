package com.claustrum.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.claustrum.model.ModelSpec
import com.claustrum.model.ModelsController
import com.claustrum.ui.theme.ClaustrumTheme
import com.claustrum.ui.theme.Mono

/** 模型 tab — browse the catalog, set the HF token, download in-app (dark design). */
@Composable
fun ModelsScreen(controller: ModelsController) {
    val c = ClaustrumTheme.colors
    var showTokenDialog by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().background(c.ground).statusBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Text("Edge AI 模型", color = c.ink, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
        Text(
            "在 App 內下載並管理裝置端模型(LiteRT / Google AI Edge)。L1 場景描述需「看圖描述」能力的模型。",
            color = c.muted, style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp, bottom = 14.dp),
        )

        // HF token row
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(c.surface2).border(1.dp, c.warn.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (controller.hasToken.value) "🔑 Hugging Face:已設定權杖" else "🔒 Hugging Face:未設定(gated 模型需要)",
                color = c.warn, style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            Pill(if (controller.hasToken.value) "變更" else "設定") { showTokenDialog = true }
        }

        Spacer(Modifier.height(14.dp))
        controller.catalog.forEach { spec -> ModelCard(spec, controller.status[spec.name] ?: "", onDownload = { controller.download(spec) }); Spacer(Modifier.height(12.dp)) }
        Spacer(Modifier.height(24.dp))
    }

    if (showTokenDialog) TokenDialog(controller, onDismiss = { showTokenDialog = false })
}

@Composable
private fun ModelCard(spec: ModelSpec, status: String, onDownload: () -> Unit) {
    val c = ClaustrumTheme.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(14.dp)).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(spec.name, color = c.ink, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            if (spec.gated) Text("  🔒 gated", color = c.warn, style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
        }
        Text(
            "能力:${spec.capabilities.joinToString(" · ") { it.label }}  ·  約 %.2f GB".format(spec.sizeBytes / 1e9),
            color = c.muted, fontFamily = Mono, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp),
        )
        if (spec.description.isNotBlank()) {
            Text(spec.description, color = c.muted, style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
        }
        Spacer(Modifier.height(10.dp))
        // Full-width download button
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.accent)
                .clickable { onDownload() }.padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(if (status.startsWith("✅")) "重新下載" else "下載", color = c.onAccent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        if (status.isNotBlank()) {
            Text(status, color = if (status.startsWith("❌")) c.accent else c.steel,
                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun Pill(label: String, onClick: () -> Unit) {
    val c = ClaustrumTheme.colors
    Text(
        label, color = c.ink, style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(8.dp)).clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun TokenDialog(controller: ModelsController, onDismiss: () -> Unit) {
    val c = ClaustrumTheme.colors
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text("Hugging Face 存取權杖", color = c.ink) },
        text = {
            Column {
                Text(
                    "下載 gated Gemma 模型需要。權杖以加密方式儲存於裝置,僅作為下載請求的授權標頭,不外傳、不顯示、不寫入紀錄。可於 huggingface.co/settings/tokens 產生(read 權限即可)。",
                    color = c.muted, style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions.Default,
                    placeholder = { Text(if (controller.hasToken.value) "已設定 · 輸入新權杖以覆蓋" else "hf_xxx 存取權杖", color = c.faint) },
                )
            }
        },
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) controller.setToken(text); onDismiss() }) { Text("儲存", color = c.accent) } },
        dismissButton = {
            Row {
                TextButton(onClick = { controller.setToken(null); onDismiss() }) { Text("清除", color = c.muted) }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = onDismiss) { Text("取消", color = c.muted) }
            }
        },
    )
}
