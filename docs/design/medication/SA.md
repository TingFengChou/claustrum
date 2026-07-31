# medication — 系統分析(SA)

**狀態:** draft · **最後更新:** 2026-07-31 · **負責人:** claustrum
**實作:** [`SD.md`](SD.md)

## 1. 目的與範圍

讀取藥袋(藥袋)/藥單(藥單)/藥品標示影像,在**裝置端(edge AI)**抽出結構化藥品資訊與
**一般用途說明**。**僅供資訊參考,非醫療建議。** 使用者主動拍照觸發(非被動監控)。這是
「機器人視覺大腦」即時辨識能力的第一個實用垂直應用,也是一個 demo 亮點。

## 2. 參與者與情境

- **使用者** —— 主動拍一張藥袋/藥單,查看抽取結果與用途。
- **on-device VLM**(llama.rn / Gemma vision)—— L1 影像描述,裝置端執行。
- **(未來)AppFunctions 呼叫端** —— 若暴露,須最嚴格層級。

## 3. 功能需求

- **FR-1** 由影像抽出 `MedicationReading`(每項:藥品名、劑量、頻次、外觀、一般用途、信心)。
- **FR-2** 看不清的欄位設 null 並列入 `unclear_fields`;**絕不臆測藥名或劑量**;非藥單則 `unreadable=true`。
- **FR-3** 每次結果都附**固定免責**(由 App 提供,非模型輸出)。
- **FR-4** 全程裝置端:原圖不外傳、用完即刪;只保留(可選)去識別化文字。

## 4. 非功能需求

- **NFR-1 安全。** 錯誤藥名/劑量有害 → 抗幻覺紀律 + 解析層防呆(空名→null、免責永遠是我們的)。
- **NFR-2 隱私。** 藥單屬 PDPA 敏感健康個資 → 裝置端、不走雲端 AppFunctions;暴露則最嚴格層級、預設關閉。
- **NFR-3 可測試性。** 解析為純函式,可不靠硬體單元測試。
- **NFR-4 Edge。** 推論在裝置端,離線可用。

## 5. 領域模型

`MedicationReading`(見 [`schemas/medication.schema.json`](../../../schemas/medication.schema.json);
TS 型別 `app/src/domain/medication.ts`)。schema 為單一真實來源。

## 6. 限制與假設

- 需 on-device VLM(llama.rn/llama.cpp + Gemma vision GGUF + mmproj),模型檔在裝置端。
- 標示可能為繁中/英文/混合。

## 7. 驗收標準

- 清晰藥袋 → 正確抽出藥名 + 一般用途 + 免責。
- 模糊欄位 → null 且列入 `unclear_fields`,不臆測。
- 非藥單影像 → `unreadable=true`、`items` 為空。

## 8. 未解問題

- 模型選型與大小 vs 準確度/延遲(E2B/E4B/4B)—— 需 M0/M1 量測。
- 是否暴露為 AppFunction、以及層級與同意流程。

## 追溯

[ADR-0005](../../adr/0005-react-native-app.md)、[ADR-0004](../../adr/0004-phone-first-single-node.md);
[PRIVACY.md](../../PRIVACY.md);設計見 [`SD.md`](SD.md)。
