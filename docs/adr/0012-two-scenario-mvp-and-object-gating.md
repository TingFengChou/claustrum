# ADR-0012 — MVP 收斂為跌倒與亂丟垃圾，物件偵測作為候選閘門

- **狀態:** Accepted
- **日期:** 2026-08-08
- **取代範圍:** ADR-0006 的近期情境排序；其安全不變式仍有效

## 背景

「泛用保全影像理解」無法形成可驗收產品。不同事件需要不同證據：跌倒依賴人體姿態與
時序；亂丟垃圾依賴人、物件、區域與遺留時間。L1 VLM 的速度與幻覺邊界也不適合逐幀掃描
並直接判事件。真實安裝為 2F 俯視 1F，人物與小物件的像素尺寸、遮擋及 zoom/FOV 取捨會
直接決定可行性。

MediaPipe Object Detector 支援 Android `LIVE_STREAM`、category allowlist、score threshold，輸出
類別、分數與 bounding box。它可作為裝置端候選閘門，但 detector 忙碌時可能丟幀，結果也
沒有可當作跨幀身分的 tracking ID。推薦的 EfficientDet-Lite0 以 COCO 80 類訓練，只辨識具體
類別，不理解「垃圾」或「亂丟」意圖。

## 決策

MVP 只交付兩條垂直管線：

1. **跌倒／倒地安全:** CameraX → 單人 pose fast path → 可見姿態／下降／持續倒臥時序 →
   Rust L2 candidate/confirmed → policy／通知。關節點是裝置內部特徵；正式 Preview 只顯示
   匿名人物框與取景品質，不顯示骨架或直接風險標籤。
2. **亂丟垃圾:** CameraX → movement/ROI gate → MediaPipe Object Detector → 匿名短時人／物
   association → carried/separated/stationary/dwell/person-left 時序 → litter candidate → policy／
   人工確認。單一 detection、物件移動或 bottle/cup 類別都不能直接成為事件。

共用原則：

- 只有候選區域才進較昂貴的物件或 L1 分析；L1 只補客觀脈絡，不能升級事件。
- 先以場域資料驗證 COCO 類別 coverage；不足時才訓練含 LiteRT metadata 的客製 TFLite detector。
- 人／物軌跡只用短時匿名 role slot；不做人臉、身分或跨 session re-identification。
- 影格／crop 只在裝置 RAM，用完即刪；可外傳的仍只有文字與結構化事件。
- 2F→1F 按 ROI 校準 1×/2×/3×；zoom 提升主體像素但縮窄 FOV，不能以 zoom 掩蓋盲區或
  俯角造成的自遮擋。必要時採多機／多鏷取代過度數位放大。

暴力、ZoneExit、藥品、泛用查詢等既有 foundation 保留供研究，但不列入近期產品完成定義，
不得分散跌倒與亂丟垃圾的實機資料、校準及告警工作。

## 驗收影響

- 每條事件各自具標註資料、confusion matrix、端到端 p95 與 72 小時 negative corpus。
- 對外誤報總量仍須 `<1/24h`；證據不完整、association 不確定或畫面主體過小時 fail closed。
- 多人交錯、2F 俯視、小物遮擋、合法暫放後取回、既有物品與清潔行為都是必測 hard negatives。
- 對應追蹤：[跌倒 #26](https://github.com/TingFengChou/claustrum/issues/26)、
  [多人 #36](https://github.com/TingFengChou/claustrum/issues/36)、
  [相機方向 #37](https://github.com/TingFengChou/claustrum/issues/37)、
  [2F 場域 #38](https://github.com/TingFengChou/claustrum/issues/38)、
  [物件／亂丟垃圾 #39](https://github.com/TingFengChou/claustrum/issues/39)。

## 參考

- [MediaPipe Object Detector Android](https://developers.google.com/edge/mediapipe/solutions/vision/object_detector/android)
- [MediaPipe Object Detector models](https://developers.google.com/edge/mediapipe/solutions/vision/object_detector#models)
- [ML Kit Pose Detection](https://developers.google.com/ml-kit/vision/pose-detection)
- [CameraX zoom](https://developer.android.com/media/camera/camerax/configuration#zoom)
