<img src="assets/icon/claustrum.svg" width="88" align="right" alt="claustrum app icon"/>

# claustrum

**為具身 AI 打造的裝置端感知與認知能力。** 將視覺、音訊、語言等各自獨立的感官模態,綁定為一條統一、可查詢的觀察事件串流。全程在邊緣硬體上執行。

Pixel 10 · Gemma E2B / E4B · LiteRT-LM · 搭配 Android AppFunctions

> **目前階段:手機優先、單節點。** 在 Jetson 尚未就緒的期間,所有運算都跑在單一
> 台 Pixel 10 上。這改變了平台與隱私拓撲 —— 詳見
> [ADR-0004](docs/adr/0004-phone-first-single-node.md)。Jetson 雙節點設計
> ([ADR-0003](docs/adr/0003-two-node-topology.md)) 是日後要恢復的目標,而非目前
> 的建置。

---

## ⚠️ 請先讀這段

**這不是醫療器材,也不能取代真人照護。**

本專案的跌倒偵測與危害警示會漏掉事件,也會產生誤報。請勿將它當成任何人唯一的安全網。若要把它部署在住家中,請告知所有同住者、取得他們的知情同意,並提供一個可正常運作的關閉開關。

在把鏡頭對準任何人之前,請先閱讀 [`docs/PRIVACY.md`](docs/PRIVACY.md)。

---

## 它能做什麼

連續的攝影機與麥克風串流擷取成本低廉,卻幾乎不可能逐一檢視。`claustrum` 把它們壓縮成人類 —— 或機器人 —— 真正能用的東西:

| 你問 | 你得到 |
|---|---|
| 「今天家裡發生了什麼事?」 | 一條由離散、附時間戳的事件構成的時間軸 |
| 「昨天下午有人靠近藥盒嗎?」 | 一個以已記錄事件為依據的自然語言回答 |
| 有人在走廊跌倒 | 約 5 秒內的推播通知,並附上原因 |
| *(藥單)* 拍一張藥袋 | 裝置端讀出藥名與一般用途(教育性,非醫療建議;看不清不臆測) |
| *(機器人)* 「我上次在哪裡看到那台推車?」 | 一次針對語意空間記憶的查詢 |

所有運算都在裝置端執行。在手機優先的單節點上,影格依政策留在手機裡;而「影格無法離開」這項*結構性*保證,會隨著雙節點的 Jetson 拓撲一同回歸 (ADR-0003)。

## 名字的由來

**claustrum**(屏狀核)是一薄層神經元,幾乎與每一個大腦皮質區都有連結。Crick 與 Koch 曾提出,它正是把各自獨立的感官模態綁定為單一統一體驗的結構 —— 他們將它比喻為管弦樂團的指揮。

這正是本專案要做的事:把 ASR、VLM 與 LLM 的輸出綁定為一份連貫、依時間排序的理解。

## 領域詞彙

這套程式碼刻意採用一組貫徹一致的詞彙,取自動物行為學 (ethology)、身勢學 (kinesics) 與行動者網絡理論 (actor-network theory)。這三個傳統共享同一項方法論承諾:**記錄觀察到的事,不臆測動機。** 這項承諾是本專案的核心紀律,因此這些命名都承載著它。

| 詞彙 | 在此的意義 | 出處 |
|---|---|---|
| **Actant** | 場景中的一個參與者 —— `person_1`、`cat`、`robot_1`。是一個**角色槽位,而非身分。** | 行動者網絡理論 (Latour);結構符號學 (Greimas) |
| **Kineme** | 觀察到的行為之最小記錄單位。一個動作、一段時間。 | 身勢學 (Birdwhistell) —— 音素 (phoneme) 在身勢上的類比 |
| **Ethogram** | 一段期間內眾多 kineme 的目錄。系統的主要輸出。 | 動物行為學 —— 針對某物種各離散行為的正式清單 |

`Actant` 之所以是角色槽位而非某個人,並非偶然 —— 它就是隱私設計本身。本專案不做人臉辨識,也不做身分歸屬。詳見 [`docs/adr/0002-naming-and-domain-language.md`](docs/adr/0002-naming-and-domain-language.md)。

