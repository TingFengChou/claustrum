# ADR-0001 — 平台:選用 Jetson AGX Orin 而非 Android

**狀態:** 被 [ADR-0004](0004-phone-first-single-node.md) 取代 · **日期:** 2026-07-30

> **已被取代。** AGX Orin 始終沒有到貨,最終實際取得的 Jetson 是 *Nano*,無法執行本
> 決策所仰賴的 12B 模型。開發現已轉為以手機為先(phone-first)且單節點——參見
> [ADR-0004](0004-phone-first-single-node.md)。以下的論述在 *AGX Orin 的前提下* 仍然
> 成立,保留下來作為雙節點 Jetson 設計最初為何存在的紀錄。

## 背景

現有硬體:一套 Jetson AGX Orin 開發套件(32 GB 統一記憶體 LPDDR5)以及一支 Pixel 10。

最初的規劃偏好 Android,基於兩項前提:
1. 分支(fork)`google-ai-edge/gallery` 可以最快得到第一個成果——模型管理、LiteRT-LM 綁定與基準測試 UI 都能免費繼承。
2. 受限的硬體意味著只能用小模型(Gemma 4 E2B/E4B)。

## 決策

**從 M0 起,Jetson AGX Orin 就是主要平台。Gemma 4 12B Unified 是主要模型。** Pixel 10 則成為查詢介面(M4),而非運算節點。

## 理由

面對這套硬體,兩項原始前提都站不住腳。

**關於迭代速度。** 該前提把「便利的模型管理」和「快速迭代」混為一談。在 M0–M3 真正需要迭代數百次的,是提示詞(prompt)、閘控參數、schema 與評測樣本集。在 Linux 上這是「編輯即執行」;在 Android 上卻是 Gradle 建置 → 安裝 APK → adb 觀察。兩者相差一個數量級。Gallery 提供的模型下載 UI 在這個階段並不需要。

**關於模型大小。** 32 GB 統一記憶體足以從容容納一個 12B 的多模態模型。Gemma 4 12B Unified 的文件記載其可在具備 16 GB VRAM 或統一記憶體的機器上於本機執行,而且其無編碼器(encoder-free)架構——將原始影像 patch 直接投影到 LLM 的嵌入空間——降低了多模態延遲,而非增加它。

之所以重要,是因為 **VLM 幻覺是本專案的首要風險**(參見 ARCHITECTURE.md)。從 2B 換到 12B 模型,是對抗幻覺現有最有效的單一手段,而硬體本已足以支撐。提示詞工程是第二道防線,而非第一道。

## 後果

- 與 Gallery 程式碼庫分道揚鑣。可透過 `litert-lm serve` 緩解,它以相同的 `.litertlm` 模型格式對外提供一個 OpenAI 相容的本機端點——因此若日後重新考慮 Android 版本,量化方案與提示詞仍具可攜性。
- LiteRT-LM 的 Linux GPU 路徑(ML Drift)在 Jetson 的 CUDA/ARM 組合上尚未經驗證。Jetson 的原生強項是 TensorRT-LLM 與 llama.cpp CUDA。**M0 必須先對三者都做基準測試**再做決定。
- 需要市電供電、主動散熱與攝影機(皆非內建)。可用 RTSP IP 攝影機,或一支跑 RTSP 伺服器 App 的備用手機。
- AGX Orin 是標準的機器人運算平台。機器人擴充從「臆測性構想」變成「在同一台機器上加一個 ROS 2 節點」。
- 風扇噪音與 15–60 W 的耗電量需針對居家環境評估。M0 時測試 30 W 模式的可行性。
