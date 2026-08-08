# events(L2 時序事件引擎)— 系統分析(SA)

**狀態:** P3 fast path active · calibration pending · **最後更新:** 2026-08-08 · **負責人:** claustrum
**設計:** [`SD.md`](SD.md)

## 1. 目的與範圍

L2 把裝置端輕量 pose/motion/object extractor 產生的時間序列，轉成可查證事件。近期產品範圍
只含跌倒／倒地與亂丟垃圾(ADR-0012)；既有 ZoneExit/Violence foundation 不代表近期產品承諾。
它是 <1 秒快路徑的狀態機，不等待單次約 6.5–11.5 秒的 L1 VLM。VLM 只在事件建立後附加
客觀描述，不能單獨升級 candidate、risk 或 alert。

本階段已交付 Rust `EventEngine`、跨語言 `event.schema.json`、Android `FastPathObservation`、
具生命週期的 JNI engine bridge，以及 CameraX→ML Kit 單人 pose→Rust 的第一條實際 fast path。
Android 另已接 MediaPipe category/score/bbox candidate、session-local 匿名幾何 tracker 與
fail-closed litter evidence stage，但尚未產生 L2 `ObjectObservation` 或 Event。它們都未經真實
素材校準，也沒有 impact/多人 action、可靠多人 association、通知或 UI policy，因此不宣稱可部署告警。

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
| FR-8 | Android 以可關閉的 opaque handle 管理每個 camera source 狀態；JNI 只傳 observation，每個返回字串為單一 Event JSON |
| FR-9 | Litter:只有匿名人—物 association 顯示連續近接→可見分離→stationary/dwell，且先看到人物拉遠、之後人離開而物仍在 ROI，才可建立 candidate；單一物件 detection／person miss 不成立。Android 已完成 pre-Event evidence stage，L2 candidate/schema 尚待 #39 |

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
- `ObjectObservation`(規劃):匿名 person/object slots、category、bbox/zone、association confidence、
  motion state 與 dwell；不含 crop、影格、臉或跨 session ID。
- Android `P/O` 槽位只在同一守護 session、同類別 bbox 幾何連續時存在；不是身分、不可跨 session
  re-identify，也尚未成為 schema transport。

## 5. 驗收

- 撞擊型 fall 與高精度 violence 合成序列在 1 秒事件窗內產生 confirmed。
- 無撞擊 fall 先在 1 秒內產生 candidate，持續**可見**倒臥達門檻才 confirmed；恢復站立即
  取消，`Unknown` tracking miss 會中斷 dwell 計時。
- 正常坐下、孤立高動作、stale/out-of-order、不同 pair 的動作都不 confirmed。
- VLM-only payload 無法通過非 none risk 的 schema 驗證。
- Rust serialization 欄位/enum 與 JSON Schema transport shape 一致。
- Litter 必須以丟棄、合法暫放後取回、既有物、撿拾／清潔、多人交錯與日夜資料驗收；
  association 不確定或 dwell 未滿不得 confirmed。

## 6. 限制

- 首版 extractor 使用 bundled ML Kit base Pose Detection `STREAM_MODE`，只追最顯著的一人且 API
  為 beta；官方限制臉部需可見、完整身體取景最佳，遮擋/背向/倒地臉部不可見可能漏報。適合
  單人跌倒候選，不覆蓋多人 violence、zone crossing 或 impact。
- pose-only `impact_score/close_contact_score/strike_score` 固定 0；沒有第二 actant，不能觸發
  impact fast-confirm 或 violence。無 impact fall 需持續可見倒臥後才 confirmed。
- 首版任何可判 pose 都要求**雙側肩與髖**達信心門檻；Upright/Seated 再要求雙側膝踝，
  Horizontal 則允許下半身遮擋。左/右只剩單側時回 `Unknown`，不以半套軀幹推導風險。
  是否加入單側 fallback 必須以 confusion matrix 同時證明 recall 增益與 `<1/24h` 誤報門檻。
- 相鄰可靠 pose 的中心跳位 >1.25 body spans 會輪替 slot。低 detector fps 的真實快速跌倒可能
  觸發此防跨人規則；校準須同時記錄 slot-rotation rate 與 fall false negatives，再決定是否改為
  速度/姿態條件式門檻，不能只為 recall 直接放寬。
- 預設 thresholds 是保守起點，不等於已達 `<1/24h`；須用 72 小時無事件語料與演練素材校準。
- `latency_ms` 是事件時窗延遲，不含 CameraX/extractor/JNI/通知；端到端 p95 需實機量測。
- MediaPipe Object Detector 只提供 category/score/bbox 且沒有可依賴的 tracking ID；Android 以
  `VIDEO` + current/latest queue 主動合併中間候選，語意上同樣不是逐幀 recall 保證。COCO 的
  bottle/cup 也不等於垃圾。短時 greedy geometry tracker 與 pre-Event evidence state 已接，但
  遮擋／多人多物交錯會 ID-switch；ROI、場域門檻、`ObjectObservation` schema、L2 Event 與
  hard-negative 驗收仍追蹤於 issue #39。

## 追溯

[issue #26](https://github.com/TingFengChou/claustrum/issues/26)、
[ADR-0006](../../adr/0006-safety-alert-mvp.md)、
[ADR-0012](../../adr/0012-two-scenario-mvp-and-object-gating.md)、[core-rs](../core-rs/SA.md)、
[`event.schema.json`](../../../schemas/event.schema.json)。
