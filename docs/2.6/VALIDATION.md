# Version 33 — automatic local AI setup

Validated on 9 November 2026 against main revision `ba336ec`. Version: **2.6.0-beta1 (33)**. All AI inference, request planning and optional embedding computation stay on the device. Internet access is used for permitted discovery, source retrieval and approved model downloads.

## Changes and defects corrected

- Added a device-aware setup screen, preliminary primary/backup recommendations, explicit download approval, and real quality/performance checks before automatic activation. Manual selection wins; failed or incomplete candidates cannot replace a working selection.
- Added an Android-managed persistent transfer queue with Wi-Fi controls, notifications, retries, pause/resume, storage checks, checksum verification and atomic installation. **Delete partial download** cancels the transfer and deletes only unfinished bytes, preserving completed models and chats.
- Tightened exact numbered-title and model/version identity throughout planning and ranking. The previous match could accept a different sequel when an unrelated rating happened to contain the requested number.
- Added bounded local query planning and hybrid lexical ranking, with an optional explicitly imported small BERT GGUF encoder. Existing web permissions, extraction, evidence verification and recovery remain in place.
- Fixed cold-start client preparation: the previous implementation returned a loading status without scheduling the requested preparation. An older client polling status subsequently remained at `MODEL_REQUIRED`. A single explicit request now waits for startup and prepares once; status polling itself remains read-only.
- A real encoder test exposed an incorrect assumption that BERT should use llama.cpp's T5-style encoder API. The implementation now uses BERT's decode graph and model pooling metadata, with independent bounded resources and lexical fallback.

The principal files are `DeviceIntelligenceChecker.kt`, `AutomaticModelSelector.kt`, `AutomaticSetupController.kt`, `ModelQualityBenchmark.kt`, `ModelDownloadQueue.kt`, `AutomaticSetupScreen.kt`, `LocalEmbeddingRanker.kt`, `LocalRequestPlan.kt`, `ClientModelPreparation.kt`, `AiCoreRepository.kt`, `AiCoreViewModel.kt`, `ModelsScreen.kt`, research query/ranking modules, Android scheduling/backup configuration and the native GGUF bridge. See [architecture](AUTOMATIC_SETUP.md) for lifecycle, consent and cache details.

## Automated validation

| Check | Result |
| --- | --- |
| Kotlin unit suite | 382 cases; 0 failures, 0 errors, 1 intentional opt-in skip |
| Rust host tests | 31 passed; formatting and clippy passed |
| Android debug app and instrumentation APK | Built successfully |
| Android debug lint | Passed, 0 errors and 136 warnings (including existing localisation warnings) |
| Production signed bundle and release lint | Passed; lint 0 errors/136 warnings, signature and bundletool validation passed, both ABI library sets aligned to 16 KB |
| Frozen baseline/current fixture comparison | 60/120 → 120/120 |

The frozen comparison uses identical public synthetic inputs on `ba336ec` and this change: numbered identities 0/40 → 40/40, local/current routing 40/40 → 40/40, evidence/citation recovery 20/20 → 20/20, and follow-up title identities 0/20 → 20/20. A numbered-identity case requires accepting the correct subject and rejecting the wrong edition. These are deterministic pipeline assertions, **not measured end-to-end LLM factual accuracy**. Fixture timings measure JVM checks, not mobile answer latency. Raw results: [baseline](evaluation-baseline.json), [current](evaluation-current.json).

Unit coverage includes memory/thermal/battery admission, licence/runtime eligibility, M/B parameter counts, useful backup selection, approval/cancellation epochs, cache invalidation, planning identity validation, persistent queue recreation/retry, interrupted streams, Range and ignored-Range handling, hash failure preserving an older complete installation, and low-storage retention/recovery. Low storage was injected; the user's device was not filled deliberately.

## Physical phone and emulator

Tests used a separate validation installation on a Samsung SM-A528B running Android 14. The normal Play Store installation, its models and chats were preserved. A separate API 36 x86_64 emulator provided the download interruption checks.