## 架構

連續影片無法逐格餵給 VLM。核心設計是一座**時間壓縮金字塔 (temporal compression pyramid)**:

```
 30 fps raw stream
     │
 L0  Gating          motion diff · pose landmarks · object detect · frame embedding
     │               → decides which instants deserve a VLM call
     │               → target: 100×+ compression
     ▼
 L1  Caption         Gemma 4 12B Unified → structured Kineme (JSON)
     │
     ▼
 L2  Alerting        fast path: pose heuristic  (recall)
     │               slow path: VLM confirmation (precision)
     ▼
 L3  Summarize       hierarchical: kinemes → 15 min → hour → daily Ethogram
     │
     ▼
 L4  Query           embedding index → natural-language retrieval
     │
     ▼
 consumers           push notification · AppFunctions · MCP · ROS 2
```

完整細節,包含 L2 為何拆成兩條路徑,詳見 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)。

## 模組

**產品主體是一支 React Native App**(ADR-0005);Python 是離線工具,不是產品。

| 模組 | 語言 | 狀態 | 用途 |
|---|---|---|---|
| `app/` | React Native + TS(+ 原生 Rust/C++ · Kotlin) | 🚧 進行中 | **產品本體** —— 裝置端感知 App(UI + 協調殼)。**目前重點。** |
| `schemas/` | JSON Schema | ✅ 就緒 | 領域型別的**單一真實來源**(跨 TS / Kotlin / Python) |
| `core/` | Python | 🚧 進行中 | 領域型別參考實作,供離線 eval/bench 使用 |
| `bench/` | Python | ✅ 就緒 | M0 後端基準測試(離線工具)—— 先跑這個 |
| `eval/` | Python | 🚧 進行中 | 離線評測評分(離線工具) |
| `asr/` `tts/` `planner/` `bridge/` | — | 規劃中 | 第二模態、LLM 編排、AppFunctions/MCP/ROS 2 橋接 |

裝置端的重負載(L0 閘控、影格串流、on-device VLM)以原生 **Rust/C++** 核心 + **Kotlin** 平台膠合實作,透過 NDK/JNI 橋接給 RN(ADR-0005)。**即時串流辨識**是終極目標。裝置端 VLM(**llama.rn / llama.cpp**)已接入並建置於 Pixel 10(APK 內含 `librnllama.so`);首個應用:**藥單辨識**(見 [`docs/design/medication/`](docs/design/medication/))。

## 部署拓撲

**現在 —— 手機優先、單節點** (ADR-0004):

```
┌──────────────────────────────────────────────┐
│  Pixel 10  ─ single node ─                     │
│                                                │
│  camera (on-device)                            │
│  L0 gating  ·  L1 Gemma E2B/E4B (LiteRT-LM)    │
│  L2 alerting · L3 summarize · L4 KinemeStore   │
│  AppFunctions provider · notifications          │
│  consent tiers · audit log                      │
│                                                │
│  ★ frames stay on device by policy             │
└──────────────────────────────────────────────┘
```

單一行程同時持有影格並回應查詢,因此影格隔離是靠政策而非拓撲來落實。這是手機優先轉向所必須承擔、且刻意為之的暫時代價;PRIVACY.md 對此有明確說明。

**日後 —— 雙節點,一旦 Jetson 就緒** (ADR-0003,已延後):

```
┌──────────────────────────────┐        ┌─────────────────────────────┐
│  Jetson  ─ sensor node ─     │  LAN   │  Pixel 10  ─ query surface ─│
│  L0–L4 · frames only here    │─mTLS──▶│  AppFunctions · no frame API│
│  ★ frames exist only here    │  gRPC  │  ★ structurally cannot      │
└──────────────────────────────┘        │    access frames            │
                                         └─────────────────────────────┘
```

雙節點的切分是一種隱私機制,而不只是效能機制:查詢介面沒有任何通往影像資料的路徑,因此「我們選擇不回傳影格」就變成了「我們無法回傳」。恢復這項保證,正是 Jetson 拓撲仍是目標的原因。

## 快速上手

