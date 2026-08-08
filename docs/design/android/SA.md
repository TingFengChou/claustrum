# android(Kotlin 裝置外殼)— 系統分析(SA)

**狀態:** active · **最後更新:** 2026-08-08 · **負責人:** claustrum
**設計:** [`SD.md`](SD.md)

## 1. 目的

原生 Android App 負責使用者進入流程、CameraX 取幀、Rust L0 閘控、Kotlin/LiteRT-LM L1
場景描述、ML Kit 單人 pose fast path、模型下載與開發者驗證工具。可測的事件規則留在
`core-rs`;Android 只做裝置與 UI 整合。影格全程留在裝置端。

## 2. 範圍與現況

- **P0/P1(✅):** Rust `.so`→JNI→Kotlin、CameraX luma→aHash `ChangeGate`。
- **P2/P2.5(✅):** 單一 Activity Compose app shell、Splash/介紹、機器之眼手動啟動、
  gated 模型下載、LiteRT-LM 原生 `.litertlm` L1、描述記錄與 dev 驗證工具。
- **P3(🟡):** ML Kit base Pose Detection `STREAM_MODE` 已由 CameraX 餐取，純 Kotlin extractor
  產生匿名 `FastPathObservation` 並送入 Rust L2 JNI session；尚待真實素材校準、impact/多人
  action 特徵、事件呈現、policy 與告警。
- **範圍外:** 人臉/身分/年齡辨識、影格上傳、由 L1 直接判定風險。

## 3. 需求

| 編號 | 需求 |
|---|---|
| FR-1 | App 載入 arm64 `libclaustrum_core.so`，經 JNI 呼叫 `frameSignature` |
| FR-2 | 相機由使用者明確點擊後才啟動；權限或 bind 失敗須顯示錯誤並可重試 |
| FR-3 | CameraX analyzer 以 `KEEP_ONLY_LATEST` 跑 ML Kit pose，完成後同一 proxy 再跑 L0；不得被 L1 阻塞 |
| FR-4 | L0 放行幀旋正後交給 single-flight L1；忙碌時只保留最新一張 pending 幀 |
| FR-5 | L1 只客觀描述畫面可見內容；空白/碎片不覆蓋最後有效描述 |
| FR-6 | 音訊與 L2 未啟用時須清楚標示，不得顯示虛構的 all-clear 或事件 |
| FR-7 | 模型可在 App 內下載；gated 模型的 HF 權杖須加密儲存且不得進 log |
| FR-8 | Android 只把匿名 timestamp/role slot/pose/scores 傳給 Rust L2；engine session 可關閉且不使用裸指標 |
| FR-9 | ML Kit 無 tracking ID 時，追蹤遺失/跳位須輪替匿名 role slot，不能把不同人時序拼接 |
| NFR-1 | Host 單元測試不依賴模型、相機或 Android 裝置 |
| NFR-2 | `ImageProxy` 必定 close；複製 Bitmap 在 L1 使用後 recycle；影格不外傳 |
| NFR-3 | 相機啟動、首幀、連續分析錯誤與恢復狀態必須如實反映於 UI |

## 4. 相依與假設

- Rust/JNI: [`core-rs`](../core-rs/SA.md)。L1:Google AI Edge `litertlm-android`(ADR-0009)。
- 目前只打包 arm64-v8a；已在 Pixel 10 / Tensor G5 / Android 17 驗證。
- `.so`、模型、`local.properties` 與 LiteRT cache 都是裝置/建置產物，不進版控。

## 5. 驗收

- 拒絕相機權限或 bind 失敗後回到可重試狀態；只有成功分析首幀才顯示「守護中」。
- 連續 analyzer 失敗會顯示「需處理」，後續成功幀可自動恢復。
- L1 推論不佔用 CameraX analyzer；推論期間的新場景保留最新 pending 幀。
- Pose stream 每個可分析幀獨立於 L0 admit 產生 observation；單人 pose 不臆造 impact、第二人或 strike。
- `:app:testDebugUnitTest` 覆蓋 ChangeGate、GuardianSession、PerceptionPipeline、Fallback、
  CaptionText、ModelEval、ModelSpec、PoseObservationExtractor 與 NativeEventEngine；真 ML Kit/LiteRT
  推論以實機/dev 素材驗證。

## 追溯

[ADR-0006](../../adr/0006-safety-alert-mvp.md)、[ADR-0007](../../adr/0007-rust-first-redesign.md)、
[ADR-0009](../../adr/0009-edge-ai-litert-ai-edge.md)、[core-rs](../core-rs/SD.md)、[vlm](../vlm/SD.md)。
