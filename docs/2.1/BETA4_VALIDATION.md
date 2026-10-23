# 2.1.0-beta4 / version 9 validation

Validation date: 23 October 2026. Implementation details and source investigation: [chat reliability](CHAT_RELIABILITY_BETA4.md).

## Artifact and automated checks

Signed production package: `com.aistudio.betterintelligence.mobile`, version code **9**, min SDK 24, target SDK 36.

Bundle: `BetterIntelligence-2.1.0-beta4-v9.aab`, 90,293,053 bytes.
SHA-256: `2C1589A05BE804D5EACED458DB5F7DDE4848754563CD7F9262F4FF1BC641ED87`.

- Debug app, native JNI and instrumentation APK compiled successfully.
- **119 JVM/Robolectric tests, zero failures/errors**, including malformed output/literal code examples, conversation summary persistence and isolation, context budgets, research policy/robots/weather, chat layout at 320 dp and 150% font, sources expansion and scrolling transitions.
- Signed `bundleRelease` and `lintRelease` passed. Bundletool validation passed; manifest package/version verified. JAR signature verified with the expected self-signed upload certificate.
- Final test-fixture-only build passed; production sources and bundle remained unchanged.
- Binder fixture initially retained a stale startup catalogue snapshot. It now waits for both staged models to be verified installed; the rerun passed. No production model-loading workaround was introduced for this fixture issue.

## Physical device evidence

Device: HONOR VKP-NX9, Android 16, 12 GB physical RAM. Tests ran in the separate `.validation` package, preserving the installed Play app and its user data. No emulator result is used as GPU evidence.

| SHA-verified model / backend | Samples | Mean first token | Mean generation rate | First initialization |
| --- | ---: | ---: | ---: | ---: |
| Gemma 4 E2B / CPU | 8 | 705.5 ms | 22.75 tokens/s | 6,076 ms |
| Gemma 4 E2B / OpenCL GPU, FP32 | 8 | 241.5 ms | 33.09 tokens/s | 10,236 ms |
| Gemma 4 E4B / CPU | 8 | 1,580.4 ms | 14.75 tokens/s | 13,236 ms |

These are measured short deterministic probes of different output lengths, two rounds of four prompts. First-token time excludes initialization. They are not sustained-throughput, battery, thermal or general model-quality benchmarks. E2B GPU individual generation rates ranged from 7.43 to 38.54 tokens/s. This is LiteRT OpenCL graph execution, not llama.cpp Vulkan layer offloading.

Each session checked arithmetic (`17 * 6 = 102`), missing-city weather clarification, literal special-token examples, same-chat history, streaming/final equality, cancellation, successful recovery and release. E2B CPU also fetched real Open-Meteo data for London. E4B GPU was skipped with approximately 4.75 GB available versus approximately 5.1 GB estimated including FP32 headroom/reserve, as authorized by the user; it is **not verified**.

Additional phone checks:

- Chat product, public research and keyboard tests: **3 passed**. Verified draft/regeneration/edit behavior, separate-chat isolation, cancellation, cited IANA evidence, private-network refusal, web-off behavior, restored history and composer/IME gap within 3 dp.
- Shared-engine/AIDL integration: **1 passed**, including synchronous and streaming generation, minimum context allocation, same-model reuse, five exact text-polishing fixtures, AUTO telemetry, cancellation while unload/switch is refused, native model mapping release, and switch to SmolLM2 GGUF. Existing Binder interface is unchanged.
- Process-death restoration: prepare and restore phases both passed with different PIDs. Draft, interrupted partial response, active conversation, appearance, approved synthetic client and selected model survived an actual force-stop/restart. The fixture removed its synthetic conversation/policy afterward.
- Separate Room reopen tests verify the new per-conversation summary fields persist and remain isolated.

Raw synthetic test reports and build logs are retained locally under `D:\Codex\bi-beta4-*`. Full-screen phone captures containing unrelated notifications are intentionally excluded from the repository and release material.

## Limits and interpretation

The user's original malformed native decode did not recur in 12 baseline CPU and 12 baseline GPU samples. The absence of output validation was confirmed; the underlying intermittent native cause remains unproven. Typed content extraction, FP32 on the pinned verified Gemma GPU packages, central rejection and safe recovery harden that path, but do not guarantee every model answer is correct.

In particular, E4B CPU produced a clean but factually incorrect explanation of a literal `<unused15>` example (calling it a Devanagari character). Arithmetic, history and missing-city checks passed. Output validation detects malformed syntax/repetition, not arbitrary factual hallucinations.

LiteRT 0.18.0 has no public Kotlin token-count API; its context admission uses a conservative byte estimate. GGUF uses its real loaded vocabulary plus a template reserve. NPU availability and supported model formats are unchanged; this update does not claim new NPU support.

Research follow-up resolution and publisher relevance/robots parsing are bounded heuristics. Cited pages can still be inaccurate. Weather uses timestamped modelled public data for a typed city, without GPS; the free API's non-commercial terms apply. No sustained thermal or energy measurement was collected for this update.

## Closed beta release

Release name: **CB4 - Reliable chat and conversation memory**. Track: **Closed testing / Alpha**. Version 9 replaces version 8 for the existing tester audience; no supported devices were removed. Google Play accepted the submission on 23 October 2026 and displayed **Changes in review**, with automated quick checks still running. Managed publishing is off, so publication follows successful checks and approval. Publication itself was not awaited or claimed.

Play's only release warning was the absence of a deobfuscation mapping; R8 obfuscation is disabled. Native debug symbols are attached. Local submission proof: `D:\Codex\bi-beta4-closed-beta-submitted.png`.
