---
name: dev-standards
description: claustrum 專案的開發規範 — PR + AI 審查品質關卡、每個模組的 SA/SD 設計文件、最大化可測試性、以 Claude 設計 App UI、里程碑同步更新文件、產出以繁體中文為主。Development standards for claustrum. Use whenever developing, reviewing, shipping, or documenting any claustrum module or the app, and before opening a PR or calling a checkpoint done.
---

# claustrum 開發規範

這是 `claustrum` 開發工作的長期規則。每個模組、每次變更都要遵守。之所以存在,是因為
這是一套與安全相關(safety-adjacent)的裝置端感知系統:品質要靠流程與測試來保證,而
非靠人工自律。

專案脈絡:目前以**手機為先、單節點**(phone-first, single-node)在 Pixel 10 上開發,
搭配隨附的 Gemma E2B/E4B(參見 `docs/adr/0004-phone-first-single-node.md`)。Jetson
雙節點設計是延後的目標,而非目前的建置。

## 1. 一律走 PR,並以 AI code review 把關

- **絕不直接 push 到 `main`。** 開分支 → commit → push 分支 → 用 `gh pr create` 開 PR。
- 每個 PR 都會觸發 `.github/workflows/ai-review.yml` 執行 AI code review。合併前先讓
  CI 與審查跑完。
- **AI 審查是參考,不是最終裁定。** 是否 merge 要依**經查證的事實**判斷,而非審查的結論:
  - 檢視並**回覆每一則 AI 審查意見** — 要嘛修正,要嘛(在回覆中)說明經查證後為何不成立。
    不留任何一則意見未處理。
  - 當你已查證某則意見有誤時,可以無視它照常 merge(並說明原因);當查證發現審查漏掉的
    真實問題時,即使 CI 全綠也可以先擋著不 merge。
  - Merge 是有證據支撐的判斷(測試、讀程式、實際執行),絕不是對 AI 或綠勾勾的橡皮圖章。
- **本機審查(主要路徑):`scripts/ai-review.sh [base]`** —— 用 Antigravity CLI(`agy`,
  訂閱制)對 diff 做唯讀審查。**不需要任何 secret。** 這是預設的 AI 審查方式:每個 PR
  merge 前跑一次,逐則回覆,再依查證事實決定 merge。
- 雲端路徑(可選):`.github/workflows/ai-review.yml` 需要 `GEMINI_API_KEY` repo secret
  才會自動審查。只有使用者能新增 secret — 不要代勞。未設時該 job 會乾淨略過,由本機
  `agy` 審查即可涵蓋。
- PR 保持聚焦、易審;寧可拆成多個小 PR,也不要一個龐雜的大 PR,好讓審查與 CI 給出銳利的訊號。

## 2. 每個模組都要有 SA/SD 設計文件

- 每個模組或區塊都要保有**完整的 SA(系統分析,System Analysis)與 SD(系統設計,
  System Design)** 文件,置於 `docs/design/<module>/SA.md` 與 `SD.md`。
- 從 `docs/design/_template/` 起手。在修改程式的同一個 PR 內同步更新它們 — 過時的設計
  文件視為 bug。
- SA = 做什麼/為什麼(範圍、參與者、功能與非功能需求、領域模型、驗收標準、對 ADR/roadmap
  的追溯)。
- SD = 怎麼做(元件與職責、介面/合約、資料結構、以 Mermaid 圖呈現的關鍵流程、錯誤處理、
  相依性,以及必備的**測試策略**一節)。

## 3. 為可測試性而設計

- 模組要設計得**盡可能可測試**:相依性可注入(例如 VLM 後端是一個介面,而非寫死的 HTTP
  呼叫)、副作用收攏在邊界、純邏輯與 I/O 可分離。
- 每個模組交付時都要附帶由 CI 執行的測試。沒有測試的模組不算完成。SD 文件的測試策略一節
  要說明它如何被測試、用什麼假件(fake)。

## 4. App UI 以 Claude 設計,達到接近產品化的品質

- 任何 App 畫面或視覺介面,都要**以 Claude 的設計能力做到接近產品化的品質** — 載入
  `artifact-design` skill(必要時搭配 Figma skills / `dataviz`),建立真正的視覺系統:
  主題、間距、字體、元件狀態、以及空/錯誤/載入狀態。
- 不是預設元件,也不是拋棄式的佔位 UI。

## 5. 每個 checkpoint/里程碑都要更新文件

- 完成一個 checkpoint 或里程碑,**在文件反映之前都不算完成。** 在同一個 PR 內更新 README、
  `docs/ROADMAP.md` 的狀態、若設計有變動則更新 `docs/ARCHITECTURE.md`,以及相關的 SA/SD 文件。
- 文件絕不落後於專案的實際狀態。

## 6. 產出以繁體中文為主

- 文件與使用者面向的產出**以繁體中文(台灣用語)為主**。
- **保留英文**:程式碼識別字與領域術語(Actant、Kineme、Ethogram、L0–L4、欄位名)、
  技術與產品名(LiteRT-LM、Gemma、Jetson、Pixel 10…)、ADR 編號,以及程式碼/schema 本身。
  程式碼註解/docstring 目前先維持英文,除非使用者另有要求。

## 完成的定義(檢查清單)

在開 PR 或宣告 checkpoint 完成之前:

- [ ] 程式有測試;本地 CI 通過(`python -m unittest discover -s tests`)
- [ ] 所觸及模組的 SA/SD 文件已建立/更新
- [ ] 若屬里程碑或設計變動,已更新 README / ROADMAP / ARCHITECTURE
- [ ] 變更在分支上並已開 PR — 絕不直接 push `main`
- [ ] 若有 App UI,已以 Claude 設計至接近產品化的品質
- [ ] 文件產出以繁體中文為主
