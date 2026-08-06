<img src="assets/icon/claustrum.svg" width="88" align="right" alt="claustrum app icon"/>

# claustrum

**把攝影機從「事後回看」變成「主動防護」的即時守護者。** 在裝置端(edge AI)即時融合視覺與音訊,主動偵測跌倒、暴力等安全事件,並在當下告警——而不是事發後才調閱錄影。全程在邊緣硬體上執行,影像不外傳。

Pixel 10 · React Native + 原生 Rust/C++/Kotlin · llama.rn(llama.cpp)· Edge AI

> **核心命題:** 相機的角色被改變了——它是主動防護的守護者,不是事後回看的記錄器。這正是為什麼整個系統必須**即時 · 串流 · 裝置端**:事發後才知道就失去意義。詳見 [ADR-0006](docs/adr/0006-safety-alert-mvp.md)。
>
> **目前階段:手機優先、單節點。** 所有運算跑在單一台 Pixel 10 上([ADR-0004](docs/adr/0004-phone-first-single-node.md));Jetson 雙節點是日後目標([ADR-0003](docs/adr/0003-two-node-topology.md))。

---

## ⚠️ 請先讀這段

**這不是醫療器材,也不能取代真人監看與保全。**

跌倒/暴力偵測會漏掉事件,也會產生誤報。請勿將它當成唯一的安全網,而應視為協助人力的一層額外警覺。任何部署都需取得現場相關人員的知情同意;**幼兒園等涉及兒童的場景**,兒童個資屬高度敏感(PDPA),需機構同意、家長告知與明確治理。在把鏡頭對準任何人之前,請先閱讀 [`docs/PRIVACY.md`](docs/PRIVACY.md)。

---

## 它能做什麼

第一版 MVP:裝置端、多模態(視覺 + 音訊)的**主動安全事件偵測與告警**。

| 場景 | claustrum 做什麼 |
|---|---|
| 社區有人跌倒 | 裝置端即時偵測 → 數秒內**通知保全**,並附上原因 |
| 幼兒園發生暴力衝突 | 融合**聲音 + 畫面**偵測 → 主動**聲光告警** |

重點是**主動**:偵測與告警在事件當下於裝置上完成,影像不外傳。居家查詢、藥袋辨識等能力保留於路線圖後段(見下方)。

## 名字的由來

**claustrum**(屏狀核)是一薄層神經元,幾乎與每一個大腦皮質區都有連結。Crick 與 Koch 曾提出,它正是把各自獨立的感官模態綁定為單一統一體驗的結構 —— 他們將它比喻為管弦樂團的指揮。

這正是本專案要做的事:把視覺與音訊(日後含語言)綁定為一份連貫、即時的理解,用於當下的防護判斷。

## 領域詞彙

這套程式碼刻意採用一組貫徹一致的詞彙,取自動物行為學 (ethology)、身勢學 (kinesics) 與行動者網絡理論 (actor-network theory)。這三個傳統共享同一項方法論承諾:**記錄觀察到的事,不臆測動機。** 這項承諾是本專案的核心紀律,因此這些命名都承載著它。

| 詞彙 | 在此的意義 | 出處 |
|---|---|---|
| **Actant** | 場景中的一個參與者 —— `person_1`、`cat`、`robot_1`。是一個**角色槽位,而非身分。** | 行動者網絡理論 (Latour);結構符號學 (Greimas) |
| **Kineme** | 觀察到的行為之最小記錄單位。一個動作、一段時間。 | 身勢學 (Birdwhistell) —— 音素 (phoneme) 在身勢上的類比 |
| **Ethogram** | 一段期間內眾多 kineme 的目錄。 | 動物行為學 —— 針對某物種各離散行為的正式清單 |

`Actant` 之所以是角色槽位而非某個人,並非偶然 —— 它就是隱私設計本身。本專案不做人臉辨識,也不做身分歸屬。詳見 [`docs/adr/0002-naming-and-domain-language.md`](docs/adr/0002-naming-and-domain-language.md)。

## 架構

連續影音無法逐格餵給模型。核心設計是一座**時間壓縮金字塔 (temporal compression pyramid)**;安全告警 MVP 的重心在 **L0→L2**:

```
 相機 30 fps + 麥克風音訊
     │
 L0  閘控 (Gating)   motion diff · pose landmarks · 音訊事件 · frame embedding
     │               → 決定哪些瞬間值得一次昂貴推論(目標 100×+ 壓縮)
     ▼
 L1  感知 (Perceive) 裝置端 Gemma E2B/E4B(llama.rn / llama.cpp)→ 結構化 Kineme
     │
     ▼
 L2  告警 (Alert)    快路徑:pose / 音訊啟發式(recall)★ MVP 核心
     │               慢路徑:VLM 確認(precision)→ 通知保全 / 聲光告警
     ▼
 L3/L4 (延後)        摘要 Ethogram · 自然語言查詢
```

完整細節,包含 L2 為何拆成兩條路徑,詳見 [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)。

## 模組

**產品主體是一支 React Native App**(ADR-0005);Python 是離線工具,不是產品。

