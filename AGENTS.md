# AGENTS.md — claustrum

給在此 repo 工作的 AI 代理(含 OpenAI Codex code review、Codex CLI 等)的指引。人類讀
[`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md);完整開發規範亦編寫於 `.claude/skills/dev-standards`。

## 專案一句話

把攝影機從「事後回看」變成「主動防護」的即時、裝置端(edge AI)、多模態守護者。Rust 感知核心
(ADR-0007)+ L1 用 Google AI Edge / LiteRT(ADR-0009)+ 原生 Android(Kotlin/Compose)。

## 建置 / 測試

- Rust 核心:`cd core-rs && cargo test`;靜態檢查:`cargo clippy --all-targets -- -D warnings`;交叉編譯 `.so`:
  `cargo ndk -t arm64-v8a -o ../android/app/src/main/jniLibs build --release`(需 `ANDROID_NDK_HOME`)。
- Android:`cd android && ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`。
- Python 工具:`python -m unittest discover -s tests`。
- 建置產物不進版控:`android/app/src/main/jniLibs/`、`core-rs/target/`、`android/local.properties`、模型檔。
- Android/CameraX 工作優先使用 Google 官方 [android/skills](https://github.com/android/skills)
  的 `camerax`、`testing-setup`、`android-cli`；測試用 fake 而非 mock 複雜 CameraX 介面。

## 交付流程(強制)

分支 → commit → push → PR。push 後 GitHub Actions 跑 CI(硬關卡)+ AI 助理審查。**逐則檢視/回覆
審查意見,經查證確認非問題後才 merge**(依事實,不看綠勾蓋章)。不可直接推 `main`。產出以繁體中文為主
(程式識別字/技術名詞保留英文)。

## Code Review Rules

審查只標高優先(P0/P1)、具體、附檔名。依序聚焦:

1. **正確性與邊界**:錯誤描述、漏報/誤報告警、崩潰(空值/邊界/資源釋放/執行緒/OOM/重入)。
2. **專案不變式(不得退步)**:
   - Actant 是角色槽位、**非身分**;不辨識人臉/身分/年齡(用「一人」而非「長者」)。
   - `risk.level != none` **需畫面內可見證據**;不臆測(抗誤報)。
   - **L1 只客觀描述看得到的**;風險/事件判斷屬 L2。
   - novelty 由管線計算、非模型回報;dataclass 與 `schemas/` JSON Schema 一致。
   - 影格只在裝置端、用完即刪;只有文字描述/事件可外傳(隱私/PDPA)。
   - 藥品/醫療需免責、不臆測。
3. **可測試性**:新邏輯可不靠硬體單元測試?有無測試?
4. **設計文件同步**:動到模組更新 `docs/design/<module>` SA/SD;里程碑更新 README/ROADMAP。
5. **簡潔、可讀、一致**。

## 指標紅線

每 24 小時誤報數 < 1 是首要指標——任何會提高對外誤報的變更都要特別謹慎。
