# android(Kotlin 裝置外殼)— 系統分析(SA)

**狀態:** active · **最後更新:** 2026-08-06 · **負責人:** claustrum
**設計:** [`SD.md`](SD.md)

## 1. 目的

裝置端外殼(app body):載入 Rust 感知核心(`libclaustrum_core.so`)、之後接 CameraX
影像流,並呈現 L1 即時字幕 / L2 告警 UI。P0 只求最小可驗證:**證明 Rust 核心能在
Pixel 10(Tensor G5 / Android 17)上經 JNI 回話並跑 L0 閘控**。

## 2. 範圍

- **在範圍內(P0):** 單一 `Activity`、載入 `.so`、呼叫 `NativeCore.nativeHello()`
  與 `frameSignature()`、以合成幀做裝置端自我測試並顯示結果。
- **後續:** CameraX 取幀(luma)→ JNI L0 閘控 →(P2)llama.cpp L1 字幕 →(P3)L2 事件告警;
  Jetpack Compose UI(取代 P0 的程式化 View)。
- **不在範圍內:** 影格離開裝置、雲端上傳、人物身分特徵。

## 3. 需求

| 編號 | 需求 |
|---|---|
| FR-1 | app 啟動時載入 `libclaustrum_core.so`,不得因缺符號/ABI 崩潰 |
| FR-2 | 呼叫 `nativeHello()` 顯示 Rust 核心版本橫幅(裝置端「回話」證明) |
| FR-3 | 以合成 luma 幀呼叫 `frameSignature()`,驗證 identical→距離 0、變化→距離大 |
| FR-4 | 僅打包必要 ABI(P0 = arm64-v8a),APK 內含 `.so` |
| NFR-1 | 版本矩陣可重現:Gradle 9.3.1 · AGP 8.12.0 · Kotlin 2.1.20 · compileSdk 36 · minSdk 26 |
| NFR-2 | `.so` 由 `core-rs` 經 cargo-ndk 產生,不進版控(建置產物) |
| NFR-3 | 隱私:影格/像素不過 JNI 橋、不落地、不上傳 |

## 4. 相依與假設

- Rust 核心與 JNI 符號來自 [`core-rs`](../core-rs/SA.md)(`ffi.rs`)。
- NDK 27.1.12297006;SDK Platform 36;裝置 arm64-v8a。
- 假設開發機已安裝上述 SDK/NDK(`local.properties` 指向,不進版控)。

## 5. 驗收(P0 已達成)

裝置端實測(Pixel 10):`nativeHello()` 回 `claustrum-core 0.1.0 — Rust core online`;
`frameSignature` flat=`0xffffffffffffffff`、split=`0xffffffff00000000`、
distance(same)=0、distance(change)=32 → **PASS**。無 `UnsatisfiedLinkError`、無崩潰。

## 追溯

相關:[ADR-0007](../../adr/0007-rust-first-redesign.md)、[core-rs 設計](../core-rs/SD.md)。
