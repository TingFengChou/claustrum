# 設計文件

`claustrum` 的每個模組或段落都保有一份完整的 **SA**(System Analysis)與
**SD**(System Design)文件。這是專案的常設規則 — 見 `dev-standards` skill。過時的設計
文件會被視為 bug:SA/SD 要在與其所描述程式碼相同的 PR 中一併更新。

## 目錄配置

```
docs/design/
  README.md            本檔 — 慣例說明
  _template/
    SA.md              複製它來開始一個模組的 System Analysis
    SD.md              複製它來開始一個模組的 System Design
  <module>/
    SA.md              模組必須做什麼,以及為什麼
    SD.md              它如何做到
```

## 模組索引(對應 ADR-0007 Rust 重建)

| 模組 | 狀態 | 文件 |
|---|---|---|
| [`core-rs/`](core-rs/) | 🟢 P0(L0 閘控 + JNI) | [SA](core-rs/SA.md) · [SD](core-rs/SD.md) |
| [`android/`](android/) | 🟢 P0/P1(外殼 + CameraX×L0) | [SA](android/SA.md) · [SD](android/SD.md) |
| `events/` | 📐 P3 規劃(L2/L3 時序事件) | 與實作 PR 一併補上 |
| [`core/`](core/) · [`medication/`](medication/) · [`app/`](app/) | 🗄️ ADR-0007 前的參考 | 各 SA/SD |

新模組:複製 [`_template/`](_template/) 開始。此索引隨模組里程碑更新(見 dev-standards skill)。

## SA 與 SD — 分工

- **SA 回答*什麼*與*為什麼*。** 範圍、參與者、功能與非功能需求、模組觸及的領域模型、
  限制、驗收標準,以及對 ADR 與 roadmap 的追溯。不含實作。
- **SD 回答*如何*。** 元件及其職責、介面與合約、資料結構、關鍵流程(附 Mermaid 圖)、
  錯誤處理、相依性,以及 — 必要的 — 一個**測試策略**段落,因為每個模組都必須可測試
  (dev-standards skill)。

每份文件寧可短而真實,也不要長而空談。若某段落不適用,寫上「n/a」並說明原因,而不是硬湊
內容。

## 追溯

具有長遠影響的設計決策記錄為 ADR,放在 [`docs/adr/`](../adr/),而不是埋在 SD 裡。
SA/SD 引用相關的 ADR,而不是重述它。目前的平台現實(手機優先、單節點)是
[ADR-0004](../adr/0004-phone-first-single-node.md)。
