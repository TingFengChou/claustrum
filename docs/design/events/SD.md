# events(L2 時序事件引擎)— 系統設計(SD)

**狀態:** P3 foundation active · **最後更新:** 2026-08-08 · **負責人:** claustrum
**分析:** [`SA.md`](SA.md)

## 1. 元件

| 元件 | 職責 |
|---|---|
| `core-rs/src/events.rs::EventEngine` | 依 timestamp 消費 observation，輸出新跨越的事件狀態 |
| `TrackState` | 每個匿名 actant 的 fall/zone 狀態；stale 後清除 |
| `ViolenceState` | 每個排序後匿名 actant pair 的 hit window、candidate 與 cooldown |
| `Event::add_vlm_corroboration` | 在事件後附加 bounded L1 描述；不改判斷欄位 |
| `Event::to_json` | serde 序列化為跨 JNI/語言 transport shape |
| `schemas/event.schema.json` | Event 契約與 anti-hallucination/privacy 防線 |

## 2. Fall 狀態機

```mermaid
stateDiagram-v2
  [*] --> Tracking
  Tracking --> Candidate: "站立後 ≤1s 內快速下降 + 水平/倒臥"
  Candidate --> Confirmed: "同時高 impact（快路徑）"
  Candidate --> Confirmed: "持續水平/倒臥 ≥2s（保守路徑）"
  Candidate --> Tracking: "恢復站立 / 坐下 / 蹲下 / observation 中斷"
  Candidate --> Candidate: "Unknown 中斷連續倒臥 dwell；下次可見倒臥重新計時"
  Confirmed --> Tracking: "恢復站立"
```

- 快路徑 evidence:`upright_pose`、`rapid_descent`、`horizontal_or_prone_pose`、`impact_motion`。
- 無 impact 時先輸出低風險 candidate；只有持續倒臥才 confirmed，避免刻意坐下/蹲下誤報。
- confirmed 後維持 latch，直到角色恢復站立，不會每幀重複事件。

## 3. Violence 狀態機

一筆 hit 必須同時滿足:

- `visible_people >= 2` 且 observation 明確提供不同的 `secondary_actant`；
- `motion >= 0.85`、`close_contact >= 0.80`、`strike >= 0.90`；
- 同一排序後匿名 pair 才能累積，1 秒窗內 2 hits → candidate、4 hits → confirmed；
- confirmed 後同 pair 冷卻 30 秒。

不同 pair 絕不共用計數器，避免繁忙場景把無關動作拼成暴力。設定驗證要求 candidate 至少
2 hits、confirmed 必須多於 candidate hits，且所有 score threshold 必須大於 0，避免錯誤設定
把單一或預設零分 sample 變成事件。`strike_score` 必須來自後續經素材校準的動作模型；在
extractor 尚未接上前，本 detector 只有 host-testable contract。

## 4. ZoneExit

`zone_exit=true` 必須是 tracker 判定的「穿越已設定畫面邊界」one-shot，不是「畫面中沒看到人」。
輸出 confirmed `zone_exit`，但 risk 固定 `none/none/null`。同角色 5 秒 cooldown 去重。

## 5. VLM 二階佐證

`add_vlm_corroboration(at, caption)` 只新增 `source=vlm`、`kind=vlm_visible_description` 的 evidence，
截斷至 240 字。方法不寫入 status、risk、confidence、latency；schema 亦規定非 none risk 至少包含
一筆 `fast_path` evidence，因此 VLM-only 事件無法通過契約。

## 6. 時序、錯誤與資源

- Engine 假設同 source observation 依 timestamp 排序；out-of-order 直接忽略，避免回捲狀態。
- score 非 finite 視為 0，其他值 clamp 0..1。
- `EventEngine::new` 先驗證 source 長度、score thresholds、時間窗/hit/retention 關係；不合法回
  `EventConfigError`，不讓「所有影格皆命中」的壞設定進入監看。
- 單一 actant observation gap >750ms 會中斷 fall transition；candidate 期間的 `Unknown` pose
  也會中斷「持續倒臥」計時，後續可見倒臥須重新累積 dwell。
- actant/pair 超過 60 秒未見即移除，避免長期監看狀態無界成長。
- ID 為 `evt_<detected_at_ms>_<sequence>`；source_id 是邏輯位置，不是硬體序號。

## 7. Schema/serde 對應

Rust enums 使用 `snake_case` serde；`Event.event_type` rename 為 JSON `type`，`Actant.actant_type`
rename 為 `type`，risk 為 nested object。`to_json` 是正式 transport 邊界。Schema 額外限制:

- `additionalProperties:false`，拒絕 raw frame/keyframe 欄位；
- actant label 只接受 `person_<number>`；
- event type 與 risk category 必須一致；zone_exit risk 必須 none；
- violence 至少兩個 actants；非 none risk 必須有 fast_path evidence + non-empty reason。

## 8. 測試策略

- Rust 合成序列:正常坐下、impact fall、dwell fall、Unknown 中斷 dwell、恢復取消、gap、zone
  去重、孤立 motion、repeated violence、pair 隔離、危險設定拒絕、VLM 不升級、NaN/out-of-order、
  serde transport shape。
- Python schema:合法 fall、reason 必填、VLM-only 拒絕、zone neutral、角色 privacy、禁止額外 payload。
- 接上 extractor 後必補錄影素材 confusion matrix、p95 end-to-end latency、72h negative corpus 與
  `<1/24h` 誤報門檻；目前 host 測試不能替代實機校準。

## 追溯

[SA](SA.md)、[`events.rs`](../../../core-rs/src/events.rs)、
[`event.schema.json`](../../../schemas/event.schema.json)、[ADR-0006](../../adr/0006-safety-alert-mvp.md)。
