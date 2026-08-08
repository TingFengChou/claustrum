# vlm(L1 場景描述)— 系統分析(SA)

**狀態:** active(LiteRT 真實描述已落地) · **最後更新:** 2026-08-08 · **負責人:** claustrum
**設計:** [`SD.md`](SD.md)

## 1. 目的

L1 把 L0 **放行**的旋正影格變成一句客觀場景描述，供 UI/驗證記錄，並可在 L2 事件建立後
附加為二階脈絡。L1 **不負責**跌倒/暴力判定，也不能單獨升級 risk/alert。引擎採
**Google AI Edge / LiteRT-LM**(多模態 Gemma；不自建 llama.cpp，ADR-0009)，對外是 Kotlin
`Captioner<Bitmap>` 邊界。

## 2. 範圍

- **在範圍內(已落地):** Kotlin `Captioner<Bitmap>`、`LiteRtCaptioner`、誠實的 Kotlin
  `PlaceholderCaptioner`/`FallbackCaptioner`、`PerceptionPipeline` single-flight 協調與文字清理。
- **已清除:** ADR-0008 的 Rust `Captioner`／`NativeCore.describe` 過渡 seam 已移除；現行 L1
  只有 Kotlin `Captioner<Bitmap>` 邊界，不再存在平行 ABI。
- **不在範圍內:** L0 signature/gate、L2 事件判定、通知、影格持久化或上傳。

## 3. 需求

| 編號 | 需求 |
|---|---|
| FR-1 | `describe(Bitmap)` 對 L0 放行幀回一段有界客觀描述；不輸出 event/risk |
| FR-2 | 後端可抽換：`PerceptionPipeline` 只依賴 Kotlin `Captioner<F>`，host 測試可用 fake |
| FR-3 | 模型不存在或失敗時回誠實 placeholder，不偽造場景理解 |
| FR-4 | 每個放行幀用新 Conversation；輸出清理為繁中單句並拒絕無效碎片 |
| NFR-1 | 隱私：影像只在裝置記憶體，不落地、不上傳；目前只在 RAM 保存文字記錄 |
| NFR-2 | 不阻塞：編碼/推論離開 CameraX analyzer，single-flight + 最新 pending 一張 |
| NFR-3 | 資源有界：Engine 重用；Conversation/Bitmap/縮圖依生命週期關閉或 recycle |

## 4. 相依與假設

- 上游:Kotlin `ChangeGate` 的放行決策。輸入為 CameraX `ImageProxy` 複製、旋正後的 Bitmap。
- 真後端相依:Google AI Edge LiteRT-LM `litertlm-android`、多模態 Gemma `.litertlm`。
  沿用 [AI Edge Gallery](https://github.com/google-ai-edge/gallery)(Apache-2.0)的下載/載入模式。
  模型需下載到裝置(見 README「Edge AI 模型使用」)。

## 5. 驗收

- **真後端(✅ Pixel 10):** `.litertlm`-native Gemma 3n E2B 以 GPU/GPU 產生繁中描述，
  實測約 6.5–11.5 秒；略過幀不啟動 L1。
- **host tests:** `CaptionText`、`FallbackCaptioner`、`PerceptionPipeline`、`ModelEval` 皆不需硬體。
- **限制:** 描述不等於事件偵測；模型品質與相機取景仍須依 issue #29 實測。

## 追溯

滿足 ADR-0009；ADR-0008 只保留「可抽換 Captioner」概念。相關:[ADR-0007](../../adr/0007-rust-first-redesign.md)、
[ADR-0011](../../adr/0011-l2-fast-path-evidence.md)、[core-rs](../core-rs/SA.md)、[android](../android/SA.md)。
