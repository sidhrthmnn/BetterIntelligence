# Better Intelligence task API

## Connect

Copy the three AIDL files under `app/src/main/aidl/com/example/ipc` into the same AIDL package in the client. Keep the declaration order. BI appends task methods after the legacy text methods to preserve their Binder transaction IDs. Add the production app's permission and visibility declaration to the client manifest.

```xml
<uses-permission android:name="com.aistudio.betterintelligence.mobile.permission.BIND_LOCAL_AI_CORE" />
<queries>
    <package android:name="com.aistudio.betterintelligence.mobile" />
</queries>
```

Bind explicitly with the action `com.aistudio.betterintelligence.mobile.ACTION_BIND_AI_CORE` and package `com.aistudio.betterintelligence.mobile`. The user approves the connection in **Apps**. Grant the relevant scope to each approved app. Gemma image, audio and transcription also require the **Resource-intensive tasks** scope. Text and embeddings use the text inference scope. Shared memory uses its existing read and write scopes, independently of tasks.

Call `getApiVersion()` to negotiate. Version 3 supports the calls below. For older installations where the method is absent, use `getServiceStatus()` and the existing text API. The legacy status still advertises its own version 2 protocol. Retry the binding when BI is installed again and request approval again after BI's private settings are cleared. See the [connecting app guide](../APP_INTEGRATION.md) and [working Kotlin client](../../sample-client/src/main/java/com/example/betterclient/BiTaskClient.kt).

## Discovery and consent

| Method | Result |
| --- | --- |
| `getCapabilities()` | `capabilities` list of bundles. Each has `task_type`, `label`, `implemented`, `installed_models`, `input`, `output_format`, `resource_intensive`, `backend_preference` and a readiness `status`. |
| `getInstalledModels()` | `models` list for an approved app. |
| `getRecommendedModels(capability)` | `models` that fit the current device budget, with `estimated_ram_mb`. Pass an exact task type. |
| `getModelStatus(modelId)` | Model bundle with `model_id`, `name`, `format`, `quantization`, `download_mb`, `download_progress`, `downloaded`, `installed`, `loaded`, `capabilities` and `status`. A downloaded model is verified and stored. Loaded and processing models also have a usable runtime session. |
| `requestModelDownload(modelId)` | With **Request model downloads** permission, creates a pending approval for an eligible catalog file. `USER_DOWNLOAD_APPROVAL_REQUIRED` means the user must approve in **Models** before any transfer starts. |

## Submit and receive a task

Send a `Bundle` and, for a media task, one read-only `ParcelFileDescriptor`. Make service calls off the UI thread. Supported task types and result formats are listed in the [runtime table](ARCHITECTURE.md). Known unavailable task types return `CAPABILITY_UNAVAILABLE`.

| Request key | Type and meaning |
| --- | --- |
| `api_version` | Required integer `3`. |
| `task_type` | Required task type string. |
| `request_id` | Optional client correlation ID up to 80 letters, digits, hyphens, underscores or periods. |
| `text` | String. Required for text, embeddings and speech. Optional for media, which has a default question. Limits are 4,000 characters for polishing, 1,600 for media, 8,192 for text generation, 4,096 UTF-8 bytes for embeddings, and Android's speech input limit for speech. |
| `context_roles`, `context_text` | Matching string array lists of up to 12 turns and 6,000 total characters. `user` and `assistant` roles. Available for text generation and image understanding. |
| `model_id` | Optional positive long. A suggestion subject to the user's model selection and runtime admission. |
| `quality` | Optional `FAST`, `BALANCED` or `QUALITY`. Sets the requested generation budget or model ranking where applicable. |
| `media_mime` | `image/jpeg`, `image/png`, `audio/wav` or `audio/x-wav`, matching the task. |
| `language_tag` | Optional BCP 47 language tag for speech synthesis. Defaults to the system language. |
| `output_format` | Optional. If provided, must equal the supported output format for the task. |

Image input is JPEG or PNG, up to 8 MiB and 24 megapixels, with each dimension at most 8,192 pixels. BI strips metadata by decoding and scales the image to at most 384 pixels on its longest side. Audio input is mono 16 kHz, 16-bit PCM WAV, up to 12 seconds. BI accepts bounded, seekable regular files. The client can copy a provider's content URI to its own private cache first. The client can close its descriptor after `submitTask` returns. BI closes its copy after success, cancellation or failure.

A `QUEUED` response supplies the BI-generated `task_id`. Use it for `getTaskStatus` or `cancelTask`. Only the verified submitting UID and package can query or cancel the task. BI cancels queued or active work when the callback's process dies. Proofreading takes precedence at task boundaries. A running native task must release its resources before the next task starts.

`onProgress` receives `WAITING_FOR_ENGINE`, `LOADING` or `PROCESSING` when applicable. `progress` indicates the phase. `onText` emits deltas for text tasks, including transcription. On `SUCCESS`, `onFinished` is the authoritative result. It contains `text`, a normalized `FloatArray` under `embedding`, or an output WAV descriptor, depending on the requested task. Close the output descriptor after reading. For BGE Small the vector has 384 components. Compare normalized vectors from the same encoder using dot products. No embedding index is retained by BI.

The terminal bundle includes `model_id`, `model_name`, `runtime`, `backend`, `output_format`, `latency_ms`, `load_ms`, `first_token_ms`, `tokens_per_second`, `observed_ram_mb` and `thermal_state` where measured. Zero or empty metrics can mean the runtime does not expose that measurement. Status polling only retains metadata for up to ten minutes and the most recent 64 tasks. The terminal callback is the only way to receive output.

| Status | Client action |
| --- | --- |
| `APPROVAL_REQUIRED`, `DENIED` | Direct the user to Apps to approve the connection or scope. |
| `DISABLED`, `CAPABILITY_UNAVAILABLE`, `BACKEND_UNAVAILABLE` | Show that the selected capability or processor cannot run. |
| `SYSTEM_VOICE_REQUIRED`, `MODEL_REQUIRED` | Direct the user to install and configure an offline Android voice or compatible BI model. |
| `RESOURCE_LIMIT`, `QUEUE_FULL`, `RATE_LIMITED` | Show the reason and retry later if the condition clears. |
| `INVALID_REQUEST`, `INVALID_INPUT`, `VERSION_UNSUPPORTED` | Correct the request, media or API version. |
| `CALLER_GONE`, `TASK_NOT_FOUND`, `CANCELLED`, `INFERENCE_FAILED` | Treat as terminal or restore the app connection where needed. |
