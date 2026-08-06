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
| `LiteRtCaptioner`(Kotlin) | LiteRT-LM SDK `litertlm-android`:`Engine`(backend fallback GPU/GPU→CPU/GPU→CPU/CPU)+ 每放行幀新 `Conversation`;`Content.ImageBytes(png)`+`Text` → 描述;`enable_thinking=false`;穩定 cache 目錄;`maxNumImages=1` + 文字由 `Content.Text` 取出 + client 端輸出上限 | ✅ 引擎初始化(GPU/GPU)+ 有界輸出(~10s);⚠️ 但 `.task` 格式下模型只吐 `<pad>`(文字亦然)→ 待改 `.litertlm` 或 MediaPipe(見 §6.1) |

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

**裝置端實測(Pixel 10,截至目前):**
- 相依 `com.google.ai.edge.litertlm:litertlm-android:0.11.0`(需 Kotlin ≥ 2.2 讀其 metadata)。
- **GPU delegate 需在 manifest 宣告 `libOpenCL.so`(`uses-native-library`,required=false)**,否則 GPU backend 初始化失敗(`vision_litert_compiled_model_executor` / `delegate_kernel_litert` INTERNAL error)。宣告後 **`Engine`(backend=GPU/GPU)初始化成功**。
- **maxNumTokens 需容納輸入**:影像約 256 vision tokens + 提示詞 ≈ 299,故不可設 256(會 `Input token ids too long`);用 1024。
- 編譯後的 vision/program cache 持久化於穩定目錄(`<externalFiles>/litert-cache/`,約 1.4GB + 166MB),跨重裝保留。
- **已根因(2026-08-07,關鍵發現):** 逾時的**真因不是延遲,而是模型只吐 `<pad>` token**。裝置端逐字量測:首 token @~3.8s、之後穩定串流,但**每個 chunk 都是字面 `"<pad>"`,模型永不吐 EOS**,故 60s 逾時只是症狀。已驗證:
  - 修好三件周邊(皆為必要前置,已保留):`EngineConfig.maxNumImages=1`(否則不配置影像槽,影像被丟棄);串流文字改由 `Message.contents` 取 `Content.Text.text`(非 `toString()` 的 token dump);**client 端輸出上限**(一句話結束或 140 字即 `cancelProcess`,把不收斂的生成收束為 ~10s 有界結果)+ 影像 downscale ≤768 + 依 rotation 旋正 + 提示詞要求「一句話 30 字內」。
  - **決定性測試:純文字提示(不含影像)同樣只回 `<pad>`** → 問題**與視覺無關**,是 **litertlm 0.11.0 無法正確解碼 `gemma-3n-E2B-it-int4.task`(MediaPipe `.task` 格式)**:引擎能載入 tflite 子圖與 vision_adapter、能跑生成,但 detokenizer/LM head 對不上,每步都解成 pad。
- **待決策(L1 最後一哩,擋在此):** 需二擇一——
  1. **改用 `.litertlm`-native 模型**(litertlm SDK 的原生格式;Google 於 HF/Kaggle 有發佈 Gemma 3n `.litertlm`)。合 ADR-0009(LiteRT-LM),但需**重新下載** 多 GB 模型並改 `model` 目錄格式/URL。
  2. **改用 MediaPipe LLM Inference API**(`com.google.mediapipe.tasks.genai`,Google AI Edge Gallery 採用,原生吃 `.task`)。**可重用現有已下載的 `.task`**,為已驗證路徑;需在 vlm 加一個 MediaPipe 後端(與 `LiteRtCaptioner` 並列於 `Captioner` 介面後)。
  - 兩者皆屬「Google AI Edge」範疇。**目前 `FallbackCaptioner` 首次失敗即降級 placeholder,App 不成死路**;此決策不阻擋其餘開發。追蹤於任務 #2/#9。UI 端已有 Lottie 載入動畫呈現初始化/等待。

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
