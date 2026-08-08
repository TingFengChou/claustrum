# model(裝置端模型管理)— 系統分析(SA)

**狀態:** active(下載器 + 目錄 + HF 授權 + DEFAULT_L1 載入已落地；模型切換待續) · **最後更新:** 2026-08-08 · **負責人:** claustrum
**設計:** [`SD.md`](SD.md)

## 1. 目的

**產品化**的裝置端模型管理:App **自己**下載並管理模型,不靠使用者 `adb push` 或另一個 App。
使用者可**瀏覽多個模型、看各自能力(看圖描述/物件偵測/文字…)、擇一下載並切換**——因為不同模型效果
不同,換模型必須是產品的一等公民(參考 Google AI Edge Gallery,Apache-2.0)。

## 2. 範圍

- **已落地:** 模型**目錄**(catalog,含能力/大小/gated 標示)、**App 內下載**(WorkManager,
  可續傳、前景服務、進度)、gated 401 誠實提示、模型「已下載/尚未」狀態。
- **已落地:** Hugging Face 授權——`TokenStore`(EncryptedSharedPreferences 加密)+ 目錄權杖 UI,
  gated 下載注入 `Bearer`。
- **已落地:** `DEFAULT_L1` 下載完成後由 [`LiteRtCaptioner`](../vlm/SD.md) 背景載入並產生真描述。
- **已落地:** 官方 EfficientDet-Lite2 固定 URL + size/SHA-256 驗證；MediaPipe metrics 獨立同意／
  撤回後才允許 detector 初始化。
- **待接:** 模型**切換** UI(選定 L1 用哪顆)；目前固定使用 E2B `DEFAULT_L1`，下載後需重啟 App 才會載入。
- **不在範圍:** 推論本身(見 vlm)、L0/L2(見 core-rs)。

## 3. 需求

| 編號 | 需求 |
|---|---|
| FR-1 | 提供可瀏覽的模型**目錄**,每筆含 name/modelId/file/size/能力/gated |
| FR-2 | App 內下載模型檔到裝置(HF resolve URL),**可續傳**、顯示進度、前景不被殺 |
| FR-3 | gated 模型回 401/403 時,清楚提示需 HF 授權(不崩潰) |
| FR-4 | 下載完成後可判定模型**存在**(路徑 + 大小),供 L1 載入 |
| FR-5 | 保留**替換/切換模型**的機制與介面(不同模型效果不同) |
| FR-6 | 非 HF 來源可使用固定 direct URL；下載後須驗證 expected size + SHA-256，且不得把 HF token 傳給其他 host |
| FR-7 | 具第三方 telemetry 的 runtime 模型須在下載／啟用前獨立知情同意，並可撤回 |
| NFR-1 | 大檔(數 GB)下載穩健:前景服務 + 續傳 + 唯一工作(不重複堆疊) |
| NFR-2 | 可測試:目錄/URL/能力純邏輯 host `cargo`/JVM 測試 |
| NFR-3 | 隱私:模型檔存 App 專屬外部目錄;不含使用者資料 |

## 4. 相依與假設

- 下載來源:Hugging Face(`https://huggingface.co/<id>/resolve/main/<file>`)或 catalog 固定的官方 URL。
- WorkManager(前景 dataSync 服務,需 manifest 宣告 `foregroundServiceType`)。
- **Gemma 全系列在 HF 為 gated**(Gemma 授權)→ 目前由使用者貼上 read 權杖；OAuth 待續。
- 下游:[`vlm` LiteRtCaptioner](../vlm/SD.md) 以下載好的 `.litertlm` 原生模型初始化 LiteRT-LM；
  MediaPipe `.task` 不相容於目前 SDK，不能混用。

## 5. 驗收

- **已驗(Pixel 10):** 目錄畫面呈現 L1 模型 + 能力/大小/gated;點下載 → WorkManager 前景服務
  啟動 → HTTP 到 HF → gated 模型正確顯示「需要授權(401)」;**無崩潰**。ModelSpec 6 個 JVM 測試綠。
- **已驗(Pixel 10):** HF 權杖下載 3.66GB E2B `.litertlm`；GPU/GPU L1 產生真實繁中描述。
- **待驗:** 下載中斷續傳、自動/手動模型切換與 E2B vs E4B 同組素材評測。
- **已實機驗:** Lite2 object model consent、App 下載/checksum、detector 熱載入與撤回停止；撤回由
  UI 直接通知 runtime owner，不依賴下一張 CameraX 影格；重新同意已下載模型不會重走網路。

## 追溯

滿足本 SA;實作見 [`SD.md`](SD.md)。相關:[ADR-0009](../../adr/0009-edge-ai-litert-ai-edge.md)、[vlm](../vlm/SA.md)、[android](../android/SD.md)。
