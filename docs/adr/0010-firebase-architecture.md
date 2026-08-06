# ADR-0010 — Firebase 架構(雲端後端;隱私/機密紀律)

**狀態:** 規劃(設計;尚未接線,需 owner 建立 Firebase 專案) · **日期:** 2026-08-06
**延續:** [ADR-0004](0004-phone-first-single-node.md)(手機優先、單節點)、[ADR-0006](0006-safety-alert-mvp.md)(隱私/PDPA)、[ADR-0009](0009-edge-ai-litert-ai-edge.md)

> 感知(L0/L1/L2)全在裝置端(edge)。Firebase 只承擔**雲端後端**:授權、告警派送、事件同步、
> 設定下發、穩定度。**影格/影像/音訊永不上雲**;只有**文字描述與事件**可離開裝置。

## 背景

問到:HF 權杖能否用 **Firebase Remote Config** 下發?

**不行。** Remote Config 的值是**用戶端可讀的設定**,會隨 App 下發、可從流量或 App 中被抽出——
它**不是機密儲存**。在**公開 repo + 對外散布的 App** 上把 gated HF 權杖放 Remote Config,等於把
你的 HF 憑證洩露給每一個使用者(與硬編碼同罪)。Google 官方也明載:**勿在 Remote Config 放機密**。

## 決策:各 Firebase 服務的用途(與紅線)

| 服務 | 用途 | 紅線 |
|---|---|---|
| **Remote Config** | **非機密**設定:模型目錄(allowlist)、L0 閾值、告警冷卻、feature flags | **絕不放權杖/機密** |
| **Auth** | 使用者帳號;或聯合 **HF OAuth** 讓每人用自己的帳號取得 gated 模型存取 | 不存他人憑證 |
| **App Check** | 驗證是正版 App 實例,保護下方任何後端代理 | — |
| **Cloud Functions + Secret Manager** | (選)伺服器端**模型下載代理**:服務憑證存 Secret Manager,或簽發短期 URL | 機密只在伺服器,**永不下client** |
| **Cloud Messaging(FCM)** | 把告警推播給保全 / 家屬 | 只送文字事件 |
| **Firestore** | 事件 / 告警記錄同步 | **只存文字描述與事件,永不存影格/影像/PII** |
| **Crashlytics / Analytics** | 穩定度 / 指標 | 不含 PII / 不含畫面內容 |

## gated 模型授權(取代「Remote Config 下發權杖」)

兩條正解,擇一(皆不把權杖放 Remote Config):
1. **每位使用者自行 HF OAuth 登入**(像 AI Edge Gallery)—— 用自己的帳號與授權,權杖不共享。裝置端。
2. **伺服器端下載代理**(Cloud Function)—— 後端持服務憑證(Secret Manager)代理下載或簽發短期 URL,
   權杖不下 client。適合「使用者不需自備 HF 帳號」的產品化情境。

目前 App 內為 interim 的「貼 read 權杖(加密儲存)」(PR #22);上述 1 或 2 為產品化升級。

## 後果

- **好處:** 告警派送(FCM)、設定熱更新(Remote Config)、事件同步、授權集中,皆為產品化必需。
- **紅線不可退:** 影格不上雲;機密不進 Remote Config / 不下 client;Firestore 只放文字事件、無 PII。
- **待辦(owner):** 建立 Firebase 專案、加入 `google-services.json`(不進版控)、於 Secret Manager 設機密。
- **待辦(工程):** 依需求逐一接線(先 Remote Config 模型目錄 + FCM 告警;OAuth / 下載代理視產品方向)。

## 追溯

相關:[ADR-0006](0006-safety-alert-mvp.md)(隱私/PDPA/抗誤報)、[ADR-0009](0009-edge-ai-litert-ai-edge.md)、
[`model` 設計](../design/model/SD.md)、README「Edge AI 模型使用 / Firebase(規劃)」。
