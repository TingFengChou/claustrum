# events(L2 時序事件引擎)— 系統分析(SA)

**狀態:** P3 foundation active · **最後更新:** 2026-08-08 · **負責人:** claustrum
**設計:** [`SD.md`](SD.md)

## 1. 目的與範圍

L2 把裝置端輕量 pose/motion/action extractor 產生的時間序列，轉成可查證的安全事件。
它是 <1 秒快路徑的狀態機，不等待單次約 6.5–11.5 秒的 L1 VLM。VLM 只在事件建立後附加
客觀描述，不能單獨升級 candidate、risk 或 alert。

本階段交付純 Rust `EventEngine`、跨語言 `event.schema.json` 與合成序列測試。Android pose/
action extractor、JNI 串接、通知與實機資料校準是接續工作，不宣稱已完成。

## 2. 功能需求

| 編號 | 需求 |
|---|---|
| FR-1 | Fall:站立→快速下降→水平/倒臥；撞擊強證據可在 1 秒窗內確認，否則持續倒臥後確認 |
| FR-2 | ZoneExit:只有可見的區域邊界穿越才建立事件；離開本身不自動視為風險 |
| FR-3 | Violence:同一匿名兩人角色 pair 在 1 秒窗內重複滿足近距離、高動作、高 strike score 才確認 |
| FR-4 | candidate 與 confirmed 分離；只有 confirmed + 非 none risk 才可交由下游考慮告警 |
| FR-5 | 所有非 none risk 必須含 `fast_path` 畫面可見證據與 reason |
| FR-6 | L1 caption 可作 VLM 二階佐證，但不得改變 status/risk/confidence/latency |
| FR-7 | 輸出可序列化為 `schemas/event.schema.json`，不含影格、人物身分或年齡/臉部欄位 |

## 3. 非功能需求

- **誤報優先:** 產品紅線 `<1/24h`；正常坐下、單一高動作 sample、不同角色 pair 混合不得確認。
- **即時:** 狀態機每筆 observation 為小型記憶體操作；強證據 fall/violence 的事件窗 <1 秒。
- **有界:** stale actant/pair 狀態定期移除；事件 cooldown 防重複。
- **可測:** Rust host 測試只用合成 observation，不需相機、模型或 Android。
- **隱私:** Actant 是短時窗匿名角色槽位，不是身分；引擎不接收或保存 pixels。

## 4. 領域模型

- `Observation`:timestamp、匿名角色、pose 與 0..1 的 descent/impact/motion/contact/strike 特徵。
- `Event`:type/status/time/source/actants/evidence/risk/confidence/detector/latency。
- `Evidence.source`:`fast_path` 或 `vlm`；非 none risk 至少一筆必須為 `fast_path`。
- `ZoneExit`:客觀事件但 `risk.none`；若日後要變成 child hazard，須由另一條具可見證據的規則處理。

## 5. 驗收

- 撞擊型 fall 與高精度 violence 合成序列在 1 秒事件窗內產生 confirmed。
- 無撞擊 fall 先在 1 秒內產生 candidate，持續**可見**倒臥達門檻才 confirmed；恢復站立即
  取消，`Unknown` tracking miss 會中斷 dwell 計時。
- 正常坐下、孤立高動作、stale/out-of-order、不同 pair 的動作都不 confirmed。
- VLM-only payload 無法通過非 none risk 的 schema 驗證。
- Rust serialization 欄位/enum 與 JSON Schema transport shape 一致。

## 6. 限制

- 目前 score 是 extractor 的輸入契約，尚未決定/接上 LiteRT pose/action 模型。
- 預設 thresholds 是保守起點，不等於已達 `<1/24h`；須用 72 小時無事件語料與演練素材校準。
- `latency_ms` 是事件時窗延遲，不含 CameraX/extractor/JNI/通知；端到端 p95 需實機量測。

## 追溯

[issue #26](https://github.com/TingFengChou/claustrum/issues/26)、
[ADR-0006](../../adr/0006-safety-alert-mvp.md)、[core-rs](../core-rs/SA.md)、
[`event.schema.json`](../../../schemas/event.schema.json)。
