# vlm(L1 場景描述)— 系統分析(SA)

**狀態:** active(介面 + 佔位後端已落地) · **最後更新:** 2026-08-06 · **負責人:** claustrum
**設計:** [`SD.md`](SD.md)

## 1. 目的

L1:把 L0 **放行**的幀變成一句場景描述,供 L2 事件判斷(跌倒/暴力…)使用。引擎為
on-device VLM(llama.cpp),但 L1 對外只暴露一個可抽換的 `Captioner` 邊界(見 ADR-0008)。

## 2. 範圍

- **在範圍內(已落地):** `Captioner` trait、佔位後端 `PlaceholderCaptioner`(誠實診斷)、
  JNI `describe(...)`;放行幀 → 描述字串。
- **在範圍內(待實作):** `LlamaCaptioner`(llama.cpp + mmproj,SmolVLM/Gemma)。
- **不在範圍內:** L0 閘控(見 [`core-rs` gate](../core-rs/SA.md))、L2 事件(見 [`events`](../../ROADMAP.md))、影格回傳/落地。

## 3. 需求

| 編號 | 需求 |
|---|---|
| FR-1 | `describe(luma, w, h)` 回一句場景描述字串;只在 L0 放行時被呼叫 |
| FR-2 | 後端可抽換:換 VLM 引擎不動管線與 JNI 簽章 |
| FR-3 | 佔位後端回**誠實診斷**(尺寸/亮度/2×2 網格),明確標示未載入 VLM,不偽造理解 |
| FR-4 | 畸形輸入(零維度、luma 過短)回安全字串,不 panic |
| NFR-1 | 隱私:只回字串;像素不過橋回傳、不落地 |
| NFR-2 | 可測試:純邏輯(佔位後端 + 診斷統計)host `cargo test`,無硬體 |
| NFR-3 | 真後端:模型載一次、跨放行幀重用(`&mut self` 狀態) |

## 4. 相依與假設

- 上游:L0 放行決策(Kotlin `ChangeGate`)。輸入為單通道 luma。
- 真後端相依:llama.cpp(cmake + NDK 建置)、模型 GGUF + mmproj、可能的 `libmtmd`。
  **這些前置尚未滿足**(見 ADR-0008 §4),需下載授權與選型。

## 5. 驗收

- **佔位(✅ Pixel 10):** 放行幀觸發 L1;描述含正確 `640×480`、亮度%、2×2 網格;
  略過幀不重算;host 4 tests 綠。
- **真後端(待):** 選定模型在裝置端對放行幀產生合理中文場景描述,延遲可接受。

## 追溯

滿足 ADR-0008。相關:[ADR-0007](../../adr/0007-rust-first-redesign.md)、[core-rs](../core-rs/SA.md)、[android](../android/SA.md)。
