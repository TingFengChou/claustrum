# HANDOFF — 交接與續作事項

**最後更新:** 2026-08-08 · 給下一個接手的人 / 下一個 session。記錄「現況、決策、下一步、怎麼繼續」。

## TL;DR

- **裝置端 App 已成形**:進入流程(Splash→介紹→守護)、底部導覽、機器之眼手動啟動、
  App 內 gated 模型下載、**L1 真實場景描述已通**(`.litertlm`-native Gemma 3n)、開發者模式驗證工具。
- **Android/L1 主線在 PR #24**(分支 `feat/litert-captioner`,**尚未 merge**)。既有 AI review 的 7 個
  P1 threads 已 outdated；2026-08-08 再審新增修正相機失敗不可重試、analyzer 健康狀態與
  LiteRT fallback 前的 Engine 資源釋放；最新 SHA checks 全綠。L2 foundation 疊在 draft
  PR #30(`codex/l2-event-engine`)；三項 checks 亦全綠。
- **最重要的發現**:**L1 場景描述不是可靠的跌倒偵測器**(遠景/小主體會漏、會幻覺)。真偵測要 **L2**(見 issue #26)+ 相機佈建讓主體佔比足夠(見 `docs/design/vlm/SD.md` §8)。

## 目前狀態

Rust 優先(ADR-0007)+ L1 用 LiteRT-LM(ADR-0009)。皆於 **Pixel 10 / Tensor G5 / Android 17** 驗證。

**已 merge 至 main:** P0(Rust→JNI→Kotlin)· P1(CameraX×L0 閘控)· P2 seam(Captioner 佔位)· ADR-0009 轉向 · App 內模型下載 · UI/UX 設計定義。

**在 PR #24(未 merge)—— 本輪大量新增,皆實機驗證:**

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
| 43 個 Android host 單元測試(含 GuardianSession) + Rust 10 + Python 18 | ✅ |

### Legacy React Native / Rush 退場稽核(2026-08-08)

- tracked React Native source 已由 commit `4a4dffaa` 刪除，只有 ADR 與 git 歷史保留決策脈絡。
- 工作目錄曾殘留約 12GB 的未追蹤 `app/node_modules`、舊 RN Android build 與 iOS local 檔；已
  整包移至 macOS Trash：`/Users/austin/.Trash/claustrum-legacy-react-native-app-20260808`，可復原。
- repo 歷史與現況均未找到 Microsoft Rush 的 `rush.json`、`common/config/rush` 或相關套件設定；
  搜尋到的 `Brush` 是 Jetpack Compose graphics API，與 Rush 無關。
- 若「Rush」是指 Rust：Rust 是 ADR-0007 的現行感知核心，不在退場範圍。

## L1 模型現況(重要)

- **模型:** `google/gemma-3n-E2B-it-litert-lm` → `gemma-3n-E2B-it-int4.litertlm`(**3,655,827,456 bytes**,gated)。
- **為何是 `.litertlm` 不是 `.task`:** litertlm 0.11.0 **無法解碼 MediaPipe `.task`**(文字/視覺都只吐 `<pad>`);原生 `.litertlm` 才正常。詳見 `docs/design/vlm/SD.md` §6.1 與記憶 `l1-litertlm-task-pad-incompat`。
- **輸入品質邊界(=相機選型依歸):** 主體佔畫面 **≥ ~⅓** 才穩;遠景/小主體/偏心會漏或幻覺;避免超廣角,寧可多機分區。**L1 ≠ 跌倒偵測器**,偵測交給 L2。全文 `docs/design/vlm/SD.md` §8。
- **待釐清:模型能力 vs 輸入取景(兩條獨立軸線)。** 目前用 Gemma 3n **E2B**(小變體),對細粒度姿態/動作較弱、較易幻覺(出現過「驚慌駕駛」等畫面不存在描述)。**尚未隔離**是取景還是能力主導。**釐清法**:用開發者模式以同一組近景 `dev_eval/` 影格跑 **E2B vs E4B** 比 pass-rate/幻覺/延遲(**issue #29**)。若 E4B 明顯較佳 → 能力是瓶頸,權衡換 E4B/他模型;若都不穩 → 更該靠 L2。

## 下一步(建議順序)

1. **複核並 Merge PR #24**(owner 決定；先確認最新 SHA checks 與 review threads)。`gh pr merge 24 --squash`。
2. **L2 事件引擎(issue #26,P3)已開始**:`codex/l2-event-engine` 已建立 Rust
   Fall/ZoneExit/Violence 狀態機、`schemas/event.schema.json`、serde transport 與 SA/SD；
   下一個關鍵是 Android pose/action extractor → JNI observation 接線與真實素材校準。L1 描述
   只能附加二階脈絡，不能單獨升級 risk/alert。
   - **相鄰技術債:** `NativeCore.describe` + `core-rs/src/vlm.rs` 是 ADR-0008 的未使用 Rust L1
     佔位 seam；現行 `MonitorActivity` 只走 Kotlin `LiteRtCaptioner`。另開小 PR 移除 JNI symbol、
     Rust module/tests 並更新 ADR-0008/0009；**保留**仍在用的 Rust `frameSignature` 與 L2 engine。
3. **相機佈建準則落地**:依 §8,關注區主體佔比 ≥ ⅓、多機分區;dev 模式的模型驗證(`dev_eval/` + `dev_videos/`)用來量測。
4. **釐清模型能力 vs 取景(issue #29)**:用開發者模式跑 E2B vs E4B 同組近景影格,比 pass-rate/幻覺/延遲 → 決定 `DEFAULT_L1` 與是否需更強模型。
5. **L1 效能**(issues #25/#27/#28):變化閘控外加「L1 最小間隔」節流(壓熱/耗電)· 評估 NPU delegate · prefill/輸出優化。
6. **#3 HF OAuth 網頁登入**(取代貼權杖;現況:App 內貼 HF read 權杖即可,已加密存裝置)。
7. **#4 Firebase 接線**(Remote Config 模型目錄 + FCM 告警;`google-services.json` 已放置,ADR-0010)。
8. **#5 升級 library**(targetSdk 已 36;逐一升 lib 並驗證建置)。
9. **P4 音訊融合**(目前音訊誠實標示「未啟用」,不誤報)。

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

- **Merge PR #24**:owner 決定(不自動 merge 進 main)。
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

Gradle 9.3.1 · AGP 8.12.0 · **Kotlin 2.2.10**(為 litertlm metadata 升)· compileSdk/targetSdk 36 · minSdk 26 · NDK 27.1.12297006 · CameraX 1.4.1 · WorkManager 2.9.1 · security-crypto 1.1.0-alpha06 · Compose BOM 2026.02.00 · **litertlm-android 0.11.0** · **lottie-compose 6.6.6**。`.so`/`jniLibs/`/`local.properties`/模型檔不進版控。

## 已知限制

- **L1 延遲 ~6.5–11.5s/張**(有效 ~0.15 fps)；single-flight 只保留最新 pending 放行幀，
  會合併中間畫面，因此只保證不阻塞，不保證事件召回；事件召回必須走獨立 L2 fast path。
- **L1 非跌倒偵測器**(遠景會漏/幻覺)→ 需 L2 + 相機佈建(§8)。
- 音訊模態尚未啟用(誠實標示,不誤報)。

## 參考

ADR:[0006](adr/0006-safety-alert-mvp.md) MVP、[0007](adr/0007-rust-first-redesign.md) Rust 重建、[0009](adr/0009-edge-ai-litert-ai-edge.md) LiteRT、[0010](adr/0010-firebase-architecture.md) Firebase。
設計:[`docs/design/`](design/README.md)(尤其 [`vlm/SD.md`](design/vlm/SD.md) §6.1 pad 根因、§8 相機選型)。
開放 issues:#25/#26/#27/#28/#29。GitHub Milestones:P2 / P2.5 / P3 / P4 / MVP。
