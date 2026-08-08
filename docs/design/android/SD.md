# android(Kotlin 裝置外殼)— 系統設計(SD)

**狀態:** active · **最後更新:** 2026-08-08 · **負責人:** claustrum
**分析:** [`SA.md`](SA.md)

## 1. 概觀

`android/` 是單一 `MonitorActivity` 的原生 Kotlin/Compose App。Activity 持有 CameraX 與
L0→L1 管線；Compose 只渲染 immutable `MonitorUi`。L0 的 aHash 在 Rust，L1 使用
`litertlm-android` 與 `.litertlm` 原生 Gemma 3n。事件判斷不放在 L1，而由 `core-rs` L2
接手。

## 2. 版本矩陣

| 項目 | 版本 |
|---|---|
| Gradle / AGP | 9.3.1 / 8.12.0 |
| Kotlin / Compose BOM | 2.2.10 / 2026.02.00 |
| compileSdk / targetSdk / minSdk | 36 / 36 / 26 |
| NDK / ABI | 27.1.12297006 / arm64-v8a |
| CameraX / WorkManager | 1.4.1 / 2.9.1 |
| ML Kit Pose Detection | 18.0.0-beta5(base bundled model) |
| MediaPipe Tasks Vision | 0.10.35(Object Detector) |
| LiteRT-LM / Lottie | 0.11.0 / 6.6.6 |

## 3. 元件

| 元件 | 職責 |
|---|---|
| `MonitorActivity` | route、CameraX、pose→L2／object candidate／L0→L1 排程、dev 工具、生命週期 |
| `GuardianSession` | 可測的啟動/首幀/分析健康／明確停止狀態；錯誤後重試與自動恢復 |
| `PerceptionPipeline` | L0 統計、可替換 Captioner、最後有效描述 |
| `LiteRtCaptioner` | 初始化 delegate、每幀新 Conversation、有界串流輸出、資源釋放 |
| `FallbackCaptioner` | 真後端首次 timeout/error 後降級為誠實 placeholder |
| `FastPathObservation` | pixel-free L2 輸入；匿名 slot、pose、motion/action scores 與 timestamp |
| `MlKitPoseFrameAdapter` | 把 ML Kit 的 8 個必要 landmark 正規化；ML Kit 型別不進核心邏輯/JNI |
| `PoseObservationExtractor` | host-test 的跨幀 pose/descent/motion 特徵；追蹤跳位時輪替匿名 slot |
| `PersonTrackingOverlay` | multi-person-ready 的 Compose Canvas；只畫匿名人物框與取景像素提示，骨架保留為內部 fall 特徵 |
| `PersonOverlayGeometry` | host-test 的 confidence filter、head/side padding 與 preview bounds clipping |
| `ObjectCandidateGate` | Rust aHash 的獨立排程器；變化 active window + 靜態 periodic probe，不作事件證據 |
| `LatestOnlyQueue` | L1/object 共用：producer 只覆蓋 latest pending、單一 worker drain；避免 handoff 將舊幀蓋回新幀 |
| `MediaPipeObjectDetector` | pinned EfficientDet-Lite2 `VIDEO` 邊界；allowlist/score/bbox，不含事件判斷 |
| `AnonymousObjectTracker` | 同類別 normalized bbox 幾何 association、session-local P/O 槽位與 motion；不用身分／外觀特徵 |
| `LitterEvidenceTracker` | 連續近接→可見分離→分離後靜置→人離開 pending-review；不建 Event |
| `ObjectRuntimeStats` | 最近 120 幀的 p50/p95/max 有界診斷 window；每 20 幀寫本機 log，不影響事件證據 |
| `ObjectEvalManifest` / `ObjectEval` | strict anonymous bbox manifest + TP/FP/FN、P/R、IoU、min-pixel、latency 聚合；只供 dev commissioning |
| `ObjectCandidateOverlay` | CameraX 映射後的本機橘色候選框與 category/P-O 槽位/motion/evidence/latency/合併 telemetry |
| `CameraZoomPolicy` | host-test 的裝置 zoom range clamp 與 0.5× UI step |
| `NativeEventEngine` | 擁有一個 Rust L2 opaque handle；同步 process/close，返回單筆 Event JSON 清單 |
| `ModelsController` | 模型目錄、HF token、MediaPipe opt-in、WorkManager 下載狀態 |
| `AppShell` | 守護/事件/模型/設定四個 Compose tab；啟動期間固定顯示全域相機狀態與停止控制 |

