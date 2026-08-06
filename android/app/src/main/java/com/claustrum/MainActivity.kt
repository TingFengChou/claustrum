package com.claustrum

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.claustrum.model.TokenStore
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.claustrum.core.ChangeGate
import com.claustrum.core.NativeCore
import com.claustrum.model.Capability
import com.claustrum.model.ModelDownloadWorker
import com.claustrum.model.ModelRepository
import com.claustrum.model.ModelSpec
import com.claustrum.vlm.Captioner
import com.claustrum.vlm.PlaceholderCaptioner
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Two screens, programmatic (Compose comes later):
 *  1. **Model gate** — browse the catalog, see each model's capabilities/size,
 *     and download in-app (WorkManager, resumable, progress). Productization:
 *     the app fetches its own model — no adb push, no separate app (ADR-0009,
 *     pattern from Google AI Edge Gallery).
 *  2. **Camera** — CameraX luma → Rust L0 gate → (admitted) L1 [Captioner].
 *
 * L1 backend today is [PlaceholderCaptioner] (Rust diagnostic); `LiteRtCaptioner`
 * (real multimodal Gemma via LiteRT-LM) slots in behind the same interface once a
 * vision model is downloaded.
 */
class MainActivity : ComponentActivity() {

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val gate = ChangeGate(threshold = 8)
    private val captioner: Captioner = PlaceholderCaptioner
    private val tokenStore by lazy { TokenStore(this) }

    private val totalFrames = AtomicLong(0)
    private val admittedFrames = AtomicLong(0)
    @Volatile private var lastCaption = "（尚無:等待第一個放行幀）"

