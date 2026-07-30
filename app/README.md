# claustrum app

claustrum 的**產品本體** —— 一支在 Pixel 10 上、以 edge AI 於裝置端執行的感知 App
(React Native + TypeScript 殼 + 原生 Rust/C++ 核心 + Kotlin 平台層)。架構見
[ADR-0005](../docs/adr/0005-react-native-app.md);模組設計見
[`docs/design/app/`](../docs/design/app/)。**即時串流辨識**是終極目標。

## 開發

```sh
npm install          # 安裝相依(於 app/ 目錄)
npm start            # 啟動 Metro
npm run android      # 建置並部署到已連接的 Pixel 10(需 Android SDK / JDK 17)
npx tsc --noEmit     # TypeScript 型別檢查
npm test             # Jest
```

## 結構

```
app/
  App.tsx            進入點,渲染 HomeScreen
  src/
    theme.ts         視覺識別(取自 app icon:深靛底 + 三模態強調色)
    domain/kineme.ts Kineme 型別(由 schemas/kineme.schema.json 對應)
    screens/         畫面(HomeScreen —— 已設計的首屏)
  android/  ios/      原生專案(iOS 暫不投入)
```

## 原則

- **影格不過 JS bridge** —— 原生層持有影格,只把去識別化的 `Kineme` 過橋(隱私 + 效能)。
- **效能/串流熱路徑走原生 Rust/C++**,不佔 RN 的 JS 執行緒。
- **UI 以 Claude 設計**至接近產品化品質(規範第 4 條)。
