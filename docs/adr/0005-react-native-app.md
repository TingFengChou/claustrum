# ADR-0005 — 產品主體為 React Native app,Python 降為離線工具

**狀態:** 已接受 · **日期:** 2026-07-31
**釐清:** [ADR-0004](0004-phone-first-single-node.md)(手機優先、單節點)

> **北極星(絕不遺漏)。** claustrum 的終極目標是一個**即時串流辨識**的**機器人視覺
> 辨識系統大腦** —— 以 **edge AI** 在裝置端完成,不依賴雲端。它 binding 各感官模態成
> 一條連貫的理解串流(正呼應 claustrum 之名)。本 ADR 的每一項技術取捨,都是為了保住這
> 條「即時、串流、邊緣」的路:因此熱路徑用原生 Rust/C++、影格不過 JS bridge、模型常駐
> 裝置端。居家部署只是第一個垂直場景,不是天花板。

## 背景

ADR-0004 定調「手機優先、單節點」:Pixel 10 一手包辦 camera 擷取、L0–L4、查詢介面
與 AppFunctions。但它沒有定義**產品的執行形態**。結果 repo 目前清一色是 Python
(`core/domain.py`、`bench/`、`prompts/`、`schemas/`、`eval/`),看起來像一個伺服器 /
CLI 專案 —— 而依 ADR-0004,最終產品其實應該是一支**行動 App**(裝置端 camera、
on-device VLM、AppFunctions provider、以及需要 Claude 設計的 UI)。

這個落差必須明確解決:產品主體是什麼技術棧,現有的 Python 又該何去何從。

## 決策

**產品主體是一支 React Native(TypeScript)App。** 裝置端的重負載以**原生 Kotlin 模組**
實作並橋接給 RN;RN 負責 UI 與協調層。**Python 降為離線工具。**

分工:

| 層 | 實作 | 說明 |
|---|---|---|
| UI / 導覽 / 應用狀態 | **React Native + TypeScript** | 以 Claude 設計至接近產品化(規範第 4 條) |
| L0 閘控 · 影格環形緩衝 · 串流熱路徑 | **Rust 或 C/C++ 原生核心**(NDK / JNI) | 效能與串流關鍵路徑;影格只存在於此 |
| L1 影像描述(on-device VLM) | C/C++ 執行期(llama.cpp / LiteRT)+ Kotlin 綁定 | 推論本就是 C/C++;RN 觸及不到 |
| camera 擷取 | 原生 Kotlin 模組(CameraX) | Android 平台能力 |
| L2 警示 · L3/L4 · KinemeStore | 原生 Kotlin(前景服務 + SQLite) | 常駐、與相機同進程 |
| AppFunctions provider · 推播 | 原生 Kotlin | Android 平台專屬 |
| 領域型別契約 | **JSON Schema(單一真實來源)** | TS / Kotlin / Rust 型別由 schema 產生/對應 |
| M0 基準量測 · 評測評分 | **Python(`bench/`、`eval/`)** | 離線工具,不是產品 |

**不必全專案都是 React Native。** 效能或串流關鍵的部分,可用 **Rust 或 C/C++** 的高效能
開源函式庫(自行實作或引入皆可),透過 NDK/JNI(或 Rust FFI)暴露給上層。RN 只是「UI +
協調殼」,不是整個系統。

## 理由

- **RN 是使用者選定的技術棧。** 據此,UI 與協調用 RN/TS;但 on-device VLM、CameraX、
  AppFunctions、前景服務都是 Android 平台能力,RN 觸及不到,因此必須以原生 Kotlin 模組
  實作、透過 bridge 暴露給 RN。RN 因此是「殼 + UI + 協調」,不是整個系統。
- **Python 留作離線工具是恰當的。** `bench/`(在筆電上量測手機端模型)與 `eval/`(離線
  評分)本來就適合 Python,且與 App 無執行期耦合。它們不是產品,而是開發工具。
- **JSON Schema 當跨語言契約。** 領域型別如今會出現在多處(Python / TypeScript /
  Kotlin / 可能還有 Rust);以 `schemas/kineme.schema.json` 為單一真實來源、由它產生或
  對應各語言型別,避免多份定義漂移(schema 與型別漂移是這類專案最常見的隱形 bug)。
  `core/domain.py` 保留供離線 eval/bench 使用。
- **效能/串流熱路徑用 Rust 或 C/C++。** L0 閘控(motion diff、pose、frame embedding)、
  影格環形緩衝、以及 VLM 執行期(llama.cpp / LiteRT 本就是 C/C++)是延遲與吞吐的關鍵,
  適合原生實作;RN 的 JS 執行緒不該碰這些。可自行實作,或引入高效能開源函式庫 ——
  但**必須可商用**(採 MIT / Apache-2.0 / BSD 等寬鬆授權;避免 GPL 之於商用的傳染性);
  本專案採 Apache-2.0,llama.cpp(MIT)、LiteRT(Apache-2.0)相容。

## 誠實面對的取捨

- **RN 對裝置端 AI 的整合成本較高。** on-device VLM / CameraX / AppFunctions 全都要寫
  原生模組並跨 bridge,比純原生 App 多一層。這是選 RN 的已知代價。
- **影格絕不跨 JS bridge。** 基於隱私(見 [PRIVACY.md](../PRIVACY.md))與效能:把整張
  影格 base64 過橋既慢又擴大暴露面。原生層持有影格、只把**去識別化的 `Kineme` 文字**過橋
  給 RN。這同時強化了單節點階段「影格依政策留在裝置」的立場(ADR-0004)。
- **M0 正式量測改到 App 內。** on-device LiteRT-LM 需在原生層執行,故貼近正式環境的延遲
  量測要在承載 App 內做;Python `bench/` 只服務 llama.cpp/Termux 的快速路徑(見
  [M0-phone-setup.md](../M0-phone-setup.md))。

## 後果

- 新增 `app/`(React Native)為主要模組;`bench/`、`eval/` 在文件中標記為離線工具。
- 需要 React Native 開發環境(Node、Android SDK、JDK 17)。iOS 端暫不投入。
- 領域型別一致性:CI 現行的「dataclass ↔ schema」漂移測試,未來要延伸涵蓋 TS/Kotlin
  由 schema 產生的型別。
- App UI 依規範第 4 條以 Claude 設計;沿用 app icon 的視覺識別(深靛底 + 三模態強調色)。
- 領域模型、schema、L0–L4 的概念設計不變(ADR-0004);這是執行形態的決策,不是重新設計。

## 重新檢視條件

若日後要上 iOS,重新評估 Kotlin Multiplatform 或把原生模組在兩端各實作一次;屆時記錄為
新的 ADR。
