# core-rs(Rust 感知核心)— 系統分析(SA)

**狀態:** active · **最後更新:** 2026-08-06 · **負責人:** claustrum
**實作:** [`SD.md`](SD.md)

## 1. 目的與範圍

`core-rs` 是 Rust 寫的**感知核心純邏輯**(ADR-0007):L0 閘控、影格管線協調、L2/L3 事件引擎。
編譯為 `.so`(cargo-ndk)、經 JNI 供 Android 呼叫。**範圍外**:相機/JNI 膠合、UI(Kotlin/
Compose)、L1 VLM 本體(改走 Kotlin 端 **Google AI Edge / LiteRT**,ADR-0009;core-rs 不呼叫 VLM)。

核心原則:所有可測試的邏輯放這裡,能在 **Host(macOS/Linux)以 `cargo test` + 合成輸入**
獨立驗證,不依賴 Android 硬體。

## 2. 參與者與情境

- **Android 層(Kotlin)** —— 由 CameraX 取得影格 luma,經 JNI 餵給 core-rs;接收 Kineme/Event。
- **Google AI Edge / LiteRT**(Kotlin 層)—— L1 VLM 推論(L0 放行後由 Kotlin analyzer 觸發;core-rs 不直接呼叫)。
- **告警消費者** —— 接收 core-rs 輸出的 Event。

## 3. 功能需求

- **FR-1(P1,已起步)** L0 變化閘控:以 aHash + Hamming 距離判斷畫面是否改變到值得叫 L1,
  **省算力**(靜態場景不叫 VLM)。
- **FR-2(P2)** 提供 L0 放行決策給 Kotlin analyzer,由其在放行時觸發 L1(Kotlin/LiteRT)→ Kineme;core-rs 本身不含 VLM 呼叫。
- **FR-3(P3)** L2/L3 事件引擎:對 Kineme/Observation 時間序列的狀態機(Fall/Leave/Violence)→ Event。
- **FR-4(P0/後續)** JNI 介面:向 Android 暴露必要的入口。

## 4. 非功能需求

- **NFR-1 即時**:L0 閘控每幀微秒級。
- **NFR-2 Edge**:全裝置端。
- **NFR-3 可測試**:純邏輯以 `cargo test`(Host)覆蓋,CI 執行;不需硬體。
- **NFR-4 記憶體安全 + 可攜**:Rust;日後 Jetson/機器人可重用同核心。
- **NFR-5 隱私**:只吃單通道 luma / 觀察資料算 signature;不保留影格。

## 5. 領域模型

`Signature`(64-bit aHash);後續 `Observation`(時間戳觀察)與 `Event`(由 [events 設計](../events/SA.md) 而來),以 `schemas/` 為跨語言契約。

## 6. 限制與假設

- 輸入影格為單通道 luma(由 Android CameraX 提供 / 轉換)。
- L1 由 Kotlin 端 Google AI Edge / LiteRT 提供(ADR-0009);L2 型別對齊 `schemas/event.schema.json`(待建)。

## 7. 驗收標準

- 靜態/雜訊畫面被閘控略過;真實變化被放行;首幀放行;畸形輸入不 panic。
- 以上皆為 `core-rs` 的 `cargo test`(P0 已達成:6 tests 綠)。

## 8. 未解問題

- JNI 綁定方式:`jni` crate(手寫)vs `uniffi`(自動生成)。
- L2 事件引擎的 Rust 型別如何由 JSON Schema 生成/對應。
- aHash 閾值與降採樣尺寸的實機調校。

## 追溯

[ADR-0007](../../adr/0007-rust-first-redesign.md);事件引擎見 [`docs/design/events`](../events/SA.md)。設計見 [`SD.md`](SD.md)。
