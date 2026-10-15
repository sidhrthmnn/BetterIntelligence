# Better Intelligence

Android app and shared local AI service with real on-device GGUF inference through llama.cpp. In **Models**, choose from **Library**, **Find models**, or **My models**, then tap **Use model** after downloading. Use **Chat**, polish selected text from another app, or approve consumer apps in **Apps**. Hardware tools are under **Advanced tools**. Downloads and activation are separate choices; installation does not silently download gigabytes.

The replacement Play app uses package `com.aistudio.betterintelligence.mobile`. Its provider, permission and action names use the same prefix, so it can coexist with the previous app while testers migrate. Consumers must use the updated client and manifest declarations below. See [release signing and publishing](docs/RELEASING.md).

## Shared AI Polish

Apps such as keyboards can use Better Intelligence’s selected local model for English proofreading without bundling their own model or inference runtime. The new API supports `status` and `polish` through `ContentResolver.call()`, or `getServiceStatus()` and `polishText()` through Binder. Only successful responses contain replacement text. New consumers require user approval, and memory access is off by default. Text is excluded from access logs and polishing never adds shared memories or uses a cloud fallback.

See [the integration guide](docs/APP_INTEGRATION.md) for the permission, Kotlin client, response states, Typeright integration pattern and verification commands. Consumer apps must implement the connection; this repository does not change Typeright’s existing AI provider. Without an app integration, Android’s text selection toolbar and Share menu offer **Polish with Better Intelligence**, with a review step before copying or replacing text.

## Model library

| Model | GGUF bytes | Estimated runtime RAM |
| --- | ---: | ---: |
| SmolLM2 135M Instruct Q4_K_M | 105,454,144 | 512 MB |
| Gemma 3 270M Instruct Q4_K_M | 253,115,424 | 768 MB |
| SmolLM2 360M Instruct Q4_K_M | 270,590,880 | 768 MB |
| Qwen 2.5 0.5B Instruct Q4_K_M | 491,400,032 | 1,024 MB |
| Gemma 3 1B Instruct Q4_K_M | 806,058,240 | 1,792 MB |
| SmolLM2 1.7B Instruct Q4_K_M | 1,055,609,536 | 2,048 MB |
| Qwen 2.5 1.5B Instruct Q4_K_M | 1,117,320,736 | 2,048 MB |

These are compact quantized choices for mobile CPU inference. See [model selection and publisher sources](docs/MOBILE_MODELS.md) for the tradeoffs. **Find models** searches public Hugging Face repositories or accepts an `owner/repository` URL. **Import a GGUF file** accepts files downloaded elsewhere, including gated models after obtaining access from their publisher. Downloads and imports appear in **My models**. A model must be a compatible single-file text GGUF; other formats, multimodal projectors and split files are not supported by this chooser.

`models/library.json` and the matching APK asset record pinned upstream revisions, byte counts and SHA-256 checksums. In-app downloads use those pinned files. The four original weights remain in Git LFS; the three additions download on demand and local copies are ignored by Git. All weights are separate from the APK. Run `git lfs pull` for the original four, or `python scripts/download_models.py` (Python 3.11+) for all seven. The script also accepts one or more library filenames.

Recommendations select from the curated library using supported ABI, current available RAM with a 512 MB reserve, low-memory state, and free storage. Users can download a larger model if storage permits, but activation still checks available memory. Memory figures are estimates. Speeds must be measured on the device. Supported packaged ABIs: arm64-v8a and x86_64. This build uses the CPU backend; GPU and LiteRT execution are unavailable. Native inference uses each GGUF's chat template, real tokenization, configurable sampling, cancellation, a 180-second deadline, and an output limit of 1,024 tokens. Only one inference runs at a time. Downloads stop if the process is killed; restart them in Models.

SmolLM2 and Qwen are Apache-2.0. Gemma requires acceptance of its terms and prohibited-use policy in Models before download or activation. Redistributors must retain the notices and model license files under `models/`. See [Gemma terms](https://ai.google.dev/gemma/terms), [Gemma use policy](https://ai.google.dev/gemma/prohibited_use_policy), and [llama.cpp Android build guidance](https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md).

## Use the shared AI service from another app

Install Better Intelligence, download and select a model. Consumer apps declare:

```xml
<uses-permission android:name="com.aistudio.betterintelligence.mobile.permission.BIND_LOCAL_AI_CORE" />
<queries><package android:name="com.aistudio.betterintelligence.mobile" /></queries>
```

Copy both AIDL files from `app/src/main/aidl/com/example/ipc/` and optionally `LocalAiCoreClient.kt` from `app/src/main/java/com/example/ipc/`. Enable `buildFeatures { aidl = true }`. Preserve the AIDL package names. Bind to action `com.aistudio.betterintelligence.mobile.ACTION_BIND_AI_CORE` in package `com.aistudio.betterintelligence.mobile`. Make Binder generation calls on a worker thread; streaming callbacks run on Binder threads.

The first status or inference request creates a pending entry in **Apps**. The user enables its access switch to approve access, and may block or rate-limit it later. Access checks use the caller UID's package, never a package supplied by the consumer. Prompts and responses from external apps are not retained in access logs. Consumers can generate synchronously or stream tokens, summarize, classify, read model readiness and telemetry, or cancel their own Binder inference. Embeddings are explicitly unsupported because these are text-generation models.

`sample-client` is a separate Android app that demonstrates shared AI Polish across processes. It has no INTERNET permission and carries no model weights. Install it after Better Intelligence, launch it, approve **com.example.betterclient** in Apps, then tap **Polish with Better Intelligence**. Its instrumentation test exercises both the provider and Binder from a separate UID, including an attempted package spoof.

## Build and verify

Use JDK 21, Android SDK 36.1, NDK 28.0.12433566 and CMake 3.22.1. Configure `sdk.dir` in `local.properties`. Gradle fetches a pinned llama.cpp source archive with its SHA-256 verified.

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :sample-client:assembleDebug
```

`ModelLibraryTest` checks the pinned catalog, recommendations, download versus activation requirements, available real files and absence of fake readiness. `HuggingFaceCatalogTest` checks immutable URLs, checksums, safe filenames and unsupported-file filtering. `GgufInferenceTest` checks actual offline inference with SmolLM2, Qwen and Gemma, model switching, permissions, rate limits and checksum mismatches. For the device test, install the debug and test APKs, place the 135M, 0.5B, 1B and three new GGUF files in the app's private `files/models/` directory with adb/run-as, disable networking, then run:

```sh
adb shell am instrument -w -e class com.example.GgufInferenceTest com.aistudio.betterintelligence.mobile.test/androidx.test.runner.AndroidJUnitRunner
```

The native engine uses ordinary process memory and Android lifecycle rules. A bound consumer keeps the service alive while connected; installed apps cannot access AI automatically without implementing Binder integration.
