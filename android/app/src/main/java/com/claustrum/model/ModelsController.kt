package com.claustrum.model

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.Locale

/**
 * Holds model catalog + download + HF-token state for the Compose 模型 tab.
 * Observes WorkManager with the hosting activity as LifecycleOwner and exposes
 * Compose-observable state (status per model, whether a token is set).
 */
class ModelsController(private val activity: ComponentActivity) {

    private val tokenStore = TokenStore(activity)

    val hasToken = mutableStateOf(tokenStore.hasHfToken())
    val hasMediaPipeMetricsConsent = mutableStateOf(MediaPipeMetricsConsent.isGranted(activity))
    /** model name → human status line. */
    val status = mutableStateMapOf<String, String>()

    val catalog: List<ModelSpec> = ModelSpec.CATALOG

    init { refreshPresence() }

    fun refreshPresence() {
        catalog.forEach { status[it.name] = if (it.isPresent(activity)) "✅ 已下載" else "尚未下載" }
    }

    fun setToken(token: String?) {
        tokenStore.setHfToken(token)
        hasToken.value = tokenStore.hasHfToken()
    }

    fun setMediaPipeMetricsConsent(granted: Boolean) {
        MediaPipeMetricsConsent.setGranted(activity, granted)
        hasMediaPipeMetricsConsent.value = granted
    }

    /** Re-enabling an already verified model must not require another network download. */
    fun enableMediaPipe(spec: ModelSpec) {
        setMediaPipeMetricsConsent(true)
        if (spec.isPresent(activity)) refreshPresence() else download(spec)
    }

    fun download(spec: ModelSpec) {
        if (spec.gated && !tokenStore.hasHfToken()) {
            status[spec.name] = "🔒 需先設定上方 HF 權杖才能下載此 gated 模型"
            return
        }
        status[spec.name] = "排入下載…"
        val name = ModelRepository.enqueueDownload(activity, spec)
        WorkManager.getInstance(activity)
            .getWorkInfosForUniqueWorkLiveData(name)
            .observe(activity) { infos ->
                val info = infos?.firstOrNull() ?: return@observe
                status[spec.name] = when (info.state) {
                    WorkInfo.State.RUNNING -> {
                        val recv = info.progress.getLong(ModelDownloadWorker.KEY_P_RECEIVED, 0)
                        val total = info.progress.getLong(ModelDownloadWorker.KEY_P_TOTAL, spec.sizeBytes)
                        val rate = info.progress.getFloat(ModelDownloadWorker.KEY_P_RATE, 0f)
                        val pct = if (total > 0) recv * 100 / total else 0
                        downloadProgressLine(pct, recv, total, rate)
                    }
                    WorkInfo.State.SUCCEEDED -> "✅ 已下載"
                    WorkInfo.State.FAILED ->
                        "❌ " + (info.outputData.getString(ModelDownloadWorker.KEY_ERROR) ?: "下載失敗")
                    WorkInfo.State.CANCELLED -> "已取消"
                    else -> "排入下載…"
                }
            }
    }
}

internal fun downloadSize(received: Long, total: Long): String =
    if (total in 1 until 100_000_000L) {
        String.format(Locale.US, "%.1f / %.1f MB", received / 1e6, total / 1e6)
    } else {
        String.format(Locale.US, "%.1f / %.1f GB", received / 1e9, total / 1e9)
    }

internal fun downloadProgressLine(
    percent: Long,
    received: Long,
    total: Long,
    bytesPerSecond: Float,
): String {
    val rate = String.format(Locale.US, "%.1f", bytesPerSecond / 1e6)
    return "下載中 ${percent.coerceIn(0L, 100L)}% · ${downloadSize(received, total)} · $rate MB/s"
}
