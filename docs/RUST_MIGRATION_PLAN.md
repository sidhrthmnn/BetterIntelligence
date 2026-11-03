# Rust intelligence core migration

Audit baseline: `e2bfb85`, pulled from main before implementation. Planned release: version 26. This is an incremental migration of policy and bounded processing, not a replacement of the Android app or inference kernels.

## Existing architecture

The owned source tree, build/CI scripts, model catalogues, tests and integration documentation were inventoried. The critical loading, inference, research, conversation, persistence, streaming and client paths were reviewed before changing application code.

- Kotlin/Jetpack Compose owns the chat-first UI, navigation, settings and lifecycle. `AiCoreViewModel` prepares a turn, routes research, budgets context, streams a draft, validates evidence and persists completion/interruption.
- Room schema 9 owns models, conversations/messages, extractive session summaries, optional long-term memories, client policies and metadata-only diagnostics. DataStore holds settings and distinct configured primary/secondary IDs.
- `AiCoreRepository` selects memory-compatible models; `ClientModelPreparation` shares a single asynchronous preparation among Binder/ContentProvider clients. `EngineManager` facades share one `InferenceEngine`, process-wide native ownership and a lifecycle lock.
- GGUF uses pinned llama.cpp v0.7.0 via C++ JNI, mmap weights and real CPU/optional Vulkan. LiteRT-LM 0.18.0 handles compatible text packages and validated OpenCL graphs. An opt-in, device/model-specific Qualcomm QNN build exists; it is disabled in the normal Play build. GPU/NPU capability and measured compatibility remain platform/runtime responsibilities. Shared RAM is already modelled; it is not expanded by offloading.
- Research uses only Exa anonymous MCP discovery, then the existing bounded OkHttp/Jsoup publisher reader. DNS/redirect public-address checks, robots policies, deadlines, citation checks, source reports and consent 6 already work. DuckDuckGo/Mwmbl survive only as historical attribution, not active providers.
- Context is bounded complete turns plus attributed extractive notes. Optional memories and external clients have independent permission checks. No cloud inference provider exists. The current app is text-only; it does not expose image attachments or enabled multimodal inference. The migration must not invent those capabilities.
- Coroutine jobs and native observers implement cancellation; chat changes join generation before ownership changes. The Flow adapter currently ignores failed `trySend`, which can silently lose chunks under backpressure. UI processing currently rebuilds/re-evaluates the entire growing draft on every callback.

## Concrete issues and boundaries

1. `preferLoaded` replaces the configured primary with the active fallback before selection. Repeated keyboard requests therefore cannot recover to the actual primary. There is no per-model health/cooldown policy; startup catches broad failures and may hide their cause.
2. The selector can return a primary decision without activating it when recovery headroom is insufficient. The decision/context/model label can then disagree with the actual engine. Concurrent selection/preparation and explicit changes need a shared resolution boundary.
3. Load failures do not have typed transient/permanent handling. Busy/cancellation/context errors must not poison model health. Backend failures already handled successfully inside native inference must not blacklist the whole model.
4. Public lookup routing is deliberately broad after earlier missed-research failures. Polite writing/explanation requests and small conversational queries need coverage so freshness words inside a writing task do not force a search. LLM self-confidence is not a reliable classifier.
5. Long-term prompt assembly iterates every active memory before later truncation. Bound selection before string assembly. Existing chat storage and deletion remain authoritative; do not introduce another database.
6. Research success does not mean every generated claim is supported. Preserve the existing publisher extraction and citation verification, including incomplete source reports. Replacing the entire network/HTML stack during a language migration would risk previous fixes.

## First production slice

Create `rust-core` with a versioned JNI JSON boundary, strict size/count limits, panic containment and typed errors. Rust owns model health/recovery decisions, request admission/cancellation state, final query-route policy and bounded context/passage index selection. Kotlin remains the Android/runtime adapter: it supplies verified candidates, actual memory/thermal compatibility, semantic intent signals and bounded scoring metadata, executes load commands through existing llama.cpp/LiteRT code, and reports outcomes. No Rust model weights, second inference instance, cloud model or new search provider are introduced.

Recover the primary only at an idle request boundary, after a bounded cooldown and sufficient measured RAM headroom. Probe on eligible requests rather than waking a timer or loading models in background. Keep the configured primary separate from the active model. Serialize selection/activation, retry only bounded transient failures, and never switch mid-stream. Missing health status means uninitialized, not permanently unavailable. Use monotonic time and bounded health/diagnostic tables containing no user text or paths.

Retain Room/DataStore, OkHttp/Jsoup and actual runtime hardware selection. There is no reason to add Tokio, Reqwest, SQLx or another HTML parser merely to duplicate these working adapters. Do not add empty cloud/scraper/storage implementations. Full Rust HTTP/HTML, persistence and direct llama C-FFI ownership are deferred until parity and a concrete benefit can be established. This release will identify precisely which modules are active.

## Build, rollout and verification

Build Rust for existing `arm64-v8a` and `x86_64`, Android API 24, with at least 16 KB ELF alignment. Lock toolchain/dependencies; Gradle and CI must build/package the library. Internal LEGACY/RUST modes and per-component flags permit rollback. If the Rust library cannot initialize, use the existing implementation with a text-free diagnostic rather than breaking chat. Never bypass Kotlin privacy/permission or native memory/hardware checks.

Run Rust tests (health/recovery, transient/permanent failures, missing status, quotas/cooldowns, cancellation, bounded selection and protocol errors), the complete existing JVM suite, JNI/device integration, actual offline local inference, cancellation/model reuse and Binder compatibility where available. Use isolated validation packages; preserve the installed Play app and its data. Compare deterministic workloads and short on-device samples against legacy mode. Record measured versus unmeasured metrics; claim no kernel speedup or GPU/NPU execution from this policy migration.

Final documentation will include bridge/build/rollback details, implementation status, validation results and measured limits. Push to main and submit the tested signed next-version bundle to the existing closed Alpha track as explicitly requested; do not wait for Google publication.
