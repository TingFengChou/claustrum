# HANDOFF — 交接與續作事項

**最後更新:** 2026-08-08 · 給下一個接手的人 / 下一個 session。記錄「現況、決策、下一步、怎麼繼續」。

## TL;DR

- **裝置端 App 已成形**:進入流程(Splash→介紹→守護)、底部導覽、機器之眼手動啟動、
  App 內 gated 模型下載、**L1 真實場景描述已通**(`.litertlm`-native Gemma 3n)、開發者模式驗證工具。
- **PR #24 與 #30 已於 2026-08-08 squash merge 至 `main`**(`37849491`、`c06c05b7`)；兩者最新
  SHA 的 CI/Android/Gemini checks 全綠。PR #24 的 7 個舊 P1 threads 已逐則查證、回覆並 resolve；
  feature branches 已刪除。
- **最重要的發現**:**L1 場景描述不是可靠的跌倒偵測器**(遠景/小主體會漏、會幻覺)。L2
  已接 ML Kit 單人 pose fast path，但仍須固定鏡位素材校準後才可告警(issue #26)。

## 目前狀態

Rust 優先(ADR-0007)+ L1 用 LiteRT-LM(ADR-0009)。皆於 **Pixel 10 / Tensor G5 / Android 17** 驗證。

**已 merge 至 main:** P0(Rust→JNI→Kotlin)· P1(CameraX×L0 閘控)· P2/L1 LiteRT 真實描述 ·
App 內模型下載 · P2.5 Compose App Shell · P3 Rust L2 engine/event schema + Android/JNI bridge。

**目前 `main` 已具備:**

| 項目 | 狀態 |
|---|---|
| 進入流程 Splash(機器之眼 Lottie)→ 首次介紹頁 → 守護 | ✅ |
| 底部導覽 App Shell(守護/事件/模型/設定,無死路) | ✅ |
| 機器之眼**手動啟動**(進頁不自動開相機,點「啟動守護」才開) | ✅ |
| 啟動白畫面修正(深色 `Theme.Claustrum`) | ✅ |
| **L1 真實場景描述**(`.litertlm`-native,GPU/GPU,~6.5–11.5s) | ✅ |
| L1 輸出:emoji/符號過濾 + 非中文/碎片拒絕 + 保留最後有效描述 | ✅ |
| Codex P1(初始化移出 analyzer、保留放行幀、旋正、有界輸出) | ✅ |
| 相機權限/bind 失敗可重試、連續 analyzer 失敗顯示「需處理」並可恢復 | ✅ |
| LiteRT delegate 初始化失敗先 close Engine，再嘗試下一個 backend(防 OOM) | ✅ |
| 開發者模式:測試影片播放(過 L0→L1)、模型驗證(pass-rate+延遲)、描述串流+記錄 | ✅ |
| 移除 legacy RN `app/` | ✅ |
| Rust L2 + Android/JNI + ML Kit 單人 pose fast path | ✅ 接線；素材校準/impact/多人 action/policy 待續 |
| 56 個 Android host 單元測試 + Rust 29 + Python 28 | ✅ |

### Legacy React Native 退場稽核(2026-08-08)

- tracked React Native source 已由 commit `4a4dffaa` 刪除，只有 ADR 與 git 歷史保留決策脈絡。
- 工作目錄曾殘留約 12GB 的未追蹤 `app/node_modules`、舊 RN Android build 與 iOS local 檔；已
  整包移至 macOS Trash：`/Users/austin/.Trash/claustrum-legacy-react-native-app-20260808`，可復原。

## L1 模型現況(重要)

- **模型:** `google/gemma-3n-E2B-it-litert-lm` → `gemma-3n-E2B-it-int4.litertlm`(**3,655,827,456 bytes**,gated)。
- **為何是 `.litertlm` 不是 `.task`:** litertlm 0.11.0 **無法解碼 MediaPipe `.task`**(文字/視覺都只吐 `<pad>`);原生 `.litertlm` 才正常。詳見 `docs/design/vlm/SD.md` §6.1 與記憶 `l1-litertlm-task-pad-incompat`。
- **輸入品質邊界(=相機選型依歸):** 主體佔畫面 **≥ ~⅓** 才穩;遠景/小主體/偏心會漏或幻覺;避免超廣角,寧可多機分區。**L1 ≠ 跌倒偵測器**,偵測交給 L2。全文 `docs/design/vlm/SD.md` §8。
- **待釐清:模型能力 vs 輸入取景(兩條獨立軸線)。** 目前用 Gemma 3n **E2B**(小變體),對細粒度姿態/動作較弱、較易幻覺(出現過「驚慌駕駛」等畫面不存在描述)。**尚未隔離**是取景還是能力主導。**釐清法**:用開發者模式以同一組近景 `dev_eval/` 影格跑 **E2B vs E4B** 比 pass-rate/幻覺/延遲(**issue #29**)。若 E4B 明顯較佳 → 能力是瓶頸,權衡換 E4B/他模型;若都不穩 → 更該靠 L2。

## 下一步(建議順序)

1. **L2 錄影回歸與校準(issue #26,P3):** CameraX→ML Kit base Pose Detection `STREAM_MODE`→
   純 Kotlin `PoseObservationExtractor`→`FastPathObservation`→JNI→Rust 已接線。下一步讓
   `dev_videos/` 走同一 L2 path，收集 fall/正常坐下/刻意躺下/遮擋素材的 confusion matrix、p95
   與 72h negative corpus；未達 `<1/24h` 前不接 policy/通知。
   - 限制：只追最顯著一人、API beta、無 tracking ID，且官方要求臉部可見/完整身體取景最佳；
     背向、遮擋或倒地後臉被擋是 recall 硬風險。追蹤中斷會輪替匿名 role slot。
   - pose-only 的 impact/contact/strike 固定 0；impact 快確認與多人 violence 要另接可替換 extractor/音訊。
   - `MlKitAnalyzer` 已評估但不採用，因現有 L0/L1 仍需同一個 raw `ImageProxy` 分支；目前由單一
     analyzer 明確持有 proxy，ML Kit task 完成後才跑 L0，completion `finally` close。
2. **清除 legacy Rust L1 seam:** `NativeCore.describe` + `core-rs/src/vlm.rs` 是 ADR-0008 的未使用
   佔位；另開小 PR 移除 JNI symbol、Rust module/tests 並更新 ADR-0008/0009。**保留**仍在用的
   Rust `frameSignature` 與 L2 engine。
3. **相機佈建準則落地:** 依 §8,關注區主體佔比 ≥ ⅓、多機分區；以 `dev_eval/` + `dev_videos/` 量測。
4. **釐清模型能力 vs 取景(issue #29):** 同組近景影格比較 E2B/E4B pass-rate、幻覺與延遲。
5. **L1 效能**(issues #25/#27/#28):最小間隔節流、NPU delegate、prefill/輸出優化。
6. **#3 HF OAuth 網頁登入**(取代貼權杖；現況為裝置端加密 HF read token)。
7. **#4 Firebase 接線**(Remote Config 模型目錄 + FCM 告警；ADR-0010)。
8. **#5 升級 library**(targetSdk 已 36；逐一升級並驗證)。
9. **P4 音訊融合**(目前誠實標示未啟用)。

## 開發者模式(驗證工具)用法

1. 設定頁開「開發者模式」(持久化)。
2. `adb push` 測試素材到裝置:
   ```bash
   BASE=/sdcard/Android/data/com.claustrum/files
   adb shell mkdir -p $BASE/dev_eval $BASE/dev_videos
   # 標註影格(檔名帶預期關鍵詞,any-match 計 pass):
   adb push fall_close.jpg "$BASE/dev_eval/fall__倒臥,跌倒,倒地,躺,地上.jpg"
   # 測試影片:
   adb push clip.mp4 "$BASE/dev_videos/clip.mp4"
   ```
3. 守護頁:**▶ 模型驗證**(跑 dev_eval → pass-rate + avg/p50 延遲)· **▶ 測試影片**(於 visor 播放並過 L0→L1)。
4. 描述逐列記錄於守護頁「描述串流」(最近 10)與事件頁(完整 100、時間序)。
5. **換模型 SOP:** 換 `ModelSpec` 後,先跑模型驗證比對 pass-rate 與延遲,再決定是否採用。

## 阻擋 / 需要人介入

- **`GEMINI_API_KEY`**(repo secret):未設時雲端 `ai-code-review` 部分功能 skip。
- **HF read 權杖**:owner 在 App 模型目錄「設定」貼上即可下載 gated Gemma(加密存裝置)。
- **實體相機測試**:遠端無法對準實體鏡頭;用開發者模式的測試影片/影格驗證 L1。

## 開發流程(硬性)

見 [`DEVELOPMENT.md`](DEVELOPMENT.md)。分支 → commit → push → PR → **CI(硬關卡)+ AI 審查(Gemini/Codex)** → 逐則回覆審查 → 查證無誤才 merge → 刪分支。**不可直接推 main**。每模組保有 SA/SD;里程碑同步更新 README/ROADMAP;繁體中文為主、程式識別碼英文。UI 自動化測試用 Maestro(`.maestro/`)。

## 關鍵指令

```bash
# Rust 核心:host 測 + 交叉編譯 .so
cd core-rs && cargo test
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/27.1.12297006
cargo ndk -t arm64-v8a -o ../android/app/src/main/jniLibs build --release
# Android:單元測試 / 建 APK / 安裝
cd android && ./gradlew :app:testDebugUnitTest && ./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 版本矩陣(known-good)

Gradle 9.3.1 · AGP 8.12.0 · **Kotlin 2.2.10**(為 litertlm metadata 升)· compileSdk/targetSdk 36 · minSdk 26 · NDK 27.1.12297006 · CameraX 1.4.1 · **ML Kit pose-detection 18.0.0-beta5** · WorkManager 2.9.1 · security-crypto 1.1.0-alpha06 · Compose BOM 2026.02.00 · **litertlm-android 0.11.0** · **lottie-compose 6.6.6**。`.so`/`jniLibs/`/`local.properties`/模型檔不進版控。

## 已知限制

- **L1 延遲 ~6.5–11.5s/張**(有效 ~0.15 fps)；single-flight 只保留最新 pending 放行幀，
  會合併中間畫面，因此只保證不阻塞，不保證事件召回；事件召回必須走獨立 L2 fast path。
- **L1 非跌倒偵測器**(遠景會漏/幻覺)→ 需 L2 + 相機佈建(§8)。
- 音訊模態尚未啟用(誠實標示,不誤報)。

## 參考

ADR:[0006](adr/0006-safety-alert-mvp.md) MVP、[0007](adr/0007-rust-first-redesign.md) Rust 重建、[0009](adr/0009-edge-ai-litert-ai-edge.md) LiteRT、[0010](adr/0010-firebase-architecture.md) Firebase、[0011](adr/0011-l2-fast-path-evidence.md) L2 fast path。
設計:[`docs/design/`](design/README.md)(尤其 [`vlm/SD.md`](design/vlm/SD.md) §6.1 pad 根因、§8 相機選型)。
開放 issues:#25/#26/#27/#28/#29。GitHub Milestones:P2 / P2.5 / P3 / P4 / MVP。
