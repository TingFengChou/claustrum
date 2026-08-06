# vlm(L1 場景描述)— 系統設計(SD)

**狀態:** active · **最後更新:** 2026-08-06 · **負責人:** claustrum
**分析:** [`SA.md`](SA.md)

## 1. 概觀

L1 的單一邊界為 `Captioner`。**過渡佔位** `PlaceholderCaptioner` 落在 `core-rs`(誠實診斷,
經 JNI `describe`),證明 L0→L1 觸發管線可跑。**真實後端 `LiteRtCaptioner` 落在 Kotlin**——
Google AI Edge / LiteRT LLM Inference,多模態 Gemma `.litertlm`(ADR-0009,取代 ADR-0008 的
llama.cpp 路線)。L1 執行所在的層 = Kotlin,因為 LiteRT/LLM Inference 是 Android(Kotlin)API。

## 2. 元件與職責

| 元件 | 職責 | 狀態 |
|---|---|---|
| `Captioner`(trait) | L1 邊界:`describe(&mut self, luma, w, h) -> String`、`backend()` | ✅ |
| `PlaceholderCaptioner` | 誠實診斷:尺寸 + 平均亮度 + 2×2 亮度網格;標示「未載入 VLM」 | ✅ |
| `LumaStats` | 從 luma 算整體與 2×2 象限平均亮度(純函式) | ✅ |
| `ffi::…_describe` | JNI:`convert_byte_array` → `PlaceholderCaptioner.describe` → `new_string` | ✅(android only) |
| `LiteRtCaptioner`(Kotlin) | Google AI Edge / LiteRT LLM Inference,多模態 Gemma;圖+文 → 描述 | ⏳ 待實作(見 ADR-0009、README「Edge AI 模型使用」) |

## 3. 介面與合約

- **Rust:** `Captioner::describe(&mut self, luma: &[u8], width, height) -> String`。
  畸形(零維度、`luma.len() < w*h`)回 `"L1 佔位:無效幀"`,不 panic。
- **JNI:** `com.claustrum.core.NativeCore.describe(luma: ByteArray, w: Int, h: Int): String`
  (`src/ffi.rs`,android target only)。傳 luma、回描述;**幀不回傳**。
- **呼叫時機:** Kotlin analyzer **僅在 `ChangeGate.admit()==true`** 時呼叫 `describe`——
  這正是「只在場景變化才喚醒 L1」的省算力點。

## 4. 資料結構

`LumaStats { mean: u32, quads: [u32;4] }`——象限序 0=TL 1=TR 2=BL 3=BR;百分比對 255 正規化。
真後端另持有模型 handle / context(`&mut self`)。

## 5. 關鍵流程

```
# 過渡(佔位,Rust):
L0 放行 → NativeCore.describe(luma,w,h) → (JNI) Captioner.describe
        → PlaceholderCaptioner:LumaStats.of(luma) → "L1 佔位(未載入 VLM)· WxH · 亮度 N% · 網格[..]"
# 真後端(Kotlin/LiteRT-LM SDK litertlm-android):
L0 放行 → LiteRtCaptioner.describe(bitmap)
        → engine(載一次,visionBackend=GPU)+ **每幀新 Conversation**(單幀獨立,不累積歷史)
        → Content.ImageBytes(png) + Content.Text(客觀提示) → sendMessageAsync
        → LiteRT(Tensor G5 GPU/NPU)→ 場景描述字串;用畢 conversation.close()
        → Kotlin 覆蓋層顯示 / 餵給 L2
```

## 6. 錯誤處理與穩健性

- 佔位後端與 JNI 皆對零維度/短 luma 早退回安全字串。
- JNI `new_string` 失敗回 null;Kotlin `describe` 宣告為 `String?`,呼叫端以
  `?: "L1 佔位:描述失敗"` 兜底,避免熱路徑 NPE。
- 真後端:模型載入失敗須回可辨識錯誤字串(不崩潰),並在 UI 標示後端狀態。

## 6.1 真後端狀態管理(LiteRT 在 Kotlin)

因 L1 改走 Kotlin 端 LiteRT-LM SDK(`com.google.ai.edge.litertlm:litertlm-android`,ADR-0009),
模型狀態由 **Kotlin 持有**:`Engine`(`EngineConfig(modelPath, backend=GPU, visionBackend=GPU,
maxNumTokens)`)**載入一次**(初始化成本高)、跨放行幀重用,`onDestroy` 才 `close()`。**每個放行幀
建立全新 `Conversation`**(單幀獨立判斷,避免累積對話歷史造成上下文污染 / token 爆量),送
`Content.ImageBytes(png)` + `Content.Text(客觀提示)`,用畢 `close()`。因 L0 已閘控,PNG 編碼與新對話
只在放行幀付出,非逐幀熱路徑。不需早先規劃的 Rust 端 `OnceLock<Mutex<…>>`(llama.cpp-in-Rust 路線的
產物,已隨 ADR-0009 作廢)。過渡期 `PlaceholderCaptioner` 仍為無狀態、JNI 每次新建即可。

**LiteRtCaptioner 實作守則(穩健性,必守):**
- **生命週期競態:** `sendMessageAsync` 為非同步;`onDestroy` 前須先 `conversation.cancelProcess()`
  並等待/保證回呼結束,才 `engine.close()`,避免釋放使用中資源導致 C++ 崩潰。
- **單線(single-flight)防重入:** L0 快速連續放行時,**推論中則丟棄新放行幀**(只保留最新一張,
  比照 CameraX `KEEP_ONLY_LATEST`),不得並發建立多個 `Conversation`(否則 OOM / GPU 過載)。
- **執行緒:** PNG 編碼與推論在**背景 executor**(非 CameraX analyzer 執行緒)進行,避免阻塞取幀。

## 7. 測試策略(必備)

- **Host `cargo test`(✅ 4):** 畸形安全、回報尺寸與 backend、暗/亮幀亮度極值、
  2×2 網格定位(下半亮 → `網格[0 0 / 100 100]`)。
- **裝置整合(✅):** 放行幀觸發、描述含正確尺寸/亮度/網格(Pixel 10)。
- **真後端(待):** 以固定測試圖對照描述關鍵詞;延遲量測。

## 追溯

滿足 [`SA.md`](SA.md) FR-1…FR-4、NFR-1…NFR-3。相關:[ADR-0008](../../adr/0008-l1-caption-engine.md)、[core-rs SD](../core-rs/SD.md)、[android SD](../android/SD.md)。
