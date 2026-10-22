# Better Intelligence

Chat-first Android assistant and shared local AI service. Version 2.0 opens directly into chat, with saved conversations, Markdown, streaming, edit/regenerate and keyboard-aware scrolling. Open **Settings → AI & Models** to download, select, import or delete models, **Connected Apps** to approve local clients, and **Advanced** for hardware diagnostics. GGUF uses llama.cpp; compatible LiteRT-LM packages use LiteRT-LM. Downloads and activation are separate choices; installation does not silently download gigabytes.

Optional public web research is under **Web & Grounding**. Search is off by default and requires fresh permission after upgrading from Wikipedia/Brave. It uses Bing's public news/web feeds, checks topic and year relevance, tries up to six public HTTPS pages and retains up to three readable sources. It needs no API key and cannot bypass scripts, sign-in or CAPTCHA. When research fails, chat gives a clear unavailable response instead of generating an unsupported live answer. Direct public HTTPS links can be read without a search query. Only search terms from the current question go to discovery; connected-app requests stay local and never use research. Citations identify retrieved evidence and do not independently prove every claim. See the [current privacy policy](PRIVACY.md), [2.0 implementation and release record](docs/2.0/VALIDATION.md) and [device measurements](docs/2.0/reports/README.md).

The replacement Play app uses package `com.aistudio.betterintelligence.mobile`. Its provider, permission and action names use the same prefix, so it can coexist with the previous app while testers migrate. Consumers must use the updated client and manifest declarations below. See [release signing and publishing](docs/RELEASING.md).

## Shared AI Polish

Apps such as keyboards can use Better Intelligence’s selected local model for English proofreading without bundling their own model or inference runtime. The new API supports `status` and `polish` through `ContentResolver.call()`, or `getServiceStatus()` and `polishText()` through Binder. Only successful responses contain replacement text. New consumers require user approval, and memory access is off by default. Text is excluded from access logs and polishing never adds shared memories or uses a cloud fallback.

See [the integration guide](docs/APP_INTEGRATION.md) for the permission, Kotlin client, response states, TypeRight integration pattern and verification commands. TypeRight 215 consumes the approved installed BI service and presents its completed suggestion unchanged for review, with selected-span replacement and exact undo. Consumer apps must implement the connection. Without an app integration, Android’s text selection toolbar and Share menu offer **Polish with Better Intelligence**, with a review step before copying or replacing text.

## Model library

| Model | Download bytes | Estimated RAM (context noted where different) |
| --- | ---: | ---: |
| SmolLM2 135M Instruct Q4_K_M | 105,454,144 | 410 MB |
| Gemma 3 270M Instruct Q4_K_M | 253,115,424 | 553 MB |
| SmolLM2 360M Instruct Q4_K_M | 270,590,880 | 615 MB |
| Qwen 2.5 0.5B Instruct Q4_K_M | 491,400,032 | 786 MB |
| Gemma 3 1B Instruct Q4_K_M | 806,058,240 | 1,138 MB |
| SmolLM2 1.7B Instruct Q4_K_M | 1,055,609,536 | 1,727 MB |
| Qwen 2.5 1.5B Instruct Q4_K_M | 1,117,320,736 | 1,463 MB |
| Qwen 3 4B Instruct 2507 Q4_K_M | 2,497,280,736 | 3,116 MB |
| Qwen 2.5 7B Instruct Q4_K_M | 4,683,074,240 | 5,192 MB |
| Gemma 4 E2B LiteRT mixed precision | 2,588,147,712 | 2,374 MB (text only) |
| Gemma 4 E4B LiteRT mixed precision | 3,659,530,240 | 3,590 MB (text only) |
| Gemma 4 E2B GGUF Q4_K_M | 3,106,738,272 | 3,596 MB |
| Gemma 4 E4B GGUF Q4_K_M | 4,977,171,584 | 5,718 MB |
| Qwen 3 0.6B LiteRT INT4 | 347,251,840 | 894 MB |
| LFM 2.5 1.2B LiteRT INT4 GPU **experimental** | 736,220,768 | ~1.1 GB at 4K (planning estimate) |
| SmolLM3 3B LiteRT INT4 **experimental** | 2,004,633,520 | ~2.9 GB at 4K (planning estimate) |
| Qwen 3 1.7B LiteRT INT4 **experimental** | 977,184,032 | 3,100 MB CPU planning floor; not a device measurement |

These examples include compact quantized GGUF and Gemma LiteRT choices. The complete [pinned catalog](models/library.json) also includes newer LiteRT packages. The primary categories are **Fast: Qwen 3 0.6B**, **Balanced: Gemma 4 E2B**, and **Advanced: Gemma 4 E4B**. **Experimental** separately offers pinned LFM 2.5 1.2B and SmolLM3 3B LiteRT builds. These have publisher-side Android support but are not benchmarked on the HONOR and never replace automatic recommendations. Download and test them explicitly before using them for TypeRight; see [the experiment plan](docs/EXPERIMENTAL_MODELS.md).  see [measured tradeoffs](docs/2.0/reports/README.md) and [publisher sources](docs/MOBILE_MODELS.md). **Find models** searches public Hugging Face repositories or accepts an `owner/repository` URL. **Import GGUF or LiteRT-LM** accepts compatible files downloaded elsewhere, including gated models after obtaining access from their publisher. Downloads and imports appear in **My models**. Imports require a supported single-file text GGUF or compatible `.litertlm` package; split GGUF files and multimodal projectors are not supported.