## 4. 執行緒與資料流

```text
CameraX analysisExecutor
  ImageProxy(YUV) → ML Kit base PoseDetector(STREAM_MODE)
  → normalized PoseFrame → PoseObservationExtractor → NativeEventEngine/JNI → Rust EventEngine
  → ImageProxyTransformFactory → main thread CoordinateTransform(Analysis→PreviewView)
    → List<TrackedPersonUi> → Compose anonymous bbox + subject-pixel commissioning hint
  → 同一個仍開啟的 proxy:緊密 luma → NativeCore.frameSignature → ChangeGate.admit
  → 同一 signature → ObjectCandidateGate(change window / periodic probe)
  → L1/object 任一放行時:toBitmap + rotationDegrees 旋正
      → L1:enqueueL1
      → object:建立獨立 copy/所有權 → enqueueObject
  → ML Kit task completion finally ImageProxy.close

inferenceExecutor(single thread)
  初始化 LiteRT Engine → swapCaptioner
  admitted Bitmap → describe → recycle → drain 最新 pending Bitmap

objectExecutor(single thread)
  consent + verified model → MediaPipe ObjectDetector(VIDEO)
  current Bitmap → category/score/bbox → AnonymousObjectTracker → LitterEvidenceTracker
  → recycle → drain 最新 pending Bitmap
  → main thread CoordinateTransform(Analysis→PreviewView) → ObjectCandidateUi

  [守護已停止 + dev button]
  external-files/dev_object_eval/manifest.json + images
  → separate MediaPipe ObjectDetector(VIDEO) → normalized detections
  → ObjectEval confidence-greedy same-category IoU≥0.5
  → RAM Summary / Compose + aggregate-only local log → recycle Bitmap

main thread
  GuardianSession / pipeline snapshot + ephemeral trackedPeople → MonitorUi → Compose

```

- Pose detector 在 bind 前建立，analyzer 不初始化模型、不等待 L1；`KEEP_ONLY_LATEST` 使整條
  ImageAnalysis 以 pose detector 可消化的速率運作，L2 不受 L0 admit gate 節流。
- `MlKitAnalyzer` 已評估但不採用：現有 L0/L1 仍需讀同一個 `ImageProxy`，其聚合 result callback
  不提供這個 raw-frame 分支；因此由單一 analyzer 明確持有 proxy，待 ML Kit task 完成後再跑 L0。
- ML Kit `STREAM_MODE` 只追畫面中最顯著的一人且沒有公開 tracking ID。extractor 偵測 landmark
  遺失、>750ms gap 或相對身體尺度的大幅跳位時輪替匿名 role slot，避免跨人拼接。
- 疊圖不自行重算 sensor rotation 或 center crop：`ImageProxyTransformFactory` 以 upright landmark
  座標建立 source transform，`CoordinateTransform` 映射至 `PreviewView.outputTransform`；Compose
  與 PreviewView 共用 FIT_CENTER viewport。直向 camera feed 在寬 visor 會保留左右 letterbox，
  刻意不採 FILL_CENTER：實機校準證明後者會把完整人物的頭/腳裁出可見區，妨礙人工確認。
  `TrackedPersonUi` 是 list 以容納未來 multi-pose，但當前永遠最多一筆。UI 只說偵測到一人，
  文案為「人體姿態候選」，不把模型 output 宣稱為已確認的人，也不展示關節或宣稱穩定 tracking ID。
- Debug build 首次 object frame 會以輸入全框角點輸出 `OBJECT_MAP_PROBE`（只含尺寸／映射座標，
  不含 detection、影像或類別），供實機區分 CameraX transform 錯位與 detector localization 誤差。
- `PreviewView.ScaleType.FIT_CENTER` 是刻意決策：FILL_CENTER 在 Pixel 10 portrait→寬 visor 實測會
  裁掉人物頭腳。letterbox 比缺少可見證據安全。
