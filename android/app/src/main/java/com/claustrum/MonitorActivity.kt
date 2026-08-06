package com.claustrum

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import com.claustrum.core.ChangeGate
import com.claustrum.core.NativeCore
import com.claustrum.model.ModelSpec
import com.claustrum.ui.LiveMonitorScreen
import com.claustrum.ui.MonitorUi
import com.claustrum.ui.theme.ClaustrumTheme
import com.claustrum.vlm.Captioner
import com.claustrum.vlm.FallbackCaptioner
import com.claustrum.vlm.LiteRtCaptioner
import com.claustrum.vlm.PerceptionPipeline
import com.claustrum.vlm.PlaceholderCaptioner
import java.util.concurrent.Executors

/**
 * 即時守護 · 機器之眼 (main screen). Owns CameraX + the L0→L1 [PerceptionPipeline]
 * and renders the designed Compose UI ([LiveMonitorScreen]). L1 uses the real
 * [LiteRtCaptioner] when a vision model is present, else the placeholder.
 */
class MonitorActivity : ComponentActivity() {

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val inferenceExecutor = Executors.newSingleThreadExecutor() // L1 off the analyzer thread
    private val inFlight = java.util.concurrent.atomic.AtomicBoolean(false) // single-flight L1
    @Volatile private var pipeline: PerceptionPipeline<Bitmap>? = null
    @Volatile private var guarding = false
    @Volatile private var lastRes = "—"
    private val uiState = mutableStateOf(MonitorUi())
    private lateinit var previewView: PreviewView

    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else uiState.value = uiState.value.copy(caption = "需要相機權限才能守護。")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        previewView = PreviewView(this)
        setContent {
            // Signature Tesla/Optimus look is dark graphite — force it (guardian instrument),
            // don't follow the system light theme (design: docs/design/ui).
            ClaustrumTheme(darkTheme = true) {
                LiveMonitorScreen(
                    ui = uiState.value,
                    previewView = previewView,
                    // Open the models screen ON TOP (backable) — system Back returns here.
                    onOpenModels = { startActivity(android.content.Intent(this, MainActivity::class.java)) },
                )
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera() else requestCamera.launch(Manifest.permission.CAMERA)
    }

    private fun buildCaptioner(): Captioner<Bitmap> {
        val spec = ModelSpec.DEFAULT_L1
        if (spec.isPresent(this)) {
            try {
                // Stable cache dir (survives reinstall) so the compiled vision graph persists.
                val cache = java.io.File(getExternalFilesDir(null), "litert-cache").apply { mkdirs() }
                val lite = LiteRtCaptioner(spec.localFile(this).absolutePath, cacheDir = cache.absolutePath)
                // Degrade to the honest placeholder if inference keeps failing (no dead-end).
                return FallbackCaptioner(lite, PlaceholderCaptioner)
            } catch (t: Throwable) {
                Log.e(TAG, "LiteRtCaptioner init failed; using placeholder", t)
            }
        }
        return PlaceholderCaptioner
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .also { it.setAnalyzer(analysisExecutor, ::analyze) }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (t: Throwable) {
                Log.e(TAG, "bindToLifecycle failed", t)
                guarding = false
                uiState.value = uiState.value.copy(caption = "相機綁定失敗:${t.message}", guarding = false)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyze(image: ImageProxy) {
        try {
            val p = pipeline ?: PerceptionPipeline(ChangeGate(threshold = 8), buildCaptioner()).also { pipeline = it }
            val w = image.width
            val h = image.height
            lastRes = "${w}×${h}"
            val luma = extractLuma(image)
            val sig = NativeCore.frameSignature(luma, w, h)
            val admitted = p.admit(sig) // fast L0 on this (analyzer) thread
            guarding = true
            // L1 runs on a SEPARATE thread, single-flight (drop new admits while busy),
            // so a slow/hung describe never blocks frame acquisition / L0.
            if (admitted && inFlight.compareAndSet(false, true)) {
                val bmp = image.toBitmap() // copy out before we close the proxy
                inferenceExecutor.execute {
                    try { p.describe(bmp) } catch (t: Throwable) { Log.e(TAG, "describe failed", t) }
                    finally { inFlight.set(false); pushState() }
                }
            }
            pushState()
        } catch (t: Throwable) {
            Log.e(TAG, "analyze failed", t)
        } finally {
            image.close()
        }
    }

    private fun pushState() {
        val p = pipeline ?: return
        val snap = MonitorUi(
            backend = p.backend,
            resolution = lastRes,
            distance = p.lastDistance,
            threshold = p.threshold,
            admitted = p.lastAdmitted,
            admittedCount = p.admittedCount,
            total = p.total,
            savedPct = p.savedPct,
            caption = p.lastCaption,
            guarding = guarding,
        )
        runOnUiThread { uiState.value = snap }
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
        val p = pipeline
        inferenceExecutor.execute { try { p?.close() } catch (_: Throwable) {} }
        inferenceExecutor.shutdown()
        analysisExecutor.shutdown()
    }

    companion object {
        private const val TAG = "claustrum"
    }
}
