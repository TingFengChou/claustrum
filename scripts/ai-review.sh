#!/usr/bin/env bash
#
# 本機 AI code review,使用 Antigravity CLI(agy,訂閱制)。
# 這是專案 AI 審查關卡的「本機路徑」——不需要 GEMINI_API_KEY;雲端的
# .github/workflows/ai-review.yml 是可選的替代路徑。
#
# 用法:
#   scripts/ai-review.sh [base-ref]     # 預設 base = main
#
# 依規範:AI review 為參考,merge 由查證事實決定,且每則意見都要回覆。
set -euo pipefail

BASE="${1:-main}"
DIFF_FILE="$(mktemp -t claustrum-review.XXXXXX.diff)"
trap 'rm -f "$DIFF_FILE"' EXIT

git diff "${BASE}...HEAD" > "$DIFF_FILE"
if [ ! -s "$DIFF_FILE" ]; then
  echo "沒有相對於 ${BASE} 的變更,略過審查。"
  exit 0
fi

RUBRIC="你正在審查 claustrum 專案的一個 PR。請讀取這個 git diff 檔:${DIFF_FILE},並只列出具體問題(附檔名與簡短引用),依序聚焦:
1. 正確性與邊界情況(尤其可能造成錯誤描述、漏報/誤報安全警示、或崩潰的地方)。
2. 專案不變式(勿讓其退步):Actant 是角色槽位、絕非身分;risk.level != none 需有畫面內可見證據;dataclass 與 JSON Schema 一致;novelty 由管線計算而非模型回報;藥品/醫療相關功能必須有免責且不臆測。
3. 可測試性:新邏輯是否可不靠硬體單元測試?有無測試?
4. 設計文件是否同步(動到模組是否更新其 docs/design/<module> 的 SA/SD;里程碑是否更新 README/ROADMAP)。
5. 簡潔、可讀、與周邊程式一致。
請精簡具體;乾淨的檔案不必提。最後給一行結論:LGTM / minor comments / needs work。請以繁體中文回覆,且不要修改任何檔案。"

echo "== 使用 agy(Antigravity)對 ${BASE}...HEAD 做本機審查 =="
agy --mode plan --output-format text --print-timeout 240s -p "$RUBRIC"
