# 開發流程

`claustrum` 的工作方式。這些規則同時編寫在 `dev-standards` 這個 agent skill
(`.claude/skills/dev-standards/`) 中,因此會被自動套用;本文件是給人閱讀的版本。

## 透過 PR 出貨,以審查作為關卡

**強制流程(每個功能 / 每個 phase):**

1. 不可直接推送到 `main`。開分支 → commit → **push**。
2. **開 PR**;push 後 GitHub Actions 自動跑:
   - **`ci.yml`** — 測試 + schema/identity 守衛。**硬性關卡**,紅燈不得合併。
   - **`ai-code-review.yml`** — GitHub Action 上的 **AI 助理審查**(Gemini;可擴充 Codex/OpenAI)。在 PR 留下審查意見。
3. **人 / Claude 檢視 AI 的審查結果**,並在 PR 上**逐則回覆**:要嘛修掉,要嘛查證後說明為何不成立。
4. **只有在維護者(人 / Claude Code)看過 AI 審查意見、逐則查證確認不是真正的問題之後,才能 merge。**
   合併是基於事實的決定,不是蓋橡皮圖章、也不是看到綠勾就按。查證發現審查漏掉的真問題時,即使綠燈
   也要暫緩修正。決定建立在證據上 — 測試、閱讀程式碼、實際在 Pixel 10 執行。
5. merge(squash)後刪分支。

> **雲端 AI 審查需要 `GEMINI_API_KEY`**(repo secret,僅 owner 能加:Settings → Secrets and
> variables → Actions)。未設定前該 job 乾淨略過,改由本機 `agy` 涵蓋審查關卡(見下)。設定後
> 每個 PR 皆自動由雲端 AI 助理審查並留言。

### AI 審查:本機(主要)與雲端(可選)

- **本機(主要):`scripts/ai-review.sh [base]`** —— 用 Antigravity CLI(`agy`,訂閱制)
  對 diff 做唯讀審查,**不需要任何 secret**。每個 PR merge 前跑一次,逐則回覆。
- **雲端(可選):** `.github/workflows/ai-review.yml` 需要 `GEMINI_API_KEY` 儲存庫密鑰
  (Settings → Secrets and variables → Actions;只有管理員能新增)。未設時該 job 乾淨略過,
  由本機 `agy` 涵蓋。可選 `GEMINI_MODEL` 變數更換模型。

此審查僅供參考 —— 它從不阻擋合併;擋關的是測試,merge 由查證事實決定。

## 每個模組的設計文件(SA/SD)

每個模組都在 [`docs/design/<module>/`](design/) 下保有一份 System Analysis 與
System Design 文件。從 [`docs/design/_template/`](design/_template/) 開始。在與程式碼
相同的 PR 中一併更新它們 — 見 [`docs/design/README.md`](design/README.md)。`core`
是完整的示範範例。

## 可測試性與測試紀律

模組的建構方式讓它無需硬體即可測試:相依項置於介面之後、副作用集中在邊界、測試隨模組一起
出貨並由 CI 執行。沒有測試的模組就不算完成。

**邊開發邊補測試(每個功能/phase):**

- **單元測試(純邏輯,由 CI 跑):** Python `python -m unittest`、Rust `cargo test`、
  Android JVM `./gradlew :app:testDebugUnitTest`。CI 三者皆自動執行(`.github/workflows/ci.yml`)。
- **UI / 使用者旅程(journey)自動化一律用 [Maestro](https://maestro.mobile.dev):** flow 放
  `.maestro/*.yaml`,涵蓋關鍵旅程(模型下載/切換、進入即時偵測、告警處置)。
  執行:`maestro test .maestro/`(需連接裝置/模擬器)。**flow 不得驗證任何真實機密(如 HF 權杖值)。**
- 裝置專屬的整合(JNI/相機/LiteRT 推論)以裝置實測或 `androidTest` 覆蓋。

## App UI

任何 app UI 都以 Claude 的設計能力打造到接近正式產品的品質(載入 `artifact-design`
skill;相關時搭配 Figma / `dataviz`),而不是使用預設或佔位元件。品牌素材放在
[`assets/`](../assets/)。

## 文件反映現實

完成一個檢查點或里程碑時,要一併更新 README、[`ROADMAP.md`](ROADMAP.md) 的狀態、
若設計有變動則更新 [`ARCHITECTURE.md`](ARCHITECTURE.md),以及受影響的 SA/SD 文件 —
都在同一個 PR 中。文件不可落後於程式碼。

## 完成的定義

- [ ] 測試已撰寫;`python -m unittest discover -s tests` 為綠燈
- [ ] 已為變動到的模組更新 SA/SD
- [ ] 若涉及里程碑或設計變動,已更新 README / ROADMAP / ARCHITECTURE
- [ ] 位於已開 PR 的分支上;CI 為綠燈;審查已處理
- [ ] 任何 app UI 都已設計到接近正式產品的品質
