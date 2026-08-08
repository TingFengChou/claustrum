# 架構

## 唯一真正重要的設計決策

裝置端的 VLM 吃的是**影像**,而不是影片串流。把 30 fps 直接餵給 VLM 不是調參問題,而是一道永遠算不平的算術。本專案其餘每一個決策,都源自於在推論之前,時間軸被壓縮得多麼激進。

## 分層

### L0 — 閘控(Gating)

成本低、永遠開著、毫秒級。決定哪些瞬間值得付出昂貴的一次呼叫。

目前 L0 訊號是 luma 64-bit aHash 與前一影格的 Hamming distance。另有 ML Kit base pose
`STREAM_MODE` + Kotlin 時序特徵作為獨立 L2 fast path；它在 L0 gate 之前處理每個 CameraX
analysis frame，不被只為 L1 節流的 aHash gate 擋住。物件偵測、frame embedding、impact 與
多人 action extractor 仍是候選或後續工作，不得當成現況。

下列是目標策略草圖，不是目前已上線的行為:

```
if no motion and last kineme < 15 min ago:        skip, record "quiet"
elif no motion and last VLM call > 15 min ago:    heartbeat call (confirm scene state)
elif motion and scene similarity > 0.9:           skip (same ongoing action)
elif pose matches hazard pattern:                 → L2 fast path, highest priority
else:                                             → L1 caption
```

相似度檢查之所以存在,是因為一個人靜靜坐著看電視二十分鐘,不該產生二十個一模一樣的 Kineme。少了它,L3 的摘要會被一堆「什麼都沒發生」的重述給淹沒。

壓縮率必須以實際部署場景的 runtime telemetry 回報，不預設固定百分比或「不漏事件」。L0
只負責控制 L1 成本，不能承擔安全事件召回保證。

### L1 — 影像描述(Caption)

手機上的 Gemma E2B / E4B(LiteRT-LM),常駐記憶體,每個被選中的關鍵影格呼叫一次(或每個 2×4 時間網格一次 — 見 M0 spike)。ADR-0001 假設的 12B 模型塞不進手機,也塞不進 Jetson Nano;見 [ADR-0004](adr/0004-phone-first-single-node.md)。

目前輸入是單一放行關鍵影格 + 固定客觀 prompt；每幀使用新的 Conversation，避免跨幀上下文
污染。輸出是繁中單句**可見場景描述文字**，不含 risk/event 判斷，也沒有可當真值使用的校準
confidence。若後續建 Kineme，由管線填入 model/prompt/novelty 等 metadata；`novelty` 必須由 L0/
時間序列計算，絕不由只看到單一瞬間的模型回報。任何跨幀比較都屬管線/L2，不是 L1。

模型選擇同時影響幻覺、描述品質、延遲、記憶體與熱功耗；必須用同一組素材量測，不以模型
大小或單一跑分臆測。見 HANDOFF issue #29。

### L2 — 警示(Alerting)

**事件確認不等待 VLM。** 一次 L1 呼叫實測約 6.5–11.5 秒，且小模型會幻覺；它不能是
<1 秒偵測或對外通報的唯一 gate。L2 改用可回歸的 pose/motion/action 多訊號時序證據:

```
ML Kit 單人 pose + Kotlin extractor(已接、待校準)
    → FastPathObservation(timestamp + anonymous role + pose/descent/motion)
    → NativeEventEngine / JNI opaque handle → Rust EventEngine
    │
    ├── Fall:站立 → ≤1s 快速下降 + 水平/倒臥
    │      ├── 高 impact → confirmed fast path
    │      └── 無 impact → candidate → 持續倒臥才 confirmed
    ├── Violence:同一匿名兩人 pair 在 1s 內重複高 motion/contact/strike → confirmed
    └── ZoneExit:可見邊界穿越 → confirmed neutral event(risk none)
                │
                ├── confirmed + medium/high + fast-path evidence → 通知/人工確認層
                └── L1 caption 後到時只附加客觀文字脈絡,不改 status/risk
```

目前 `FastPathObservation`、JNI create/process/destroy、Rust engine registry 與 CameraX→ML Kit
單人 pose 餐取均已實作。ML Kit 沒有公開 tracking ID；Android 在追蹤遺失/gap/大跳位時輪替
匿名 role slot，避免跨人拼接。Pose-only 不產生 impact、第二人、contact 或 strike，因此首版只
能建立單人 fall candidate，並在持續倒臥後 confirmed；Event 目前只寫 log，尚未進 UI/通知。
JNI 傳輸不含 pixels/landmarks，每個返回字串是一份 schema-aligned Event JSON。