- Manifest 使用 `fullSensor` 並自行處理 `orientation|screenSize`；`OrientationEventListener` 將四向
  實體安裝角度映射為 `Surface.ROTATION_*`，同時更新 Preview/Analysis `targetRotation`。Compose
  以 `BoxWithConstraints` 在 landscape 變為左右雙欄，預覽與狀態不互相擠壓。
- Zoom UI 讀 `CameraInfo.zoomState` 的實際 min/max，以 `CameraZoomPolicy` clamp 後呼叫
  `CameraControl.setZoomRatio`；倍率寫入 SharedPreferences，CameraX bind 或回前景時重套。
  約略人物高度由可靠 landmark span 加頭腳餘量計算；低於約 256 px 只顯示「建議放大」，不把
  commissioning 指標冒充 detector 正確率。
- Pixel 10 對準 2F→1F 真實鏡位首輪 smoke：1× 無可見行人時，樹幹／告示牌形成約 197 px
  pose 候選；2× 出現一名被樹／告示牌部分遮擋的行人時卻無 pose output。這顯示高俯角與場景
  物體的 domain gap，UI 因此只能稱 candidate；告警須等 #38 場域 confusion matrix。
- 1.25 body-span 跳位門檻在 detector fps 很低時可能切斷真 fall；實機報告須量 slot rotation 與
  false negatives，再決定是否採速度/姿態條件門檻，不能在無資料時放寬。
- ML Kit pose 需臉部可見、完整身體取景最佳；遮擋/背向/倒地臉部不可見會輸出 Unknown 並中斷
  dwell。這是實機 recall 校準硬關卡，也是保留可替換 adapter 的理由。
- 第一版只由可靠的肩/髖/膝/踝 landmark 判定 Upright/Seated/Horizontal，並計算下降與動作分數；
  `impact/contact/strike` 固定 0、`visiblePeople` 最多 1，所以不會誤啟動多人 violence 規則。
- Detector 建立、同步 `process()` 或 Rust session 失敗時原子停用並 close pose/L2；同一個仍開啟的
  proxy 立即 fallback 到 L0/L1，後續幀也跳過 pose。這是「告警 fail-closed、感知 fail-open」。
- L1 single-flight；忙碌時 `AtomicReference` 只保留最新放行幀並 recycle 被取代的舊幀。
- Object detector 也使用 current + latest pending，但與 L1 executor／gate 完全獨立；這避免慢 L1
  阻塞 object candidates，也避免 MediaPipe 堆積無界 Bitmap。採同步 `VIDEO` 而非 async
  `LIVE_STREAM`，原因是 Activity 能明確擁有／recycle 每一張副本；兩者在過載時都不保證每幀輸出。
- Object allowlist 只保留 `person` 與 12 種可攜 COCO 類別，score threshold 0.35、max 10；這只
  減少無關結果，不代表類別精確到足以判定垃圾。全域 aHash 變化只啟動排程；單物件 motion 由
  normalized bbox 的 session-local tracker 計算，同類別 association 最長 gap 3 秒，槽位在退背景／
  撤回／destroy 重設，不是身分。
- `LitterEvidenceTracker` 要求連續人—物近接；person miss 不算分離，必須看到同一人物槽位仍在畫面
  且與物件分開。兩次可見拉遠、物件分離後至少 30 秒靜置、人物之後未見，才顯示 pending-review；
  既有靜止物、取回與 stale track 均 fail closed。此層不建 Event；ROI、多人 association、門檻
  confusion matrix 與 L2 schema 仍屬 #39。
- Dev object eval 與 production detector 共用同一 pinned Lite2/allowlist/score/max-results 設定，但另建
  instance 並由同一 `objectExecutor` 序列化。執行期間禁止啟動守護；撤回 MediaPipe consent 會在
  當幀後停止，不發布部分 summary。manifest 只允許 basename、normalized bbox 與 allowlisted
  category；未知欄位全部拒絕，所以不會悄悄引入 person ID 或拼字錯誤標註。評估刻意跳過 movement
  gate、tracker 與 litter state，量到的是 detector frame-level capability，不是事件 precision。
- Pixel 10 以既有兩張非固定鏡位影格完成 end-to-end smoke：3 個 person GT 為 TP 0／FP 4／FN 3，
  兩次 p50/p95 為 180/241 與 138/185 ms、min short side 54 px。素材不代表 2F→1F 場域，數字不可當驗收結果；
  它只驗證真 model、UI 與 metrics 接線並暴露該素材上的 detector domain gap。
