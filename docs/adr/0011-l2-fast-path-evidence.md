# ADR-0011 — L2 以快路徑可見證據確認；VLM 僅作脈絡

**狀態:** 已接受 · **日期:** 2026-08-08
**延續/修正:** [ADR-0006](0006-safety-alert-mvp.md)、[ADR-0009](0009-edge-ai-litert-ai-edge.md)

## 背景

Pixel 10 實測 Gemma 3n E2B L1 單張約 6.5–11.5 秒，且遠景/小主體會漏報或幻覺。讓 VLM
位於跌倒/暴力的確認關鍵路徑，無法達成 <1 秒快速偵測，也會把模型幻覺變成對外通報依據。
這與首要指標「誤報 <1/24h」及 `risk.level != none` 必須有畫面可見證據衝突。

ADR-0006 原先的「pose 快路徑(recall)+確認(precision)」仍成立，但其中「逐步加入 VLM 確認」
需依實機證據收窄。

## 決策

1. L2 確認由裝置端輕量 pose/motion/action **多訊號時序證據**完成，不等待 L1。
2. 事件分 `candidate` / `confirmed`；只有 `confirmed` + medium/high risk + `fast_path` evidence
   才可進入通知/人工確認層。
3. VLM caption 只能在事件建立後附加為 bounded 客觀脈絡；**不得單獨建立、確認、升級 risk、
   confidence 或 alert eligibility**。
4. 弱 fast-path signal 不因 VLM 說「跌倒/暴力」而升級。看不清時保持 candidate 或捨棄。
5. ZoneExit 等客觀事件不自動等同危險；沒有額外可見 hazard evidence 時 risk 固定 none。
6. Event schema 強制非 none risk 至少一筆 fast-path evidence，並要求事件類型專屬 evidence。

## 理由

- **延遲:** 強證據 fall/violence 可在 1 秒 observation window 內確認。
- **抗誤報:** 多訊號、持續時間、角色 pair 隔離與 cooldown 比單張文字判斷更可回歸。
- **可測:** Rust 狀態機能用合成序列與 negative corpus 測試；VLM 自然語言輸出不適合作唯一安全 gate。
- **隱私:** L2 只接收匿名結構化 observations；影格不需跨模組或上雲。

## 後果

- 首條 ML Kit 單人 pose fast path 已接上，但仍須校準 high-precision thresholds、補 impact/多人
  action 與 negative corpus；在此之前不宣稱真機告警已可用。
- 無 impact 的 fall 會先 candidate，持續倒臥後才 confirmed；這是為降低誤報接受的確認延遲。
- VLM 仍有價值:提供人工檢視/事件記錄的客觀文字脈絡，但不是安全裁決者。
- ADR-0006 的 MVP 場景、多模態方向與人工確認原則不變；只取代「VLM 作確認 gate」的解讀。

## 追溯

[`events` SA/SD](../design/events/SA.md)、[`event.schema.json`](../../schemas/event.schema.json)、
[issue #26](https://github.com/TingFengChou/claustrum/issues/26)。
