# Data Safety review for 2.0

Prepared 18 October 2026. This is the implementation review, **not a record of submitted Play declarations**. The Console form must be checked against the final signed artifact and existing listing before production submission.

Google's [Data Safety guidance](https://support.google.com/googleplay/android-developer/answer/10787469) defines collection as transmission off device, including transmission to third parties. Optional and ephemeral transmission still belongs in the form. Do not carry forward an unconditional “no data collected” answer after adding optional search. User-initiated sharing has a separate exception; that exception does not remove the collection declaration. Do not assert ephemeral provider retention without evidence of that provider's actual practices.

| Path | Information | Destination / retention | Release handling |
|---|---|---|---|
| Local chat, local models, approved Binder/API v2 inference | Prompt, generated text, optional explicitly permitted memory | Device only; local conversations/memories have deletion controls | No automatic cloud inference or search; not off-device collection |
| Optional Wikipedia grounding | Current question (at most 500 characters), request/network metadata | Wikimedia HTTPS endpoints; provider policy applies | Optional in-app search history / user-generated content, app functionality; no assertion of ephemeral provider retention |
| Optional Brave grounding | Current question, request/network metadata, user-supplied API credential | Brave Search API over HTTPS; provider policy applies | Optional search; credential is encrypted locally and sent only to authenticate this API request |
| Model discovery/download | Search query, requested repository/file, normal network metadata | Hugging Face and its download infrastructure | Explain in privacy policy; no chat or editor content is used for model discovery |
| Local diagnostics | Backend, resource/performance counters, approved client identity, operational result | App private storage | No automatic developer upload; input/output excluded from IPC logs |
| User-selected external source/license/privacy link | URL and browser network metadata | User's browser / chosen website | Opens ordinary browser; no controlled WebView or app-side page scraper |

The release removes unused Firebase AI, App Check and related cloud initialization dependencies. Check the merged release manifest for residual initialization providers, advertising IDs, analytics SDKs, location permissions and accidental network interceptors. Current source requests Internet/network state, foreground-service/data-sync, notifications and wake lock; it does not request contacts, location, microphone, broad storage or keyboard access. Connected-app integration is approved in BI.

Provider keys are excluded from Android backup/device transfer. Android backup may retain settings/conversations under the user's Android backup preferences. Source excerpts remain attached to a conversation until deletion; the temporary query/evidence cache is RAM-only, bounded to eight entries and five minutes. Removing a model preserves chats and approvals.

Before submission: publish the current privacy policy at the listing's accessible HTTPS URL; inspect optional search and connected-app consent on the actual Play-delivered update; review provider retention and IP-derived location handling against the final Console questions; preserve accurate existing declarations that still apply. Never declare independent security certification or app-attributable GPU energy measurements without evidence.