| 模組 | 語言 | 狀態 | 用途 |
|---|---|---|---|
| `app/` | React Native + TS(+ 原生 Rust/C++ · Kotlin) | 🚧 進行中 | **產品本體** —— 裝置端感知/告警 App。**目前重點。** |
| `schemas/` | JSON Schema | ✅ 就緒 | 領域型別的**單一真實來源**(跨 TS / Kotlin / Python) |
| `core/` | Python | ✅ 就緒 | 領域型別參考實作,供離線 eval/bench 使用 |
| `bench/` `eval/` | Python | ✅ 就緒 | 離線基準測試 / 評測(工具,非產品) |

裝置端的重負載(L0 閘控、影格/音訊串流、on-device 推論)以原生 **Rust/C++** 核心 + **Kotlin** 平台膠合實作,透過 NDK/JNI 橋接給 RN(ADR-0005)。**即時串流辨識**是終極目標。裝置端推論引擎 **llama.rn / llama.cpp** 已接入並建置於 Pixel 10(APK 內含 `librnllama.so`)。

## 部署拓撲

**現在 —— 手機優先、單節點** (ADR-0004):Pixel 10 一手包辦相機 + 麥克風擷取、L0–L2 偵測、告警。單一行程同時持有影格並判斷,因此影格隔離是靠**政策**(不外傳、用完即刪)而非拓撲來落實——這是手機優先所承擔、且刻意為之的暫時代價。**日後**一旦 Jetson 就緒,再恢復 [ADR-0003](docs/adr/0003-two-node-topology.md) 的雙節點結構性影格隔離。

## 快速上手

產品是 App;先跑起來看:

```bash
git clone https://github.com/TingFengChou/claustrum.git
cd claustrum/app
npm install
npm run android          # 建置並安裝到已連接的 Android 裝置(Pixel 10)
```

裝置端推論引擎(llama.rn)會一併編譯進 App。離線工具(bench/eval,Python)另見 [`bench/README.md`](bench/README.md)。

## 路線圖

全景圖(GitHub 原生渲染的 Mermaid)與各里程碑細節見 [`docs/ROADMAP.md`](docs/ROADMAP.md)。

- **Phase 1 — 基礎(已完成)**:領域模型與 schema · 開發規範(PR + AI 審查 · SA/SD · 測試)· RN App 產品本體並實機運行於 Pixel 10 · 裝置端 VLM 引擎 llama.rn/llama.cpp 接入。
- **Phase 2 — MVP v1(進行中 · 手機驗證優先)**:**A** 感知閉環(相機 + 麥克風 → 裝置端偵測 → 告警)→ **B** 跌倒偵測(→ 通知保全)/ **C** 暴力偵測(音 + 視 → 聲光告警)→ **D** 告警通道 + 誤報抑制。
- **Phase 3+ — 延後 / 未來**:藥袋辨識(軟體層已備)· 離線管線 / Ethogram / 自然語言查詢 · AppFunctions · 硬化(7 天連續)· 雙節點 Jetson 與機器人橋接(MCP / ROS 2)· 完整 ASR / TTS。

## 關鍵指標

MVP 以這些指標評斷成敗,而非展示效果:

| 指標 | 目標 |
|---|---|
| 跌倒偵測召回率 | > 90 % |
| **每 24 小時誤報數** | **< 1** |
| 端到端告警延遲 (p95) | < 5 秒 |
| 影像描述幻覺率 | < 5 % |
| 無熱節流的連續運轉時間 | 7 天 |

每 24 小時誤報數是首要指標。一套每天狼來了一次的系統,會很快被靜音,到那時召回率再高也毫無意義——對「通報保全/驚動園方」這種對外告警尤其如此。

## 開發

工作透過 **PR** 交付,合併前以 **AI 程式碼審查**(本機 Antigravity CLI `agy`,不需 secret)與 CI 把關;merge 依查證事實決定。每個模組保有 SA/SD 設計文件;模組以可測試性為前提建置;App UI 以 Claude 設計至接近正式上線品質;文件隨每個里程碑一併更新。完整流程:[`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md)。這些標準也編寫成 `dev-standards` skill,自動套用。

## 決策

- [ADR-0001 — 平台:選擇 Jetson AGX Orin 而非 Android](docs/adr/0001-platform-choice.md)(已被 ADR-0004 取代)
- [ADR-0002 — 命名與領域語言](docs/adr/0002-naming-and-domain-language.md)
- [ADR-0003 — 雙節點拓撲與影格隔離邊界](docs/adr/0003-two-node-topology.md)(已被 ADR-0004 延後)
- [ADR-0004 — 手機優先、單節點啟動](docs/adr/0004-phone-first-single-node.md)
- [ADR-0005 — 產品主體為 React Native app,Python 降為離線工具](docs/adr/0005-react-native-app.md)
- [ADR-0006 — MVP 重新聚焦:多模態主動安全告警(社區跌倒、幼兒園暴力)](docs/adr/0006-safety-alert-mvp.md)

## 授權

Apache-2.0。詳見 [`LICENSE`](LICENSE)。
