# core-rs(Rust 感知核心)— 系統分析(SA)

**狀態:** active · **最後更新:** 2026-08-08 · **負責人:** claustrum
**實作:** [`SD.md`](SD.md)

## 1. 目的與範圍

`core-rs` 是 Rust 寫的**感知核心純邏輯**(ADR-0007):L0 signature/gate contract 與 L2 事件引擎。
編譯為 `.so`(cargo-ndk)、經 JNI 供 Android 呼叫。**範圍外**:相機/JNI 膠合、UI(Kotlin/
Compose)、L1 VLM 本體(改走 Kotlin 端 **Google AI Edge / LiteRT**,ADR-0009;core-rs 不呼叫 VLM)。

核心原則:所有可測試的邏輯放這裡,能在 **Host(macOS/Linux)以 `cargo test` + 合成輸入**
獨立驗證,不依賴 Android 硬體。

## 2. 參與者與情境

- **Android 層(Kotlin)** —— 由 CameraX 取得影格 luma，經 JNI 取得 Rust aHash；Kotlin
  `ChangeGate` 保存最後放行 signature。L2 另以 JNI 傳匿名 Observation、接收 Event JSON；
  observation 契約/bridge 與首條 ML Kit 單人 pose extractor 已接，待素材校準。
- **Google AI Edge / LiteRT**(Kotlin 層)—— L1 VLM 推論(L0 放行後由 Kotlin analyzer 觸發;core-rs 不直接呼叫)。
- **告警消費者** —— 接收 core-rs 輸出的 Event。

## 3. 功能需求

- **FR-1(P1,已起步)** L0 變化閘控:以 aHash + Hamming 距離判斷畫面是否改變到值得叫 L1,
  **省算力**(靜態場景不叫 VLM)。
- **FR-2(P2)** Rust 回傳 signature 給 Kotlin analyzer，由 Kotlin gate 決定放行並觸發
  L1(Kotlin/LiteRT)；core-rs 不參與現行 VLM 呼叫。
- **FR-3(P3 🟡)** L2 事件引擎:對輕量 pose/motion/action Observation 時間序列的
  狀態機(Fall/ZoneExit/Violence)→ Event；Android/JNI 與單人 pose/descent/motion 已接，
  impact/多人 action/policy 待續。
- **FR-4(P0/後續)** JNI 介面:向 Android 暴露必要的入口。

## 4. 非功能需求

- **NFR-1 即時**:L0 閘控每幀微秒級。
- **NFR-2 Edge**:全裝置端。
- **NFR-3 可測試**:純邏輯以 `cargo test`(Host)覆蓋,CI 執行;不需硬體。
- **NFR-4 記憶體安全 + 可攜**:Rust;日後 Jetson/機器人可重用同核心。
- **NFR-5 隱私**:JNI 只短暫複製單通道 luma 來算 signature；L2 只吃匿名觀察資料；皆不保留影格。

## 5. 領域模型

`Signature`(64-bit aHash)、`Observation`(時間戳 pose/motion/action 特徵)與 `Event`(見
[events 設計](../events/SA.md))；Event 以 serde 對齊 `schemas/event.schema.json`。

## 6. 限制與假設

- 輸入影格為單通道 luma(由 Android CameraX 提供 / 轉換)。
- L1 由 Kotlin 端 Google AI Edge / LiteRT 提供(ADR-0009)；L2 型別與 JNI 輸出對齊
  `schemas/event.schema.json`。首版 ML Kit pose 只追最顯著一人，且尚未經實機素材校準。

## 7. 驗收標準

- 靜態/雜訊畫面被閘控略過;真實變化被放行;首幀放行;畸形輸入不 panic。
- L0、L2 state machine、serde 與 JNI registry 由 `core-rs` 的 25 個 host tests 覆蓋；
  extractor 另由 Android host tests 驗證，實機校準不以合成測試取代。

## 8. 未解問題

- aHash 閾值、pose/descent thresholds 與固定鏡位素材的實機調校。
- impact、多人 action 與音訊特徵應由哪些可替換 extractor 提供。

## 追溯

[ADR-0007](../../adr/0007-rust-first-redesign.md);事件引擎見 [`docs/design/events`](../events/SA.md)。設計見 [`SD.md`](SD.md)。
