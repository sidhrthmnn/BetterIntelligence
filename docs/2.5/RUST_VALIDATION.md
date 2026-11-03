# Rust intelligence core: version 26 validation

Validated on 3 November 2026. Version **2.5.0-beta1 / 26**. Implementation/build revision: `8e688947d0d336f47f9cb1ccdef71e0d744a181c`; subsequent commits adjust test workflows and documentation only. See [architecture and rollback](../RUST_ENGINE.md), [pre-implementation audit](../RUST_MIGRATION_PLAN.md) and [sanitised measurements](rust-device-validation.json).

## Delivered scope and files

| Area | Implementation |
| --- | --- |
| Rust library | `rust-core/src/{lib,android,errors,streaming,memory}.rs`, `engine/{orchestrator,recovery}.rs`, `search/{planner,ranking}.rs`; locked Cargo dependencies/toolchain |
| Android bridge | `RustIntelligence.kt`, `RustModelPolicy.kt`; bounded versioned JSON/JNI, health outcomes, mode/feature flags, legacy rollback |
| Actual model lifecycle | `AiCoreRepository.kt`, `InferenceEngine.kt`, `ModelMemoryPlanner.kt`, `LocalAiAccess.kt`; primary identity, safe recovery, actual activation checks and AI-polish request preparation |
| Context, research and UI delivery | `Daos.kt`, `ConversationContext.kt`, `AnswerRoutingPolicy.kt`, `WebPageExtractor.kt`, `LlamaCppEngine.kt`, `AiCoreViewModel.kt`; bounded retrieval, metadata-only selection, cached passage scores, cancellation and batched drafts |
| Build/release | `build_rust_core.py`, Android Gradle and CI workflow, bundle validator; both existing ABIs, dependency notices, unchanged signing/app identity |
| Tests | Rust policy suite, JVM regressions, real-JNI device suite, isolated cross-UID sample client and existing research/navigation/keyboard regressions |

llama.cpp and LiteRT-LM remain the inference runtimes. Rust owns policy and admission, not a second model or new inference kernels. Room schema 9, existing downloads/model files, Compose navigation and AIDL/ContentProvider API version 2 remain compatible. Production app data was preserved: all phone tests used the separate `.validation` package, synthetic chats and an isolated approved client.

## Test outcomes

| Check | Final outcome |
| --- | --- |
| Rust locked host suite | **29 passed**, no ignored/failed tests; rustfmt and Clippy with warnings denied passed |
| Android JVM suite | **256 passed**, 0 failures/errors across 41 suites |
| Android build | Debug app/test APKs, signed production AAB/APK and release lint passed |
| Real Rust JNI and native inference | **9 phone tests passed**: bounded/malformed protocol, contention/ownership, cancellation/stale chunks, real health recovery, route/context/extraction parity, Room reopen/delete/isolation, offline GGUF generation/reload and measurements |
| Live Gemma research through chat UI | **1 scenario with 6 questions passed**: reported Bond casting, Jubilee service status, named-line follow-up, saved raincoat/city/“OK” continuation, direct weather and Doom casting |
| Separate Android UID | Unapproved provider/Binder calls returned `APPROVAL_REQUIRED`; approved real provider and Binder polishing returned `SUCCESS`, nonempty text and `intelligence_engine=RUST`; setup ran only in the validation DB |
| Existing UI regressions | Both navigation/theme/draft/process-recreation tests passed; real keyboard inset test passed |
| Release packaging | bundletool validation, JAR signature verification and project bundle validator passed; **20 libraries** across ARM64/x86_64, all load segments aligned to at least 16 KB; real Rust JNI export present in both libraries |

No failing test was silently removed. The initial streaming-cancellation test reserved all 512 context tokens for output and correctly failed before generation; its output limit was corrected to 256 and actual job cancellation is asserted. An existing research assertion rejected the correct cited wording “Jubilee Line status is good”; it now accepts that paraphrase while explicitly requiring the requested line and retaining source-scope assertions. The new client test initially restarted `prepare_polish` on every poll; it now starts once and polls `status`, matching the documented contract. All were rerun successfully. A classifier regression also ensures “input” is not mistaken for an NPU fault.

