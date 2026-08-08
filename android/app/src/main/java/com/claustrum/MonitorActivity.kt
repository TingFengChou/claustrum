package com.claustrum

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.OrientationEventListener
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Camera
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.view.TransformExperimental
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.camera.view.transform.OutputTransform
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import com.claustrum.core.ChangeGate
import com.claustrum.core.NativeCore
import com.claustrum.camera.CameraZoomPolicy
import com.claustrum.events.NativeEventEngine
import com.claustrum.events.PoseFrame
import com.claustrum.events.PoseObservationExtractor
import com.claustrum.events.estimatedSubjectHeightPx
import com.claustrum.events.toPoseFrame
import com.claustrum.model.CaptionLog
import com.claustrum.model.DevMode
import com.claustrum.model.MediaPipeMetricsConsent
import com.claustrum.model.ModelSpec
import com.claustrum.model.ModelsController
import com.claustrum.monitor.GuardianSession
import com.claustrum.objects.DetectedObject
import com.claustrum.objects.MediaPipeObjectDetector
import com.claustrum.objects.LatestOnlyQueue
import com.claustrum.objects.ObjectCandidateGate
import com.claustrum.objects.ObjectRuntimeStats
import com.claustrum.vlm.ModelEval
import com.claustrum.ui.AppShell
import com.claustrum.ui.IntroScreen
import com.claustrum.ui.MonitorUi
import com.claustrum.ui.ObjectCandidateGeometry
import com.claustrum.ui.ObjectCandidateUi
import com.claustrum.ui.PreviewPoint
import com.claustrum.ui.SplashScreen
import com.claustrum.ui.TrackedJoint
import com.claustrum.ui.TrackedPersonUi
import com.claustrum.ui.objectCandidateSummary
import com.claustrum.ui.theme.ClaustrumTheme
import com.claustrum.vlm.Captioner
import com.claustrum.vlm.FallbackCaptioner
import com.claustrum.vlm.LiteRtCaptioner
import com.claustrum.vlm.PerceptionPipeline
import com.claustrum.vlm.PlaceholderCaptioner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import java.util.concurrent.Executors

/**
 * 即時守護 · 機器之眼 (main screen). Owns CameraX + the L0→L1 [PerceptionPipeline]
 * and renders the designed Compose UI ([LiveMonitorScreen]). L1 uses the real
 * [LiteRtCaptioner] when a vision model is present, else the placeholder.
 */
@ExperimentalGetImage
@TransformExperimental
class MonitorActivity : ComponentActivity() {

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val inferenceExecutor = Executors.newSingleThreadExecutor() // L1 off the analyzer thread
    private val objectExecutor = Executors.newSingleThreadExecutor()
    private val objectDetectorStarting = java.util.concurrent.atomic.AtomicBoolean(false)
    private val poseTaskInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    private val destroyed = java.util.concurrent.atomic.AtomicBoolean(false)
    private val foreground = java.util.concurrent.atomic.AtomicBoolean(false)
    private val overlayVersion = java.util.concurrent.atomic.AtomicLong(0L)
    private val objectOverlayVersion = java.util.concurrent.atomic.AtomicLong(0L)
    private val objectMapProbeLogged = java.util.concurrent.atomic.AtomicBoolean(false)
    private val poseFastPathEnabled = java.util.concurrent.atomic.AtomicBoolean(true)
    private val poseDetectorClosed = java.util.concurrent.atomic.AtomicBoolean(false)
    // Latest admitted frame that arrived while L1 was busy. Intermediate admitted frames
    // are intentionally coalesced: this bounds L1 work but is not an event-recall guarantee.
    private val l1Queue = LatestOnlyQueue<Bitmap> { it.recycle() }
    private val poseExtractor = PoseObservationExtractor()
    private val objectGate = ObjectCandidateGate()
    private val objectStats = ObjectRuntimeStats()
    private val objectQueue = LatestOnlyQueue<ObjectFrame> { it.bitmap.recycle() }
    private val imageProxyTransformFactory = ImageProxyTransformFactory().apply {
        isUsingRotationDegrees = true
    }
    private val poseDetectorDelegate = lazy(LazyThreadSafetyMode.NONE) {
        PoseDetection.getClient(
            PoseDetectorOptions.Builder()
                .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                .build(),
        )
    }
    private val poseDetector by poseDetectorDelegate
    @Volatile private var eventEngine: NativeEventEngine? = null
    @Volatile private var objectDetector: MediaPipeObjectDetector? = null
    @Volatile private var pipeline: PerceptionPipeline<Bitmap>? = null
    @Volatile private var previewUseCase: Preview? = null
    @Volatile private var analysisUseCase: ImageAnalysis? = null
    @Volatile private var boundCamera: Camera? = null
    @Volatile private var lastRes = "—"
    @Volatile private var trackedPeople: List<TrackedPersonUi> = emptyList()
    @Volatile private var objectCandidates: List<ObjectCandidateUi> = emptyList()
    @Volatile private var objectDetectorStatus = "物件候選未啟用 · 到模型頁查看"
    @Volatile private var objectModelCheckedAtMs = Long.MIN_VALUE
    @Volatile private var objectSubmitted = 0L
    @Volatile private var objectCoalesced = 0L
    @Volatile private var zoomRatio = 1f
    @Volatile private var minZoomRatio = 1f
    @Volatile private var maxZoomRatio = 1f
    @Volatile private var desiredZoomRatio = 1f
    private val guardian = GuardianSession()
    private val uiState = mutableStateOf(MonitorUi())
    private lateinit var previewView: PreviewView
    private val models by lazy {
        ModelsController(this, onMediaPipeConsentRevoked = ::disableObjectDetectorForConsent)
    }