- Pixel 10／2×／2F→1F 首測中，Lite0 約 121 ms 且對樹／告示牌輸出兩個 `person` 候選、漏掉
  小型真人；改用 Lite2 後空景 20 幀 p50 191 ms／p95 237 ms／max 238 ms、合併 2/20，約三分鐘
  未再誤框樹木；後續三人同框只框到兩人，小框定位仍有十幾至數十像素誤差。480×640 全框 probe
  映到 912×608 view 的 `(228,0)–(684,608)`，精確符合 FIT_CENTER，排除整體 CameraX transform
  位移；部署前仍須 #39 confusion matrix／場域微調，不能用固定 UI offset 補償模型誤差。
- 真後端初始化失敗會依 GPU/GPU→CPU/GPU→CPU/CPU 嘗試；每個失敗的 Engine 先 close，
  避免 fallback 前累積模型/GPU 配置。
- `PerceptionPipeline.swapCaptioner` 與 `describe` 都在 inference executor，避免並行關閉後端。

## 5. 守護狀態

```text
待命 --點擊--> 啟動中 --bind+首個成功幀--> 守護中
  ^  ^              |                         |
  |  |              +--權限/bind失敗----------+
  |  |                     (顯示原因，可重試)
  |  +------停止守護---------------------------+
  +---------使用者重新點擊---------------------+

守護中 --連續 3 次 analyzer 失敗--> 需處理 --成功幀--> 守護中
  ^                                     |
  +---------------停止後可重啟----------+
```

`GuardianSession` 為 synchronized 純 Kotlin 狀態物件。相機成功 bind 只代表「啟動中」；必須有
可分析首幀才可宣稱「守護中」。單次 analyzer 錯誤視為暫態，連續達門檻才降級狀態。
`GuardianSession.stop()` 在啟動中與已 bind 都回到待命，且重複停止為 no-op。Activity 先遞增
`guardianSessionVersion`，讓舊 CameraProvider、ML Kit、MediaPipe 與 overlay callback 全部失效，
再 clear analyzer／unbind、清 L1/object pending queue 與 overlays、序列化重設 tracker 並關閉 L2
engine；正在執行的本機推論只完成資源收尾。Pose／L1 重型 backend 可重用，下一次啟動建立新
L2/object session。全域 control strip 在四個 tab 都可見，不依賴回到守護頁。`pushState()` 可由
背景 executor 排隊，但真正套用 Compose state 時會再讀最新 `GuardianSession`，避免停止前建立的
snapshot 稍後把 UI 恢復為「幽靈守護中」。

Pixel 10 實機先重現上述幽靈狀態後驗證修正：從模型 tab 可直接停止；40 次循環的 CameraService
history 為 40 CONNECT／40 DISCONNECT，最終 device closed；另在啟動後 100ms 按停止，即使 camera
已開始 connect 仍回到待命並關閉。logcat 未出現 crash、ANR、bind、MediaPipe frame 或 L2 stop
error。這是 issue #42 的裝置驗收證據，不代表固定式背景服務／硬體 LED 已完成。

## 6. L1 合約

- Prompt 只要求繁體中文客觀描述人物、姿態與動作；禁止風險/意圖臆測。
- 輸出第一個完整句或達 hard cap 時 cancel；逾時 60 秒。
- `CaptionText` 去 emoji/符號、只留第一句、拒絕少於 4 個漢字的碎片。
- 空結果不覆蓋最後有效描述，但仍寫入驗證 log 為「此幀未產生有效描述」。
- `.task` 在 LiteRT-LM 0.11.0 只吐 `<pad>`，因此只用 `.litertlm` 原生模型；詳見
  [vlm SD](../vlm/SD.md)。

## 7. 隱私與資源

- Camera frame 不落地、不上傳；luma 僅跨 JNI 給 Rust 算 signature。
- Preview overlay 只保存產生匿名框所需的投影後 8 點與短時 role slot；不在 UI 顯示關節，也不保存影格、臉部特徵或 tracking ID，
  偵測 miss、L2 disable、`onStop` / `onDestroy` 會清空，避免背景後顯示舊人物。
