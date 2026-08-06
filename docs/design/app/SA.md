# app — 系統分析(SA)

**狀態:** active · **最後更新:** 2026-08-06 · **負責人:** claustrum
**實作:** [`SD.md`](SD.md)

> MVP A(感知閉環)已落地並實機驗證:即時相機預覽 + 監測 UI + 告警通道(見 SD §6.5)。偵測模型(pose/音訊)為下一步。

## 1. 目的與範圍

`app/` 是**產品本體** —— 一支在 Pixel 10 上、以 edge AI 於裝置端執行的感知 App。它是
使用者的介面與協調殼,也是裝置端 L0–L4 感知管線的容器。範圍外:離線的 `bench/`、
`eval/`(Python 工具)。技術棧與分層見 [ADR-0005](../../adr/0005-react-native-app.md)。

**北極星:即時串流辨識**(real-time streaming recognition)—— 這是一個機器人視覺辨識
系統的大腦,居家部署只是第一個垂直場景。

## 2. 參與者與情境

- **住戶** —— 查看家中狀態、以自然語言查詢、啟動/暫停感測、管理同意。
- **camera** —— 影格來源(裝置端,影格留在原生層)。
- **on-device VLM** —— L1 影像描述(裝置端,edge AI)。
- **AppFunctions 呼叫端**(如 Gemini)—— 依分層同意查詢(僅文字)。
- **原生核心** —— Rust/C++ 熱路徑 + Kotlin 平台膠合。

## 3. 功能需求

- **FR-1** 顯示「今日家中」狀態與最近事件(Kineme 串流)。
- **FR-2** 以自然語言查詢家中事件,回傳文字與時間戳。
- **FR-3** 啟動/暫停感測;暫停狀態在 App 與系統層都可見。
- **FR-4** 分層同意 UI 與使用者可見的稽核紀錄(見 [PRIVACY.md](../../PRIVACY.md))。
- **FR-5**(北極星)感測啟動時,新 Kineme **即時**串流顯示於 UI。

## 4. 非功能需求

- **NFR-1 即時性。** 端到端警示 p95 < 5 s;UI 對新事件即時更新(串流,非輪詢批次)。
- **NFR-2 Edge / 離線。** 核心功能不依賴雲端;模型常駐裝置端。
- **NFR-3 影格不過 JS bridge。** 原生層持有影格,只把去識別化的 `Kineme` 過橋(隱私+效能)。
- **NFR-4 UI 品質。** 以 Claude 設計至接近產品化(規範第 4 條)。
- **NFR-5 可測試性。** 純邏輯(TS domain、原生核心)可不靠硬體單元測試。

## 5. 領域模型

`Kineme`(TS 型別由 `schemas/kineme.schema.json` 對應,見 `app/src/domain/kineme.ts`)、
`Ethogram`。JSON Schema 為單一真實來源(ADR-0005)。

## 6. 限制與假設

- 技術棧:React Native + TypeScript(UI/協調)、原生 Rust/C++(熱路徑/串流/VLM)、
  Kotlin(CameraX / AppFunctions / 前景服務 / bridge)。
- 平台:Android(Pixel 10 / Tensor G5 / Android 17);iOS 暫不投入。
- Edge:所有感知在裝置端完成。

## 7. 驗收標準

- App 能建置、安裝、啟動,顯示已設計的首屏(現況)。
- (後續)感測啟動後 UI 即時串流顯示 Kineme;查詢能正確回答並附時間戳;暫停狀態可見。

## 8. 未解問題

- RN ↔ 原生核心的橋接介面(事件串流、背壓)確切形狀。
- RN 狀態管理與導覽選型。
- 原生核心語言細分(哪些用 Rust、哪些沿用既有 C/C++ 開源如 llama.cpp)。

## 追溯

[ADR-0005](../../adr/0005-react-native-app.md)、[ADR-0004](../../adr/0004-phone-first-single-node.md);
設計見 [`SD.md`](SD.md)。