    // App entry route: boot animation → (first-run) onboarding → guardian shell.
    private enum class Route { SPLASH, INTRO, SHELL }
    private val route = mutableStateOf(Route.SPLASH)
    // Developer-mode validation state (only surfaced when DevMode is on).
    private val evalRunning = mutableStateOf(false)
    private val evalSummary = mutableStateOf<ModelEval.Summary?>(null)
    private val devVideoPlaying = mutableStateOf(false)
    private val devVideoFrame = mutableStateOf<Bitmap?>(null)
    @Volatile private var l1Source = "相機" // CaptionLog source for the single-flight L1 path

    private data class ObjectFrame(
        val bitmap: Bitmap,
        val atMs: Long,
        val sourceTransform: OutputTransform,
    )

    /** Keep CameraX output upright through all four physical mounting directions. */
    private val orientationEventListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val rotation = when (orientation) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                previewUseCase?.targetRotation = rotation
                analysisUseCase?.targetRotation = rotation
            }
        }
    }

    private val requestCamera =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else {
                guardian.activationFailed("需要相機權限才能守護；允許後可再次啟動。")
                pushState()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        desiredZoomRatio = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getFloat(KEY_ZOOM_RATIO, 1f)
        DevMode.load(this)
        previewView = PreviewView(this).apply {
            // Preserve the full analysis field of view. Cropping a portrait camera into
            // the wide visor can hide a person's head/feet from the visible evidence.
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
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
                        // Manual activation: the machine eye wakes (camera starts) only
                        // when the user taps 啟動守護 — never automatically on entry.
                        onActivate = { activateGuardian() },
                        onZoomChange = { setZoomRatio(it) },
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
        if (!guardian.beginActivation()) return
        pushState()
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
     * off the inference executor. L1 coalesces intermediate samples so playback never
     * freezes on a slow describe; this tool evaluates captions, not event recall. Lets us
     * validate on real footage
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

    /** Start permission/binding work once per activation attempt. */
    private fun ensureCameraRunning() {
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
        // Start the bundled base detector before binding. STREAM_MODE is deliberately
        // single-person and low latency; Rust still owns every event threshold/state.
        try {
            poseDetector
            ensureEventEngine()
        } catch (t: Throwable) {
            // Fail closed: camera/L0/L1 remain useful, but no event is claimed when the
            // fast path cannot initialize.
            disablePoseFastPath("L2 pose fast path initialization failed", t)
        }
        ensureObjectDetector()
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val initialRotation = previewView.display?.rotation ?: Surface.ROTATION_0
                val preview = Preview.Builder()
                    .setTargetRotation(initialRotation)
                    .build()
                    .also { it.surfaceProvider = previewView.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setTargetRotation(initialRotation)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                    .also { it.setAnalyzer(analysisExecutor, ::analyze) }
                previewUseCase = preview
                analysisUseCase = analysis
                provider.unbindAll()
                val camera = provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
                boundCamera = camera
                camera.cameraInfo.zoomState.observe(this) { state ->
                    zoomRatio = state.zoomRatio
                    minZoomRatio = state.minZoomRatio
                    maxZoomRatio = state.maxZoomRatio
                    pushState()
                }
                applyDesiredZoom(camera)
                guardian.cameraBound()
                pushState()
            } catch (t: Throwable) {
                Log.e(TAG, "bindToLifecycle failed", t)
                guardian.activationFailed("相機啟動失敗:${t.message ?: t.javaClass.simpleName}；可再次嘗試。")
                pushState()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * One CameraX analyzer owns the proxy. ML Kit runs first and closes over the media
     * image; once its task completes, the same still-open proxy feeds L0/L1 and is then
     * closed exactly once. KEEP_ONLY_LATEST bounds work at detector throughput.
     */
    private fun analyze(image: ImageProxy) {
        // Reserve ownership before checking destroy so onDestroy cannot shut the executor
        // in the gap between accepting this proxy and registering ML Kit's callback.
        poseTaskInFlight.set(true)
        if (destroyed.get()) {
            finishAnalysis(image)
            return
        }
        if (!poseFastPathEnabled.get()) {
            analyzeWithoutPose(image)
            return
        }
        val mediaImage = image.image
        if (mediaImage == null) {
            try {
                processFastPath(PoseFrame(System.currentTimeMillis(), emptyMap()))
                analyzePerception(image)
            } catch (t: Throwable) {
                Log.e(TAG, "image without media payload failed", t)
                guardian.frameFailed("影格分析持續失敗:${t.message ?: t.javaClass.simpleName}")
                pushState()
            } finally {
                finishAnalysis(image)
            }
            return
        }
        val atMs = System.currentTimeMillis()
        val rotation = image.imageInfo.rotationDegrees
        val uprightWidth = if (rotation % 180 == 0) image.width else image.height
        val uprightHeight = if (rotation % 180 == 0) image.height else image.width
        try {
            val input = InputImage.fromMediaImage(mediaImage, rotation)
            poseDetector.process(input).addOnCompleteListener(analysisExecutor) { task ->
                try {
                    if (destroyed.get()) return@addOnCompleteListener
                    val frame = if (task.isSuccessful) {
                        task.result.toPoseFrame(atMs, uprightWidth, uprightHeight)
                    } else {
                        Log.w(TAG, "ML Kit pose frame failed; interrupting L2 track", task.exception)
                        PoseFrame(atMs, emptyMap())
                    }
                    val slot = processFastPath(frame)
                    publishPoseOverlay(image, frame, slot)
                    analyzePerception(image)
                } catch (t: Throwable) {
                    Log.e(TAG, "pose/L0 analysis failed", t)
                    clearPoseOverlay()
                    guardian.frameFailed("影格分析持續失敗:${t.message ?: t.javaClass.simpleName}")
                    pushState()
                } finally {
                    finishAnalysis(image)
                }
            }
        } catch (t: Throwable) {
            // A broken detector must not take the existing L0/L1 monitor down with it.
            // Disable only L2, then process this same still-open proxy without pose.
            disablePoseFastPath("pose analysis submission failed; L2 disabled", t)
            analyzeWithoutPose(image)
        }
    }

    private fun analyzeWithoutPose(image: ImageProxy) {
        try {
            analyzePerception(image)
        } catch (t: Throwable) {
            Log.e(TAG, "L0/L1 fallback analysis failed", t)
            guardian.frameFailed("影格分析持續失敗:${t.message ?: t.javaClass.simpleName}")
            pushState()
        } finally {
            finishAnalysis(image)
        }
    }

    private fun finishAnalysis(image: ImageProxy) {
        poseTaskInFlight.set(false)
        image.close()
        if (destroyed.get()) analysisExecutor.shutdown()
    }

    /** Existing L0→L1 plus bounded object-candidate route after ML Kit releases the image. */
    private fun analyzePerception(image: ImageProxy) {
        val p = pipeline ?: return
        val w = image.width
        val h = image.height
        lastRes = "${w}×${h}"
        val luma = extractLuma(image)
        val sig = NativeCore.frameSignature(luma, w, h)
        val atMs = SystemClock.uptimeMillis()
        maybeEnsureObjectDetector(atMs)
        val l1Admitted = p.admit(sig)
        val objectAdmitted = objectDetector != null && objectGate.shouldAnalyze(sig, atMs)
        if (l1Admitted || objectAdmitted) {
            val sourceTransform = if (objectAdmitted) {
                try {
                    imageProxyTransformFactory.getOutputTransform(image)
                } catch (t: Throwable) {
                    Log.w(TAG, "object overlay source transform unavailable", t)
                    null
                }
            } else {
                null
            }
            val upright = rotatedCopy(image)
            when {
                l1Admitted && sourceTransform != null -> {
                    val l1Copy = try {
                        upright.copy(Bitmap.Config.ARGB_8888, false)
                    } catch (t: Throwable) {
                        upright.recycle()
                        throw t
                    }
                    enqueueL1(l1Copy)
                    enqueueObject(ObjectFrame(upright, atMs, sourceTransform))
                }
                l1Admitted -> enqueueL1(upright)
                sourceTransform != null -> enqueueObject(ObjectFrame(upright, atMs, sourceTransform))
                else -> upright.recycle()
            }
        }
        guardian.frameProcessed()
        pushState()
    }

    /** Pick up a model downloaded from the Models tab without restarting the camera. */
    private fun maybeEnsureObjectDetector(atMs: Long) {
        if (objectModelCheckedAtMs != Long.MIN_VALUE &&
            atMs - objectModelCheckedAtMs < OBJECT_MODEL_CHECK_MS
        ) return
        objectModelCheckedAtMs = atMs
        if (!MediaPipeMetricsConsent.isGranted(this)) {
            disableObjectDetectorForConsent()
        } else if (objectDetector == null && !objectDetectorStarting.get()) {
            ensureObjectDetector()
        }
    }

    /** MediaPipe objects stay on their own executor; create/use/close are serialized. */
    private fun ensureObjectDetector() {
        if (destroyed.get() || objectDetector != null ||
            !objectDetectorStarting.compareAndSet(false, true)
        ) return
        if (!MediaPipeMetricsConsent.isGranted(this)) {
            objectDetectorStarting.set(false)
            objectDetectorStatus = "物件候選未啟用 · 到模型頁查看"
            pushState()
            return
        }
        val spec = ModelSpec.EFFICIENTDET_LITE2_OBJECTS
        if (!spec.isPresent(this)) {
            objectDetectorStarting.set(false)
            objectDetectorStatus = "物件模型未下載 · 到模型頁下載 7.5 MB"
            pushState()
            return
        }
        objectDetectorStatus = "物件模型載入中…"
        pushState()
        objectExecutor.execute {
            var created: MediaPipeObjectDetector? = null
            try {
                created = MediaPipeObjectDetector(applicationContext, spec.localFile(this))
                if (destroyed.get() || !MediaPipeMetricsConsent.isGranted(this)) {
                    created.close()
                    objectDetectorStatus = "物件候選未啟用 · 到模型頁查看"
                } else {
                    objectStats.reset()
                    objectMapProbeLogged.set(false)
                    objectSubmitted = 0L
                    objectCoalesced = 0L
                    objectDetector = created
                    objectDetectorStatus = "物件候選就緒 · 動態閘門"
                    created = null
                }
            } catch (t: Throwable) {
                Log.e(TAG, "MediaPipe object detector initialization failed", t)
                objectDetectorStatus = "物件候選停用:${t.message ?: t.javaClass.simpleName}"
            } finally {
                try { created?.close() } catch (_: Throwable) {}
                objectDetectorStarting.set(false)
                pushState()
            }
        }
    }

    /** Consent withdrawal stops new submissions before serialized close. */
    private fun disableObjectDetectorForConsent() {
        val detector = objectDetector ?: run {
            val status = "物件候選未啟用 · 到模型頁查看"
            if (objectDetectorStatus != status || objectCandidates.isNotEmpty()) {
                objectDetectorStatus = status
                clearObjectOverlay()
                pushState()
            }
            return
        }
        objectDetector = null
        objectDetectorStatus = "物件候選已停止 · 未同意效能統計"
        objectQueue.clear()
        clearObjectOverlay()
        pushState()
        objectExecutor.execute { try { detector.close() } catch (_: Throwable) {} }
    }

    /** Current + latest pending queue: bounded memory and no stale detector backlog. */
    private fun enqueueObject(frame: ObjectFrame) {
        if (destroyed.get() || objectDetector == null) {
            frame.bitmap.recycle()
            return
        }
        objectSubmitted += 1
        val offer = objectQueue.offer(frame)
        if (offer.replacedPending) objectCoalesced += 1
        if (offer.startWorker) runObjectDetector()
    }

    private fun runObjectDetector() {
        try {
            objectExecutor.execute {
                while (true) {
                    val frame = objectQueue.takeOrReleaseWorker() ?: return@execute
                    val startedAt = SystemClock.uptimeMillis()
                    try {
                        val detected = objectDetector?.detect(frame.bitmap, frame.atMs).orEmpty()
                        val latencyMs = SystemClock.uptimeMillis() - startedAt
                        val stats = objectStats.record(latencyMs)
                        if (stats.processed % OBJECT_STATS_LOG_INTERVAL == 0L) {
                            Log.i(
                                TAG,
                                "OBJECT_STATS processed=${stats.processed} " +
                                    "p50_ms=${stats.p50LatencyMs} p95_ms=${stats.p95LatencyMs} " +
                                    "max_ms=${stats.maxLatencyMs} submitted=$objectSubmitted " +
                                    "coalesced=$objectCoalesced",
                            )
                        }
                        // Withdrawal may race an already-running native call. Never let its
                        // late result restore an overlay after consent has been revoked.
                        if (objectDetector != null && MediaPipeMetricsConsent.isGranted(this)) {
                            publishObjectOverlay(
                            sourceTransform = frame.sourceTransform,
                            detections = detected,
                            latencyMs = latencyMs,
                            inputWidth = frame.bitmap.width,
                            inputHeight = frame.bitmap.height,
                            )
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "MediaPipe object frame failed", t)
                        objectDetectorStatus = "物件候選本幀失敗:${t.message ?: t.javaClass.simpleName}"
                        clearObjectOverlay()
                        pushState()
                    } finally {
                        frame.bitmap.recycle()
                    }
                }
            }
        } catch (rejected: java.util.concurrent.RejectedExecutionException) {
            objectQueue.clear()
            if (!destroyed.get()) throw rejected
        }
    }

    /** Map all four rectangle corners so rotation, crop and zoom stay aligned. */
    private fun publishObjectOverlay(
        sourceTransform: OutputTransform,
        detections: List<DetectedObject>,
        latencyMs: Long,
        inputWidth: Int,
        inputHeight: Int,
    ) {
        val version = objectOverlayVersion.incrementAndGet()
        previewView.post {
            if (objectOverlayVersion.get() != version) return@post
            val targetTransform = previewView.outputTransform
            val viewWidth = previewView.width
            val viewHeight = previewView.height
            if (targetTransform == null || viewWidth <= 0 || viewHeight <= 0 ||
                destroyed.get() || !foreground.get()
            ) {
                applyObjectOverlay(emptyList(), "物件候選暫停 · 預覽不可用", version)
                return@post
            }
            try {
                val transform = CoordinateTransform(sourceTransform, targetTransform)
                if (BuildConfig.DEBUG && objectMapProbeLogged.compareAndSet(false, true)) {
                    val fullFrame = floatArrayOf(0f, 0f, inputWidth.toFloat(), inputHeight.toFloat())
                    transform.mapPoints(fullFrame)
                    Log.i(
                        TAG,
                        "OBJECT_MAP_PROBE input=${inputWidth}x$inputHeight " +
                            "view=${viewWidth}x$viewHeight full=${fullFrame.joinToString()}",
                    )
                }
                val mapped = detections.mapNotNull { detection ->
                    val corners = floatArrayOf(
                        detection.leftPx, detection.topPx,
                        detection.rightPx, detection.topPx,
                        detection.rightPx, detection.bottomPx,
                        detection.leftPx, detection.bottomPx,
                    )
                    transform.mapPoints(corners)
                    val xs = floatArrayOf(corners[0], corners[2], corners[4], corners[6])
                    val ys = floatArrayOf(corners[1], corners[3], corners[5], corners[7])
                    val bounds = ObjectCandidateGeometry.normalizedBounds(
                        leftPx = xs.min(),
                        topPx = ys.min(),
                        rightPx = xs.max(),
                        bottomPx = ys.max(),
                        viewWidth = viewWidth,
                        viewHeight = viewHeight,
                    ) ?: return@mapNotNull null
                    ObjectCandidateUi(detection.category, detection.score, bounds)
                }
                val queue = if (objectCoalesced == 0L) "" else {
                    " · 合併 $objectCoalesced/$objectSubmitted"
                }
                applyObjectOverlay(mapped, objectCandidateSummary(mapped, latencyMs) + queue, version)
            } catch (t: Throwable) {
                Log.w(TAG, "object overlay coordinate transform failed", t)
                applyObjectOverlay(emptyList(), "物件候選映射失敗", version)
            }
        }
    }

    private fun applyObjectOverlay(candidates: List<ObjectCandidateUi>, status: String, version: Long) {
        if (objectOverlayVersion.get() != version) return
        objectCandidates = candidates
        objectDetectorStatus = status
        if (destroyed.get()) return
        runOnUiThread {
            if (!destroyed.get() && objectOverlayVersion.get() == version) {
                uiState.value = uiState.value.copy(
                    objectCandidates = candidates,
                    objectDetectorStatus = status,
                )
            }
        }
    }

    private fun clearObjectOverlay() {
        val version = objectOverlayVersion.incrementAndGet()
        applyObjectOverlay(emptyList(), objectDetectorStatus, version)
    }

    private fun ensureEventEngine() {
        if (eventEngine == null) eventEngine = NativeEventEngine(L2_SOURCE_ID)
    }

    private fun processFastPath(frame: PoseFrame): Int? {
        val observation = poseExtractor.extract(frame)
        val visibleSlot = observation.actant.takeIf { observation.visiblePeople > 0 }
        val engine = eventEngine ?: return visibleSlot
        try {
            // Policy/UI/notification intentionally remain disconnected until recorded
            // footage calibration. Structured event text is safe to log; pixels are not.
            engine.process(observation).forEach { eventJson -> Log.i(TAG, "L2_EVENT $eventJson") }
        } catch (t: Throwable) {
            disablePoseFastPath("Rust L2 event engine disabled after processing failure", t)
            return null
        }
        return visibleSlot
    }

    /**
     * Maps upright ML Kit landmark coordinates into the actual visible PreviewView.
     * CameraX owns rotation/crop/scale math; the UI receives only normalized, pixel-free
     * points. The list contract is multi-person-ready, while today's detector emits one.
     */
    private fun publishPoseOverlay(image: ImageProxy, frame: PoseFrame, slot: Int?) {
        val version = overlayVersion.incrementAndGet()
        if (slot == null || frame.points.isEmpty()) {
            applyPoseOverlay(emptyList(), version)
            return
        }
        val sourceTransform = try {
            imageProxyTransformFactory.getOutputTransform(image)
        } catch (t: Throwable) {
            Log.w(TAG, "pose overlay source transform unavailable", t)
            applyPoseOverlay(emptyList(), version)
            return
        }
        val rotation = image.imageInfo.rotationDegrees
        val uprightWidth = if (rotation % 180 == 0) image.width else image.height
        val uprightHeight = if (rotation % 180 == 0) image.height else image.width
        val sourcePoints = frame.points.mapValues { (_, point) ->
            floatArrayOf(point.x * uprightWidth, point.y * uprightHeight, point.likelihood)
        }
        val subjectHeightPx = frame.estimatedSubjectHeightPx(uprightHeight)
        previewView.post {
            if (overlayVersion.get() != version) return@post
            val targetTransform = previewView.outputTransform
            val viewWidth = previewView.width
            val viewHeight = previewView.height
            if (targetTransform == null || viewWidth <= 0 || viewHeight <= 0 ||
                destroyed.get() || !foreground.get()
            ) {
                applyPoseOverlay(emptyList(), version)
                return@post
            }
            try {
                val transform = CoordinateTransform(sourceTransform, targetTransform)
                val mapped = buildMap {
                    sourcePoints.forEach { (joint, source) ->
                        val xy = floatArrayOf(source[0], source[1])
                        transform.mapPoints(xy)
                        put(
                            joint.toTrackedJoint(),
                            PreviewPoint(
                                x = xy[0] / viewWidth,
                                y = xy[1] / viewHeight,
                                likelihood = source[2],
                            ),
                        )
                    }
                }
                applyPoseOverlay(
                    listOf(
                        TrackedPersonUi(
                            slot = slot,
                            points = mapped,
                            subjectHeightPx = subjectHeightPx,
                        ),
                    ),
                    version,
                )
            } catch (t: Throwable) {
                Log.w(TAG, "pose overlay coordinate transform failed", t)
                applyPoseOverlay(emptyList(), version)
            }
        }
    }

    private fun com.claustrum.events.PoseJoint.toTrackedJoint(): TrackedJoint = when (this) {
        com.claustrum.events.PoseJoint.LEFT_SHOULDER -> TrackedJoint.LEFT_SHOULDER
        com.claustrum.events.PoseJoint.RIGHT_SHOULDER -> TrackedJoint.RIGHT_SHOULDER
        com.claustrum.events.PoseJoint.LEFT_HIP -> TrackedJoint.LEFT_HIP
        com.claustrum.events.PoseJoint.RIGHT_HIP -> TrackedJoint.RIGHT_HIP
        com.claustrum.events.PoseJoint.LEFT_KNEE -> TrackedJoint.LEFT_KNEE
        com.claustrum.events.PoseJoint.RIGHT_KNEE -> TrackedJoint.RIGHT_KNEE
        com.claustrum.events.PoseJoint.LEFT_ANKLE -> TrackedJoint.LEFT_ANKLE
        com.claustrum.events.PoseJoint.RIGHT_ANKLE -> TrackedJoint.RIGHT_ANKLE
    }

    private fun applyPoseOverlay(people: List<TrackedPersonUi>, version: Long) {
        if (overlayVersion.get() != version) return
        val changed = trackedPeople != people
        trackedPeople = people
        if (!changed || destroyed.get()) return
        runOnUiThread {
            if (!destroyed.get() && overlayVersion.get() == version) {
                uiState.value = uiState.value.copy(trackedPeople = people)
            }
        }
    }

    private fun clearPoseOverlay() {
        val version = overlayVersion.incrementAndGet()
        applyPoseOverlay(emptyList(), version)
    }

    private fun setZoomRatio(requested: Float) {
        desiredZoomRatio = CameraZoomPolicy.clamp(
            requested = requested,
            min = minZoomRatio,
            max = maxZoomRatio,
            fallback = desiredZoomRatio,
        )
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putFloat(KEY_ZOOM_RATIO, desiredZoomRatio)
            .apply()
        boundCamera?.let(::applyDesiredZoom)
    }

    private fun applyDesiredZoom(camera: Camera) {
        val state = camera.cameraInfo.zoomState.value ?: return
        val clamped = CameraZoomPolicy.clamp(
            requested = desiredZoomRatio,
            min = state.minZoomRatio,
            max = state.maxZoomRatio,
            fallback = state.zoomRatio,
        )
        desiredZoomRatio = clamped
        val future = camera.cameraControl.setZoomRatio(clamped)
        future.addListener({
            try {
                future.get()
            } catch (t: Throwable) {
                // A newer zoom request or lifecycle close legitimately cancels the old
                // future. Zoom is a commissioning aid; perception must continue.
                Log.d(TAG, "zoom request not applied: ${t.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun disablePoseFastPath(message: String, cause: Throwable? = null) {
        if (!poseFastPathEnabled.compareAndSet(true, false)) return
        if (cause == null) Log.i(TAG, message) else Log.e(TAG, message, cause)
        val engine = eventEngine
        eventEngine = null
        engine?.close()
        poseExtractor.reset()
        clearPoseOverlay()
        closePoseDetector()
    }

    private fun closePoseDetector() {
        if (poseDetectorDelegate.isInitialized() && poseDetectorClosed.compareAndSet(false, true)) {
            poseDetector.close()
        }
    }

    /**
     * Hand an admitted frame to L1. Single-flight: if inference is already running,
     * the frame is kept as the (replaceable) latest pending frame and processed as
     * soon as the current one finishes. Intermediate admitted frames can be replaced;
     * L1 stays bounded and never runs on the analyzer thread.
     */
    private fun enqueueL1(bmp: Bitmap) {
        if (destroyed.get()) {
            bmp.recycle()
            return
        }
        if (l1Queue.offer(bmp).startWorker) runL1()
    }

    private fun runL1() {
        try {
            inferenceExecutor.execute {
                while (true) {
                    val cur = l1Queue.takeOrReleaseWorker() ?: return@execute
                    val t0 = System.currentTimeMillis()
                    var r = ""
                    try {
                        r = pipeline?.describe(cur) ?: ""
                    } catch (t: Throwable) {
                        Log.e(TAG, "describe failed", t)
                    } finally {
                        cur.recycle()
                    }
                    CaptionLog.add(
                        System.currentTimeMillis(),
                        r.ifBlank { "(此幀模型未產生有效描述)" },
                        l1Source,
                        System.currentTimeMillis() - t0,
                    )
                    pushState()
                }
            }
        } catch (rejected: java.util.concurrent.RejectedExecutionException) {
            l1Queue.clear()
            if (!destroyed.get()) throw rejected
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
        val guard = guardian.snapshot()
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
            active = guard.active,
            guarding = guard.guarding,
            statusError = guard.error,
            trackedPeople = trackedPeople,
            objectCandidates = objectCandidates,
            objectDetectorStatus = objectDetectorStatus,
            zoomRatio = zoomRatio,
            minZoomRatio = minZoomRatio,
            maxZoomRatio = maxZoomRatio,
        )
        // Projection is posted independently on the main thread. Merge the latest
        // overlay at apply time so an older telemetry snapshot cannot erase it.
        runOnUiThread {
            uiState.value = snap.copy(
                trackedPeople = trackedPeople,
                objectCandidates = objectCandidates,
                objectDetectorStatus = objectDetectorStatus,
            )
        }
    }

    private fun extractLuma(image: ImageProxy): ByteArray {
        val w = image.width
        val h = image.height
        val plane = image.planes[0]
        // Isolate position/limit from the SDK-owned buffer and reset both in case an
        // earlier consumer changed its view before this completion callback runs.
        val buffer = plane.buffer.duplicate().apply { clear() }
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
        destroyed.set(true)
        super.onDestroy()
        disablePoseFastPath("L2 pose fast path closed")
        val p = pipeline
        inferenceExecutor.execute { try { p?.close() } catch (_: Throwable) {} }
        inferenceExecutor.shutdown()
        // Do not reject the registered ML Kit completion callback: it owns the only
        // in-flight ImageProxy and will close it before shutting down this executor.
        if (!poseTaskInFlight.get()) analysisExecutor.shutdown()
        l1Queue.clear()
        objectQueue.clear()
        val detector = objectDetector
        objectDetector = null
        objectExecutor.execute {
            try { detector?.close() } catch (_: Throwable) {}
        }
        objectExecutor.shutdown()
    }

    override fun onStart() {
        super.onStart()
        foreground.set(true)
        orientationEventListener.enable()
        previewView.post { boundCamera?.let(::applyDesiredZoom) }
    }

    override fun onStop() {
        foreground.set(false)
        orientationEventListener.disable()
        clearPoseOverlay()
        clearObjectOverlay()
        objectGate.reset()
        super.onStop()
    }

    companion object {
        private const val TAG = "claustrum"
        private const val PREFS = "claustrum.prefs"
        private const val KEY_ONBOARDED = "onboarded"
        private const val KEY_ZOOM_RATIO = "camera.zoom_ratio"
        private const val L2_SOURCE_ID = "camera_back"
        private const val OBJECT_MODEL_CHECK_MS = 2_000L
        private const val OBJECT_STATS_LOG_INTERVAL = 20L
    }
}