```bash
git clone https://github.com/TingFengChou/claustrum.git
cd claustrum

# M0 — the only thing that matters right now.
# Serve Gemma E2B/E4B on the phone, then expose it to the host over adb.
# On-device serving is not one command — see docs/M0-phone-setup.md.
#   adb forward tcp:8082 tcp:8082
pip install -r bench/requirements.txt
cp bench/backends.example.yaml bench/backends.yaml   # point at 127.0.0.1:<forwarded port>
python bench/run_bench.py --frames bench/frames --out eval/reports
```

這套 harness 跑在你的筆電上,透過 `adb forward` 與手機通訊;它會透過 `adb` 取樣手機的熱狀態與電池狀態 (`bench/phone_monitor.py`)。

在 M0 產出數據之前,下游的任何東西都無法設計。詳見 [`bench/README.md`](bench/README.md)。

## 路線圖

| M | 名稱 | 產出 | 預估 |
|---|---|---|---|
| **M0** | 後端試點 (Backend spike) | Pixel 10 上裝置端 Gemma E2B/E4B 的延遲 / 記憶體 / 熱狀態一覽表;決定關鍵影格預算 | 1–2 週 |
| **M1** | 結構化影像描述 | Prompt v1 + Kineme schema;影像描述可接受度 > 70 %、JSON 解析成功率 > 98 % | 2–3 週 |
| **M2** | 離線管線 | L0 gating + L1 批次 + KinemeStore;1 小時影片達成 > 100× 壓縮 | 3–4 週 |
| **M3** | Ethogram + 查詢 | L3 階層式摘要 + L4 檢索 | 3 週 |
| **M4** | AppFunctions | Pixel 10 provider、同意授權分層、稽核紀錄 | 3–4 週 |
| **M5** | 即時 + 警示 | L2 雙路徑;誤報 < 3 / 24 小時 | 4–5 週 |
| **M6** | 強化 (Hardening) | 連續執行 7 天;誤報 < 1 / 24 小時 | 4 週 |
| **M7** | 機器人橋接 | MCP server + ROS 2 node + 空間錨定 PoC | 3 週 |

M4 只相依於 M3,而不相依於即時管線 —— 之所以把它排在前面,是因為它能在開始耗費心力的 M5–M6 工作之前,先驗證 kineme 的品質是否足以支撐自然語言查詢。

含驗收標準的完整計畫:[`docs/ROADMAP.md`](docs/ROADMAP.md)。

## 關鍵指標

本專案是以這些指標,而非以展示效果,來評斷成敗:

| 指標 | 目標 |
|---|---|
| 關鍵影格壓縮比 | > 100× |
| 影像描述幻覺率 | < 5 % |
| 跌倒偵測召回率 | > 90 % |
| **每 24 小時誤報數** | **< 1** |
| 端到端警示延遲 (p95) | < 5 秒 |
| 無熱節流的連續運轉時間 | 7 天 |

每 24 小時誤報數是首要指標。一套每天狼來了一次的系統,兩週內通知就會被靜音,到那時召回率再高也毫無意義。

## 開發

工作透過 PR 交付,並以 CI 與一道諮詢性質的 AI 程式碼審查作為關卡;每個模組都保有 SA/SD 設計文件;模組以可測試性為前提建置;app UI 設計到接近正式上線的品質;文件則隨每個里程碑一併更新。完整流程:[`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md)。設計文件:[`docs/design/`](docs/design/)(`core` 是已完成的範例)。這些標準也編寫成 `dev-standards` skill,因此會自動套用。

## 決策

- [ADR-0001 — 平台:選擇 Jetson AGX Orin 而非 Android](docs/adr/0001-platform-choice.md)
- [ADR-0002 — 命名與領域語言](docs/adr/0002-naming-and-domain-language.md)
- [ADR-0003 — 雙節點拓撲與影格隔離邊界](docs/adr/0003-two-node-topology.md)
- [ADR-0004 — 手機優先、單節點啟動](docs/adr/0004-phone-first-single-node.md)
- [ADR-0005 — 產品主體為 React Native app,Python 降為離線工具](docs/adr/0005-react-native-app.md)

## 授權

Apache-2.0。詳見 [`LICENSE`](LICENSE)。
