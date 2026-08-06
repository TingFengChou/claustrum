# android(Kotlin 裝置外殼)— 系統設計(SD)

**狀態:** active · **最後更新:** 2026-08-06 · **負責人:** claustrum
**分析:** [`SA.md`](SA.md)

## 1. 概觀

獨立 Gradle 專案 `android/`(不再依賴 React Native,見 ADR-0007)。P0 為最小 app:
`Activity` 載入 Rust `.so`、經 JNI 呼叫核心、顯示結果。P1 接上 CameraX:每幀 luma →
Rust `frameSignature` → Kotlin `ChangeGate` 閘控 → 即時顯示放行/略過與省算力比。

## 2. 版本矩陣(機器已驗證的 known-good 組合)

| 項目 | 版本 | 備註 |
|---|---|---|
| Gradle wrapper | 9.3.1 | 沿用 RN app 的 wrapper |
| AGP | 8.12.0 | 8.12 用標準雙插件;AGP 9.x 改內建 Kotlin 會衝突 |
| Kotlin | 2.1.20 | `org.jetbrains.kotlin.android` |
| compileSdk / targetSdk | 36 | |
| minSdk | 26 | |
| NDK(產 `.so`) | 27.1.12297006 | cargo-ndk 使用 |
| ABI(P0) | arm64-v8a | `abiFilters` 限定 |
| CameraX | 1.4.1 | camera-core/-camera2/-lifecycle/-view |
| AndroidX | activity-ktx 1.9.3 · core-ktx 1.13.1 | `ComponentActivity` = LifecycleOwner |

> AGP 9.2.1 需 Gradle 9.4.1、9.3.1 需 9.5.0,且 AGP 9 內建 Kotlin 與
> `kotlin.android` 插件衝突(`Cannot add extension 'kotlin'`)。故釘在 AGP 8.12.0。

## 3. 專案結構

```
android/
  settings.gradle.kts        # google()/mavenCentral();include(":app")
  build.gradle.kts           # 釘 AGP 8.12.0 / Kotlin 2.1.20(apply false)
  gradle.properties          # useAndroidX、jvmargs
  local.properties           # sdk.dir(機器本地,不進版控)
  gradle/wrapper/…           # 沿用 RN app 的 wrapper(9.3.1)
  app/
    build.gradle.kts         # com.android.application + kotlin.android;jniLibs
    src/main/AndroidManifest.xml
    src/main/java/com/claustrum/MainActivity.kt       # P1:CameraX 預覽 + luma 分析 + 閘控 UI
    src/main/java/com/claustrum/core/NativeCore.kt    # JNI 綁定(external fun)
    src/main/java/com/claustrum/core/ChangeGate.kt    # L0 閘控(純 Kotlin,持有上次放行 hash)
    src/main/jniLibs/arm64-v8a/libclaustrum_core.so   # cargo-ndk 產物(不進版控)
    src/main/res/values/strings.xml
    src/test/java/com/claustrum/core/ChangeGateTest.kt # JVM 單元測試(7)
```

## 3.1 P1 資料流(CameraX × L0)

```
CameraX ImageAnalysis(YUV_420_888, KEEP_ONLY_LATEST, 背景 executor)
  → ImageProxy.planes[0]（Y/luma，依 rowStride 緊密複製成 w*h ByteArray）
  → NativeCore.frameSignature(luma, w, h)  ── Rust 算 aHash（像素不回傳）
  → ChangeGate.admit(sig)                  ── Hamming(vs 上次放行) ≥ 門檻 ? 放行 : 略過
  → 若放行:NativeCore.describe(luma, w, h) ── L1(Rust vlm；只在放行時喚醒)→ lastCaption
  → runOnUiThread 更新覆蓋層（sig、距離、決策、放行/總數、省算力%、L1 最新描述）
  → ImageProxy.close()                     ── luma 立即釋放，不落地
```

L1 **只在放行幀**被呼叫——這即「只在場景變化時喚醒 VLM」的省算力點。目前 L1 後端為
`core-rs` 的佔位 `Captioner`(誠實診斷),真 llama.cpp 後端見 [vlm 設計](../vlm/SD.md)、ADR-0008。

## 4. 關鍵介面

- `object NativeCore`:`init { System.loadLibrary("claustrum_core") }`;
  `external fun nativeHello(): String`、`external fun frameSignature(luma: ByteArray, w: Int, h: Int): Long`。
  符號對應 `core-rs/src/ffi.rs` 的 `Java_com_claustrum_core_NativeCore_*`。
- `MainActivity`:純程式化 View(P0 不引入 Compose 以降低版本風險);組合橫幅 +
  L0 自我測試報告,以 `Long.bitCount(prev xor cur)` 算 Hamming 距離。

## 5. 建置流程

```bash
# 1) 產 .so 到 jniLibs(在 core-rs/)
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/27.1.12297006
cargo ndk -t arm64-v8a -o ../android/app/src/main/jniLibs build --release
# 2) 建 APK(在 android/)
./gradlew :app:assembleDebug
# 3) 安裝(若舊簽章衝突需先 adb uninstall com.claustrum)
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 6. 測試策略(必備)

- **JVM 單元測試(P1 ✅):** `ChangeGateTest`(7)以純 Kotlin 驗首幀放行、相同略過、
  大變化放行、次門檻漂移略過、距離=bitCount、prev 只在放行時前進、reset。
  `./gradlew :app:testDebugUnitTest`(無硬體)。
- **裝置端整合測試(P0/P1 手動已過):** 安裝 + 啟動 + 螢幕驗證 `nativeHello` 橫幅
  與 CameraX 即時閘控統計(見 SA §5)。
- L0 閘控純邏輯亦在 [`core-rs`](../core-rs/SD.md) host `cargo test` 覆蓋(Rust 端 `frameSignature`);
  JNI 薄綁定由「載入 `.so` 能回正確值」的整合測試背書。
- **後續自動化:** Android `androidTest`(Instrumented)呼叫 `NativeCore` 做煙霧測試。

## 7. 隱私與穩健性

- 影格以 luma `ByteArray` 傳入 JNI,只回 hash/布林;**像素不回傳、不落地**。
- `frameSignature` 底層 `gate::frame_signature` 對長度不足/零維度回 `Signature(0)` 不 panic。
- Play Protect 對 sideload debug APK 的 `HARMFUL` 標記為未知來源提示,非程式缺陷。

## 追溯

滿足 [`SA.md`](SA.md) FR-1…FR-4、NFR-1…NFR-3。相關:[ADR-0007](../../adr/0007-rust-first-redesign.md)、[core-rs 設計](../core-rs/SD.md)。