`models/library.json` and the matching APK asset record pinned upstream revisions, byte counts and SHA-256 checksums. In-app downloads use those pinned files. The four original weights remain in Git LFS; additional weights download on demand and local copies are ignored by Git. All weights are separate from the APK. Run `git lfs pull` for the original four, or `python scripts/download_models.py` (Python 3.11+) for the catalog. The script also accepts one or more library filenames. The Gemma 4 benchmark download script uses a separate directory and verifies all four pinned artifacts.

The 4B and 7B options are for Android devices with substantial free RAM. Their CPU generation can be slow; download size and parameter count alone do not predict quality or speed. **Delete** on a model card removes its private weights with confirmation. An active model is unloaded first; stop any current generation before deleting it. Curated and Hugging Face downloads remain available to download again. Local imports disappear from My models, while the original source file and chat history are preserved.

Chat follows the bottom of an incoming answer, including answers taller than the screen. Scroll up to read earlier text without token updates pulling you away, and tap **Jump to latest** to return to the bottom. Sending a new prompt returns to the latest turn.

Recommendations select from the curated library using supported ABI, current available RAM with a 512 MB reserve, low-memory state, and free storage. Users can download a larger model if storage permits, but activation checks current memory. Memory figures are estimates. Supported ABIs are arm64-v8a and x86_64. GGUF uses llama.cpp CPU/Vulkan; compatible `.litertlm` models use LiteRT-LM 0.18 CPU/OpenCL. Automatic compares compatible CPU/GPU configurations and caches its choice; CPU remains the fallback. GPU memory is shared system RAM. NPU stays unavailable without a matching compiled model and vendor runtime/driver. See [hardware acceleration](docs/HARDWARE_ACCELERATION.md) and [the measured Gemma comparison](docs/LITERT_LLAMA_COMPARISON.md). Only one inference runs at a time, with cancellation, a 180-second generation deadline and at most 1,024 output tokens. Downloads stop if the process is killed; restart them in Models.

SmolLM2, SmolLM3, Qwen and Gemma 4 weights are Apache-2.0; LFM 2.5 is governed by the separate LFM Open License v1.0, with commercial-use conditions. Gemma 3 requires acceptance of its terms and prohibited-use policy in Models before download or activation. Redistributors must retain the notices and model license files under `models/`. See [Gemma terms](https://ai.google.dev/gemma/terms), [Gemma use policy](https://ai.google.dev/gemma/prohibited_use_policy), and [llama.cpp Android build guidance](https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md).

## Use the shared AI service from another app

Install Better Intelligence, download and select a model. Consumer apps declare:

```xml
<uses-permission android:name="com.aistudio.betterintelligence.mobile.permission.BIND_LOCAL_AI_CORE" />
<queries><package android:name="com.aistudio.betterintelligence.mobile" /></queries>
```

Copy both AIDL files from `app/src/main/aidl/com/example/ipc/` and optionally `LocalAiCoreClient.kt` from `app/src/main/java/com/example/ipc/`. Enable `buildFeatures { aidl = true }`. Preserve the AIDL package names. Bind to action `com.aistudio.betterintelligence.mobile.ACTION_BIND_AI_CORE` in package `com.aistudio.betterintelligence.mobile`. Make Binder generation calls on a worker thread; streaming callbacks run on Binder threads.

The first status or inference request creates a pending entry in **Connected Apps**. The user enables its access switch to approve access, and may block or rate-limit it later. Access checks use the caller UID's package, never a package supplied by the consumer. Prompts and responses from external apps are not retained in access logs. Consumers can generate synchronously or stream tokens, summarize, classify, read model readiness and telemetry, or cancel their own Binder inference. Embeddings are explicitly unsupported because these are text-generation models.

`sample-client` is a separate Android app that demonstrates shared AI Polish across processes. It has no INTERNET permission and carries no model weights. Install it after Better Intelligence, launch it, approve **com.example.betterclient** in Apps, then tap **Polish with Better Intelligence**. Its instrumentation test exercises both the provider and Binder from a separate UID, including an attempted package spoof.

## Build and verify

Use JDK 21, Android SDK 36.1, NDK 28.0.12433566 and CMake 3.22.1. Configure `sdk.dir` in `local.properties`. Gradle fetches SHA-256-pinned llama.cpp and Vulkan-Headers archives. Vulkan shader generation also requires a host C++17 compiler and Ninja; Windows builds should use a short BI_NATIVE_BUILD_DIR. See [native build setup](docs/HARDWARE_ACCELERATION.md#build).

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :sample-client:assembleDebug
```

`ModelLibraryTest` checks the pinned catalog, recommendations, download versus activation requirements, available real files and absence of fake readiness. `HuggingFaceCatalogTest` checks immutable URLs, checksums, safe filenames and unsupported-file filtering. `GgufInferenceTest` checks actual offline inference with SmolLM2, Qwen and Gemma, model switching, permissions, rate limits and checksum mismatches. For the device test, install the debug and test APKs, place the 135M, 0.5B, 1B and three new GGUF files in the app's private `files/models/` directory with adb/run-as, disable networking, then run:

```sh
adb shell am instrument -w -e class com.example.GgufInferenceTest com.aistudio.betterintelligence.mobile.test/androidx.test.runner.AndroidJUnitRunner
```

The native engine uses ordinary process memory and Android lifecycle rules. A bound consumer keeps the service alive while connected; installed apps cannot access AI automatically without implementing Binder integration.
