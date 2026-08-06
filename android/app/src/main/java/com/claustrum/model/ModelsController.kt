package com.claustrum.model

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.work.WorkInfo
import androidx.work.WorkManager

/**
 * Holds model catalog + download + HF-token state for the Compose 模型 tab.
 * Observes WorkManager with the hosting activity as LifecycleOwner and exposes
 * Compose-observable state (status per model, whether a token is set).
 */
class ModelsController(private val activity: ComponentActivity) {

    private val tokenStore = TokenStore(activity)

    val hasToken = mutableStateOf(tokenStore.hasHfToken())
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
                        "下載中 %d%% · %.1f / %.1f GB · %.1f MB/s".format(pct, recv / 1e9, total / 1e9, rate / 1e6)
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
