# core-rs(Rust 感知核心)— 系統設計(SD)

**狀態:** active · **最後更新:** 2026-08-06 · **負責人:** claustrum
**實作:** [`SA.md`](SA.md)

## 1. 概觀

純 Rust lib crate(`claustrum-core`)。`crate-type = ["rlib"]` 供 Host `cargo test`;之後加
`cdylib` 產 `.so`(cargo-ndk)供 Android JNI。P0 已落地 **L0 變化閘控**;pipeline 與 events
後續加入。

## 2. 元件與職責

| 模組 | 職責 | 狀態 |
|---|---|---|
| `gate` | L0 變化閘控:`Signature`(8×8 aHash)、`frame_signature`、`distance`、`ChangeGate` | ✅ P0 |
| `vlm` | L1 邊界 `Captioner` + 佔位後端(誠實診斷);真後端改走 Kotlin 端 Google AI Edge / LiteRT(見 [vlm 設計](../vlm/SD.md)、ADR-0009) | ✅ P2 seam |
| `events`(規劃) | L2/L3 detector 狀態機(見 events 設計) | P3 |
| `ffi` | JNI 入口(`jni` crate);android target only:`nativeHello`/`frameSignature`/`describe` | ✅ P0/P2 |

## 3. 介面與合約

- `ChangeGate::new(threshold: u32)` / `admit(&mut self, luma: &[u8], w, h) -> bool` —— 有狀態閘控;
  只在放行時更新 prev(慢速漂移對照上次「已處理」幀)。
- `frame_signature(luma, w, h) -> Signature` / `distance(a, b) -> u32` —— 純函式。
- **JNI**(`src/ffi.rs`,android target only):`com.claustrum.core.NativeCore` 對應
  `nativeHello(): String`、`frameSignature(luma: ByteArray, w, h): Long`(回 aHash;Kotlin 端
  以 `Long.bitCount(prev xor cur)` 做閘控)。傳 luma、回結果;**影格不過橋**。
- **影格不留**:只從 luma 算 signature。

`.so` 建置(cargo-ndk):
```bash
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/27.1.12297006
cargo ndk -t arm64-v8a -o ../android/app/src/main/jniLibs build --release
```

## 4. 資料結構

`Signature(u64)` —— 8×8 average-hash 位元。後續 `Observation` / `Event` 對齊 `schemas/`。

## 5. 關鍵流程(L0 閘控)

```
luma(w×h)→ 降採樣 8×8 區塊平均 → 與整體均值比較設 aHash 位元 → Signature
Signature vs 上次已放行 Signature 的 Hamming 距離 ≥ threshold ? 放行(叫 L1) : 略過
```

閾值建議起始 6–10 bits;`ChangeGate` 對照「上次已放行」幀,讓慢速漂移能累積。

## 6. 錯誤處理與穩健性

- luma 短於 `w*h` → 回 `Signature(0)`,不 panic(已測)。
- 對感測雜訊/微光變化穩健(aHash 降採樣 + 閾值;已測 noise-only 被略過)。

## 7. 相依性

- P0:無外部 crate。
- 後續:`jni`(已用);L1 不再是 core-rs 相依(改走 Kotlin 端 LiteRT,ADR-0009)。

## 8. 測試策略(必備)

- `gate` 以合成 luma `cargo test`(Host,無硬體):identical/noise → 略過、真實變化 → 放行、
  首幀放行、畸形輸入安全。**P0:6 tests 綠。**
- CI(`.github/workflows/ci.yml`)執行 `cargo test --manifest-path core-rs/Cargo.toml`。
- JNI `ffi` 為薄包裝(僅 `convert_byte_array` → 已測的 `gate::frame_signature`,含長度/零維度
  防呆),以裝置端**整合測試**(Android app 載入 `.so` 呼叫)覆蓋;可測的純邏輯已在 host 測。
- 後續 events 同樣以合成序列測試。L1 觸發邏輯在 Kotlin 端以 `Captioner` 介面 + `FakeCaptioner`
  做 host 單元測試(不綁硬體);只有 `LiteRtCaptioner` 真呼叫需裝置整合測試(見 [android SD](../android/SD.md))。

## 追溯

滿足 [`SA.md`](SA.md) FR-1…FR-4、NFR-1…NFR-5。相關:[ADR-0007](../../adr/0007-rust-first-redesign.md)、[events 設計](../events/SD.md)。
