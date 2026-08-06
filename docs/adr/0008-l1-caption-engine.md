# ADR-0008 — L1 場景描述引擎:llama.cpp(Rust FFI)+ 可抽換 Captioner 介面

**狀態:** 已接受(介面與佔位後端已落地;真實後端待模型與原生建置) · **日期:** 2026-08-06
**延續:** [ADR-0007](0007-rust-first-redesign.md)(Rust 優先、效能優先)
**保留:** [ADR-0004](0004-phone-first-single-node.md)、[ADR-0006](0006-safety-alert-mvp.md)

> 北極星不變:即時串流 · Edge AI · 主動防護。本 ADR 決定 L1(把「放行幀」變成場景描述)
> 的引擎與**軟體邊界**,讓真實 VLM 後端可在不動管線的前提下抽換進來。

## 背景

L0 變化閘控(P1)已在裝置端運作:只有「場景有變」的幀會被放行。L1 要把這些放行幀變成
一句場景描述(之後餵給 L2 事件判斷:跌倒/暴力…)。重運算 VLM 本來就在 C++(llama.cpp),
ADR-0007 已定調「llama.cpp via Rust FFI」。

本 ADR 要處理兩件事:
1. **軟體邊界**——L1 不該和某個特定推論庫綁死;要能先用佔位、再換真模型、日後甚至換引擎。
2. **真實後端的前置條件**——誠實記錄它需要什麼,避免把「還沒驗證」講成「已完成」。

## 決策

### 1. `Captioner` trait 作為 L1 的單一邊界(已落地)

`core-rs` 新增 `vlm` 模組,定義:

```rust
pub trait Captioner {
    fn describe(&mut self, luma: &[u8], width: usize, height: usize) -> String;
    fn backend(&self) -> &'static str;
}
```

- 管線(Kotlin CameraX → JNI → Rust)只認這個 trait;JNI `NativeCore.describe(...)` 的
  簽章固定,換後端不動 Android 端。
- `&mut self`:真後端可持有模型/context 狀態跨呼叫(載一次、重複用)。
- **影格不過橋回傳**:只回描述字串;像素留在原生層(隱私 + 效能,延續 ADR-0007)。

### 2. 佔位後端 `PlaceholderCaptioner`(已落地、已裝置驗證)

在真模型就緒前,回傳**誠實診斷**(尺寸、平均亮度、2×2 亮度網格),並明確標示
「未載入 VLM」。它**不偽造**場景理解,只證明放行幀正確抵達 describe()。這讓 L0→L1
觸發管線能端到端測試與裝置驗證(Pixel 10 已驗:放行幀觸發、640×480、亮度/網格正確)。

### 3. 真實後端:llama.cpp,經 Rust FFI(待實作)

- 綁定:優先評估 `llama-cpp-2`(crates.io 可取,`llama-cpp-sys-2` 由 cmake 建 llama.cpp)。
- **多模態**:SmolVLM / Gemma 的視覺需 llama.cpp 的 `libmtmd`(mmproj 投影器)。若
  `llama-cpp-2` 未包 mtmd,則對 `libmtmd` 走原生 FFI(或以 C shim 封裝)。**待建置時確認。**
- 舊 RN 版曾以 `llama.rn`(內含 llama.cpp)在本機 Pixel 10 驗證過 SmolVLM 多模態可行——
  作為「裝置能跑」的既有證據,但不直接重用其 RN 綁定。

### 4. 真實後端的前置條件(尚未滿足,需使用者參與)

| 前置 | 狀態 | 說明 |
|---|---|---|
| `cmake` | ❌ 未安裝 | 建 llama.cpp 需要;`brew install cmake`(免密碼) |
| 交叉編譯 | ⚠️ 未驗 | `llama-cpp-sys-2` 經 cargo-ndk 為 arm64 建 C++(GGML backend flags 需調) |
| 多模態支援 | ⚠️ 待確認 | `llama-cpp-2` 是否含 mtmd;否則走 `libmtmd` FFI |
| 模型檔 | ❌ 無 | GGUF + mmproj(SmolVLM-256M ~250MB 或 Gemma E2B/E4B);需**下載授權**與**選型** |

## 後果

- **好處:** L1 管線今晚即可端到端驗證,重/高風險的原生建置與模型下載被隔離成獨立、可聚焦的
  下一步;換後端零管線改動。
- **代價:** 佔位後端不是真理解;必須在 UI 與文件明確標示,避免誤導(已標示)。
- **待辦:** 安裝 cmake → cargo-ndk 建 llama.cpp `.so` → 確認 mtmd → 下載選定模型(需授權)→
  實作 `LlamaCaptioner: Captioner` → 裝置驗證真描述。

## 追溯

實作:[`core-rs` vlm](../design/vlm/SD.md)。相關:[ADR-0007](0007-rust-first-redesign.md)、
[ROADMAP P2](../ROADMAP.md)。
