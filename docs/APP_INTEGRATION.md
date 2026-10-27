# Use Better Intelligence from another Android app

Better Intelligence owns the downloaded GGUF or LiteRT-LM model and runs inference locally. A keyboard such as Typeright can submit text for proofreading and receive a replacement without carrying model weights or native inference code. The consumer needs an integration; installing Better Intelligence does not redirect existing AI buttons automatically. BI selects the runtime from the model format and manages CPU/GPU processing internally. Existing AIDL transaction IDs, permission names and response fields remain compatible.

API v2 Binder generation has no context-size argument. BI uses the context selected in Advanced settings (2048 by default), or the safe context admitted by explicit model preparation, bounded by the model's real limits. A short external polish reuses a fitting loaded fallback instead of promoting back to the larger Primary. Clients should still bound input/output, handle readiness and review proposed edits. TypeRight supplies its own English proofreading prompt over the existing streaming Binder method; the built-in `polish` prompt is a separate path.

## Setup

Install Better Intelligence, download a model in Models, and tap Use model. In the consumer manifest, declare:

```xml
<uses-permission android:name="com.aistudio.betterintelligence.mobile.permission.BIND_LOCAL_AI_CORE" />
<queries>
    <package android:name="com.aistudio.betterintelligence.mobile" />
    <provider android:authorities="com.aistudio.betterintelligence.mobile.provider" />
</queries>
```

No INTERNET permission is needed in the consumer. Copy [LocalAiPolishClient.kt](../app/src/main/java/com/example/ipc/LocalAiPolishClient.kt) into the consumer. It requires Kotlin coroutines and has no AIDL dependency. Keep or change its Kotlin package and update the import in your app.

```kotlin
val ai = LocalAiPolishClient(applicationContext)
// Call from a lifecycle-owned coroutine. The helper dispatches Binder calls to IO.
val status = ai.status()
// The status request creates an unapproved entry in Better Intelligence > Apps.
// Ask the user to open Better Intelligence and enable this app, then retry.
val originalText = "i has 2 apples."
val result = ai.polish(originalText)
if (result.isSuccess) {
    showReview(originalText, requireNotNull(result.text))
} else {
    showConnectionState(result.status)
}
```

For raw Android clients:

```kotlin
val uri = Uri.parse("content://com.aistudio.betterintelligence.mobile.provider")
withContext(Dispatchers.IO) {
    val status = contentResolver.call(uri, "status", null, null)
    val polished = contentResolver.call(uri, "polish", originalText, null)
    if (polished?.getString("status") == "SUCCESS") {
        val replacement = polished.getString("text")
        // Hand back to the UI for review; never insert an error or status string.
    }
}
```

## API v2

`status` returns a Bundle with `api_version=2`, `supports_polish=true`, `polish_language=en`, `model_loaded`, `active_model`, `max_polish_characters=4000`, `settings_package`, and `status`. It registers the caller but does not consume the inference rate limit. Readiness reflects the actual runtime; an installed but unloaded model is not ready.

Version 2.0 adds the optional `model_loading` boolean. Startup restoration and model replacement return the existing `BUSY` status while work is in progress, so older clients can show their normal wait/retry message. `MODEL_REQUIRED` means initialization has settled without a ready selected model. Approval/denial still takes priority over either state. No AIDL method or transaction ordering changes.

### Explicit RAM-aware preparation (2.3.0-beta2)

New status Bundles advertise `supports_model_preparation=true`. Only after a user requests Polish, an approved client can call `ContentResolver.call(uri, "prepare_polish", null, null)`. This sends no editor text. BI checks current RAM, uses the configured Primary/Secondary policy and shares one preparation job across clients. It does not download weights. A load failure is surfaced rather than silently claiming that the fallback was activated. A fitting already-loaded fallback is reused. The admitted `effective_context` is passed to actual Binder generation.

Poll `status` with a bounded deadline while `model_loading=true` / `status=BUSY`. Additional fields are:

| Field | Meaning |
| --- | --- |
| `activity` | `CHECKING_MEMORY`, `LOADING_MODEL`, `READY`, `OTHER_REQUEST`, `LOW_MEMORY`, `PREPARATION_FAILED` or `MODEL_REQUIRED` |
| `target_model` | Model being loaded, when known; no client text |
| `model_role` | Selected Primary, stepped Primary or `SECONDARY_FALLBACK` |
| `effective_context` | Context admitted for the prepared model |

Status reads alone never load a model or invoke an LLM. When `OTHER_REQUEST` is active, explain that another request is running and let the user retry; do not queue stale editor text. Approval still takes precedence. A loading deadline must leave editor text unchanged and discard any later result from a cancelled editor session. Consumers without the optional capability continue using the existing v2 fields and AIDL methods.

TypeRight 222 waits up to 60 seconds for preparation and labels connecting, RAM checks, fallback loading, input processing, actual token generation and completion separately. It sends the selection only after readiness, does not replay inference, and retains its existing cancellation and editor-session protection. Model quality still depends on the selected fallback; a successful load does not guarantee a good correction.

