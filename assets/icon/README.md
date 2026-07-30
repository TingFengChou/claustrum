# App 圖示

`claustrum.svg` 是主要的 App 圖示(1024×1024,自我完備)。

**概念。** 三條感官模態的線束 — 視覺(琥珀色)、聽覺(玫瑰色)、語言(青綠色) — 匯聚而入,並透過那片薄薄彎曲的 *claustrum 屏狀核薄層*,綁定成單一發光的節點與一道統一的輸出流。它以一個標記呈現專案的核心論點:分立的感官融合成一道連貫、被觀測的資訊流。深色的底色讀起來像是環境中無所不在、始終開啟的感測。

**調色盤。**
- background `#1c1436 → #070610`
- vision `#ffb054` · audio `#ff5c8a` · language `#43e0d0`
- fusion / unified stream `#ffffff`

## 產出 App 素材

SVG 是唯一的真實來源。要製作實際的 Pixel App,請從它匯出:

- **Android 自適應圖示** — 前景 = 透明底上的圖案主體,背景 = 漸層 `#1c1436→#070610`。將圖案主體保持在中央安全區(約 66 %)之內;目前的標記已經位於其中。匯出 `mipmap` 各密度(mdpi→xxxhdpi),或直接以向量形式提供 `ic_launcher_foreground`。
- **傳統 / 通知圖示** — 標記的單色白底透明變體,用於狀態列 / 通知圖示(Android 會為其上色)。
- **商店 / favicon** — 壓平成 PNG,尺寸為 512 與 1024。

使用任何 SVG 工具進行點陣化,例如 `rsvg-convert -w 1024 -h 1024 claustrum.svg -o claustrum-1024.png` 或 `resvg`。請勿手動編輯匯出的 PNG — 改動 SVG 後重新匯出。
