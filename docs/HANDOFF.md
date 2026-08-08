# HANDOFF — 交接與續作事項

**最後更新:** 2026-08-08 · 給下一個接手的人 / 下一個 session。記錄「現況、決策、下一步、怎麼繼續」。

## TL;DR

- **裝置端 App 已成形**:進入流程(Splash→介紹→守護)、底部導覽、機器之眼手動啟動、
  App 內 gated 模型下載、**L1 真實場景描述已通**(`.litertlm`-native Gemma 3n)、開發者模式驗證工具。
- **PR #24/#30/#33/#34/#35/#40/#43 已於 2026-08-08 merge 至 `main`**；#35 的 ML Kit pose fast path
  merge commit 為 `65905cd2`，#40 的 FIT_CENTER／rotation／zoom／匿名框 merge commit 為
  `898662ba`，#43 的 object candidate merge commit 為 `bd258aed`。checks 與 review threads 均
  逐則處理後合併；Pixel 10 已驗證相機、pose/JNI、
  前後景恢復與 2F→1F 初測。
- **本輪續作:** #43 後新增 session-local `AnonymousObjectTracker` 與 `LitterEvidenceTracker`：
  顯示 P/O 槽位、bbox motion、連續近接→可見分離→分離後靜置→人離開待檢視；仍不建立
  litter Event。本輪不關閉場域／多人 association #39、no-telemetry #41 或明確停止守護 #42。
