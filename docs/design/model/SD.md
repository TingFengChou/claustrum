# model(裝置端模型管理)— 系統設計(SD)

**狀態:** active · **最後更新:** 2026-08-06 · **負責人:** claustrum
**分析:** [`SA.md`](SA.md)

## 1. 概觀

Android 端 `com.claustrum.model` 套件。移植/簡化自 Google AI Edge Gallery(Apache-2.0)的
模型下載與目錄設計,縮到單檔下載 + 我們的用例。

## 2. 元件與職責

| 元件 | 職責 | 狀態 |
|---|---|---|
| `Capability`(enum) | 模型能力:`ASK_IMAGE`(L1 用)、`CHAT`、`ASK_AUDIO`(未來) | ✅ |
| `ModelSpec`(data) | 目錄項:name/modelId/file/size/capabilities/gated/config;`resolveUrl()`、`localFile()`、`tempFile()`、`isPresent()`;`CATALOG`、`DEFAULT_L1`、`l1Candidates()` | ✅ |
| `ModelDownloadWorker` | WorkManager `CoroutineWorker`:HTTP 下載、`.tmp`+`Range` 續傳、`Bearer` 權杖、200ms 進度、前景通知、完成後 rename | ✅ |
| `ModelRepository` | present 判定、`enqueueUniqueWork` 下載、供 Activity 觀察進度 | ✅ |
| `TokenStore` | HF 存取權杖:EncryptedSharedPreferences(AES-256)加密儲存;`hfToken()`/`setHfToken()`/`hasHfToken()` | ✅ |
| HF 授權 UI | 模型目錄頂部權杖列 + 對話框輸入;gated 下載注入 `Bearer`(`KEY_TOKEN`) | ✅ |

## 3. 介面與合約

- **HF URL:** `https://huggingface.co/<modelId>/resolve/main/<fileName>`。
- **下載輸入(Data):** url / dest / tmp / total / token? / name。
- **進度(setProgress):** `KEY_P_RECEIVED`、`KEY_P_TOTAL`、`KEY_P_RATE`;失敗 `KEY_ERROR`。
- **儲存位置:** `<externalFiles>/models/<version>/<file>`;下載中為 `<file>.tmp`(append + Range 續傳)。
- **gated:** HTTP 401/403 → `Result.failure`,訊息提示需 HF 授權(不崩潰)。
- **前景服務:** manifest 需 merge `androidx.work.impl.foreground.SystemForegroundService`
  的 `android:foregroundServiceType="dataSync"`(否則 Android 14+ `startForeground` 拋例外崩潰)。

## 4. 關鍵流程

```
使用者在「模型目錄」點下載
  → ModelRepository.enqueueDownload(spec, hfToken?)  (唯一工作,KEEP)
  → ModelDownloadWorker:開 HttpURLConnection(Bearer? / Range?)
      401/403 → 提示需授權;200/206 → 串流寫 .tmp,每 200ms setProgress + 前景通知%
  → 完成 rename .tmp → <file>;WorkInfo SUCCEEDED
  → Activity 觀察 getWorkInfosForUniqueWorkLiveData → 更新該模型狀態
```

## 5. 測試策略(必備)

- **JVM 單元測試(✅ 6):** `ModelSpecTest` — resolveUrl 為 HF resolve 格式、vision 模型
  `supportsImage`、文字模型不支援、`l1Candidates()` 僅 vision、DEFAULT_L1 支援影像、目錄無重複檔。
- **裝置整合(✅):** 目錄呈現 + 點下載 → 前景服務啟動 + gated 401 正確提示,無崩潰(Pixel 10)。
- **待:** HF 授權後的完整下載 + 續傳中斷復原,以 `androidTest` 覆蓋。

## 6. 待辦(產品化下一步)

1. ~~**HF 授權**~~ ✅ 已落地:`TokenStore`(EncryptedSharedPreferences)+ 目錄權杖 UI,gated 下載注入 `Bearer`。
   後續可加 HF OAuth 網頁登入流程(目前為貼上 read 權杖)。
2. **`LiteRtCaptioner`**:下載完成的多模態 Gemma 以 LiteRT-LM(`litertlm-android`)載入並推論(見 [vlm SD](../vlm/SD.md))。
3. **模型切換 UI**:選定 L1 用哪顆 vision 模型(UI/UX 定稿見 [`ui`](../ui/README.md))。

## 追溯

滿足 [`SA.md`](SA.md) FR-1…FR-5、NFR-1…NFR-3。相關:[ADR-0009](../../adr/0009-edge-ai-litert-ai-edge.md)、[vlm SD](../vlm/SD.md)、[android SD](../android/SD.md)。