| Scenario | Observed result |
| --- | --- |
| Setup consent, cancellation, stale approval and changed-checksum rejection | Passed on phone and emulator |
| Light and AMOLED navigation | 11 destinations in each theme passed; screenshots inspected |
| Real partial transfer → process termination → app recreation | Completed 105,454,144 bytes; trusted checksum verified; queue and partial file cleared |
| Delete an actual paused network download through the UI | Deleted 1,132,689 unfinished bytes; completed installed model retained and verified |
| Native model reuse, two thread settings, history isolation and unload cleanup | Passed on phone |
| Legacy cross-UID ContentProvider and AIDL client | Passed after cold-start preparation fix, 6.825 seconds |
| Optional imported local encoder | Real BGE-small GGUF CPU execution, relevance ordering and resource cleanup passed |

The real transfer exercised Android's WorkManager foreground path after recreation. This establishes successful safe completion, not proof that the live server used HTTP 206 in that run; mocked transport tests separately verify Range and restart behavior. Scheduling remains subject to Android background restrictions and user force-stop behavior.

The encoder was a 67,308,128-byte [GGML BGE-small test file](https://huggingface.co/ggml-org/models/tree/main/bert-bge-small), obtained only for validation, never bundled or silently downloaded by BI. Its SHA-256 is recorded in [device validation](device-validation.json). For a public synthetic photosynthesis query, the relevant passage scored 4.430 and an unrelated football passage 1.878 (cosine similarity scaled by five). This verifies one compatible file and the native path, not all embedding models or a factual confidence score. Imported test resources were removed after validation.

## Actual local-model measurements

All values below are measured on that phone using CPU, four threads and zero GPU layers. Peak memory is observed process memory; load timings explicitly reload the model, although OS filesystem caches may remain warm. Throughput and first-token values are means over the small reproducible suite, not sustained production benchmarks.

| Model | Quality checks | Cold load | First token | Decode | Peak process memory | Automatic qualification |
| --- | --- | --- | --- | --- | --- | --- |
| SmolLM2 360M Q4_K_M | 65/100 | 167 ms | 945 ms | 45.14 tokens/s | 587 MB | Failed required reasoning check |
| Qwen2.5 0.5B Q4_K_M | 70/100 | 445 ms | 1,005 ms | 33.46 tokens/s | 861 MB | Failed required fact/reasoning checks |

Both repeated consistently and supplied useful evidence/context answers, but produced incorrect answers on some core probes. BI therefore did **not** claim a benchmark-verified pair or automatically promote either candidate; manual choice remained available and was restored. Results are observations of these files/settings, not claims about larger models. [Measured reports](model-benchmarks.json).

A third optional Gemma 3 1B candidate was correctly skipped because Gemma terms had not been accepted in the isolated validation app. No licence acceptance was fabricated or bypassed, and no Gemma result was invented. Larger models, LiteRT candidates and sustained battery measurements remain untested in this release's benchmark run.

The phone's native runtime reported NEON/FMA/repacking support. CPU two/four-thread probes completed with proper history isolation and cleanup. The packaged Vulkan probe reported **no hardware Vulkan 1.2 compute device with 16-bit storage** on this phone. No GPU generation was claimed. NPU performance was not tested; an actual compatible runtime, model and device validation remain required. Existing CPU/GPU fallback remains functional independently of optional NPU/encoder availability. [Backend checks](backend-checks.json).

## Limits

- This release does not establish a universal increase in factual accuracy or that any small model is best for every device. Failed quality checks are visible instead of being relabelled successful.
- No new live-search-provider comparison was run for this task. Frozen research regression checks passed; earlier discovery/extraction and source-integrity safeguards are preserved.
- Benchmarking defers under memory, battery or thermal pressure. A candidate that cannot safely finish is incomplete, not measured.
- Positive GPU/NPU and other encoder/model compatibility require appropriate physical hardware and files. There is no simulated acceleration.
- Publishing status, final artifact digest and commit IDs are recorded separately after the closed-beta submission. Review submission does not mean Google has approved or published the update.

Release artifact and submission: [version 33 release record](release-v33.json), [bundle validation](bundle-v33-validation.json). Play Console showed **Changes in review** for **Closed testing � Alpha, 33 (2.6.0-beta1)**, with quick checks running and managed publishing off. Submission is queued for review after those checks; approval/publication was not waited for.
