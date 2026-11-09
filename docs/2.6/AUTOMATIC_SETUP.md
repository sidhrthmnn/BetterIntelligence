# Automatic local AI setup

BI keeps llama.cpp, LiteRT-LM, the Rust admission/health policy, Room model records and the existing Binder contracts. No hosted planner, LLM, embedding or reranker is introduced.

## Device check and recommendations

`DeviceIntelligenceChecker` samples Android's available RAM, storage, battery, thermal state and the packaged native runtime probe. Shared GPU memory remains part of system RAM. A Vulkan device probe is labelled as a runtime capability, never as a successful model decode. The existing inference engine performs actual CPU/GPU validation and handles fallback. NPU remains unavailable without its existing compatible runtime/model validation.

`AutomaticModelSelector` filters the curated library and installed instruction models using trusted SHA-256 metadata, model format/runtime eligibility, licence acceptance and the existing context-sensitive memory planner. Parameter counts distinguish M from B. It reuses installed useful models before proposing downloads. Tiny diagnostic models remain manually usable. Preliminary recommendations describe estimates; they do not invent quality or speed measurements.

The approval dialog states the total size and freezes the reviewed model IDs. Only that pair is queued. Wi-Fi is the default; allowing metered data requires an explicit choice. Selected files download from smallest to largest. The first verified file can be loaded and tested for chat while the rest completes, provided no working/manual model or generation is active. A provisional load is not labelled benchmarked.

## System-managed downloads

On Android 14+, a visible user download action schedules a persisted user-initiated data-transfer job. Deferred transfers and older Android releases use WorkManager with network/storage constraints and exponential backoff. Android controls scheduling, battery restrictions and retries; BI does not promise continuous execution after a force-stop. Opening BI checks for missing scheduled work without duplicating existing jobs.

One process-wide queue mutex and per-model job admission prevent duplicate transfers. Queue order, Wi-Fi choice and retry counts are persisted separately from Room's progress state. Notifications show percentage, transfer rate and remaining megabytes, with Pause and Cancel actions once a model is identified. Three failed attempts stop automatic retry for that item; the user can retry explicitly.

The transport retains HTTPS/TLS verification, bounded redirects, HTTP Range resume, restart when a server ignores Range, exact-size validation, storage checks, SHA-256 and format validation. Installation renames the verified temporary file on the same Android filesystem. A working completed file is never deleted before replacement is ready. Bad hashes/formats discard invalid partials; network interruption and low storage retain resumable bytes.

**Pause** keeps the partial file. **Delete partial download** removes its queue entry, cancels and joins the transfer, then deletes only the canonical private `.part` file. It resets the download state and keeps any completed installation, active context and chats. The UI confirms this distinction before deletion.

## Real model checks and lifecycle

`ModelQualityBenchmark` runs ten actual generations: nine reproducible quality probes and a repeated reasoning probe for stability. Checks cover facts, exact instructions, JSON, reasoning, summarisation, supplied evidence, unknown observations, context recall and conversational continuity. They are constrained checks, not a general intelligence score or a model self-rating.

Reports contain observed loading time, mean first-token time, prompt/decode throughput, peak observed process memory, active backend, thermal label and check outcomes. No private chat text is saved. Cache identity includes device fingerprint, model checksum, quantisation, llama.cpp/LiteRT/QNN runtime versions, suite version, fixed benchmark context, requested mode/layer limit and the observed backend, CPU threads and GPU layers. A cached GPU/NPU result is not reused when that backend is unavailable. Existing backend selection also has its own model/configuration-specific performance cache.

Quality receives 85% of selection weight, bounded speed 10% and successful repeated stability 5%. Automatic candidates must score at least 65 and pass the core factual, reasoning, supplied-evidence and repeated stability checks; a measured secondary must also use less observed memory. Repeated consistency is measured separately from mathematical correctness: an identical wrong answer is stable but still fails the reasoning/qualification check. Correct refusals, short contextual replies and valid fenced JSON are recognised. An incomplete/failed benchmark is never saved as a completed measurement and does not prevent trying the other candidate. Cached failed candidates are excluded from a fresh automatic recommendation; manual use and an explicit rerun remain available. If no candidate passes, the previous selection is kept and the user can choose manually.

Each measured model is explicitly unloaded before timing a fresh native load, so an already-ready context cannot masquerade as a cold model load. OS filesystem caches can still be warm. Approval/cancellation epochs prevent delayed setup work from overriding a later manual choice, including immediately before committing new pair preferences.

