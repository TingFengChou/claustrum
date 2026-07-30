# Privacy and compliance

This is a design constraints document, not a disclaimer. If you are about to point a camera at your family, read it.

## Design commitments

These are enforced structurally where possible, by policy where not.

| Commitment | Enforcement |
|---|---|
| Video frames never leave the device | **Phone era: policy** (single node — one process holds frames and answers queries, so there is no structural boundary). **Two-node era: structural** — the query surface has no code path to frame storage. See [ADR-0004](adr/0004-phone-first-single-node.md). |
| No face recognition, no identity attribution | **Structural** — `Actant` is a role slot; no identity field exists in the schema |
| Frames encrypted at rest, deleted after 7 days | Policy — retention job, configurable |
| Kineme text retained 90 days | Policy — configurable |
| Cloud escalation off by default | Policy — per-instance consent required |
| Camera has a physical cover and an in-app pause | Hardware + policy |
| Pause state is visibly indicated | Persistent notification / LED |

Bathrooms and bedrooms are out of scope by default. If deployed there at all, only in text-only mode with no frame retention.

## The AppFunctions problem

This is the sharpest tension in the project and it deserves to be stated plainly.

Android's AppFunctions documentation notes that **system agents may process user queries on the server** in order to use larger models. So when the household query surface is exposed to Gemini:

- Video frames stay on device ✓
- **The user's question and the returned kineme text may leave the device** ✗

Structured text about household activity is, in some respects, a worse exposure than video: it is searchable, comparable, and cheap to retain indefinitely.

### Consequence: tiered exposure, default closed

| Tier | Tools exposed | Payload | Default |
|---|---|---|---|
| **T0** | none | — | ✅ default |
| T1 | `getHomeStatus` | very coarse — "someone home / nobody home", "no anomalies today" | opt-in |
| T2 | `queryKinemes`, `getEthogram` | kineme text, timestamps | opt-in, with explicit warning |
| T3 | frame URIs | images | ❌ never exposed externally |

Implemented per-function with `AppFunctionManager.setAppFunctionEnabled()`. Each function has its own ID and can be toggled independently.

### Required, not optional

Three things ship *with* the AppFunctions provider, never after it:

1. **Tiered consent UI** with the server-processing warning in plain language, on the settings screen — not buried in a policy document.
2. **Caller allowlist.** Reject any calling package that is not expected. Log rejections.
3. **User-visible audit log.** A screen showing who queried what, and when.

The audit log is the only thing that makes the toggle trustworthy. And all three must exist before the feature is usable, because once the household starts depending on it, the will to retrofit restrictions evaporates.

## Legal (Taiwan)

Not legal advice. Consult a lawyer before anything beyond personal use.

- **個人資料保護法 (PDPA):** purely domestic personal use has room for exemption, but the moment a visitor is captured, or the system is offered to anyone else, it falls within scope.
- **Informed consent** from every co-resident, obtained in advance. For minors, from a guardian.
- Any commercial or internal-product use requires legal review first. Semantic summaries of household activity processed via a cloud agent are not within the domestic-use exemption.

## Not a medical device

Fall detection will miss events. Hazard detection will produce false positives. This system must not be anyone's sole safety net, and must not be presented as care provision.

This statement belongs at the top of the README, not the bottom of a document. It is in both places on purpose.
