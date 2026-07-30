# 開發流程

`claustrum` 的工作方式。這些規則同時編寫在 `dev-standards` 這個 agent skill
(`.claude/skills/dev-standards/`) 中,因此會被自動套用;本文件是給人閱讀的版本。

## 透過 PR 出貨,以審查作為關卡

- 不可直接推送到 `main`。開分支 → commit → push → 開一個 PR。
- 每個 PR 會跑兩項檢查:
  - **`ci.yml`** — 測試 + schema/identity 守衛。這是**硬性關卡**。
  - **`ai-code-review.yml`** — 一則建議性質的 Gemini 審查留言。詳見下文。
- **合併是基於事實的決定,不是蓋橡皮圖章。** 每一則 AI 審查留言都要被檢視並**回覆** —
  要嘛修掉,要嘛在查證後說明它為何不成立。只有在確認某則 AI 留言確實有誤時才能無視它
  逕行合併(並說明原因);當查證發現審查漏掉的真正問題時,即使 PR 已是綠燈也要暫緩。
  決定建立在證據上 — 測試、閱讀程式碼、實際執行 — 而不是單憑結論或那個勾勾。

### 啟用 AI 審查

AI 審查需要一個 `GEMINI_API_KEY` 儲存庫密鑰(Settings → Secrets and
variables → Actions)。只有儲存庫管理員能新增它。在設定之前,審查工作流程會乾淨地跳過。
可選擇設定一個 `GEMINI_MODEL` 儲存庫變數來更換模型。此審查僅供參考 — 它從不阻擋合併;
擋關的是測試。

## 每個模組的設計文件(SA/SD)

每個模組都在 [`docs/design/<module>/`](design/) 下保有一份 System Analysis 與
System Design 文件。從 [`docs/design/_template/`](design/_template/) 開始。在與程式碼
相同的 PR 中一併更新它們 — 見 [`docs/design/README.md`](design/README.md)。`core`
是完整的示範範例。

## 可測試性

模組的建構方式讓它無需硬體即可測試:相依項置於介面之後、副作用集中在邊界、測試隨模組一起
出貨並由 CI 執行。沒有測試的模組就不算完成。

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
