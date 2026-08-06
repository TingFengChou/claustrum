# model(裝置端模型管理)— 系統分析(SA)

**狀態:** active(下載器 + 目錄已落地;HF 授權待接) · **最後更新:** 2026-08-06 · **負責人:** claustrum
**設計:** [`SD.md`](SD.md)

## 1. 目的

**產品化**的裝置端模型管理:App **自己**下載並管理模型,不靠使用者 `adb push` 或另一個 App。
使用者可**瀏覽多個模型、看各自能力(看圖描述/文字…)、擇一下載並切換**——因為不同模型效果
不同,換模型必須是產品的一等公民(參考 Google AI Edge Gallery,Apache-2.0)。

## 2. 範圍

- **已落地:** 模型**目錄**(catalog,含能力/大小/gated 標示)、**App 內下載**(WorkManager,
  可續傳、前景服務、進度)、gated 401 誠實提示、模型「已下載/尚未」狀態。
- **已落地:** Hugging Face 授權——`TokenStore`(EncryptedSharedPreferences 加密)+ 目錄權杖 UI,
  gated 下載注入 `Bearer`。
- **待接:** 下載後供 L1 [`LiteRtCaptioner`](../vlm/SD.md) 載入、模型**切換** UI(選定 L1 用哪顆)。
- **不在範圍:** 推論本身(見 vlm)、L0/L2(見 core-rs)。

## 3. 需求

| 編號 | 需求 |
|---|---|
| FR-1 | 提供可瀏覽的模型**目錄**,每筆含 name/modelId/file/size/能力/gated |
| FR-2 | App 內下載模型檔到裝置(HF resolve URL),**可續傳**、顯示進度、前景不被殺 |
| FR-3 | gated 模型回 401/403 時,清楚提示需 HF 授權(不崩潰) |
| FR-4 | 下載完成後可判定模型**存在**(路徑 + 大小),供 L1 載入 |
| FR-5 | 保留**替換/切換模型**的機制與介面(不同模型效果不同) |
| NFR-1 | 大檔(數 GB)下載穩健:前景服務 + 續傳 + 唯一工作(不重複堆疊) |
| NFR-2 | 可測試:目錄/URL/能力純邏輯 host `cargo`/JVM 測試 |
| NFR-3 | 隱私:模型檔存 App 專屬外部目錄;不含使用者資料 |

## 4. 相依與假設

- 下載來源:Hugging Face(`https://huggingface.co/<id>/resolve/main/<file>`)。
- WorkManager(前景 dataSync 服務,需 manifest 宣告 `foregroundServiceType`)。
- **Gemma 全系列在 HF 為 gated**(Gemma 授權)→ 產品化下載需 HF 登入/權杖(下一步)。
- 下游:[`vlm` LiteRtCaptioner](../vlm/SD.md) 以下載好的 `.task`/`.litertlm` 初始化 LiteRT-LM。

## 5. 驗收

- **已驗(Pixel 10):** 目錄畫面呈現 3 模型 + 能力/大小/gated;點下載 → WorkManager 前景服務
  啟動 → HTTP 到 HF → gated 模型正確顯示「需要授權(401)」;**無崩潰**。ModelSpec 6 個 JVM 測試綠。
- **待驗:** HF 授權後完整下載一顆多模態 Gemma;L1 以其產生真實描述。

## 追溯

滿足本 SA;實作見 [`SD.md`](SD.md)。相關:[ADR-0009](../../adr/0009-edge-ai-litert-ai-edge.md)、[vlm](../vlm/SA.md)、[android](../android/SD.md)。
