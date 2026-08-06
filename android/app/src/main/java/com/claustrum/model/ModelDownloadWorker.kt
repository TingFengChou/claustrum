package com.claustrum.model

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads one model file to the app's external files dir, with resume and
 * progress. Ported/simplified from Google AI Edge Gallery's `DownloadWorker`
 * (Apache-2.0): standard `HttpURLConnection`, `.tmp` + HTTP `Range` resume,
 * optional `Authorization: Bearer` for gated Hugging Face repos, progress every
 * ~200 ms, foreground notification so a multi-GB download survives backgrounding.
 *
 * On success the `.tmp` file is renamed to the final path ([ModelSpec.localFile]).
 */
class ModelDownloadWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val url = inputData.getString(KEY_URL) ?: return@withContext fail("缺少下載網址")
        val destPath = inputData.getString(KEY_DEST) ?: return@withContext fail("缺少目的路徑")
        val tmpPath = inputData.getString(KEY_TMP) ?: return@withContext fail("缺少暫存路徑")
        val totalBytes = inputData.getLong(KEY_TOTAL, 0L)
        val token = inputData.getString(KEY_TOKEN)
        val modelName = inputData.getString(KEY_NAME) ?: "model"

        val destFile = java.io.File(destPath)
        val tmpFile = java.io.File(tmpPath)
        tmpFile.parentFile?.mkdirs()

        try {
            setForeground(foregroundInfo(modelName, 0))
        } catch (_: Exception) {
            // Foreground may be unavailable (e.g. restricted); continue in background.
        }

        try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                if (token != null) setRequestProperty("Authorization", "Bearer $token")
                connectTimeout = 30_000
                readTimeout = 30_000
            }

            var received = tmpFile.length()
            if (received > 0) {
                // Resume: ask for the rest; identity encoding so ranges are honored.
                connection.setRequestProperty("Range", "bytes=$received-")
                connection.setRequestProperty("Accept-Encoding", "identity")
            }
            connection.connect()

            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED || code == HttpURLConnection.HTTP_FORBIDDEN) {
                return@withContext fail("需要授權($code):此模型在 Hugging Face 為 gated,需 HF 存取權杖")
            }
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                return@withContext fail("HTTP 錯誤:$code")
            }
            if (code == HttpURLConnection.HTTP_OK) received = 0L // server ignored Range → restart

            val total = if (totalBytes > 0) totalBytes else (received + connection.contentLengthLong)

            connection.inputStream.use { input ->
                FileOutputStream(tmpFile, /* append = */ received > 0).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var lastReport = 0L
                    var windowBytes = 0L
                    var windowStart = System.currentTimeMillis()
                    while (true) {
                        val n = input.read(buffer)
                        if (n == -1) break
                        output.write(buffer, 0, n)
                        received += n
                        windowBytes += n
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 200) {
                            val rate = if (now > windowStart) windowBytes * 1000f / (now - windowStart) else 0f
                            setProgress(
                                workDataOf(
                                    KEY_P_RECEIVED to received,
                                    KEY_P_TOTAL to total,
                                    KEY_P_RATE to rate,
                                )
                            )
                            try {
                                setForeground(foregroundInfo(modelName, pct(received, total)))
                            } catch (_: Exception) {}
                            lastReport = now
                            windowBytes = 0
                            windowStart = now
                        }
                    }
                }
            }

            // Complete: promote tmp → final.
            destFile.parentFile?.mkdirs()
            if (destFile.exists()) destFile.delete()
            if (!tmpFile.renameTo(destFile)) {
                return@withContext fail("無法完成檔案(rename 失敗)")
            }
            Log.i(TAG, "Downloaded ${destFile.name} (${destFile.length()} bytes)")
            Result.success(workDataOf(KEY_P_RECEIVED to received, KEY_P_TOTAL to total))
        } catch (e: IOException) {
            Log.e(TAG, "download failed", e)
            fail("下載失敗:${e.message}")
        }
    }

    private fun fail(msg: String): Result = Result.failure(workDataOf(KEY_ERROR to msg))

    private fun pct(received: Long, total: Long): Int =
        if (total > 0) (received * 100 / total).toInt().coerceIn(0, 100) else 0

    private fun foregroundInfo(modelName: String, percent: Int): ForegroundInfo {
        val ctx = applicationContext
        val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL, "模型下載", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notif = NotificationCompat.Builder(ctx, CHANNEL)
            .setContentTitle("下載模型:$modelName")
            .setContentText("$percent%")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setProgress(100, percent, percent == 0)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ID, notif)
        }
    }

    companion object {
        private const val TAG = "ModelDownloadWorker"
        private const val CHANNEL = "model_download"
        private const val NOTIF_ID = 4711

        const val KEY_URL = "url"
        const val KEY_DEST = "dest"
        const val KEY_TMP = "tmp"
        const val KEY_TOTAL = "total"
        const val KEY_TOKEN = "token"
        const val KEY_NAME = "name"

        const val KEY_P_RECEIVED = "p_received"
        const val KEY_P_TOTAL = "p_total"
        const val KEY_P_RATE = "p_rate"
        const val KEY_ERROR = "error"
    }
}
