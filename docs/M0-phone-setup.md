# Pixel 10 上的 M0 — 服務設定

M0 測試框架([`bench/run_bench.py`](../bench/run_bench.py))是個單純的 HTTP
用戶端:它會向 `127.0.0.1:<port>` 上一個 OpenAI 相容的
`/v1/chat/completions` 發出 POST,而 `adb forward` 會把這個請求打通到跑在手機上的
server。測試框架並不在意*是什麼*在服務模型 — 只在意端點存在。本文件講的就是如何把那個
server 架起來,因為在 Android 上這並非一道指令就能完成。

## 誠實面對的問題

選定的正式環境執行期是 **透過 LiteRT-LM 跑的 Gemma E2B/E4B**(ADR-0004)。但
LiteRT-LM — 就跟 MediaPipe LLM Inference 與 AICore 一樣 — 設計上是要跑在 **Android
app 內**,而不是當成一個你透過 adb 連過去的無介面 HTTP server。在裝置端的 Android 上,
並沒有現成可用的 `litert-lm serve`。所以「內建的 LiteRT-LM」(正式環境路徑)與
「無介面 HTTP 測試」(M0 的快速路徑)並不是同一套執行期,假裝它們相同會讓 M0 的延遲數字
變成虛構。

分兩步解決。

## 步驟 1 — 在 Termux 裡以 llama.cpp 取得快速、無介面的數據(M0 建議做法)

`llama-server` 是 OpenAI 相容的,透過 mmproj 投影器支援 Gemma 多模態,能在 Android 的
Termux 上建置,而且完全無介面運行。這是在真實手機上取得真實延遲 / 熱表現 / 品質訊號最省成本的
方式。

```bash
# on the phone, in Termux
pkg install git cmake clang
git clone https://github.com/ggml-org/llama.cpp && cd llama.cpp
cmake -B build && cmake --build build -j        # add -DGGML_VULKAN=ON to try the GPU
# fetch a Gemma E2B/E4B GGUF + its mmproj into ~/models, then:
./build/bin/llama-server -m ~/models/gemma-e4b-Q4_K_M.gguf \
    --mmproj ~/models/mmproj-gemma-e4b.gguf --port 8082 --host 127.0.0.1
```

```bash
# on the host
adb forward tcp:8082 tcp:8082
python bench/run_bench.py --backend gemma-e4b --repeats 5
```

**必須記錄在 M0 報告裡的告誡:** 行動裝置 GPU 上的 Vulkan 表現參差不齊;如果建置回退到
CPU,延遲就是一個*偏悲觀*的界限,而功耗也不能代表正式環境執行期。這些數字請用於
E2B-vs-E4B 的**品質**決策以及網格實驗(兩者都與執行期無關),延遲則當作暫定值看待。

## 步驟 2 — 在凍結預算之前,以 LiteRT-LM 取得貼近正式環境的數據

關鍵影格預算取決於真實的正式環境延遲,所以必須在實際出貨的執行期上量測。有兩種方式,依投入
遞增排列:

- **最小承載 app** — 一個極小的 Android app,透過 LiteRT-LM 載入 `.litertlm`,並以內嵌的
  HTTP server 對外開放一個 localhost 的 `/v1/chat/completions`。如此一來,完全相同的
  `bench/run_bench.py` 就能透過 `adb forward` 原封不動地運作。這樣就能維持單一測試、兩套
  執行期。
- **app 內微型基準測試** — 在 app 內量測,並匯出一份與測試框架格式相同的 JSON。重用度較低,
  但不必寫內嵌 server。

建議採用承載 app:它本來就是真正的擷取 app 的種子。

## 目前卡在哪裡

1. ~~在手機上授權 adb~~ **已完成。** Pixel 10 已接受「允許 USB 偵錯」授權,`adb` 可連線。
2. **建立樣本集** — 見 [`bench/README.md`](../bench/README.md)。沒有那些模稜兩可的
   `unclear` 影格,任何數字都沒有意義。
3. **決定步驟 1 的模型檔案** — 要拉哪一種 E2B/E4B GGUF 量化版本。

adb 一旦授權完成,`adb shell getprop ro.product.model ro.build.version.release`
與 `ro.soc.model` 就能確認確切的裝置與 SoC,由此決定是否值得嘗試 Vulkan 建置。
