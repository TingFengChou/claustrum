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
| LiteRT-LM / Lottie | 0.11.0 / 6.6.6 |

## 3. 元件

| 元件 | 職責 |
|---|---|
| `MonitorActivity` | route、CameraX、pose→L2 與 L0→L1 排程、dev 工具、生命週期 |
| `GuardianSession` | 可測的啟動/首幀/分析健康狀態；錯誤後重試與自動恢復 |
| `PerceptionPipeline` | L0 統計、可替換 Captioner、最後有效描述 |
| `LiteRtCaptioner` | 初始化 delegate、每幀新 Conversation、有界串流輸出、資源釋放 |
| `FallbackCaptioner` | 真後端首次 timeout/error 後降級為誠實 placeholder |
| `FastPathObservation` | pixel-free L2 輸入；匿名 slot、pose、motion/action scores 與 timestamp |
| `MlKitPoseFrameAdapter` | 把 ML Kit 的 8 個必要 landmark 正規化；ML Kit 型別不進核心邏輯/JNI |
| `PoseObservationExtractor` | host-test 的跨幀 pose/descent/motion 特徵；追蹤跳位時輪替匿名 slot |
| `NativeEventEngine` | 擁有一個 Rust L2 opaque handle；同步 process/close，返回單筆 Event JSON 清單 |
| `ModelsController` | 模型目錄、HF token、WorkManager 下載狀態 |
| `AppShell` | 守護/事件/模型/設定四個 Compose tab |

## 4. 執行緒與資料流

```text
CameraX analysisExecutor
  ImageProxy(YUV) → ML Kit base PoseDetector(STREAM_MODE)
  → normalized PoseFrame → PoseObservationExtractor → NativeEventEngine/JNI → Rust EventEngine
  → 同一個仍開啟的 proxy:緊密 luma → NativeCore.frameSignature → ChangeGate.admit
  → 若放行:toBitmap + rotationDegrees 旋正 → enqueueL1
  → ML Kit task completion finally ImageProxy.close

inferenceExecutor(single thread)
  初始化 LiteRT Engine → swapCaptioner
  admitted Bitmap → describe → recycle → drain 最新 pending Bitmap

main thread
  GuardianSession / pipeline snapshot → MonitorUi → Compose

```

- Pose detector 在 bind 前建立，analyzer 不初始化模型、不等待 L1；`KEEP_ONLY_LATEST` 使整條
  ImageAnalysis 以 pose detector 可消化的速率運作，L2 不受 L0 admit gate 節流。
- `MlKitAnalyzer` 已評估但不採用：現有 L0/L1 仍需讀同一個 `ImageProxy`，其聚合 result callback
  不提供這個 raw-frame 分支；因此由單一 analyzer 明確持有 proxy，待 ML Kit task 完成後再跑 L0。
- ML Kit `STREAM_MODE` 只追畫面中最顯著的一人且沒有公開 tracking ID。extractor 偵測 landmark
  遺失、>750ms gap 或相對身體尺度的大幅跳位時輪替匿名 role slot，避免跨人拼接。
- 1.25 body-span 跳位門檻在 detector fps 很低時可能切斷真 fall；實機報告須量 slot rotation 與
  false negatives，再決定是否採速度/姿態條件門檻，不能在無資料時放寬。
- ML Kit pose 需臉部可見、完整身體取景最佳；遮擋/背向/倒地臉部不可見會輸出 Unknown 並中斷
  dwell。這是實機 recall 校準硬關卡，也是保留可替換 adapter 的理由。
- 第一版只由可靠的肩/髖/膝/踝 landmark 判定 Upright/Seated/Horizontal，並計算下降與動作分數；
  `impact/contact/strike` 固定 0、`visiblePeople` 最多 1，所以不會誤啟動多人 violence 規則。
- L1 single-flight；忙碌時 `AtomicReference` 只保留最新放行幀並 recycle 被取代的舊幀。
- 真後端初始化失敗會依 GPU/GPU→CPU/GPU→CPU/CPU 嘗試；每個失敗的 Engine 先 close，
  避免 fallback 前累積模型/GPU 配置。
- `PerceptionPipeline.swapCaptioner` 與 `describe` 都在 inference executor，避免並行關閉後端。

## 5. 守護狀態

```text
待命 --點擊--> 啟動中 --bind+首個成功幀--> 守護中
  ^                 |                         |
  |                 +--權限/bind失敗----------+
  |                        (顯示原因，可重試)
  +--使用者重新點擊---------------------------+

守護中 --連續 3 次 analyzer 失敗--> 需處理 --成功幀--> 守護中
```

`GuardianSession` 為 synchronized 純 Kotlin 狀態物件。相機成功 bind 只代表「啟動中」；必須有
可分析首幀才可宣稱「守護中」。單次 analyzer 錯誤視為暫態，連續達門檻才降級狀態。

## 6. L1 合約

- Prompt 只要求繁體中文客觀描述人物、姿態與動作；禁止風險/意圖臆測。
- 輸出第一個完整句或達 hard cap 時 cancel；逾時 60 秒。
- `CaptionText` 去 emoji/符號、只留第一句、拒絕少於 4 個漢字的碎片。
- 空結果不覆蓋最後有效描述，但仍寫入驗證 log 為「此幀未產生有效描述」。
- `.task` 在 LiteRT-LM 0.11.0 只吐 `<pad>`，因此只用 `.litertlm` 原生模型；詳見
  [vlm SD](../vlm/SD.md)。

## 7. 隱私與資源

- Camera frame 不落地、不上傳；luma 僅跨 JNI 給 Rust 算 signature。
- `ImageProxy` 在 ML Kit task completion 的 `finally` close；L1 Bitmap、downscale Bitmap 與被取代
  pending 幀都 recycle。landmark 只為相鄰影格 motion 暫存在 RAM，tracking miss/destroy 即清除。
- Activity destroy 先停止接受新幀；若唯一 pose task 尚未完成，保留 analysis executor 到 callback
  close proxy 後才 shutdown，避免 lifecycle race 讓 completion 被 executor 拒絕。
- L2 JNI 不接收 luma/Bitmap/landmark，只接收短時匿名 observation；`NativeEventEngine.close()`
  可重入，Rust registry 的 handle 不是 memory address。
- CaptionLog 只存文字、時間、來源與延遲，process death 即清空。
- Dev 素材只讀使用者明確放入 app external-files 的 `dev_eval/`、`dev_videos/`。

## 8. 測試

- Host JVM:`ChangeGateTest`、`GuardianSessionTest`、`PerceptionPipelineTest`、
  `FallbackCaptionerTest`、`CaptionTextTest`、`ModelEvalTest`、`ModelSpecTest`、
  `PoseObservationExtractorTest`、`NativeEventEngineTest`。pose 測試用純資料 fake，不 mock `ImageProxy`。
- Android build:`./gradlew :app:testDebugUnitTest :app:assembleDebug`。
- Android lint:`./gradlew :app:lintDebug`；bridge 測試使用 fake，不 mock Android/CameraX 類別。
- 裝置:權限/bind、tab、待命→啟動→守護、GPU delegate、真模型描述；L2 另需固定鏡位錄影
  confusion matrix、p95、72h negative corpus，host 測試不能替代校準。
- Rust/JNI 純邏輯另由 `core-rs cargo test` 與 Android 裝置煙霧測試覆蓋。

## 追溯

[SA](SA.md)、[ADR-0007](../../adr/0007-rust-first-redesign.md)、
[ADR-0009](../../adr/0009-edge-ai-litert-ai-edge.md)、[vlm](../vlm/SD.md)。
