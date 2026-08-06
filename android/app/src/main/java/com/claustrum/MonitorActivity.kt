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
import com.claustrum.model.ModelsController
import com.claustrum.ui.AppShell
import com.claustrum.ui.IntroScreen
import com.claustrum.ui.MonitorUi
import com.claustrum.ui.SplashScreen
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
    // Latest admitted frame that arrived while L1 was busy — drained when it finishes,
    // so a scene change during inference is never lost (ChangeGate.prev already advanced).
    private val pending = java.util.concurrent.atomic.AtomicReference<Bitmap?>(null)
    @Volatile private var pipeline: PerceptionPipeline<Bitmap>? = null
    @Volatile private var guarding = false
    @Volatile private var lastRes = "—"
    private val uiState = mutableStateOf(MonitorUi())
    private lateinit var previewView: PreviewView
    private val models by lazy { ModelsController(this) }

    // App entry route: boot animation → (first-run) onboarding → guardian shell.
    private enum class Route { SPLASH, INTRO, SHELL }
    private val route = mutableStateOf(Route.SPLASH)
    private var cameraStarted = false
    // The guardian is armed only when the user deliberately activates it — the camera
    // preview does NOT start on entering the shell (the machine eye stays in standby).
    private val guardActive = mutableStateOf(false)

    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else {
                guardActive.value = false // fall back to standby so the user can retry
                uiState.value = uiState.value.copy(caption = "需要相機權限才能守護。")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        previewView = PreviewView(this)
        setContent {
            // Signature Tesla/Optimus look is dark graphite — force it (guardian instrument),
            // don't follow the system light theme (design: docs/design/ui).
            ClaustrumTheme(darkTheme = true) {
                when (route.value) {
                    Route.SPLASH -> SplashScreen(onDone = {
                        route.value = if (isOnboarded()) Route.SHELL else Route.INTRO
                    })
                    Route.INTRO -> IntroScreen(onFinish = {
                        markOnboarded()
                        route.value = Route.SHELL
                    })
                    // Single-Activity shell: 守護 / 事件 / 模型 / 設定 behind a bottom nav
                    // (no Activity transitions, no dead-ends).
                    Route.SHELL -> AppShell(
                        monitorUi = uiState.value,
                        previewView = previewView,
                        models = models,
                        guardActive = guardActive.value,
                        // Manual activation: the machine eye wakes (camera starts) only
                        // when the user taps 啟動守護 — never automatically on entry.
                        onActivate = { activateGuardian() },
                    )
                }
            }
        }
        // Warm up perception while the splash/onboarding is on screen: L0 + a
        // placeholder L1 are cheap to construct; the real (possibly multi-GB) vision
        // backend is built on the inference thread and swapped in once ready — never
        // on the CameraX analyzer thread.
        pipeline = PerceptionPipeline(ChangeGate(threshold = 8), PlaceholderCaptioner)
        inferenceExecutor.execute {
            val real = buildCaptioner()
            pipeline?.swapCaptioner(real)
            pushState()
        }
    }

    /** User tapped 啟動守護 — arm the guardian and wake the camera (once). */
    private fun activateGuardian() {
        if (guardActive.value) return
        guardActive.value = true
        ensureCameraRunning()
    }

    /** Start the camera exactly once, when the guardian is activated. */
    private fun ensureCameraRunning() {
        if (cameraStarted) return
        cameraStarted = true
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera() else requestCamera.launch(Manifest.permission.CAMERA)
    }

    private fun isOnboarded(): Boolean =
        getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_ONBOARDED, false)

    private fun markOnboarded() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ONBOARDED, true).apply()
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
            val p = pipeline ?: return
            val w = image.width
            val h = image.height
            lastRes = "${w}×${h}"
            val luma = extractLuma(image)
            val sig = NativeCore.frameSignature(luma, w, h)
            val admitted = p.admit(sig) // fast L0 on this (analyzer) thread
            guarding = true
            // Only on a scene change: copy the frame out (rotated upright per sensor
            // metadata) BEFORE we close the proxy, then hand it to the L1 executor.
            if (admitted) enqueueL1(rotatedCopy(image))
            pushState()
        } catch (t: Throwable) {
            Log.e(TAG, "analyze failed", t)
        } finally {
            image.close()
        }
    }

    /**
     * Hand an admitted frame to L1. Single-flight: if inference is already running,
     * the frame is kept as the (replaceable) latest pending frame and processed as
     * soon as the current one finishes — so a scene change during a slow describe is
     * never dropped, and L1 never runs on the analyzer thread.
     */
    private fun enqueueL1(bmp: Bitmap) {
        if (inFlight.compareAndSet(false, true)) {
            runL1(bmp)
        } else {
            pending.getAndSet(bmp)?.recycle() // replace stale pending, free it
        }
    }

    private fun runL1(first: Bitmap) {
        inferenceExecutor.execute {
            var cur: Bitmap? = first
            while (cur != null) {
                try { pipeline?.describe(cur) } catch (t: Throwable) { Log.e(TAG, "describe failed", t) }
                finally { cur.recycle() }
                pushState()
                cur = pending.getAndSet(null)
                if (cur == null) {
                    inFlight.set(false)
                    // A producer may have queued a frame after we read null but before
                    // clearing the flag; reclaim it (else it would sit forever).
                    cur = pending.getAndSet(null)
                    if (cur != null && !inFlight.compareAndSet(false, true)) {
                        pending.getAndSet(cur)?.recycle() // another flight won; hand it back
                        cur = null
                    }
                }
            }
        }
    }

    /** Copy the proxy to an upright Bitmap (CameraX reports rotation via metadata). */
    private fun rotatedCopy(image: ImageProxy): Bitmap {
        val bmp = image.toBitmap()
        val deg = image.imageInfo.rotationDegrees
        if (deg == 0) return bmp
        val m = android.graphics.Matrix().apply { postRotate(deg.toFloat()) }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (rotated !== bmp) bmp.recycle()
        return rotated
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
        pending.getAndSet(null)?.recycle()
    }

    companion object {
        private const val TAG = "claustrum"
        private const val PREFS = "claustrum.prefs"
        private const val KEY_ONBOARDED = "onboarded"
    }
}
