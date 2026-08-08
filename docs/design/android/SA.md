# android(Kotlin 裝置外殼)— 系統分析(SA)

**狀態:** active · **最後更新:** 2026-08-08 · **負責人:** claustrum
**設計:** [`SD.md`](SD.md)

## 1. 目的

原生 Android App 負責使用者進入流程、CameraX 取幀、Rust L0 閘控、Kotlin/LiteRT-LM L1
場景描述、ML Kit 單人 pose fast path、MediaPipe object candidate fast path、模型下載與開發者
驗證工具。可測的事件規則留在
`core-rs`;Android 只做裝置與 UI 整合。影格全程留在裝置端。

## 2. 範圍與現況

- **P0/P1(✅):** Rust `.so`→JNI→Kotlin、CameraX luma→aHash `ChangeGate`。
- **P2/P2.5(✅):** 單一 Activity Compose app shell、Splash/介紹、機器之眼手動啟動、
  gated 模型下載、LiteRT-LM 原生 `.litertlm` L1、描述記錄與 dev 驗證工具。
- **P3(🟡):** ML Kit base Pose Detection `STREAM_MODE` 已由 CameraX 餐取，純 Kotlin extractor
  產生匿名 `FastPathObservation` 並送入 Rust L2 JNI session；Preview 會以 CameraX 官方座標轉換
  畫出匿名人物框與取景像素提示。`fullSensor`、landscape 重排、CameraX target rotation 與持久化
  zoom 已接線。MediaPipe EfficientDet-Lite2 的 movement gate、有界候選佇列、allowlist、本機
  bbox 疊圖、session-local 匿名 P/O tracker 與 fail-closed litter evidence stage 也已接線；尚待
  真實素材校準、ROI／可靠多人 association、L2 ObjectObservation/Event、policy 與告警。
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
| FR-10 | Preview 疊圖須用 CameraX analysis→PreviewView transform 與 FIT_CENTER 保留完整視野；只顯示匿名人物框與取景品質，不顯示骨架／身分／未確認風險，遺失/退背景立即清除 |
| FR-11 | CameraX zoom 必須依 `ZoomState` 裝置 min/max clamp、持久化並於回前景重套；UI 必須讓使用者知道實際倍率 |
| FR-12 | `fullSensor` 四方向須同步 Preview/Analysis `targetRotation`；landscape UI 不得讓預覽或主要狀態不可操作 |
| FR-13 | 亂丟垃圾以 movement gate → MediaPipe Object Detector candidate → session-local 匿名人／物時序接入；單一類別／移動／person miss 不得直接成為事件，ROI/多人 association/Event 未完成須明示 |
| FR-14 | Object detector 必須使用獨立有界 current + latest pending queue；Bitmap 被取代／完成／destroy 時 recycle，bbox 以 CameraX transform 對齊 Preview |
| FR-15 | MediaPipe metrics 未取得獨立知情同意時不得初始化 detector；模型頁可撤回，撤回後停止新輸入並序列化 close |
| FR-16 | 啟動後須能明確停止守護；跨 tab 顯示相機狀態，停止時 unbind/清 queue/overlay 且可安全重啟；舊 callback 不得污染新 session |
| FR-17 | Object 槽位不得使用臉、外觀 embedding 或跨 session re-identification；同類別幾何 track gap、退背景、撤回與 destroy 均須重設，evidence 最終只可標待檢視 |
| NFR-1 | Host 單元測試不依賴模型、相機或 Android 裝置 |
| NFR-2 | `ImageProxy` 必定 close；複製 Bitmap 在 L1 使用後 recycle；影格不外傳 |
| NFR-3 | 相機啟動、首幀、連續分析錯誤與恢復狀態必須如實反映於 UI |
| NFR-4 | Pose detector/JNI 同步失敗只停用 L2；同一幀與後續幀仍須走 L0/L1，不得連帶停擺 |
| NFR-5 | overlay 資料型別預留多人 list，但現用 ML Kit 只回一人；不得以 UI 外觀宣稱多人已完成 |
| NFR-6 | Object category/score/bbox 只在 RAM 作候選；不落地、不跨 JNI、不外傳，也不得映射為 litter Event |

## 4. 相依與假設

- Rust/JNI: [`core-rs`](../core-rs/SA.md)。L1:Google AI Edge `litertlm-android`(ADR-0009)。
- 目前只打包 arm64-v8a；已在 Pixel 10 / Tensor G5 / Android 17 驗證。
- `.so`、模型、`local.properties` 與 LiteRT cache 都是裝置/建置產物，不進版控。

## 5. 驗收

- 拒絕相機權限或 bind 失敗後回到可重試狀態；只有成功分析首幀才顯示「守護中」。
- 連續 analyzer 失敗會顯示「需處理」，後續成功幀可自動恢復。
- L1 推論不佔用 CameraX analyzer；推論期間的新場景保留最新 pending 幀。
- Pose stream 每個可分析幀獨立於 L0 admit 產生 observation；單人 pose 不臆造 impact、第二人或 strike。
- Object movement gate 的 schedule／periodic probe、bbox clamp 與模型 catalog/checksum 可由 host
  test 驗證；真 detector latency、類別 coverage、旋轉/zoom 對齊與最小物件像素必須在 Pixel 實測。
- 匿名 tracker 與 litter evidence 以純 Kotlin host tests 驗證 continuity、motion、person miss 不算
  separation、既有靜止物、取回與 stale reset；多人／多物交錯 ID-switch 仍須固定鏡位資料實測。
- 後/前景切換與偵測遺失不留舊人物框；FIT_CENTER letterbox 下框對齊 Preview。四向 rotation、
  landscape 重排、zoom persistence 與 2F→1F 取景須以實機完成 issue #37/#38 驗收。
- 前景內停止控制須在啟動中／守護中／需處理都可用；停止後 CameraX 不再 streaming、跨 tab
  指示消失，舊 callback 不恢復疊圖／event，且同一 Activity 可再次安全啟動。Pixel 10 已完成
  模型 tab 停止、100ms 快速停止與 40 次 CameraService CONNECT/DISCONNECT 對稱循環，無相關
  crash/error；issue #42 已隨 PR #45 merge 關閉。
- `:app:testDebugUnitTest` 覆蓋 ChangeGate、GuardianSession、PerceptionPipeline、Fallback、
  CaptionText、ModelEval、ModelSpec、PoseObservationExtractor、NativeEventEngine、
  AnonymousObjectTracker 與 LitterEvidenceTracker；真 ML Kit/LiteRT 推論以實機/dev 素材驗證。

## 追溯

[ADR-0006](../../adr/0006-safety-alert-mvp.md)、[ADR-0007](../../adr/0007-rust-first-redesign.md)、
[ADR-0009](../../adr/0009-edge-ai-litert-ai-edge.md)、
[ADR-0012](../../adr/0012-two-scenario-mvp-and-object-gating.md)、[issue #42](https://github.com/TingFengChou/claustrum/issues/42)、[core-rs](../core-rs/SD.md)、[vlm](../vlm/SD.md)。
