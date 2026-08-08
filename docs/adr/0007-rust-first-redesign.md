# ADR-0007 — 打掉重練:Rust 優先、效能優先的原生架構

**狀態:** 已接受；L1 的 llama.cpp/Rust FFI 子決策已被 [ADR-0009](0009-edge-ai-litert-ai-edge.md)
取代為 Kotlin/LiteRT-LM，legacy ABI 已移除 · **日期:** 2026-08-06
**取代:** [ADR-0005](0005-react-native-app.md)(React Native 為產品主體)
**保留:** [ADR-0004](0004-phone-first-single-node.md)(手機優先、單節點)、[ADR-0006](0006-safety-alert-mvp.md)(MVP:多模態主動安全告警)

> 北極星不變:**即時串流 · Edge AI · 綁定多模態 · 主動防護(相機不是事後回看)**。本 ADR
> 只換「怎麼實作」——以效能為最高優先,把感知熱路徑搬到 Rust/原生。

## 背景

ADR-0005 選了 React Native 為產品主體、原生為輔。實作過程證明:對一個**即時串流、逐幀
感知**的系統,RN/JS 是錯的重心——
- vision-camera 在 RN 0.86 反覆踩雷(v4 被 babel 卡死、v5 擷取 API 早期彆扭)。
- 逐幀影像差異(L0 閘控)在 JS 端沒有便宜的像素存取。
- 每幀擷取成檔、跨 JS 迴圈都是開銷;真正的重運算(VLM)本來就在 C++(llama.cpp)。

使用者決策:**採 Rust,整個架構打掉重練,以效能為優先。**

## 決策

**產品重建為:原生 Android(Kotlin + Jetpack Compose)UI + Rust 感知核心 + 裝置端 L1 推論。
移除 React Native。**

分層與語言:

| 層 | 語言 / 技術 | 為什麼 |
|---|---|---|
| 感知核心:L0 閘控 · 影格管線 · L2/L3 事件引擎 | **Rust**(cargo-ndk → `.so`,JNI) | 安全 + 高效;每幀便宜比較與狀態機的家 |
| L1 VLM 推論 | **Google AI Edge / LiteRT-LM(Kotlin)** | 多模態 on-device；本列由 ADR-0009 修正，舊 llama.cpp/Rust ABI 已移除 |
| 相機擷取 | **CameraX(Kotlin)** | 平台能力;**影格交給 Rust,永不進 UI 層** |
| 平台 / UI | **Kotlin + Jetpack Compose** | 原生、無 JS 橋接;預覽/字幕/告警/控制 |
| 領域契約 | **JSON Schema** | 跨 Rust/Kotlin 單一真實來源 |
| 離線工具 | **Python**(bench/eval) | 不變 |
| 建置 | Gradle + cargo-ndk(NDK 27) | Rust `.so` 隨 App 打包 |

資料流:`CameraX → (JNI) Rust: L0 aHash → Kotlin gate → 變化才叫 LiteRT-LM L1；獨立 fast path →
Rust L2 事件 → Compose UI`。**影格與像素只在裝置內原生層流動**(隱私 + 效能)。

## 理由

- **效能優先**:逐幀熱路徑(擷取、差異、推論)全在原生;JS 執行緒與橋接開銷歸零。
- **省算力(你要的變化閘控)**:L0 在 Rust 做降採樣灰階差 / thumbhash,靜態場景幾乎零成本,
  只有畫面改變才付 VLM 代價。
- **消除框架摩擦**:不再與 vision-camera/RN/babel 打架;相機用平台原生 CameraX。
- **Rust**:記憶體安全 + 接近 C 的效能;可攜(日後 Jetson/機器人共用 L0/L2 核心)。
- **對齊北極星**:即時串流辨識唯有原生熱路徑撐得起。

**可測試性(必備原則):** Rust 核心的**純邏輯**(L0 變化閘控、L2/L3 事件狀態機)必須能在
**Host(macOS/Linux)以 `cargo test` + 合成影格/觀察序列**獨立單元測試,不依賴 Android 硬體。
只有 JNI 橋接與 CameraX/裝置膠合層是裝置端。這與 dev-standards 的可測試性規範一致。

## 什麼保留、什麼丟棄

- **保留**:北極星與 ADR-0006 的 MVP 範圍(社區跌倒 / 幼兒園暴力告警)、領域概念與 schema、
  dev-standards、事件引擎設計([docs/design/events](../design/events/),於 Rust 重實作)、
  llama.cpp/SmolVLM/模型路徑心得([app-model-setup](../app-model-setup.md))、視覺識別/app icon、
  Python bench/eval。
- **丟棄**:React Native app(`app/` 的 RN 程式碼)。保留在 git 歷史作為概念驗證與參考;
  即時字幕已在 RN 版證明端到端可行,原生版把它做成可即時、gated、production。

## 後果

- 需要 Rust + cargo-ndk + NDK(已就緒)+ Android(Kotlin/Compose)專案結構。
- 領域型別多一種語言(Rust);仍以 JSON Schema 為契約。
- 迭代比 JS 慢、原生程式碼變多——這是效能與可靠度的代價,已接受。
- iOS 暫不投入(北極星為 Android/Pixel 單節點)。

## 重建計畫(階段)

- **P0** 骨架:Rust core crate + Android(Compose)app + JNI 橋接 + cargo-ndk 建置;裝置上
  「Rust core 回話」。**並撰寫 `docs/design/core-rs`(JNI 介面 + 內部架構 + 純邏輯的
  cargo test 策略)與 `docs/design/android` 的 SA/SD**(dev-standards:每個模組有設計文件)。
- **P1** L0 變化閘控(Rust):影格差異 → 只在改變時往下走(省算力)。
- **P2** L1:由 ADR-0009 改為 Kotlin/LiteRT-LM → 被閘控的影格產生客觀字幕 → Compose 字幕層。
- **P3** L2 事件引擎(Rust):Fall/Leave/Violence 狀態機 → 告警。
- **P4** 音訊模態(尖叫/衝突聲)融合。

## 重新檢視條件

若原生迭代成本過高而效能已足,重評估某些非熱路徑(如 UI 邏輯)是否可用較高階語言。日後上
iOS 或 Jetson 時,Rust 核心可跨平台重用,屆時記錄新 ADR。
