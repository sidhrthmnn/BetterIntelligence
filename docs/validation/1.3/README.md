# Version 1.3 validation

## Scope and changed files

- `LiteRtSession.kt`, `LiteRtEngine.kt`, `InferenceEngine.kt`, `EngineManager.kt`: real LiteRT-LM execution, the shared lifecycle/admission gate, cancellation acknowledgement before release, model-format routing, measured AUTO selection/cache and CPU fallback.
- `HardwareAccelerationManager.kt`, `SmartBackendSelector.kt`, `ModelLibrary.kt`: truthful OpenCL/Vulkan distinctions, cached compatibility, current shared-memory/thermal limits and text-only LiteRT estimates. Private dirty allocations can receive release credit; clean mmap pages are not counted twice.
- `gguf_jni.cpp`: Gemma 4's documented text-only chat format for the GGUF path. Existing CPU NEON/FMA kernels and Vulkan offloading remain packaged.
- `AiCoreRepository.kt`, `ModelDownloadManager.kt`, model catalog/assets and notices: four pinned Gemma 4 choices, checksum/container checks, correct Gemma 4 Apache licensing, shared activation/deletion behavior.
- Binder/provider files: the same AIDL methods and response structure obtain model metadata from either runtime. No AIDL transaction IDs or consumer permissions changed.
- Advanced settings: mode options reflect actual model/backend validation; llama.cpp layer controls are hidden for LiteRT graph execution. Existing icon/theme and keyboard layout remain.
- Gradle/manifest: LiteRT-LM 0.18.0, compatible Kotlin 2.4/KSP build tooling, optional vendor OpenCL access, version 1.3 / code 4. Native ABI/minimum-SDK choices remain arm64-v8a/x86_64 and API 24.
- New physical-device benchmark and integration tests, catalog tests, reproducible download/report scripts, bundle dependency/alignment checks and updated documentation.

## Tests and outcomes

- All **53 JVM/Robolectric unit tests passed** with the updated toolchain and production configuration. Coverage includes hardware resource policy, low-memory/thermal behavior, backend scoring/cache invalidation/cooldown, model memory sizing, lifecycle protection, model catalog and client access/Binder status compatibility.
- **90 measured responses** completed on the physical HONOR phone: E2B and E4B through LiteRT CPU, LiteRT OpenCL GPU and llama.cpp CPU, five prompts × three rounds each. The [comparison](../../LITERT_LLAMA_COMPARISON.md) reports actual timing/output and its small-sample limits.
- Two raw llama.cpp Vulkan benchmark attempts **failed initialization** with `createComputePipeline: ErrorUnknown`. These are recorded as failures, not successful GPU measurements. CPU remained functional.
- `LiteRtIntegrationDeviceTest.sharedModelBinderAutoCancellationAndSwitching` **passed** in 50.003 seconds on the physical phone. It exercised installed-model activation, unchanged Binder methods, correct `102` generation, same-file reuse, AUTO selecting OpenCL GPU, concurrent switch/unload rejection, cancellation with partial output, native mapping release and switching back to GGUF. The integration telemetry records actual GPU backend, zero llama.cpp layers, 36.03 tok/s on the short arithmetic response and 16.711 seconds request TTFT including first-time tuning. Android thermal state was light warm and battery sensor 36°C; no SoC power measurement is claimed.
- `ChatKeyboardTest.composerMeetsKeyboardAndNavigationReturnsAfterDismissal` **passed** on the physical phone, checking single/multiline composer alignment and navigation restoration after keyboard dismissal.
- The validation package was isolated from the user's Play installation. Production chats, models and app data were not deleted or overwritten.

The raw reports and `summary.json` are alongside this file. `integration-telemetry.json` is from the shared app path. `bundle-validation.json` describes the signed release artifact, native dependencies, 16 KB load alignment and digest after bundle inspection.

## Release artifact checks

The final production build (`testDebugUnitTest` + `bundleRelease`) passed, including release lint. Bundletool validation passed. The manifest reports production package `com.aistudio.betterintelligence.mobile`, version 1.3/code 4, compatible Binder/provider permissions and exported addresses, and a non-debuggable application. All eighteen packaged native libraries across arm64-v8a/x86_64 have at least 16 KB ELF load alignment, and all DT_NEEDED dependencies are packaged or platform libraries. Only the optional ggml Vulkan module requires `libvulkan.so`. No model weights are embedded.

The signed AAB is 92,403,732 bytes; SHA-256 is `5b02487223042383079e105d9efeb804efbb587500adb48ce94e30f16532584f`. Jarsigner verifies its signature. Strict verification reports the expected self-signed upload certificate/trust-chain and absent timestamp warnings; this is an upload key rather than a public-CA-signed certificate. Google Play performs its own upload-key validation.

## Interpretation and remaining limits

LiteRT's mobile mixed-precision packages outperform the tested GGUF Q4_K_M packages on this phone, including CPU results (E2B 28.93 vs 10.61 tok/s; E4B 14.51 vs 5.31 tok/s). These are model-package/runtime results, not a controlled same-quantization engine-only experiment. GGUF CPU still uses the existing portable NEON/FMA implementation; no new unverified CPU instruction set is claimed.

Real OpenCL GPU inference is verified on this phone. Vulkan is implemented and packaged but failed on this driver; it must not be described as verified working on this HONOR. Other devices require their own tests. NPU is unavailable without a matching compiled model and vendor runtime/driver. Long-session battery/energy behavior, heavy multitasking, and other physical phones remain unmeasured. Driver memory may be missing from process PSS, so lower GPU PSS is not proof of lower total RAM use. First load/tuning can take seconds; warm latency is separately reported.
