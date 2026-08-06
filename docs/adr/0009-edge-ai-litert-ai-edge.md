# ADR-0009 — L1 改用 Google AI Edge / LiteRT(不自建 llama.cpp)

**狀態:** 已接受 · **日期:** 2026-08-06
**取代:** [ADR-0008](0008-l1-caption-engine.md)(L1 用 llama.cpp via Rust FFI)
**延續/微調:** [ADR-0007](0007-rust-first-redesign.md)(Rust 優先、效能優先)——L0/L2 熱路徑仍在 Rust;L1 重模型改走裝置 NPU/GPU 的 LiteRT

> 北極星不變:即時串流 · Edge AI · 多模態 · 主動防護。本 ADR 換掉 L1 的**推論引擎**:
> 從自建 llama.cpp 改為**沿用 Google AI Edge / LiteRT**。

## 背景

ADR-0008 選 llama.cpp(GGUF + mmproj,Rust FFI)跑 L1 場景描述。實際查證後發現兩個關鍵事實:

1. **使用者選定的 Gemma 3n 多模態在 llama.cpp 跑不動。** `ggml-org/gemma-3n-*-GGUF`
   只有純文字 GGUF、**沒有 mmproj**;Gemma 3n 的視覺編碼器(MobileNet 系)未進 llama.cpp
   的 `mtmd`。llama.cpp 有視覺的 Gemma 只有一般 **Gemma 3 4B**(Q4 2.49GB + mmproj 851MB)。
2. **自建 llama.cpp + mtmd 交叉編譯到 Android** 是數小時、高風險工程,且跑在 CPU。

而 **Google AI Edge / LiteRT** 正是為此設計:
- **Gemma 3n / Gemma 4 E2B·E4B** 官方以 **`.litertlm` / `.task`** 發佈,原生支援**多模態(圖+文、音+文 → 文)**。
- 走 **Tensor G5 的 GPU/NPU** 加速——比我們的 CPU llama.cpp 快。
- **Google AI Edge Gallery 為 Apache-2.0 開源**,跨平台(Android/iOS/macOS),可直接沿用其
  模型下載/管理與 **LLM Inference / LiteRT-LM** 載入方式。

使用者決策:**善用 AI Edge、不要重複開發、這樣比較快。**

## 決策

1. **L1 推論引擎 = Google AI Edge / LiteRT**(MediaPipe LLM Inference / LiteRT-LM),
   **不自建 llama.cpp**。ADR-0008 的 llama.cpp 路線作廢。
2. **模型 = LiteRT 社群的多模態 Gemma**(Gemma 3n E2B/E4B,或 AI Edge 目前主打的 Gemma 4
   E2B/E4B)`.litertlm`/`.task`,支援「圖+文 → 文」。實際版本於下載時在 AI Edge 生態擇一。
3. **分層(微調 ADR-0007):**
   - **L0 變化閘控** — Rust(`core-rs`),**每幀**跑。不變。
   - **L1 場景描述** — **Kotlin 端呼叫 LiteRT / LLM Inference**,**只在放行幀**跑。取代原 Rust FFI→llama.cpp。
   - **L2 事件** — Rust(`core-rs`),吃 L1 輸出。不變(規劃)。
   - 「效能優先」在此體現為:每幀熱路徑(L0)在 Rust;重模型(L1)交給裝置 NPU/LiteRT——用對工具,而非什麼都自幹。
4. **沿用而非重造:** 參考 Google AI Edge Gallery(Apache-2.0)的模型下載器與 LLM Inference
   初始化;必要時把其模式移植為我們的 `LiteRtCaptioner`(Kotlin)。
5. **`Captioner` 邊界保留,但落在 L1 執行所在的 Kotlin 層。** 現有 Rust `vlm` 佔位
   (`NativeCore.describe`,誠實診斷)續作**過渡後端**,直到 `LiteRtCaptioner` 接上;屆時 L1
   切換到 Kotlin LiteRT,Rust 佔位退為測試/後備。既有管線不浪費。

## 後果

- **好處:** 省掉高風險原生建置;取得 Gemma 多模態 + NPU 加速;跨平台;沿用開源、開發更快。
- **代價/取捨:** L1 不再經 Rust FFI(ADR-0007 的「熱路徑在 Rust」僅嚴格適用於每幀的 L0/L2;
  L1 本就是偶發的重推論,交給 LiteRT 更合適)。新增 LiteRT/MediaPipe 相依。
- **待辦:** 下載多模態 Gemma `.litertlm` 到裝置(可經 AI Edge Gallery 或 HF LiteRT Community)→
  Android 加 LLM Inference 相依 → 實作 `LiteRtCaptioner`(圖+文 → 描述)→ 接到放行幀 → 裝置驗證。
- ADR-0008 標記為被取代;`cmake`/llama.cpp 前置不再需要。

## 追溯

實作:[`vlm` 設計](../design/vlm/SD.md)(將更新為 LiteRT)、README「Edge AI 模型使用」。
相關:[ADR-0007](0007-rust-first-redesign.md)、[ADR-0004](0004-phone-first-single-node.md)(早已預示 Gemma via LiteRT-LM)。