## Measured performance, not estimates

HONOR VKP-NX9, ARM64, 11,238 MiB reported physical RAM. Same-build internal LEGACY/RUST controls; this is **not** a controlled comparison with the installed v25 binary. The Rust library remains resident in both modes. Battery, OS scheduling and JIT activity are not controlled.

SmolLM2 135M Instruct Q8_0, llama.cpp CPU, 4 threads, 512 context, temperature 0, 16 output tokens, one warmup then six interleaved requests (3 per mode). All six outputs were identical.

| Metric | LEGACY | RUST |
| --- | ---: | ---: |
| Median generation speed | 236.17 tokens/s | 237.42 tokens/s |
| Median first-token latency | 48 ms | 53 ms |
| Median request elapsed time | 137 ms | 137 ms |
| Sampled process PSS range | 386.4–436.2 MiB | 390.4–432.5 MiB |

Model-load wall time was 293 ms in the final run. These short samples establish output parity and broadly comparable CPU generation, not a statistically significant speed or memory improvement. The 135M fixture is for lifecycle/performance testing, not a claim of high-quality proofreading.

For synthetic 101-paragraph HTML, two blocks of 20 measured extractions per mode followed four warmups per block. LEGACY block medians were **378.28 and 192.02 ms**; RUST medians were **366.07 and 368.96 ms**. This order/JIT-sensitive variance does **not** establish an extraction speedup; the late legacy block was faster. Relevant excerpt text was identical in every sample. JNI `info` median was **12.76 μs** over 200 calls; one policy-test process PSS sample was **356.5 MiB**. These are not cold initialization or idle-memory baselines.

Six full Gemma 4 E2B LiteRT CPU questions reported approximately **22.1–25.0 tokens/s** with complete persisted answers and retrieved citations. Stored answer latency fields were 3.4–7.4 seconds; they do not isolate HTTP latency or constitute an end-to-end search benchmark. Manual review confirmed the named-line follow-up and the official Disney Doom casting source. Reported Bond casting remained qualified as unconfirmed. Weather included a dated forecast/high with °C. Some answers remained partial and the raincoat continuation supplied forecast information without an explicit clothing recommendation; this migration does not solve all answer-synthesis limitations.

The signed AAB is **91,867,908 bytes**, versus **90,488,570 bytes** for v25 (+1,379,338 bytes, including Kotlin/build metadata, notices and both Rust libraries). Rust libraries are 530,336 bytes ARM64 and 600,072 bytes x86_64 uncompressed, approximately 572 KB compressed combined. Native dependencies are platform `libdl.so`/`libc.so`; no additional model weights or cloud SDK is bundled.

## Limits and follow-up

- This is a functional, incremental Rust policy migration. HTTP transport, HTML parsing, semantic feature extraction, citation verification and Room/SQL persistence remain in the established Android components. No fake Rust scraper, cloud provider or SQL wrapper was added.
- Hardware selection, mmap/KV-cache management and accelerator fallback remain owned by the existing runtimes. This migration was exercised on CPU; Vulkan/QNN throughput and optional Qualcomm NPU execution were not revalidated. Normal Play builds retain QNN disabled.
- Cooldowns/health are bounded and process-local. Recovery is checked at safe requests, with no battery-waking probe timer. Network/auth/quota variants have typed host policy tests; no cloud model provider is configured or claimed as integrated.
- No controlled cold-start, Rust-initialization, isolated HTTP/chat-retrieval, battery/power, long-duration thermal or population crash-rate comparison was collected. No universal speed, factual accuracy or leak-free-driver guarantee is made.
- Exa/public publisher availability and extraction quality remain external constraints. Existing robots, private-network, redirect and citation protections remain enforced.
- Rollback uses the documented component flags or `biRustCore=false`; run the same regressions and increment Play versionCode before release.

Physical reproduction uses the opt-in `RustIntelligenceDeviceTest` (`rustInference=true`) with the checksum-verified 135M file staged only in the validation package, `ResearchFollowThroughDeviceTest` (`researchFollowThrough=true`) with its Gemma E2B file, and the separate sample client built using `biValidationPackage=true`. Never clear or overwrite the production installation to reproduce these tests.