    private lateinit var statusView: TextView
    private lateinit var previewView: PreviewView

    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else statusView.text = "需要相機權限才能執行 L0 閘控驗證。"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Enter at the model gate; the user can proceed to the camera from there.
        showModelGate()
    }

    // ---- Screen 1: model gate (catalog + in-app download) -------------------

    private fun showModelGate() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FAFAFA"))
            setPadding(48, 64, 48, 48)
        }
        root.addView(TextView(this).apply {
            text = "claustrum · Edge AI 模型"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#111111"))
        })
        root.addView(TextView(this).apply {
            text = "在 App 內下載並管理裝置端模型(LiteRT / Google AI Edge)。L1 場景描述需" +
                "「看圖描述」能力的模型。"
            textSize = 13f
            setTextColor(Color.parseColor("#555555"))
            setPadding(0, 16, 0, 16)
        })

        // HF token row — gated Gemma 需要 Hugging Face 存取權杖才能下載。
        root.addView(hfTokenRow())

        for (spec in ModelSpec.CATALOG) {
            root.addView(modelCard(spec))
        }

        root.addView(Button(this).apply {
            text = "進入即時偵測(L0 + L1 佔位)"
            setOnClickListener { showCamera() }
        })
        root.addView(TextView(this).apply {
            text = "註:真實 L1 多模態推論(LiteRtCaptioner)將於模型下載後接上;目前為 Rust 佔位診斷。"
            textSize = 11f
            setTextColor(Color.parseColor("#888888"))
            setPadding(0, 16, 0, 0)
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun hfTokenRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#FFF6E5"))
            setPadding(dp(16), dp(12), dp(12), dp(12))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, dp(12)) }
            layoutParams = lp
        }
        val label = TextView(this).apply {
            text = if (tokenStore.hasHfToken()) "🔑 Hugging Face:已設定權杖" else "🔒 Hugging Face:未設定(gated 模型需要)"
            textSize = 12f
            setTextColor(Color.parseColor("#8A6D00"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btn = Button(this).apply {
            text = if (tokenStore.hasHfToken()) "變更" else "設定"
            setOnClickListener { showTokenDialog() }
        }
        row.addView(label); row.addView(btn)
        return row
    }

    private fun showTokenDialog() {
        val pad = dp(20)
        // Masked, and never pre-filled with the stored secret (Codex review):
        // a set token is only indicated by the hint, not revealed.
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = if (tokenStore.hasHfToken()) "已設定 · 輸入新權杖以覆蓋" else "hf_xxx 存取權杖"
            setPadding(pad, dp(14), pad, dp(14))
        }
        AlertDialog.Builder(this)
            .setTitle("Hugging Face 存取權杖")
            .setMessage("下載 gated Gemma 模型需要。權杖以加密方式儲存於裝置,僅作為下載請求的授權標頭,不外傳、不顯示、不寫入紀錄。可於 huggingface.co/settings/tokens 產生(read 權限即可)。")
            .setView(input)
            .setPositiveButton("儲存") { _, _ ->
                val entered = input.text?.toString()
                if (!entered.isNullOrBlank()) tokenStore.setHfToken(entered)
                showModelGate() // rebuild to reflect new state
            }
            .setNeutralButton("清除") { _, _ ->
                tokenStore.setHfToken(null); showModelGate()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** dp → px for programmatic views (avoids hard-coded pixels across densities). */
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun modelCard(spec: ModelSpec): View {
        val caps = spec.capabilities.joinToString(" · ") { it.label }
        val sizeGb = spec.sizeBytes / 1e9
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(32, 28, 32, 28)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 24) }
            layoutParams = lp
        }
        card.addView(TextView(this).apply {
            text = "${spec.name}${if (spec.gated) "  🔒gated" else ""}"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#111111"))
        })
        card.addView(TextView(this).apply {
            text = "能力:$caps   ·   約 %.2f GB".format(sizeGb)
            textSize = 12f
            setTextColor(Color.parseColor("#666666"))
        })
        card.addView(TextView(this).apply {
            text = spec.description
            textSize = 12f
            setTextColor(Color.parseColor("#666666"))
            setPadding(0, 4, 0, 8)
        })
        val status = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#1565C0"))
            text = if (ModelRepository.isPresent(this@MainActivity, spec)) "✅ 已下載" else "尚未下載"
        }
        val button = Button(this).apply {
            text = if (ModelRepository.isPresent(this@MainActivity, spec)) "重新下載" else "下載"
            setOnClickListener {
                if (spec.gated && !tokenStore.hasHfToken()) {
                    status.text = "🔒 需先設定上方 HF 權杖才能下載此 gated 模型"
                    return@setOnClickListener
                }
                isEnabled = false
                status.text = "排入下載…"
                // Token is NOT passed here; the worker reads it from TokenStore.
                ModelRepository.enqueueDownload(this@MainActivity, spec)
            }
        }
        card.addView(button)
        card.addView(status)
        // Attach the observer at creation so a download already running (e.g. app
        // was reopened) restores its progress without needing another tap.
        observeDownload(spec, status, button)
        return card
    }

    private fun observeDownload(spec: ModelSpec, status: TextView, button: Button) {
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(ModelRepository.uniqueWorkName(spec))
            .observe(this) { infos ->
                val info = infos?.firstOrNull() ?: return@observe
                when (info.state) {
                    WorkInfo.State.RUNNING -> {
                        val recv = info.progress.getLong(ModelDownloadWorker.KEY_P_RECEIVED, 0)
                        val total = info.progress.getLong(ModelDownloadWorker.KEY_P_TOTAL, spec.sizeBytes)
                        val rate = info.progress.getFloat(ModelDownloadWorker.KEY_P_RATE, 0f)
                        val pct = if (total > 0) recv * 100 / total else 0
                        status.text = "下載中 $pct%%  (%.1f / %.1f GB, %.1f MB/s)".format(
                            recv / 1e9, total / 1e9, rate / 1e6
                        )
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        status.text = "✅ 已下載"
                        button.isEnabled = true
                        button.text = "重新下載"
                    }
                    WorkInfo.State.FAILED -> {
                        val err = info.outputData.getString(ModelDownloadWorker.KEY_ERROR) ?: "未知錯誤"
                        status.text = "❌ $err"
                        button.isEnabled = true
                    }
                    WorkInfo.State.CANCELLED -> {
                        status.text = "已取消"; button.isEnabled = true
                    }
                    else -> status.text = "排入下載…"
                }
            }
    }

    // ---- Screen 2: camera (L0 gate → L1 captioner) --------------------------

    private fun showCamera() {
        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        }
        statusView = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, WRAP).apply { gravity = Gravity.TOP }
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#B0000000"))
            setPadding(28, 40, 28, 28)
            text = "啟動相機中…（L1 後端:${captioner.backend}）"
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(previewView)
            addView(statusView)
        })

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera() else requestCamera.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .also { it.setAnalyzer(analysisExecutor, ::analyze) }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                )
            } catch (t: Throwable) {
                Log.e(TAG, "bindToLifecycle failed", t)
                runOnUiThread { statusView.text = "相機綁定失敗:${t.message}" }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Runs on [analysisExecutor]; must close the proxy. */
    private fun analyze(image: ImageProxy) {
        try {
            val w = image.width
            val h = image.height
            val luma = extractLuma(image)
            val sig = NativeCore.frameSignature(luma, w, h)
            val dist = gate.distanceFrom(sig)
            val admitted = gate.admit(sig)

            val total = totalFrames.incrementAndGet()
            val kept = if (admitted) admittedFrames.incrementAndGet() else admittedFrames.get()
            val savedPct = if (total > 0) 100.0 * (total - kept) / total else 0.0

            // L1 wakes ONLY on an admitted frame — the compute saver in action.
            if (admitted) {
                lastCaption = captioner.describe(luma, w, h)
            }

            val report = buildString {
                append("claustrum · L0→L1 感知管線(裝置端)\n")
                append("L1 後端: ${captioner.backend}\n")
                append("分析解析度: ${w}×${h}  閾值: ${gate.threshold} bits\n")
                append("sig: 0x%016x\n".format(sig))
                append("Hamming(vs 上次放行): $dist\n")
                append(if (admitted) "L0 決策: ▶ 放行 → 喚醒 L1\n" else "L0 決策: ⏸ 略過(省算力)\n")
                append("放行 $kept / 共 $total  → 省下 %.1f%% 運算\n".format(savedPct))
                append("─────\n")
                append("L1 最新描述:\n$lastCaption")
            }
            runOnUiThread { if (::statusView.isInitialized) statusView.text = report }
        } catch (t: Throwable) {
            Log.e(TAG, "analyze failed", t)
        } finally {
            image.close()
        }
    }

    private fun extractLuma(image: ImageProxy): ByteArray {
        val w = image.width
        val h = image.height
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val out = ByteArray(w * h)
        if (rowStride == w) {
            buffer.position(0)
            buffer.get(out, 0, w * h)
        } else {
            for (row in 0 until h) {
                buffer.position(row * rowStride)
                buffer.get(out, row * w, w)
            }
        }
        return out
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
    }

    companion object {
        private const val TAG = "claustrum"
        private const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = FrameLayout.LayoutParams.WRAP_CONTENT
    }
}
