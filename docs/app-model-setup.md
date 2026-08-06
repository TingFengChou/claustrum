# App 裝置端模型設定(即時字幕 / VLM)

App 的即時字幕(與後續的事件偵測)用**裝置端** VLM 推論(llama.rn / llama.cpp)。模型檔
不進版控(太大、且非程式碼),需手動推送到手機。

## 目前使用的模型

**SmolVLM-256M-Instruct(GGUF)** —— 小而快,適合手機上近即時描述(英文)。
來源(未 gated):HuggingFace `ggml-org/SmolVLM-256M-Instruct-GGUF`。

- `SmolVLM-256M-Instruct-Q8_0.gguf`(LLM,約 175 MB)
- `mmproj-SmolVLM-256M-Instruct-Q8_0.gguf`(視覺投影器,約 104 MB)

> 註:SmolVLM-256M 以英文為主,字幕輸出為英文。要繁中/更準的描述需較大的多語模型
> (如 Gemma E 系列),但每幀延遲會明顯增加。

## 推送到裝置

模型放在 App 的外部檔案目錄。**注意**:用 `adb` 建立的子目錄擁有者是 `shell`,App
(不同 uid)可能無法進入,因此要開放目錄權限:

```bash
DEST=/sdcard/Android/data/com.claustrum/files/models
adb shell mkdir -p "$DEST"
adb push SmolVLM-256M-Instruct-Q8_0.gguf "$DEST/"
adb push mmproj-SmolVLM-256M-Instruct-Q8_0.gguf "$DEST/"
adb shell chmod 0777 "$DEST"          # 讓 App(非 shell)能進入此目錄
adb shell chmod 0666 "$DEST"/*.gguf   # 檔案可讀
```

路徑對應 `app/src/vlm/models.ts` 中的 `SMOLVLM_256M`(即
`getExternalFilesDir()/models/…` 的實際路徑)。

## 驗證

啟動 App →「開始監測」。授予相機/麥克風權限後,底部「即時描述」字幕會在模型載入完成
(數秒)後開始更新。若顯示「模型載入失敗」,多半是上面的目錄權限沒開(見 chmod 步驟),
可用 `adb logcat | grep RNLlama` 看 `unable to load model` 等訊息。