- **最重要的發現**:**L1 場景描述不是可靠的跌倒偵測器**(遠景/小主體會漏、會幻覺)。L2
  已接 ML Kit 單人 pose fast path，但仍須固定鏡位素材校準後才可告警(issue #26)。
- **多人能力仍未完成:** 現行 ML Kit 只回最顯著一人；交錯/遮擋/人物切換限制、MediaPipe
  multi-pose 等方案與驗收已立 issue #36。Preview 匿名框不等於多人或事件判斷。
- **產品已收斂為兩個情境(ADR-0012):** 跌倒／倒地與亂丟垃圾。MediaPipe Object Detector
  只作候選閘門；aHash movement window、EfficientDet-Lite2 allowlist、有界佇列、本機 bbox、
  匿名短時幾何 tracker 與 fail-closed evidence stage 已接線。ROI、多人交錯可靠性、L2 Event 與
  場域驗收仍見 issue #39。
- **MediaPipe 隱私邊界:** `tasks-vision 0.10.35` 會傳非影像 API 使用／效能 metrics；模型頁已加
  獨立同意與撤回，未同意不初始化。完全 no-telemetry 路線見 issue #41。
- **2F→1F 安裝:** FIT_CENTER 保留完整視野；`fullSensor`、landscape 重排、CameraX targetRotation
  與持久化 zoom 已實作。四向驗收見 #37；1×/2×/3×、FOV／像素／俯角遮擋見 #38。
- **首輪真實鏡位結果:** 1× 無人時樹幹／告示牌出現約 197 px pose 候選；2× 有一名部分遮擋
  行人時反而無 pose output。已將 UI 改稱「人體姿態候選」，此鏡位未完成 #38 前不可接告警。
- **Object detector 同鏡位結果:** Lite0 約 121 ms，對樹木／告示牌出現兩個 `person` 候選並
  漏掉畫面小型真人；已改用 448×448 Lite2，空景約 176–227 ms、初測未再誤框樹木，但尚缺
  三人同框 smoke 只框兩人且小框有定位誤差。Lite2 實機 20 幀 p50 191 ms／p95 237 ms、合併
  2/20；全框 CameraX probe 精確符合 FIT_CENTER，問題在模型 localization/domain gap。catalog 的
  Lite2 是精度優先 baseline，不是部署完成宣告。
- **Object model UX／完整性已實機驗:** 7,515,971 bytes 由 App 下載、SHA-256 符合 pinned 值；
  metrics 撤回會直接停 detector/清框、不等待下一張影格，重新同意既有模型不重下載。測試期間
  暫移的 Gemma 已還原，Lite0/Lite2 ADB 備份亦已清除。
- **Tracker 實機 smoke:** 新 APK 真相機已畫出 `人 P5 76%` session-local label，前後景
  CameraService stop/start 後無 crash；但稍後清楚可見成人推嬰兒車時 detector 回 0，沒有
  detection 就無法 association。故只驗證接線／reset，不宣稱 recall、ID stability 或 litter stage
  可用；#39 保持 open。
- **旋轉驗證邊界:** Pixel 以 WindowManager 強制 ROTATION_90 已確認 landscape 雙欄、底部導覽與
  zoom 控制可操作；因手機實體感測器仍為 portrait，強制畫面下 camera buffer 會側轉，不能冒充
  `OrientationEventListener` 實體四向驗收。裝置原 rotation 設定已還原，#37 仍需手動轉機驗證。
- **相機停止控制仍缺:** 目前手動啟動、退背景由 lifecycle 停止，但前景內沒有「停止守護」且
  跨 tab 缺 App 內指示；已修正 PRIVACY 的過度承諾並立 issue #42。

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
| Rust L2 + Android/JNI + ML Kit 單人 pose fast path | ✅ 接線；素材校準/policy 待續 |
| Camera preview 匿名人物框（不顯示骨架）+ 主體像素提示 | ✅ CameraX transform；多人追蹤見 #36 |
| fullSensor / landscape / zoom persistence | ✅ 實作；四向與 2F→1F 實機驗收見 #37/#38 |
| MediaPipe object→litter 管線 | 🟡 candidate/gate/匿名短時 tracker/evidence overlay 已接；ROI/多人 association/Event/場域驗收見 #39 |
| Android host 96 + Rust 29 + Python 28 | ✅；Android lint 0 issue、debug APK 可組裝 |

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
   - pose-only 的 impact 固定 0；快速 impact confirmation 要另接可替換 extractor。
   - 多人追蹤與遮擋恢復依 issue #36 做 PoC；首選 MediaPipe LIVE_STREAM + `numPoses`，但仍須
     自己驗證匿名 association，不能把 `numPoses` 當成穩定 tracking ID。
   - `MlKitAnalyzer` 已評估但不採用，因現有 L0/L1 仍需同一個 raw `ImageProxy` 分支；目前由單一
     analyzer 明確持有 proxy，ML Kit task 完成後才跑 L0，completion `finally` close。
2. **亂丟垃圾時序(issue #39):** aHash movement gate → MediaPipe Object Detector `VIDEO` candidate
   → session-local tracker → fail-closed evidence stage 已接；下一步完成 ROI、固定鏡位標註集、
   多人／多物 ID-switch 與 allowlist/min-pixel confusion matrix，再定 association／dwell threshold。
   通過後才設計 `ObjectObservation` schema 與 L2 litter Event；單一 detection 或 pending-review
   不得成事件。模型不足則訓練客製 detector；MediaPipe no-telemetry 替代獨立追 #41。
3. **2F→1F 場域 commissioning(issue #38):** 實測 1×/2×/3× 的人物、小物 recall、完整 FOV、
   陡峭俯角遮擋、多人與日夜；單鏡不成立就分區／多鏡，不以數位 zoom 製造盲區。
4. **明確停止守護(issue #42):** 補 start/stop 狀態、CameraX unbind、queue/overlay 清理、安全重啟與
   跨 tab camera indicator；20 次循環實機驗收，不再只依靠 Activity 退背景。
5. **清除 legacy Rust L1 seam:** `NativeCore.describe` + `core-rs/src/vlm.rs` 是 ADR-0008 的未使用
   佔位；另開小 PR 移除 JNI symbol、Rust module/tests 並更新 ADR-0008/0009。**保留**仍在用的
   Rust `frameSignature` 與 L2 engine。
6. **釐清模型能力 vs 取景(issue #29):** 同組近景影格比較 E2B/E4B pass-rate、幻覺與延遲。
7. **L1 效能**(issues #25/#27/#28):最小間隔節流、NPU delegate、prefill/輸出優化。
8. **#3 HF OAuth 網頁登入**(取代貼權杖；現況為裝置端加密 HF read token)。
9. **#4 Firebase 接線**(Remote Config 模型目錄 + FCM 告警；ADR-0010)。
10. **#5 升級 library**(targetSdk 已 36；逐一升級並驗證)。

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
- **實體相機測試**:CameraX/ML Kit/preview transform 必須連接實機並實際對準人物；dev 影片目前
  只驗 L1，不能替代 L2/overlay。2026-08-08 已以 Pixel 10 驗證 #35 相機、pose/JNI 載入與前後景恢復。

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

Gradle 9.3.1 · AGP 8.12.0 · **Kotlin 2.2.10**(為 litertlm metadata 升)· compileSdk/targetSdk 36 · minSdk 26 · NDK 27.1.12297006 · CameraX 1.4.1 · **ML Kit pose-detection 18.0.0-beta5** · **MediaPipe tasks-vision 0.10.35** · WorkManager 2.9.1 · security-crypto 1.1.0-alpha06 · Compose BOM 2026.02.00 · **litertlm-android 0.11.0** · **lottie-compose 6.6.6**。`.so`/`jniLibs/`/`local.properties`/模型檔不進版控。

## 已知限制

- **L1 延遲 ~6.5–11.5s/張**(有效 ~0.15 fps)；single-flight 只保留最新 pending 放行幀，
  會合併中間畫面，因此只保證不阻塞，不保證事件召回；事件召回必須走獨立 L2 fast path。
- **L1 非跌倒偵測器**(遠景會漏/幻覺)→ 需 L2 + 相機佈建(§8)。
- ML Kit pose 只支援最顯著一人、無公開 tracking ID、API beta；多人能力追蹤於 #36。
- `fullSensor`/landscape/zoom 已實作，但 90°/180°/270° 與 2F→1F 尚待 #37/#38 實機完成。
- MediaPipe object candidate 後已有 session-local greedy geometry tracker，但不是 persistent
  identity tracker；遮擋、多人／多物交錯、漏偵與 queue 合併會造成 ID-switch。全域 movement gate
  也不是 bbox motion 證據。ROI／可靠多人 association／litter Event 尚未接；COCO 類別與
  pending-review 都不能直接代表垃圾，見 #39。
- MediaPipe Tasks 非影像 metrics 需同意；完全停用仍待 #41。
- 音訊模態尚未啟用(誠實標示,不誤報)。

## 參考

ADR:[0006](adr/0006-safety-alert-mvp.md) 歷史 MVP、[0007](adr/0007-rust-first-redesign.md) Rust 重建、[0009](adr/0009-edge-ai-litert-ai-edge.md) LiteRT、[0010](adr/0010-firebase-architecture.md) Firebase、[0011](adr/0011-l2-fast-path-evidence.md) L2 fast path、[0012](adr/0012-two-scenario-mvp-and-object-gating.md) 兩情境收斂。
設計:[`docs/design/`](design/README.md)(尤其 [`vlm/SD.md`](design/vlm/SD.md) §6.1 pad 根因、§8 相機選型)。
開放 issues:#25/#26/#27/#28/#29/#36/#37/#38/#39/#41。GitHub Milestones:P2 / P2.5 / P3 / P4 / MVP。
