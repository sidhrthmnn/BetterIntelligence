# Better Intelligence

Android AI service with real on-device GGUF inference through llama.cpp. Open **Models** to see phone diagnostics and recommendations, download individual files, or import multiple GGUF files. Select an installed model, then use **Chat** or connect another app through Binder. Downloads and activation are separate choices; installation does not silently download gigabytes.

## Model library

| Model | GGUF bytes | Estimated runtime RAM |
| --- | ---: | ---: |
| SmolLM2 135M Instruct Q4_K_M | 105,454,144 | 512 MB |
| SmolLM2 360M Instruct Q4_K_M | 270,590,880 | 768 MB |
| Qwen 2.5 0.5B Instruct Q4_K_M | 491,400,032 | 1,024 MB |
| Gemma 3 1B Instruct Q4_K_M | 806,058,240 | 1,792 MB |

The actual weights are tracked under `models/` using Git LFS. They are separate from the APK, like TypeRight's GGUF delivery. `models/library.json` and the matching APK asset record pinned upstream revisions, byte counts and SHA-256 checksums. In-app downloads use these pinned upstream files. To restore weights locally, run `git lfs pull` or `python scripts/download_models.py` (Python 3.11+). The script also accepts one or more library filenames.

Recommendations select from the real library using supported ABI, current available RAM with a 512 MB reserve, low-memory state, and free storage. Memory figures are estimates. Speeds are measured by running the model on the device. Supported packaged ABIs: arm64-v8a and x86_64. This build uses the CPU backend; GPU and LiteRT execution are unavailable. Native inference uses each GGUF's chat template, real tokenization, configurable sampling, cancellation, a 180-second deadline, and an output limit of 1,024 tokens. Only one inference runs at a time. Downloads stop if the process is killed; restart them in Models.

SmolLM2 and Qwen are Apache-2.0. Gemma requires acceptance of its terms and prohibited-use policy in Models before download or activation. Redistributors must retain the notices and model license files under `models/`. See [Gemma terms](https://ai.google.dev/gemma/terms), [Gemma use policy](https://ai.google.dev/gemma/prohibited_use_policy), and [llama.cpp Android build guidance](https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md).

## Use the shared AI service from another app

Install Better Intelligence, download and select a model. Consumer apps declare:

```xml
<uses-permission android:name="com.aistudio.localaicore.permission.BIND_LOCAL_AI_CORE" />
<queries><package android:name="com.aistudio.betterintelligence.vqkzp" /></queries>
```

Copy both AIDL files from `app/src/main/aidl/com/example/ipc/` and optionally `LocalAiCoreClient.kt` from `app/src/main/java/com/example/ipc/`. Enable `buildFeatures { aidl = true }`. Preserve the AIDL package names. Bind to action `com.aistudio.localaicore.ACTION_BIND_AI_CORE` in package `com.aistudio.betterintelligence.vqkzp`. Make Binder generation calls on a worker thread; streaming callbacks run on Binder threads.

The first inference attempt creates a pending entry in **Connect**. The user enables its whitelist switch to approve access, and may block or rate-limit it later. Access checks use the caller UID's package, never a package supplied by the consumer. Prompts and responses from external apps are not retained in access logs. Consumers can generate synchronously or stream tokens, summarize, classify, read model readiness and telemetry, or cancel their own inference. Embeddings are explicitly unsupported because these are text-generation models.

`sample-client` is a separate Android app that demonstrates synchronous and streaming requests across processes. It has no INTERNET permission and carries no model weights. Install it after Better Intelligence, launch it, approve **com.example.betterclient** in Connect, then relaunch it to request arithmetic responses from the selected model. Apps such as keyboards and chat apps can use the same contract; they must implement the integration themselves.

## Build and verify

Use JDK 21, Android SDK 36.1, NDK 28.0.12433566 and CMake 3.22.1. Configure `sdk.dir` in `local.properties`. Gradle fetches a pinned llama.cpp source archive with its SHA-256 verified.

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :sample-client:assembleDebug
```

`ModelLibraryTest` checks the real files, recommendations and absence of fake readiness. `GgufInferenceTest` checks actual offline inference with SmolLM2, Qwen and Gemma, model switching, permissions, rate limits and checksum mismatches. For the device test, install the debug and test APKs, place the three GGUF files in the app's private `files/models/` directory with adb/run-as, disable networking, then run:

```sh
adb shell am instrument -w -e class com.example.GgufInferenceTest com.aistudio.betterintelligence.vqkzp.test/androidx.test.runner.AndroidJUnitRunner
```

The native engine uses ordinary process memory and Android lifecycle rules. A bound consumer keeps the service alive while connected; installed apps cannot access AI automatically without implementing Binder integration.
