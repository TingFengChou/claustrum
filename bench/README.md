# M0 — 後端基準測試

**在動手寫任何其他東西之前,先跑這個。** L1 影像描述階段的單次呼叫 p95 延遲,決定了 L0 的關鍵影格預算、是否需要雙模型分層,以及即時警示的上限。在這些數字出現之前,下游的每一個架構決策都只是猜測。

**手機優先(ADR-0004)。** 模型跑在 Pixel 10 上;這套測試工具跑在你的筆電上,透過 `adb forward` 連到手機。它會透過 adb(`phone_monitor.py`)取樣手機的溫度、功耗與記憶體狀態。`--monitor tegra` 這條路徑保留給日後的 Jetson 使用。

```bash
adb devices                        # confirm the Pixel 10 is attached
adb forward tcp:8081 tcp:8081      # once per served port
```

## 測量哪些項目

| 指標 | 原因 |
|---|---|
| 延遲 p50 / p95 | 決定關鍵影格預算 |
| 首個 token 時間 | 若未來想串流部分影像描述,這項才有意義 |
| 冷啟動 | 會重啟的服務要一再付出這個代價 |
| 記憶體峰值 | 手機記憶體由模型、相機管線與作業系統共用 |
| 溫度峰值 + **溫度漂移** | 一次執行過程中的漂移可預測長時間執行是否會熱節流 |
| 平均功耗 | 持續推論會耗盡電池;決定可持續運作的上限 |
| JSON 解析成功率 | 低於約 98 % 是提示詞的問題,不是後端的問題 |

## 建立樣本集

在 `bench/frames/` 放入 20–40 張影格,命名方式須能依時間順序排序(`0001.jpg`、`0002.jpg`、……)。涵蓋真正重要的情境:

- 日常活動 — 有人走過、坐著、進食
- **刻意躺下 / 坐下** — 必須與跌倒區分開來的偽陽性案例
- 一次演練的跌倒,拍下之前 / 過程中 / 之後
- 空房間
- 光線不佳,以及接近全黑
- 寵物在做某件事
- 一張模稜兩可、正確答案就是「不清楚」的影格

最後一類正是大家會略過的。少了它,就無從得知模型是否懂得拒答。

## 執行方式

```bash
pip install -r bench/requirements.txt
cp bench/backends.example.yaml bench/backends.yaml   # edit ports / model names

# serve the model on the phone, forward the port, then:
python bench/run_bench.py --backend gemma-e2b --repeats 5

# the E2B-vs-E4B question -- is the smaller model good enough?
python bench/run_bench.py --backend gemma-e4b --repeats 5

# the grid experiment -- potentially a 4x reduction in VLM calls
python bench/run_bench.py --backend gemma-e4b --grid 1x1
python bench/run_bench.py --backend gemma-e4b --grid 2x2
```

## 網格實驗

Gemma 4 對可變長寬比的視覺處理能力,意味著把數張影格拼成一張影像後,仍可能被讀成一段時間序列。若一次 `2x2` 呼叫的成本低於兩次 `1x1` 呼叫,實際可用的 VLM 呼叫預算最多可延展到四倍,整個功耗與延遲的範圍都會隨之改變。

兩種都跑一次並比較。這是 M0 中單一槓桿最大的實驗。

## 執行之後

報告會產生在 `eval/reports/`。接著,手動進行:

1. 打開 JSON,替每個保留下來的樣本評分:`manual_score` 針對影像描述的實用性給 1–5 分,`hallucinated` 給 true/false。
2. 幻覺率是決定專案是否可行的那個數字。任何高於約 5 % 的數值,都代表安全警示路徑無法信任,必須在 M1 進行之前改進提示詞或模型。
3. 將選定的後端與關鍵影格預算記錄成一份 ADR。
