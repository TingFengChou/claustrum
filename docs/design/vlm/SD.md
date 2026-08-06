# vlm(L1 場景描述)— 系統設計(SD)

**狀態:** active · **最後更新:** 2026-08-06 · **負責人:** claustrum
**分析:** [`SA.md`](SA.md)

## 1. 概觀

`core-rs` 的 `vlm` 模組。定義 L1 的單一邊界 `Captioner`,先以 `PlaceholderCaptioner`
落地(誠實診斷),真實 `LlamaCaptioner`(llama.cpp + mmproj)之後從同一 trait 抽換進來
(ADR-0008)。

## 2. 元件與職責

| 元件 | 職責 | 狀態 |
|---|---|---|
| `Captioner`(trait) | L1 邊界:`describe(&mut self, luma, w, h) -> String`、`backend()` | ✅ |
| `PlaceholderCaptioner` | 誠實診斷:尺寸 + 平均亮度 + 2×2 亮度網格;標示「未載入 VLM」 | ✅ |
| `LumaStats` | 從 luma 算整體與 2×2 象限平均亮度(純函式) | ✅ |
| `ffi::…_describe` | JNI:`convert_byte_array` → `PlaceholderCaptioner.describe` → `new_string` | ✅(android only) |
| `LlamaCaptioner` | llama.cpp + mmproj 真後端 | ⏳ 待實作(前置見 ADR-0008 §4) |

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
L0 放行 → NativeCore.describe(luma,w,h)
        → (JNI) ffi::…_describe → Captioner.describe
        → PlaceholderCaptioner:LumaStats.of(luma) → "L1 佔位(未載入 VLM)· WxH · 亮度 N% · 網格[..]"
          (真後端:LlamaCaptioner → llama.cpp 多模態 eval → 場景描述)
        → 回字串 → Kotlin 覆蓋層顯示
```

## 6. 錯誤處理與穩健性

- 佔位後端與 JNI 皆對零維度/短 luma 早退回安全字串。
- JNI `new_string` 失敗回 null;Kotlin `describe` 宣告為 `String?`,呼叫端以
  `?: "L1 佔位:描述失敗"` 兜底,避免熱路徑 NPE。
- 真後端:模型載入失敗須回可辨識錯誤字串(不崩潰),並在 UI 標示後端狀態。

## 6.1 真後端狀態管理(前瞻)

佔位後端無狀態,JNI 每次呼叫新建即可。真 `LlamaCaptioner` 需**載一次模型、跨放行幀重用**
(`&mut self`)。JNI 為靜態入口無法持有實例,計畫二選一:
- **Rust 端全域**:`static CAPTIONER: OnceLock<Mutex<Box<dyn Captioner>>>`,`describe` 取鎖呼叫;
  另設 `nativeInitVlm(modelPath, mmprojPath)` 做一次性載入。
- **handle 交 Kotlin**:`nativeInitVlm(...) -> jlong`(Box 指標),`describe(handle, …)`、
  `nativeFreeVlm(handle)`;由 Kotlin 持有生命週期。
偏好前者(較簡單、單一相機串流足夠);待真後端實作時定案。

## 7. 測試策略(必備)

- **Host `cargo test`(✅ 4):** 畸形安全、回報尺寸與 backend、暗/亮幀亮度極值、
  2×2 網格定位(下半亮 → `網格[0 0 / 100 100]`)。
- **裝置整合(✅):** 放行幀觸發、描述含正確尺寸/亮度/網格(Pixel 10)。
- **真後端(待):** 以固定測試圖對照描述關鍵詞;延遲量測。

## 追溯

滿足 [`SA.md`](SA.md) FR-1…FR-4、NFR-1…NFR-3。相關:[ADR-0008](../../adr/0008-l1-caption-engine.md)、[core-rs SD](../core-rs/SD.md)、[android SD](../android/SD.md)。
