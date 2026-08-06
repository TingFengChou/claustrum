package com.claustrum

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.claustrum.core.ChangeGate
import com.claustrum.core.NativeCore
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * P1 screen: live camera → per-frame luma → Rust L0 change-gate.
 *
 * CameraX `ImageAnalysis` hands each frame's Y (luma) plane to the Rust core
 * ([NativeCore.frameSignature]); the [ChangeGate] admits only frames that differ
 * enough from the last admitted one — the "only wake the VLM when the scene
 * changes" compute saver. Pixels never leave the analyzer; only the aHash and a
 * boolean decision are kept.
 */
class MainActivity : ComponentActivity() {

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val gate = ChangeGate(threshold = 8)

    // Rolling stats (updated on the analysis thread, read on the UI thread).
    private val totalFrames = AtomicLong(0)
    private val admittedFrames = AtomicLong(0)

    // Latest L1 description; recomputed only on an admitted frame.
    @Volatile private var lastCaption = "（尚無:等待第一個放行幀）"

    private lateinit var statusView: TextView
    private lateinit var previewView: PreviewView

    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else statusView.text = "需要相機權限才能執行 L0 閘控驗證。"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        }
        statusView = TextView(this).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH, WRAP).apply {
                gravity = Gravity.TOP
            }
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#B0000000"))
            setPadding(28, 40, 28, 28)
            text = "啟動相機中…（Rust 核心:${safeHello()})"
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(previewView)
            addView(statusView)
        }
        setContentView(root)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun safeHello(): String = try {
        NativeCore.nativeHello()
    } catch (t: Throwable) {
        "載入失敗 ${t.message}"
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

            // L1 wakes ONLY on an admitted frame — this is the compute saver in action.
            if (admitted) {
                lastCaption = NativeCore.describe(luma, w, h) ?: "L1 佔位:描述失敗"
            }

            val report = buildString {
                append("claustrum · L0→L1 感知管線(裝置端 Rust)\n")
                append("分析解析度: ${w}×${h}  閾值: ${gate.threshold} bits\n")
                append("sig: 0x%016x\n".format(sig))
                append("Hamming(vs 上次放行): $dist\n")
                append(if (admitted) "L0 決策: ▶ 放行 → 喚醒 L1\n" else "L0 決策: ⏸ 略過(省算力)\n")
                append("放行 $kept / 共 $total  → 省下 %.1f%% 運算\n".format(savedPct))
                append("─────\n")
                append("L1 最新描述:\n$lastCaption")
            }
            runOnUiThread { statusView.text = report }
        } catch (t: Throwable) {
            Log.e(TAG, "analyze failed", t)
        } finally {
            image.close()
        }
    }

    /**
     * Copies the Y (luma) plane into a tightly packed w*h byte array, honoring
     * `rowStride` (padding) — Y plane `pixelStride` is 1 in YUV_420_888.
     */
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