Benchmarks defer under insufficient RAM, battery or thermal headroom. The repository's loading lease and resolution mutex serialize setup, manual selection and client preparation. Previous models are restored during cancellation, including WorkManager cancellation. Manual selection cancels pending automatic validation and wins over the automatic decision. Pair preferences are written atomically only after successful activation and decode. Existing Rust failure cooldowns, primary recovery and generation admission remain in charge of runtime fallback; a web failure does not mark a model unhealthy.

## Local request planning and retrieval

Complex public research requests can use a short generation by the already loaded local model to propose JSON queries. A six-second limit, strict field/intent bounds, credential checks, exact entity/version retention and public-query validation bound this optional stage. Invalid or slow planning uses the existing deterministic plan. Private/local/offline routing and web permission are decided first; the planner cannot override them or cause a model download. Planning failures do not count as chat model health failures.

Numbered identities are matched as adjacent phrases, so a page about a previous sequel with a coincidental matching star rating does not satisfy the requested sequel. Comparison sources can still cover different requested subjects separately. BM25-style lexical relevance augments existing coverage, provenance, temporal and source-diversity ranking. Existing extraction, bounded research recovery, claim verification and partial-answer recovery are retained.

Advanced Web settings optionally import a user-selected BERT-family GGUF encoder no larger than 128 MB. It must pass a real native self-encoding check before installation. Encoding uses a bounded CPU context, at most eight admitted passages and a separate RAII-managed native model. It never replaces the chat context, returns to lexical ranking on contention/resource/runtime failure, and cannot override exact identities or establish factual support. No encoder is silently downloaded. The CPU path was validated with one 67 MB BGE-small GGUF on the Samsung test phone, including passage ordering and native resource cleanup. BERT uses llama.cpp's decode graph and the file's pooling metadata, rather than the T5 encoder API. Other encoder files require their own compatibility checks; absence of a model is a fully functional lexical configuration.

Model files, encoder files, device/setup state and download consent are excluded from cloud/device backup. Session memory remains in the existing app-private conversation system. Binder/AIDL methods and defaults remain compatible. One explicit client preparation request now waits for repository startup to finish and then prepares the model once; subsequent status polling remains read-only. Concurrent clients share that preparation, and an intervening active chat prevents background loading.

## Reproducible validation

- `AutomaticSetupTest`: preliminary/actual separation, M/B parameter counts, unsafe resources, unsupported formats, queue persistence, retries, quality constraints, cache identity and validated planning.
- `ResumableDownloadTest`: interrupted stream, Range/ignored Range, hash failure preserving an old installation, low-storage retention and recovery.
- `UniversalAnswerEvaluationTest`: 120 frozen cases spanning exact numbered entities, versions, current/local routing, evidence-backed recovery and follow-up identity. Run the identical fixture runner on the previous main revision and the changed revision. These results measure deterministic pipeline checks, not end-to-end LLM factual accuracy.
- `AutomaticSetupDeviceTest`: explicit opt-in validation-package UI consent/deletion checks, real two-model generation/telemetry, manual restoration, and a staged real transfer across process recreation.
- `SimpleUiDeviceTest`: eleven destinations in light and AMOLED modes.

Example commands (use an isolated validation package and already obtained model files):

```text
gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest -PbiValidationPackage=true -PbiIpcValidation=true
python scripts/build_rust_core.py --test
adb shell am instrument -w -e automaticSetupUi true -e class com.example.AutomaticSetupDeviceTest#setupConsentAndPartialDeletion com.aistudio.betterintelligence.mobile.validation.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e automaticNative true -e class com.example.AutomaticSetupDeviceTest#actualNativeQualityAndManualRollback com.aistudio.betterintelligence.mobile.validation.test/androidx.test.runner.AndroidJUnitRunner
```

Actual run outcomes and limitations belong in the release validation record. Emulator results do not establish GPU/NPU hardware performance. No universal factual-accuracy improvement should be inferred from these small model probes.

Android references: [user-initiated data transfer jobs](https://developer.android.com/develop/background-work/background-tasks/uidt), [WorkManager long-running workers](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running), [WorkManager releases](https://developer.android.com/jetpack/androidx/releases/work).

Measured outcomes and limitations: [version 33 validation](VALIDATION.md).
