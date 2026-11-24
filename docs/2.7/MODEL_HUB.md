# Better Intelligence 2.7 — model and app hub

The application now provides three primary destinations: **Models**, **Apps** and **Settings**. Models is the home destination. Device optimisation, download preferences, appearance, privacy, default inference context and hardware monitoring remain subordinate screens. Chat and research belong to the client application, including Neuron.

## Removed application functionality

The built-in chat screen, history UI, edit/regenerate flow, drafts, chat orchestration, research coordinator, web-settings screen, chat-memory controls and request simulator have been removed from the activity/view-model layer. Launching BI no longer creates, restores or generates a conversation, runs question routing, or initializes discovery/extraction. Old saved navigation containing Chat/History falls back to Models. There are no chat input, send, history or research controls in the app.

The native inference runtimes, Rust admission/health policy, model lifecycle, catalogue, downloader and external interfaces remain. `ChatTurn`/context primitives required for native role-separated requests are inference data structures, not a BI conversation UI. Legacy Room tables and standalone deterministic evidence utilities remain for schema compatibility and historical regression coverage; they are not wired to an active chat or browsing feature. Existing private data is not silently erased or transferred to Neuron. No Neuron source code or integration was invented or changed in this task.

## Reinstall-safe connections

The original transport required Android's granted custom normal permission on both exported components and in their method guards. On the API 36 emulator, the same unchanged sample app passed its provider/Binder test before BI was removed, then failed with a provider `SecurityException` after only BI was uninstalled and installed again. The client still declared the permission, but Android no longer granted it.

`LocalAiCaller` now resolves the real Binder UID through PackageManager, confirms its installed package UID and the existing local-AI manifest declaration, and deterministically selects a declaring identity. It does not accept identity strings from extras or prompts. The service and provider admit that enrollment without relying on the stale OS install-time grant. Existing BI approval, block, memory/inference scopes, rate limits, request ownership and private access logs still gate operations. No AIDL transaction IDs, authorities, action names or response fields change.

A clean BI uninstall removes its private approval records and downloaded models as Android normally does. The user must request access again, approve the app in BI and select/download a model. Reinstalling the **other** app is no longer required. A dead client Binder must be released and reconnected using the client's normal lifecycle; server code cannot revive an object held by another process.

## One download network preference

**Download over Wi-Fi only** is the single setting. On restricts scheduled transfers to the existing unmetered-network policy; off uses any connected network, including mobile data or Wi-Fi. As before, a metered Wi-Fi hotspot can be treated as metered by Android. Old `allow_mobile_data` preferences are read only as migration fallback when no canonical `wifi_only` value exists. Writing the setting removes that obsolete key. The second mobile-data toggle and its view-model/store setters are removed.

Changing network policy reschedules pending work and cancels the alternate scheduler so an old WorkManager/JobScheduler admission cannot continue under a contradictory policy. Android's execution restrictions remain in force. Partial files, checksums, pause/resume and deletion continue using the existing downloader.

## Download reliability in 2.7.1

Downloads use a persistent queue. On Android 14 and later, a tap can start an Android user-initiated data transfer job. WorkManager handles deferred transfers and older versions of Android. Adding an item keeps the current transfer running. If BI is open with background downloads turned off, the visible activity owns the transfer and saves progress when it stops.

With Wi-Fi only enabled, Android 9 and later can schedule transfers on Wi-Fi, including metered Wi-Fi. Earlier versions still use the unmetered scheduler constraint for background work. Connections use the assigned network or an active VPN. If the app cannot confirm that a VPN is using Wi-Fi, it waits rather than sending over another network.

Transient network failures keep checked partial bytes and retry automatically when resumption is enabled. A temporarily unavailable file yields to other models in the queue. BI checks the returned byte range and verifies the expected checksum before installing. Models without a publisher checksum restart at byte zero after an interruption. Android may defer background work, and a force stop requires reopening the app.

## Validation and release

The test plan covers the actual provider-reinstall failure with an unchanged cross-UID client, fresh client approval and inference, undeclared/unknown UID rejection, scope enforcement, setting migration, navigation/recreation, absence of chat controls, seven light/dark screens, existing Kotlin regressions, Rust host tests and signed production-bundle validation. Retired chat-screen instrumentation and transcript UI tests are removed with the feature; native, schema, client and deterministic evidence tests remain.

Final measured outcomes and the production submission are recorded in the release validation files. Physical HONOR VKP-NX9 (Android 17) tests passed for navigation, download preferences, real CPU inference through the provider and Binder, and reinstall recovery with an unchanged client. GPU/NPU performance was not measured in this task. Publication requires Google's review and approval.
