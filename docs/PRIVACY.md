# 隱私與合規

這是一份設計約束文件,不是免責聲明。如果你正打算把攝影機對準自己的家人,請讀它。

## 設計承諾

這些承諾在可能之處以結構性方式強制執行,無法做到之處則以政策約束。

| 承諾 | 執行方式 |
|---|---|
| 影片影格絕不離開裝置 | **目前程式邊界:** CameraX/ML Kit/MediaPipe/LiteRT 都在 App 行程；Bitmap 只在有界 RAM queue，用完 recycle，不寫入 Event、CaptionLog 或網路 payload。**雙節點時代:結構性** — 查詢介面沒有任何通往影格儲存的程式碼路徑。見 [ADR-0004](adr/0004-phone-first-single-node.md)。 |
| 不做臉部辨識、不做身分歸屬 | **結構性** — `Actant` 是一個角色槽位;schema 中根本不存在身分欄位 |
| 影格保存 | **預設不保存**；現行 App 沒有錄影或影格資料庫。開發者測試素材只讀使用者主動放入的 app external-files |
| Caption/Kineme 保存 | 現行 `CaptionLog` 只在 RAM 最多 100 筆，process death 清除；未來若持久化需另立 retention ADR |
| 雲端升級預設關閉 | 政策 — 需逐次同意授權 |
| 攝影機啟停 | **部分落地** — App 預設待命、需手動啟動；Activity 退背景時 CameraX lifecycle 停止取幀。目前尚缺前景內明確的「停止守護」控制與硬體遮蓋，追蹤於 issue #42。 |
| 相機狀態指示 | **部分落地** — 守護頁 badge 與 Android 系統 camera privacy indicator 可見；跨 tab 的 App 內指示、常駐通知／硬體 LED 待 issue #42。 |

浴室與臥室預設不在範圍內。若真要部署於此,也僅限純文字模式且不保留任何影格。

## MediaPipe Tasks 的非影像 metrics

MediaPipe 的推論輸入仍在裝置端；Google 的
[Privacy Notice（2026-06-05）](https://github.com/google-ai-edge/mediapipe#privacy-notice)則另行說明，
Tasks API 會把 API performance/utilization metrics 傳給 Google，並要求 App 視適用法律取得知情
同意。對 Android `tasks-core:0.10.35` AAR 的實際稽核顯示 payload schema 含 platform、app id／
version、task/mode、呼叫／丟幀數、延遲與 init error；沒有 image、bbox、category 或 caption 欄位。

本專案因此採以下邊界：

- 預設不初始化 MediaPipe Object Detector；模型頁下載前顯示獨立告知，使用者可拒絕。
- 同意狀態只存在本機 SharedPreferences；撤回會直接通知 detector owner、停止新 candidate
  submission 並序列化關閉 detector，不依賴 CameraX 再送下一張影格。
- 影像、物件框與類別只在 RAM；不接 Google DataTransport，也不寫入 App 自己的網路 payload。
- `AnonymousObjectTracker` 只保存同類別 bbox 幾何、短時速度與 session-local `P/O` 整數槽位；
  不使用臉、外觀 embedding、硬體識別碼或跨 session re-identification。退背景、撤回、track gap
  或 Activity destroy 會重設；這些槽位與 evidence stage 不落地、不跨 JNI、不外傳。
- 這仍不是「零網路 metadata」。完全停用／隔離 SDK metrics 的可重現方案追蹤於
  [issue #41](https://github.com/TingFengChou/claustrum/issues/41)；完成前文件與 UI 必須持續揭露。

## AppFunctions 的難題

這是本專案中最尖銳的張力,值得被直白地說清楚。

Android 的 AppFunctions 文件指出,**系統 agent 為了使用更大的模型,可能會在伺服器上處理使用者查詢**。因此,當家庭端的查詢介面被開放給 Gemini 時:

- 影片影格留在裝置上 ✓
- **使用者的問題與回傳的 Kineme 文字可能離開裝置** ✗

關於家庭活動的結構化文字,在某些方面比影片更糟糕的暴露:它可被搜尋、可被比對,而且要無限期保留的成本極低。

### 後果:分層暴露,預設關閉

| 層級 | 暴露的工具 | 酬載(Payload) | 預設 |
|---|---|---|---|
| **T0** | 無 | — | ✅ 預設 |
| T1 | `getHomeStatus` | 極為粗略 —「有人在家 / 沒人在家」、「今天無異常」 | 選擇加入(opt-in) |
| T2 | `queryKinemes`、`getEthogram` | Kineme 文字、時間戳記 | 選擇加入,並附明確警告 |
| T3 | 影格 URI | 影像 | ❌ 絕不對外暴露 |

以 `AppFunctionManager.setAppFunctionEnabled()` 逐一函式實作。每個函式都有自己的 ID,可獨立切換。

### 必備,而非可選

有三件事會**隨著** AppFunctions provider 一同出貨,絕不在它之後才補上:

1. **分層同意授權 UI**,在設定畫面上以白話文載明伺服器處理的警告 — 而不是埋在一份政策文件裡。
2. **呼叫端允許清單(allowlist)。**拒絕任何非預期的呼叫封包。記錄被拒的紀錄。
3. **對使用者可見的稽核紀錄。**一個顯示「誰在何時查詢了什麼」的畫面。

稽核紀錄是唯一讓那個切換開關值得信任的東西。而這三者都必須在功能可用之前就存在,因為一旦家庭開始依賴它,事後補上限制的意願就會蒸發殆盡。

## 法律(台灣)

非法律意見。在超出個人使用的任何用途之前,請諮詢律師。

- **個人資料保護法 (PDPA):**純屬家庭內的個人使用有豁免的空間,但只要一位訪客被拍到、或這套系統被提供給任何其他人,它就落入了適用範圍。
- **知情同意**須事先取得自每一位共同居住者。若涉及未成年人,則須取得自監護人。
- 任何商業或內部產品用途,都須先經法律審查。透過雲端 agent 處理的家庭活動語意摘要,不在家庭使用豁免的範圍內。

## 不是醫療器材

跌倒偵測會漏掉事件。危害偵測會產生誤報。這套系統絕不該是任何人唯一的安全網,也絕不該被當作照護服務來呈現。

這段聲明該放在 README 的最上方,而不是一份文件的最下方。它被刻意放在兩處。