正常坐下、單一高動作 sample、不同人物 pair 的動作不得拼成 confirmed。上層再做去重、類別
速率限制、冷卻與人工確認。完整決策見 [ADR-0011](adr/0011-l2-fast-path-evidence.md) 與
[`events` 設計](design/events/SD.md)。

### L3 — 摘要(Summarize；後續規劃，未實作)

純文字 LLM,在閒置期間以批次執行。階層式:Kineme → 15 分鐘窗格 → 小時 → 每日的 `Ethogram`。

Kineme 依 `novelty` 與 `confidence` 加權,決定是否納入。異常(Anomaly)是透過與觀察對象**自身**前兩週的歷史比較來偵測,而不是對照絕對規則。這是一種低成本的做法,能把系統從「基於規則的警報器」推進到「有記憶的觀察者」。

### L4 — 查詢(Query；後續規劃，未實作)

Kineme 及其嵌入存放在 NVMe 上的 SQLite。以自然語言檢索事件日誌,回傳文字與時間戳記。

## 領域型別(Domain types)

在 [`core/domain.py`](../core/domain.py) 中定義一次,並由 [`schemas/`](../schemas/) 中的 JSON Schema 對應。

```
Actant    a participant — role slot, never an identity
Kineme    one observed behaviour, one time span
Ethogram  a catalogue of kinemes over a period
```

Schema 與 dataclass 逐漸分歧,是這類管線中最常見的隱形 bug。CI 會雙向驗證兩者。

## 抗幻覺(Anti-hallucination)

一個看到單一靜態影格的小模型,會自行編造因果敘事 —「他跌倒了,然後爬起來去拿藥」,單憑一張照片。對一個安全警示系統而言,這不是品質問題,而是正確性的失敗。

不能只憑參數量推論 E2B/E4B 與 12B 的幻覺率；目前尚缺同素材、同 prompt 的對照評測。
無論模型大小，結構性防禦、輸出驗證與 L2 可見時序證據都是第一線——見
[ADR-0004](adr/0004-phone-first-single-node.md)與 HANDOFF issue #29。

各項防禦,依其在小模型上的有效程度排序:

1. **明確的單一瞬間框定。**提示明白告知模型它只看到一個瞬間,不得推論畫面外的事件。
2. **`unclear` 是有效答案。**給模型一個不是靠捏造的出口。
3. **`risk` 必須有事件實際發生的證據。**「可能有危險」不算數。少了這一條,一把擱在流理台上的刀會被回報成孩童的危害。
4. **封閉的風險列舉。**固定的 `RiskCategory` 集合,能阻止模型發明出 L2 規則將永遠默默無法匹配的類別。

正因為模型很小,M1 的幻覺閘門更為吃重,而非更輕。把那裡的退步視為發布的阻擋項(release blocker)。

幻覺率是一項被追蹤的退步指標,不是一個口號式的期望。見 [`eval/`](../eval/)。

## 雲端升級(目前不允許影格)

依 ADR-0010 與專案隱私不變式，感知與事件判斷預設完全離線，**影格/影像/音訊不送雲端**。
Firebase 等後端日後只能接收文字描述、結構化事件與不含 PII 的穩定度指標。若未來要改變這條
紅線，必須另立 ADR、完成 PDPA/同意與威脅模型審查；目前不存在「使用者同意就上傳單張」的程式路徑。

## 機器人延伸(後續構想，未實作)

家庭部署是第一個垂直領域。可長期沿用的資產是 L1 感知與 L4 語意記憶。

| 家庭 | 機器人 |
|---|---|
| L0 閘控 | 感知資源排程 |
| L1 Kineme | 環境的語意標註串流 |
| L2 警示 | 安全監督層 |
| L3 Ethogram | 長期場域記憶 — 這個空間平常長什麼樣子 |
| L4 查詢 |「我上次是在哪裡看到那台推車的?」|

整合順序:先做 MCP server(成本最低、重用度最高 — 任何 agent 都能查詢),接著是 ROS 2 節點,再來是空間錨定(spatial anchoring)。把里程計與地圖座標附加到 Kineme 上,能把事件日誌變成一張語意地圖,而正是在那一刻,這套系統才從「攝影機字幕」變成對場域的理解。

## 工具合約層(Tool contract layer)

AppFunctions 與網路 MCP server 是同一份合約之上的兩種傳輸方式:

```
core/tools/           single definition and implementation
  ├─ contract.py
  └─ impl.py
       ▲                    ▲
       │                    │
bridge/appfunctions/   bridge/mcp/
(Android, on-device)   (network — robots, desktop agents)
```

機器人通常跑 Linux 而非 Android,因此走網路 MCP 路徑。AppFunctions 則靠家庭端的使用者體驗贏得一席之地 — 直接向手機助理詢問家裡的狀況。
