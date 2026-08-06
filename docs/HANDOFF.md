# HANDOFF — 交接與續作事項

**最後更新:** 2026-08-06 · 給下一個接手的人 / 下一個 session。此檔記錄「現況、決策、下一步、怎麼繼續」。

## 目前狀態(main)

Rust 優先、效能優先的原生重建(ADR-0007);L1 用 Google AI Edge / LiteRT(ADR-0009,不自建 llama.cpp)。皆於 **Pixel 10 / Tensor G5 / Android 17** 驗證。

| 階段 | 狀態 |
|---|---|
| P0 Rust 核心 → `.so` → JNI → Kotlin | ✅ 裝置回話 + L0 PASS(PR #12/#13) |
| P1 CameraX × L0 變化閘控 | ✅ 靜態省 ~100%,ChangeGate 7 測(PR #14) |
| P2 L0→L1 觸發 + `Captioner` 邊界(佔位) | ✅ 裝置驗證(PR #16) |
| P2 引擎轉向 LiteRT(ADR-0009) | ✅ 文件/設計(PR #17) |
| P2 App 內模型下載 + 目錄/能力/切換 | ✅ 裝置驗證,gated 401 正確處理(PR #18) |
| UI/UX 設計定義(Tesla/Optimus,機器之眼) | ✅ `docs/design/ui/`(PR #19) |

GitHub Milestones:#3 P2、#4 P2.5 UI 實作、#5 P3 事件、#6 P4 音訊、#7 MVP。

## 下一步(依序)

1. **HF 登入授權**(阻擋真模型下載):Gemma 全系列在 HF 為 gated。實作 HF OAuth/token,存 `EncryptedSharedPreferences`,注入 `ModelDownloadWorker` 的 `KEY_TOKEN`。參考 AI Edge Gallery `huggingface/HfModelUtils.kt`。
2. **`LiteRtCaptioner`**(真多模態):加依賴 `com.google.ai.edge.litertlm:litertlm-android:0.11.0`;`Engine(EngineConfig(modelPath, backend=GPU, visionBackend=GPU, maxNumTokens))` → `createConversation(ConversationConfig(SamplerConfig(topK,topP,temperature)))`;每放行幀 `Content.ImageBytes(bitmap.toPngByteArray())` + `Content.Text(客觀提示)` → `conversation.sendMessageAsync(...)`。載一次、跨幀重用;Kotlin 持有生命週期。實作 `Captioner` 介面,接到 analyzer 放行分支。
3. **UI 實作(Compose,P2.5)**:依 `docs/design/ui/claustrum-uiux.html` 四畫面,替換目前的程式化 View。
4. **P3 L2 事件引擎**:`core-rs` events 模組(Fall/Leave/Violence 狀態機),建 `schemas/event.schema.json`;risk 需畫面內可見證據。
5. **P4 音訊融合**。

## 阻擋 / 需要人介入

- **`GEMINI_API_KEY`**(repo secret,owner 才能加):未設時雲端 `ai-code-review.yml` 會 skip。設定後每個 PR 自動 AI 審查。
- **HF 帳號授權**:下載 gated Gemma `.task` 需要;或在 Google AI Edge Gallery App 先確認可下載。

## 開發流程(硬性)

見 [`DEVELOPMENT.md`](DEVELOPMENT.md)。摘要:分支 → commit → push → PR → **CI(硬關卡)+ GitHub Action AI 審查** → **人/Claude 檢視審查結果並逐則回覆** → 查證無誤才 merge → 刪分支。不可直接推 main。每模組保有 SA/SD([`docs/design/`](design/README.md));里程碑同步更新 README/ROADMAP;產出繁體中文為主。

## 關鍵指令

```bash
# Rust 核心:host 測 + 交叉編譯 .so 到 jniLibs
cd core-rs && cargo test
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/27.1.12297006
cargo ndk -t arm64-v8a -o ../android/app/src/main/jniLibs build --release
# Android:單元測試 / 建 APK / 安裝
cd android && ./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk   # 舊簽章衝突先 adb uninstall com.claustrum
# 本機 AI 審查(merge 前)
bash scripts/ai-review.sh main
```

## 版本矩陣(known-good)

Gradle 9.3.1 · AGP 8.12.0 · Kotlin 2.1.20 · compileSdk/targetSdk 36 · minSdk 26 · NDK 27.1.12297006 · CameraX 1.4.1 · WorkManager 2.9.1 · (規劃)litertlm-android 0.11.0。`.so`/`jniLibs/`/`local.properties`/模型檔不進版控。

## 參考

ADR:[0004](adr/0004-phone-first-single-node.md) 手機優先、[0006](adr/0006-safety-alert-mvp.md) MVP、[0007](adr/0007-rust-first-redesign.md) Rust 重建、[0009](adr/0009-edge-ai-litert-ai-edge.md) LiteRT。設計:[`docs/design/`](design/README.md)。Edge AI 模型用法:README「Edge AI 模型使用」。