`polish` accepts text in the `arg` parameter (or `extras["text"]`). It returns `status` and `latency_ms`. Only `SUCCESS` includes `text`. Some failures also contain an `error` for display. Keys unrelated to caller identity are optional; `calling_package` is ignored.

| Status | Consumer behavior |
| --- | --- |
| `SUCCESS` | Preview the corrected text and let the user apply it. |
| `APPROVAL_REQUIRED` | Open Better Intelligence; enable this consumer in Apps, then retry. |
| `MODEL_REQUIRED` | Ask the user to download and select a model in Models. |
| `BUSY` | Keep the original text and allow a later retry. |
| `RATE_LIMITED` | Keep the original text; wait or change the app's limit in Apps. |
| `DENIED` | Access is blocked, inference is disabled, or the Android permission is missing. |
| `INVALID_REQUEST` | Supply nonblank text of at most 4,000 characters. |
| `ERROR` | Local generation failed or output failed conservative validation; keep the original. |
| `UNAVAILABLE` | Client helper could not reach the installed provider. |

Approval is checked before model readiness. The runtime permits one inference at a time across chat, the provider and Binder. Calls are synchronous IPC: run them on a worker thread, as the helper does. Generation uses up to 1,024 output tokens and the native runtime's 180-second deadline. A coroutine timeout cannot interrupt an in-flight `ContentResolver.call()`; ignore late results if the editor has changed. For cancellable token generation, use the Binder streaming API instead. Cancellation of an owning Binder inference is available through `cancelActiveGeneration()`.

## Typeright integration pattern

Add an explicit **Better Intelligence** option to the keyboard's local AI provider setting. Call `status()` when it is selected. For an AI Polish action, capture the selection and editor revision, submit only the intended text, and show a before/after preview when `SUCCESS` returns. Apply the result only if the editor, selection and revision still match, and retain an undo value. Treat `APPROVAL_REQUIRED`, `MODEL_REQUIRED` and `UNAVAILABLE` as setup actions. Do not silently fall back to a cloud service. This repository supplies the provider, clients and working sample; the Typeright repository itself is unchanged.

Polish uses a fixed English proofreading prompt with deterministic sampling. Keep English/Manglish typing and other language providers separate in a keyboard. It does not attach app personas, shared memories or chat history. Conservative checks reject empty output, reasoning/fence prefixes, changed numbers, links or email addresses, and output that reaches the token ceiling. These checks do not guarantee faithful grammar or meaning. Small general models can still miss corrections, rewrite too much or refuse; preview remains necessary. Start with a short passage and choose a stronger model if needed.

## Binder compatibility

Copy both files from `app/src/main/aidl/com/example/ipc/`, preserve their package, and enable `buildFeatures { aidl = true }`. Bind to action `com.aistudio.betterintelligence.mobile.ACTION_BIND_AI_CORE` in package `com.aistudio.betterintelligence.mobile`. API v2 appends `getServiceStatus()` and `polishText(String)` after all existing methods, preserving their transaction IDs. Both return the same Bundle contract as the provider. A consumer using these methods requires a v2 service. The optional `LocalAiCoreClient` provides `connect()`, worker-dispatched `generate()`, `polish()` and `serviceStatus()` helpers; unbind when the owning lifecycle ends.

The older methods remain available. Shared memory reads and writes require separate permissions in Apps. Embeddings remain unsupported. Apps sharing an Android UID share its access decision; package names supplied by clients never grant access. New client policies are unapproved with memory sharing disabled. Existing user-approved policies retain their choices.

## Text selection without an SDK

Select text in an Android editor and choose **Polish with Better Intelligence**, or share plain text to it. Tap Polish, review the result, and replace the selection (when editable) or copy the result. This user-mediated activity does not run automatically or accept a caller-selected action that reads or writes memories.

## Verification

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :sample-client:assembleDebug :sample-client:assembleDebugAndroidTest
```

The JVM suite covers pending approval, scope restrictions, rate limiting, blocked apps, private logs, real readiness, GGUF parsing and literal-preservation guards. `sample-client` has no INTERNET permission or weights. Its `SharedPolishTest` executes provider and Binder requests under the sample's actual Android UID, including a forged `calling_package`.

For device integration tests, install all four debug APKs and put the pinned Qwen GGUF in Better Intelligence's private `files/models/` folder. Run `ServiceIntegrationSetupTest` with `approveSample=false`, then run the sample test expecting `APPROVAL_REQUIRED`. Repeat setup with `approveSample=true` and run the sample test with `expectedStatus=READY`. Set `blockSample=true` or `disableInference=true` in setup to expect `DENIED`. The setup test is emulator-only scaffolding, not an exported approval route.

For the rate-limit case, set `approveSample=true` and `requestLimit=1` in setup, then run the sample test with `expectedStatus=READY` and `rateLimited=true`: its first provider polish succeeds and its Binder polish returns `RATE_LIMITED`. Disable emulator Wi-Fi and mobile data before the approved case to verify offline operation.

References: [Android AIDL threading rules](https://developer.android.com/develop/background-work/services/aidl), [content provider permissions](https://developer.android.com/guide/topics/providers/content-provider-creating).
