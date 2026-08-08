# core-rs(Rust 感知核心)— 系統設計(SD)

**狀態:** active · **最後更新:** 2026-08-08 · **負責人:** claustrum
**實作:** [`SA.md`](SA.md)

## 1. 概觀

純 Rust lib crate(`claustrum-core`)。`crate-type = ["rlib", "cdylib"]`：前者供 Host
`cargo test`，後者由 cargo-ndk 產 `.so` 供 Android JNI。P0/P1 已落地 L0 aHash/JNI；P3
已有 events foundation。

## 2. 元件與職責

| 模組 | 職責 | 狀態 |
|---|---|---|
| `gate` | L0 變化閘控:`Signature`(8×8 aHash)、`frame_signature`、`distance`、`ChangeGate` | ✅ P0 |
| `vlm` | ADR-0008 的 legacy Rust 佔位；現行 Android 不呼叫，待小 PR 與 `NativeCore.describe` 一併移除 | ⚠️ legacy |
| `events` | L2 Fall/ZoneExit/Violence 狀態機 + Event serde(見 events 設計) | ✅ P3 foundation |
| `event_bridge` | host-testable engine registry；opaque positive handle → 隔離的 `EventEngine` session | ✅ P3 bridge |
| `ffi` | JNI 入口；active:`frameSignature` + L2 create/process/destroy；legacy:`describe` | ✅ P0/P1/P3 · ⚠️ legacy seam |

## 3. 介面與合約

- `ChangeGate::new(threshold: u32)` / `admit(&mut self, luma: &[u8], w, h) -> bool` —— 有狀態閘控;
  只在放行時更新 prev(慢速漂移對照上次「已處理」幀)。
- `frame_signature(luma, w, h) -> Signature` / `distance(a, b) -> u32` —— 純函式。
- **JNI**(`src/ffi.rs`,android target only):`com.claustrum.core.NativeCore` 對應
  `nativeHello(): String`、`frameSignature(luma: ByteArray, w, h): Long`(回 aHash;Kotlin 端
  以 `Long.bitCount(prev xor cur)` 做閘控)。luma 會由 JNI 複製到 Rust，回傳 hash 後釋放；
  不保存影格，也不把完整彩色 Bitmap 交給 Rust L0。
- **L2 JNI:**`createEventEngine(sourceId)` 取得非指標 handle；`processEventObservation(...)`
  傳送匿名結構化特徵並回傳 `Array<String>`；`destroyEventEngine(handle)` 釋放 session。
  每個字串都是一個 `event.schema.json` Event；empty array 代表未跨越狀態。

`.so` 建置(cargo-ndk):
```bash
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/27.1.12297006
cargo ndk -t arm64-v8a -o ../android/app/src/main/jniLibs build --release
```

## 4. 資料結構

`Signature(u64)` —— 8×8 average-hash 位元。`events::{Observation, Event}` 的 transport 以
serde 對齊 `schemas/event.schema.json`；匿名角色只用 `person_<slot>`。

## 5. 關鍵流程(L0 閘控)

```
luma(w×h)→ 降採樣 8×8 區塊平均 → 與整體均值比較設 aHash 位元 → Signature
Signature → Kotlin ChangeGate → 與上次已放行 Signature 的 Hamming 距離 ≥ threshold ? 放行(叫 L1) : 略過
```

閾值建議起始 6–10 bits。Rust `ChangeGate` 保留純邏輯 contract/host test；Android 現行狀態由
Kotlin `ChangeGate` 保存，兩者都對照「上次已放行」幀，讓慢速漂移能累積。

## 6. 錯誤處理與穩健性

- luma 短於 `w*h` → 回 `Signature(0)`,不 panic(已測)。
- 對感測雜訊/微光變化穩健(aHash 降採樣 + 閾值;已測 noise-only 被略過)。

## 7. 相依性

- Host/Rust core:`serde` + `serde_json`(Event transport)。
- Android target:`jni`；L1 不是 core-rs 相依(改走 Kotlin 端 LiteRT,ADR-0009)。

## 8. 測試策略(必備)

- `gate` 以合成 luma `cargo test`(Host,無硬體):identical/noise → 略過、真實變化 → 放行、
  首幀放行、畸形輸入安全。**P0:6 tests 綠。**
- CI(`.github/workflows/ci.yml`)執行 `cargo test --manifest-path core-rs/Cargo.toml`。
- JNI `ffi` 為薄包裝(僅 `convert_byte_array` → 已測的 `gate::frame_signature`,含長度/零維度
  防呆),以裝置端**整合測試**(Android app 載入 `.so` 呼叫)覆蓋;可測的純邏輯已在 host 測。
- events 已以合成序列覆蓋正常坐下、fall 快/慢確認、恢復、zone 去重、violence pair 隔離、
  VLM 不升級與 serde shape。L1 觸發邏輯在 Kotlin 端以 `Captioner` 介面 + `FakeCaptioner`
  做 host 單元測試(不綁硬體);只有 `LiteRtCaptioner` 真呼叫需裝置整合測試(見 [android SD](../android/SD.md))。
- `event_bridge` 在 host 覆蓋 handle lifecycle/wrap 與 session 隔離；Android target 另以 cargo-ndk
  編譯並檢查三個 L2 JNI symbols。

## 追溯

滿足 [`SA.md`](SA.md) FR-1…FR-4、NFR-1…NFR-5。相關:[ADR-0007](../../adr/0007-rust-first-redesign.md)、[events 設計](../events/SD.md)。
