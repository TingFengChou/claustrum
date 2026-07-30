# core — System Analysis (SA)

**狀態:** active · **最後更新:** 2026-07-30 · **負責人:** claustrum

## 1. 目的與範圍

`core` 以 Python 型別及對應它們的 JSON Schema 定義系統共享的領域詞彙,並將專案的隱私與
抗幻覺承諾**當作型別層級的不變式**來落實,而不是靠慣例。範圍內:`Actant`、`Kineme`、
`Risk`、`SpatialAnchor`、`Ethogram` 及其相關型別、它們的序列化,以及 schema 驗證。
範圍外:產出/消費這些型別的 pipeline 各階段(那是 `ethogram/` 與後續模組),以及
工具/傳輸合約(未來的 `core/tools/`)。

## 2. 參與者與情境

- **L1 caption** 產出 `Kineme`(透過模型 + pipeline)。
- **L0 / L3** 填入 pipeline 計算的欄位(例如 `novelty`)。
- **L4 / 傳輸層** 消費 `Kineme` 與 `Ethogram`。
- **CI** 消費 schema 與型別,以檢查兩者一致。

`core` 位於其他所有模組之下,且不相依於它們任何一個。

## 3. 功能需求

- **FR-1** 定義領域型別與封閉列舉(`ActantType`、`RiskLevel`、`RiskCategory`)。
- **FR-2** 在建構時落實不變式:id 格式、時間排序、`confidence`/`novelty` 的範圍、
  action 長度。
- **FR-3** 落實隱私不變式:`Actant.label` 是一個角色槽位,絕非身分;任何地方都不存在
  身分欄位。
- **FR-4** 落實抗幻覺不變式:`risk.level != none` 需要一個 `reason`;`level == none`
  與 `category == none` 成對出現。
- **FR-5** 序列化(`to_dict`)並提供一種離節點安全的 `redacted` 形式。
- **FR-6** 依 JSON Schema 驗證一筆 payload,含時間戳。

## 4. 非功能需求

- **NFR-1 相依項精簡。** 僅用標準函式庫;`jsonschema` 延遲載入且僅用於驗證。
- **NFR-2 Schema/型別一致。** dataclass 與 `schemas/kineme.schema.json` 不得漂移 —
  由 CI 在值與欄位名兩個維度上落實。
- **NFR-3 可測試性。** 每個不變式都可在無硬體下做單元測試。

## 5. 領域模型

```
Actant        場景中的一個參與者 — 角色槽位,絕非身分
Kineme        單一時間跨度內觀察到的一個行為(L1 輸出)
Risk          level + 封閉 category + 佐證 reason
SpatialAnchor 選用的地圖座標(僅限機器人部署)
Ethogram      一段期間內 kineme 的目錄(L3 輸出)
```

不變式描述於 FR-3 與 FR-4,以及 [ADR-0002](../../adr/0002-naming-and-domain-language.md)。

## 6. 限制與假設

- 不做人臉辨識、不做身分歸屬 — 這是結構性的,以型別表達。
- `novelty` 由 pipeline 計算,而非模型回報([ADR-0004](../../adr/0004-phone-first-single-node.md) 的重新框定;另見 ARCHITECTURE.md)。
- 時間戳為 ISO-8601 / RFC-3339。

## 7. 驗收標準

- 以壞掉的 id、顛倒的時間戳、超出範圍的 confidence/novelty、身分形狀的 label,或未經
  佐證的 risk 來建構型別時**會拋出例外**。
- 格式良好的 `Kineme` 能序列化並通過 schema 驗證;格式錯誤的時間戳會被驗證拒絕。
- schema 列舉等於 Python 列舉;dataclass 欄位等於 schema 屬性(雙向皆然)。

以上全部都實現為 `tests/test_domain.py` 中的測試。

## 8. 未解問題

- **模型輸出子集 schema。** prompt 向模型索取 `Kineme` 的一個*子集*(不含
  `id`/時間戳/`source_id`/`model`/`prompt_version`);該子集尚未被正式定義或驗證。
  延後至 M1。
- **`Ethogram` schema。** 目前只有 `Kineme` 有 JSON Schema;`Ethogram`(主要輸出,
  且在 M3/M4 會跨越節點邊界)需要一份。延後至 M3。

## 追溯

[ADR-0002](../../adr/0002-naming-and-domain-language.md)(命名/不變式)、
[ADR-0004](../../adr/0004-phone-first-single-node.md)(novelty 重新框定)、
roadmap M1(凍結 schema)。設計見 [`SD.md`](SD.md)。
