package com.claustrum.model

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf

/**
 * Orchestrates model presence + download via WorkManager. The Activity observes
 * progress with `WorkManager.getWorkInfosForUniqueWorkLiveData(uniqueName)`.
 */
object ModelRepository {

    fun isPresent(context: Context, spec: ModelSpec): Boolean = spec.isPresent(context)

    /** Unique work name per model file, so repeated taps don't stack downloads. */
    fun uniqueWorkName(spec: ModelSpec): String = "download-${spec.modelId}-${spec.fileName}"

    /**
     * Enqueue (or keep) the download for [spec]. The HF token (for gated repos)
     * is NOT passed here — the worker reads it from [TokenStore] at runtime so it
     * never lands in WorkManager's persisted job input (security).
     */
    fun enqueueDownload(context: Context, spec: ModelSpec): String {
        val baseInput = workDataOf(
            ModelDownloadWorker.KEY_URL to spec.resolveUrl(),
            ModelDownloadWorker.KEY_DEST to spec.localFile(context).absolutePath,
            ModelDownloadWorker.KEY_TMP to spec.tempFile(context).absolutePath,
            ModelDownloadWorker.KEY_TOTAL to spec.sizeBytes,
            ModelDownloadWorker.KEY_NAME to spec.name,
        )
        val input = androidx.work.Data.Builder().putAll(baseInput).apply {
            spec.sha256?.let { putString(ModelDownloadWorker.KEY_SHA256, it) }
        }.build()
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .setInputData(input)
            .build()

        val name = uniqueWorkName(spec)
        WorkManager.getInstance(context)
            .enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request)
        return name
    }

    fun cancel(context: Context, spec: ModelSpec) {
        WorkManager.getInstance(context).cancelUniqueWork(uniqueWorkName(spec))
    }
}
