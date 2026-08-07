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
import com.claustrum.model.CaptionLog
import com.claustrum.model.DevMode
import com.claustrum.model.ModelSpec
import com.claustrum.model.ModelsController
import com.claustrum.vlm.ModelEval
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

    // Developer-mode validation state (only surfaced when DevMode is on).
    private val evalRunning = mutableStateOf(false)
    private val evalSummary = mutableStateOf<ModelEval.Summary?>(null)
    private val devVideoPlaying = mutableStateOf(false)
    private val devVideoFrame = mutableStateOf<Bitmap?>(null)
    @Volatile private var l1Source = "相機" // CaptionLog source for the single-flight L1 path

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
        DevMode.load(this)
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
                        dev = com.claustrum.ui.DevUi(
                            enabled = DevMode.enabled.value,
                            onToggle = { DevMode.set(this, it) },
                            evalRunning = evalRunning.value,
                            evalSummary = evalSummary.value,
                            onRunEval = { runModelEval() },
                            videoPlaying = devVideoPlaying.value,
                            videoFrame = devVideoFrame.value,
                            onPlayVideo = { playDevVideo() },
                        ),
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

    // ---- Developer-mode validation tooling (never affects production paths) ------

    /**
     * Run the L1 model-eval over labelled frames in `<externalFiles>/dev_eval/`
     * (filename convention `<label>__<kw1>,<kw2>.jpg`). Each frame → L1 → keyword
     * score + latency; results recorded to [CaptionLog] and aggregated into
     * [evalSummary]. Repeatable, so a model swap always gets a basic validation.
     */
    private fun runModelEval() {
        if (evalRunning.value) return
        val dir = java.io.File(getExternalFilesDir(null), "dev_eval")
        val imgs = dir.listFiles()
            ?.filter { it.extension.lowercase() in setOf("jpg", "jpeg", "png") }
            ?.sortedBy { it.name }
        if (imgs.isNullOrEmpty()) {
            uiState.value = uiState.value.copy(caption = "dev_eval/ 內無標註影格(檔名如 fall__倒臥,跌倒.jpg)")
            return
        }
        evalRunning.value = true
        inferenceExecutor.execute {
            val p = pipeline
            val results = ArrayList<ModelEval.CaseResult>()
            try {
                for (f in imgs) {
                    val bmp = android.graphics.BitmapFactory.decodeFile(f.absolutePath) ?: continue
                    val case = ModelEval.caseFromFileName(f.name)
                    val t0 = System.currentTimeMillis()
                    var caption = ""
                    try { caption = p?.describe(bmp) ?: "" } catch (t: Throwable) { Log.e(TAG, "eval ${f.name} failed", t) }
                    finally { bmp.recycle() }
                    val dt = System.currentTimeMillis() - t0
                    val r = ModelEval.evaluate(caption, dt, case)
                    results.add(r)
                    CaptionLog.add(System.currentTimeMillis(), caption.ifBlank { "(無有效描述)" }, "驗證:${case.label}${if (r.pass) " ✓" else " ✗"}", dt)
                }
                val s = ModelEval.summarize(results)
                Log.i(TAG, "MODELEVAL ${p?.backend} pass=${s.passed}/${s.total} (${"%.0f".format(s.passRate)}%) avg=${s.avgLatencyMs}ms p50=${s.p50LatencyMs}ms")
                runOnUiThread { evalSummary.value = s }
            } finally {
                evalRunning.value = false
            }
        }
    }

    /**
     * Play test videos from `<externalFiles>/dev_videos/` through the pipeline. The
     * display advances on its own thread (smooth) while L1 samples frames single-flight
     * off the inference executor — same "不漏球但不卡住" discipline as the camera, so
     * playback never freezes on a 6.5s describe. Lets us validate on real footage
     * without physically aiming the camera.
     */
    private fun playDevVideo() {
        if (devVideoPlaying.value) return
        val dir = java.io.File(getExternalFilesDir(null), "dev_videos")
        val vids = dir.listFiles()
            ?.filter { it.extension.lowercase() in setOf("mp4", "webm", "mkv", "3gp") }
            ?.sortedBy { it.name }
        if (vids.isNullOrEmpty()) {
            uiState.value = uiState.value.copy(caption = "dev_videos/ 內無影片(mp4);請先 push 測試影片")
            return
        }
        devVideoPlaying.value = true
        Thread({
            try {
                for (v in vids) { if (!devVideoPlaying.value) break; playbackLoop(v) }
            } finally {
                l1Source = "相機"
                devVideoPlaying.value = false
                runOnUiThread { devVideoFrame.value = null }
            }
        }, "dev-video").apply { isDaemon = true }.start()
    }

    /** Advance [video] frames on this thread (display); sample to L1 every ~1.5s of video. */
    private fun playbackLoop(video: java.io.File) {
        val mmr = android.media.MediaMetadataRetriever()
        try {
            mmr.setDataSource(video.absolutePath)
            val durMs = mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return
            l1Source = "影片:${video.name}"
            val displayStepMs = 150L   // ~6.7 fps display
            val l1EveryMs = 1500L      // sample L1 at most every 1.5s of video
            var t = 0L
            var lastL1 = -l1EveryMs
            while (t < durMs && devVideoPlaying.value) {
                val frame = mmr.getFrameAtTime(t * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST) ?: break
                runOnUiThread { devVideoFrame.value = frame }   // display (never recycled here)
                if (t - lastL1 >= l1EveryMs) {
                    lastL1 = t
                    // Full frame (copied so the displayed frame is never recycled under
                    // Compose). NOTE: a centre-crop was tried to enlarge far subjects but
                    // it cut OFF-centre subjects (dashcam fall was left-of-centre) and the
                    // model then hallucinated — without subject detection a blind crop is
                    // not a reliable fix. Real answer: frame the subject via camera
                    // placement, and detect events in L2 (see docs/design/vlm/SD.md §8).
                    val copy = frame.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                    val luma = lumaOf(copy)
                    val sig = NativeCore.frameSignature(luma, copy.width, copy.height)
                    if (pipeline?.admit(sig) == true) enqueueL1(copy) else copy.recycle()
                }
                Thread.sleep(displayStepMs)
                t += displayStepMs
            }
        } catch (th: Throwable) {
            Log.e(TAG, "playbackLoop failed", th)
        } finally {
            try { mmr.release() } catch (_: Throwable) {}
        }
    }

    /** Grayscale luma bytes from an ARGB bitmap (for the L0 signature). */
    private fun lumaOf(bmp: Bitmap): ByteArray {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val out = ByteArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            out[i] = ((r * 77 + g * 150 + b * 29) shr 8).toByte()
        }
        return out
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
                val t0 = System.currentTimeMillis()
                var r = ""
                try { r = pipeline?.describe(cur) ?: "" } catch (t: Throwable) { Log.e(TAG, "describe failed", t) }
                finally { cur.recycle() }
                CaptionLog.add(System.currentTimeMillis(), r.ifBlank { "(此幀模型未產生有效描述)" }, l1Source, System.currentTimeMillis() - t0)
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