- `ImageProxy` 在 ML Kit task completion 的 `finally` close；L1 Bitmap、downscale Bitmap 與被取代
  pending 幀都 recycle。landmark 只為相鄰影格 motion 暫存在 RAM，tracking miss/destroy 即清除。
- Activity destroy 先停止接受新幀；若唯一 pose task 尚未完成，保留 analysis executor 到 callback
  close proxy 後才 shutdown，避免 lifecycle race 讓 completion 被 executor 拒絕。
- L2 JNI 不接收 luma/Bitmap/landmark，只接收短時匿名 observation；`NativeEventEngine.close()`
  可重入，Rust registry 的 handle 不是 memory address。
- CaptionLog 只存文字、時間、來源與延遲，process death 即清空。
- MediaPipe object 模型從官方固定 URL 下載，先驗 byte length + SHA-256 才 rename；非 HF URL
  絕不附加 HF bearer token。MediaPipe Tasks 的非影像效能 metrics 需模型頁獨立同意才啟用，
  可撤回並直接停止新 detector submission，不依賴下一張 CameraX 影格；詳見 PRIVACY/#41。
- Dev 素材只讀使用者明確放入 app external-files 的 `dev_eval/`、`dev_videos/`、
  `dev_object_eval/`；object eval 只輸出 aggregate、不另存或上傳影格，完成後 recycle Bitmap。

## 8. 測試

- Host JVM:`ChangeGateTest`、`GuardianSessionTest`、`PerceptionPipelineTest`、
  `FallbackCaptionerTest`、`CaptionTextTest`、`ModelEvalTest`、`ModelSpecTest`、
  `PoseObservationExtractorTest`、`NativeEventEngineTest`、`ObjectCandidateGateTest`、
  `LatestOnlyQueueTest`、`ObjectRuntimeStatsTest`、`AnonymousObjectTrackerTest`、
  `LitterEvidenceTrackerTest`、`ObjectEvalTest`、`ObjectEvalManifestTest`、`ObjectCandidateGeometryTest`、
  `ModelsControllerTest`。pose/object 測試用
  純資料，不 mock `ImageProxy` 或真模型。
- `PersonOverlayGeometryTest` 覆蓋 confidence/NaN 過濾、邊界裁切、取景提示與多人 list UI 合約；
  `CameraZoomPolicyTest` 覆蓋裝置 zoom 邊界、無效值與步進；CameraX
  transform 的 rotation/crop 正確性必須在實機以可見人物驗證，host test 不冒充裝置測試。
- Android build:`./gradlew :app:testDebugUnitTest :app:assembleDebug`。
- Android lint:`./gradlew :app:lintDebug`；bridge 測試使用 fake，不 mock Android/CameraX 類別。
- 裝置:權限/bind、tab、待命→啟動→守護、GPU delegate、真模型描述；L2 另需固定鏡位錄影
  confusion matrix、p95、72h negative corpus；overlay 已驗 portrait、人物離場與前後景清除。
  Pixel WindowManager 強制 ROTATION_90 已驗 landscape 雙欄與控制可用；這不會改變實體 orientation
  sensor，故不能替代四向 camera buffer 驗收。四向 rotation 與 2F→1F zoom 依 issue #37/#38
  逐項實機驗收；host／強制 display rotation 都不能冒充感測器校準。
- MediaPipe Object Detector 候選 adapter、匿名短時 tracker 與 pre-Event evidence state 已接線；
  Pixel 已量 detector 首輪 p50/p95 與合併率，且 dev eval 已能直接產生 bbox metrics；仍須補實際
  2F→1F 真人／小物 allowlist coverage、hard negatives、最小物件像素、
  多人／多物 ID-switch 與完整時序 overlay。ROI、litter schema/Event 仍未接線，不能把 COCO
  detection 或 pending-review 直接映射成 Event。
- Rust/JNI 純邏輯另由 `core-rs cargo test` 與 Android 裝置煙霧測試覆蓋。

## 追溯

[SA](SA.md)、[ADR-0007](../../adr/0007-rust-first-redesign.md)、
[ADR-0009](../../adr/0009-edge-ai-litert-ai-edge.md)、
[ADR-0012](../../adr/0012-two-scenario-mvp-and-object-gating.md)、[vlm](../vlm/SD.md)。
