# 架構

## 唯一真正重要的設計決策

裝置端的 VLM 吃的是**影像**,而不是影片串流。把 30 fps 直接餵給 VLM 不是調參問題,而是一道永遠算不平的算術。本專案其餘每一個決策,都源自於在推論之前,時間軸被壓縮得多麼激進。

## 分層

### L0 — 閘控(Gating)

成本低、永遠開著、毫秒級。決定哪些瞬間值得付出昂貴的一次呼叫。

訊號:
- 影格差分(Frame differencing)— 到底有沒有任何東西在動
- 物件偵測(Object detection)— 是否有人 / 動物 / 相關物件出現
- 姿態關鍵點(Pose landmarks)— 骨架關鍵點,同時餵給閘控與 L2 的快速路徑
- 影格嵌入(Frame embedding)— 與上一張已描述影格的餘弦相似度

策略:

```
if no motion and last kineme < 15 min ago:        skip, record "quiet"
elif no motion and last VLM call > 15 min ago:    heartbeat call (confirm scene state)
elif motion and scene similarity > 0.9:           skip (same ongoing action)
elif pose matches hazard pattern:                 → L2 fast path, highest priority
else:                                             → L1 caption
```

相似度檢查之所以存在,是因為一個人靜靜坐著看電視二十分鐘,不該產生二十個一模一樣的 Kineme。少了它,L3 的摘要會被一堆「什麼都沒發生」的重述給淹沒。

目標:每秒 0.05–0.5 個關鍵影格 — 60 倍到 600 倍的壓縮。

### L1 — 影像描述(Caption)

手機上的 Gemma E2B / E4B(LiteRT-LM),常駐記憶體,每個被選中的關鍵影格呼叫一次(或每個 2×4 時間網格一次 — 見 M0 spike)。ADR-0001 假設的 12B 模型塞不進手機,也塞不進 Jetson Nano;見 [ADR-0004](adr/0004-phone-first-single-node.md)。

輸入:關鍵影格 + 前一個 Kineme 的一行摘要,用以維持連續性。
輸出:一個符合 [`schemas/kineme.schema.json`](../schemas/kineme.schema.json) 的結構化 `Kineme`。

模型會回報 `confidence`,但**不會**回報 `novelty`。novelty 是一個 Kineme 相對於其鄰居的性質,而只看到單一瞬間的模型無從計算;這個值由管線根據 L0 的影格嵌入距離填入。任何需要跨 Kineme 比較的工作,都是管線的職責,不是模型的。

模型選擇是抗幻覺(hallucination-control)的決策,不是效能決策。見 ADR-0001。

### L2 — 警示(Alerting)

**刻意拆成兩條路徑。**

一次 VLM 呼叫要花上數秒。若讓它獨自判定一次跌倒,這樣的延遲無法接受。姿態啟發式(pose heuristic)可以在毫秒內回應,但只要有人坐下、彎腰撿東西、躺在沙發上,它都會誤觸發 — 它的誤報率高到單獨使用毫無用處。

所以:

```
L0 pose heuristic detects candidate
    │
    ├──▶ immediately enter PENDING state, start buffering frames
    │
    └──▶ VLM examines 3 frames (before / during / after):
         "Is this person falling, or lying down / sitting deliberately?
          Did they get up afterwards?"
              │
              ├── confirmed  → dispatch alert   (total latency ~3–5 s)
              └── rejected   → record silently, do not notify the user
```

**啟發式(heuristic)負責召回率(recall),VLM 負責精確率(precision)。**這是本專案主要的技術貢獻,也是它與現成 AI 攝影機最大的區別所在。

上層再疊加抑制(suppression)規則:在一個時間窗內去重、依類別做速率限制、並要求在同一地點出現一次被否決的候選之後有一段冷卻期。

### L3 — 摘要(Summarize)

純文字 LLM,在閒置期間以批次執行。階層式:Kineme → 15 分鐘窗格 → 小時 → 每日的 `Ethogram`。

Kineme 依 `novelty` 與 `confidence` 加權,決定是否納入。異常(Anomaly)是透過與觀察對象**自身**前兩週的歷史比較來偵測,而不是對照絕對規則。這是一種低成本的做法,能把系統從「基於規則的警報器」推進到「有記憶的觀察者」。

### L4 — 查詢(Query)

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

模型容量過去是第一線防線 — ADR-0001 正是為此仰賴 12B。在手機上(以及最終的 Jetson Nano 上)這個槓桿已不復存在:E2B/E4B 模型的虛構比 12B 模型**更多**,而非更少。因此結構性與提示層級的防禦扛起了重擔,而且它們如今是第一線,不再是第二線 — 見 [ADR-0004](adr/0004-phone-first-single-node.md)。

各項防禦,依其在小模型上的有效程度排序:

1. **明確的單一瞬間框定。**提示明白告知模型它只看到一個瞬間,不得推論畫面外的事件。
2. **`unclear` 是有效答案。**給模型一個不是靠捏造的出口。
3. **`risk` 必須有事件實際發生的證據。**「可能有危險」不算數。少了這一條,一把擱在流理台上的刀會被回報成孩童的危害。
4. **封閉的風險列舉。**固定的 `RiskCategory` 集合,能阻止模型發明出 L2 規則將永遠默默無法匹配的類別。

正因為模型很小,M1 的幻覺閘門更為吃重,而非更輕。把那裡的退步視為發布的阻擋項(release blocker)。

幻覺率是一項被追蹤的退步指標,不是一個口號式的期望。見 [`eval/`](../eval/)。

## 雲端升級(Cloud escalation)

預設:完全離線。存在一條可選路徑:

```
L1 confidence < 0.5 and risk != none
  or user explicitly asks for a closer look
      │
      ▼ per-instance explicit user consent
      └──▶ Gemini Robotics-ER 1.6 / Gemini 3 Flash
           precise spatial reasoning, pointing, multi-view success detection
```

限制:對使用者可見、預設關閉、僅單一張去識別化影格、絕不上傳影片。

## 機器人延伸

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
