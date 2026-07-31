# medication — 系統設計(SD)

**狀態:** draft · **最後更新:** 2026-07-31 · **負責人:** claustrum
**實作:** [`SA.md`](SA.md)

## 1. 概觀

影像 → on-device VLM(llama.rn)→ JSON → **純解析(安全包裝)** → `MedicationReading` → UI。
安全與隱私是設計核心,而非事後補丁。

## 2. 元件與職責

| 檔案 | 職責 |
|---|---|
| `app/src/vlm/vlmService.ts` | llama.rn(llama.cpp)包裝:`loadVlm` / `describeImage` / `releaseVlm`。影像留在原生層。 |
| `app/src/vlm/medicationPrompt.ts` | 抽取提示詞(對應 `prompts/medication_v1.md`),安全規則不可放寬。 |
| `app/src/vlm/medicationParse.ts` | **純函式** `parseMedicationResult`:JSON 容錯 + 安全防呆。無原生 import,可單元測試。 |
| `app/src/vlm/medication.ts` | `readMedication(imagePath, model)`:呼叫 VLM + 解析。 |
| `app/src/domain/medication.ts` | `MedicationReading` 型別 + `MEDICATION_DISCLAIMER`。 |
| `schemas/medication.schema.json` | 契約單一真實來源。 |

## 3. 介面與合約

- `readMedication(imagePath: string, model: string): Promise<MedicationReading>`(需先 `loadVlm`)。
- `parseMedicationResult(raw: string, model: string): MedicationReading`(純、可測)。
- VLM:`describeImage(imagePath, prompt)`;影像以 `media_paths` 傳給 llama.rn,**不經 JS 以 base64 過橋**。

## 4. 關鍵流程

```mermaid
sequenceDiagram
  participant U as 使用者
  participant RN as RN UI
  participant VLM as llama.rn(裝置端)
  participant P as parseMedicationResult(純)
  U->>RN: 拍/選 藥袋影像
  RN->>VLM: describeImage(path, 藥單提示詞)
  VLM-->>RN: 原始 JSON 文字
  RN->>P: parseMedicationResult(raw)
  P-->>RN: MedicationReading(含固定免責)
  RN->>U: 顯示藥名/用途 + ⚠️ 免責;原圖用完即刪
```

## 5. 錯誤處理與安全防呆

- **免責永遠由 App 提供**(`MEDICATION_DISCLAIMER`),忽略模型輸出的任何 disclaimer。
- 藥名為空字串/空白 → 強制 `null`(不呈現臆測名)。
- JSON 解析失敗或 `items` 空 → `unreadable=true`。
- `confidence` 夾到 [0,1]。
- 模型載入失敗 / 記憶體不足 → UI 明示,不假裝有結果。

## 6. 隱私

原圖僅存在於裝置、用完即刪;不上傳、不走雲端 AppFunctions;不保留 keyframe。符合 PRIVACY.md
對敏感健康個資的立場。

## 7. 相依性

`llama.rn`(llama.cpp,已建置進 APK:`librnllama.so`)、Gemma vision GGUF + mmproj(裝置端模型檔)。

## 8. 測試策略(必備)

- `app/src/vlm/__tests__/medicationParse.test.ts`(Jest,7 例):合法/圍籬/亂碼→unreadable/
  空名→null/**免責恆為我方**/信心夾值。純函式,不需裝置。
- e2e(裝置 + 模型)為後續:實拍藥袋 → 裝置端跑通 → 人工核對抽取正確性與幻覺率。

## 追溯

滿足 [`SA.md`](SA.md) FR-1…FR-4、NFR-1…NFR-4。相關:[ADR-0005](../../adr/0005-react-native-app.md)。
