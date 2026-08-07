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
| `LiteRtCaptioner`(Kotlin) | LiteRT-LM SDK `litertlm-android`:`Engine`(backend fallback GPU/GPU→CPU/GPU→CPU/CPU)+ 每放行幀新 `Conversation`;`Content.ImageBytes(png)`+`Text` → 描述;`enable_thinking=false`;穩定 cache 目錄;`maxNumImages=1` + 文字由 `Content.Text` 取出 + client 端輸出上限 + `.litertlm`-native 模型 | ✅ **實機真實描述**(GPU/GPU,~6.5s,非 `<pad>`);`.litertlm` 取代 `.task` 修復(見 §6.1) |

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
- **✅ 已解決(2026-08-07,決策:改 `.litertlm`-native 模型):** 目錄改指 `google/gemma-3n-E{2,4}B-it-litert-lm` 的 `.litertlm` 原生檔(E2B = `gemma-3n-E2B-it-int4.litertlm`,3,655,827,456 bytes),取代 `-litert-preview` 的 MediaPipe `.task`。**Pixel 10 實機驗證:同一 `LiteRtCaptioner`(GPU/GPU)產生真實中文場景描述而非 `<pad>`**——例:「畫面中不可見的人物,表面呈現柔和的米白色光澤,模糊不清。」;首 token ~3s、整體 ~6.5s、client 端上限收束為一句話。**結論:pad 問題純為 `.task` × litertlm 格式不相容,換原生 `.litertlm` 即完全修復。** App 端下載/切換與其餘管線不變(合 ADR-0009 LiteRT-LM)。
- **穩健性前置(隨此保留):** `maxNumImages=1`、串流文字由 `Message.contents` 取 `Content.Text.text`、client 端輸出上限(一句話或 140 字即 `cancelProcess`)、影像 downscale ≤768、依 rotation 旋正、提示詞「一句話 30 字內」。`FallbackCaptioner` 仍在首次失敗即降級 placeholder(不成死路)。UI 端已有 Lottie 載入動畫呈現初始化/等待。

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
- **輸出後處理(✅ host):** `CaptionText`(去 emoji/符號、取首句、非中文拒絕)9 項單元測試。
- **模型驗證 harness(✅):** `ModelEval`(關鍵詞 any-match 計 pass、彙總 pass-rate + avg/p50 延遲)6 項單元測試;開發者模式以 `dev_eval/` 標註影格 + `dev_videos/` 測試影片在裝置上跑,**換模型時基本正確率與效能驗證**。裝置實測:跌倒影片(去字幕)L1 正確判讀「一人倒臥在馬路上」;3/3 影格通過、單張 ~6.5–11.5s。

## 8. L1 輸入品質限制 → 相機選型/佈建依據(重要)

裝置實測(Pixel 10,Gemma 3n **E2B**)歸納出 L1 的**輸入品質邊界**;這是**日後選相機/佈建的依歸**,不可忽略:

**已知限制(實測):**
- **主體佔比是關鍵。** 主體(人)在畫面中**夠大**時,L1 判讀準確——近景裁切的跌倒幀,穩定講出「一人倒臥在馬路上」(dev_eval 3/3)。
- **小/遠主體會失敗或幻覺。** 同一起跌倒放在**整幀行車記錄器遠景**(人又小又偏左)時,L1 **描述不到跌倒**,甚至**幻覺**(如「驚慌失措的駕駛員在車內」——畫面根本沒有)。E2B 對小主體 + 雜訊場景不可靠。
- **盲裁切不是解法。** 試過置中裁切放大遠景主體,但**偏心主體(如偏左的跌倒者)會被裁掉**、反而更糟;沒有主體偵測前,盲裁切不可靠。已還原為整幀。
- **稀疏取樣會漏瞬間。** L1 單張 ~6.5–11.5s,只能每 ~1.5s 取樣一幀,跌倒**瞬間**可能落在取樣間隔外。
- **內部 downscale ≤768。** 超高解析度對 L1 幫助有限;**主體佔比與清晰度 > 百萬畫素**。
- **疊字/字幕會干擾。** 畫面若有大字幕/浮水印,易誤讀或碎片化。

**→ 相機選型/佈建建議(依歸):**
- **視角/焦段:** 對準關注區,讓目標主體**佔畫面高度 ≥ ~⅓**;避免單台超廣角涵蓋大範圍(會把人變小)。寧可**較窄 FoV / 多台分區**。
- **距離/高度:** 佈建距離要讓「人」在畫面中夠大;大空間用**多機分區**而非單台廣角。
- **解析度:** 720p–1080p 已足(L1 內部 downscale);重點是**對焦、動態模糊、主體佔比**。
- **光線:** 充足、避免逆光/過曝(過曝畫面 L1 也難描述)。

**→ 架構含意(關鍵):** L1「場景描述」**不是可靠的跌倒偵測器**(會漏、會幻覺)。跌倒/暴力**事件偵測必須是 L2**(pose/動作/時序,附畫面內可見證據 — ADR-0006),L1 僅作輔助語意。追蹤於 issue #26(L2 快路徑)。**開發者模式的模型驗證(dev_eval + dev_videos)即為此邊界的量測工具**:換模型時先量正確率/延遲,並確認佈建的主體佔比落在可用區。

## 追溯

滿足 [`SA.md`](SA.md) FR-1…FR-4、NFR-1…NFR-3。相關:[ADR-0008](../../adr/0008-l1-caption-engine.md)、[core-rs SD](../core-rs/SD.md)、[android SD](../android/SD.md)。
