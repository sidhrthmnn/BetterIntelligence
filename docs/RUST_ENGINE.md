# BI Rust intelligence core

Version 26 / 2.5.0-beta1 adds a production policy core, retaining the Android app and proven native inference. See the [audit/migration plan](RUST_MIGRATION_PLAN.md).

## Ownership and active modules

```
Compose / existing Binder and ContentProvider API
  → Kotlin intent, permission, hardware and model-file adapters
  → Rust policy (versioned JNI)
  → Kotlin load/generation commands
  → existing llama.cpp C++ JNI or LiteRT-LM
```

Rust does not implement matrix multiplication, tokenization, sampling, Vulkan kernels or NPU delegates. It does not own another model instance. Existing native ownership and Android lifecycle locks remain authoritative. Compatible installed GGUF/LiteRT files, CPU/GPU fallback, hardware validation and model downloads retain their existing implementation.

Active Rust components:

- `engine/orchestrator.rs`, `engine/recovery.rs`: bounded per-model health, primary/fallback choice, cooldowns, retry eligibility, recovery headroom and text-free counters. The configured primary is distinct from the active model. Missing status starts uninitialized. A fallback cannot be reported as the primary, nor can a selected decision claim a model that was not actually activated.
- `streaming.rs`: one process-wide admission lease, owner/request IDs, ordered chunk acceptance, cancellation fencing and explicit cleanup. Cancellation holds the lease until native cleanup finishes; an old request cannot release or append to a newer one. Native coroutine cancellation remains responsible for stopping decode. Kotlin's Flow adapter now reports backpressure failure instead of silently dropping chunks, and UI draft work is batched to at most 25 updates/second, with completion/error flushes.
- `search/planner.rs`: final route policy from Android's existing semantic intent/consent signals. Private input, offline instructions and calculations cannot be overridden. Polite writing requests remain local even when their supplied text mentions current events. Public/current questions retain the existing research and single uncertainty-recovery path.
- `search/ranking.rs`: bounded greedy passage selection with facet/subject coverage and stable ties. Android computes semantic scores once per passage rather than repeating the regex/scoring work on each greedy round. Only numeric scoring metadata crosses JNI; source text, headings, dates, qualifiers, URLs and citation checks stay in the established reader.
- `memory.rs`: bounded index selection for extractive session context and optional long-term memories. Android retrieves bounded recent/relevant rows, including older keyword matches, and keeps original attribution. Selected complete entries fit the prompt budget before string assembly. No conversation text or second database is stored in Rust.

## Model recovery

At idle chat and AI-polish request boundaries, Android supplies memory-compatible primary/secondary candidates and real resource limits. Rust prefers the primary, stepping down context through the existing planner first. A working fallback is retained during cooldown or when restoring a larger primary lacks 192 MB of additional headroom. This hysteresis cannot reject the only fitting model. No model is loaded merely to poll status, and no background timer wakes the phone to probe recovery.

Loader I/O gets at most one short retry; memory, corrupted/missing files, unsupported capability, cancellation and busy/context errors do not trigger that retry. Per-model transient backoff grows from about 2 seconds to at most 60 seconds with bounded jitter; explicit rate-limit delays cap at 5 minutes. Memory failure uses a longer cooldown and may recover early only after a material increase in available budget and a minimum pause. Explicit model selection resets that model's health; changed model identity does not reuse an old failure. Health records cap at 64.

Runtime failures feed the same policy for a subsequent safe request. Busy/cancellation/context overflow are neutral. Accelerator recovery remains owned by the proven runtime and does not blacklist the entire model. There is no silent model switch halfway through a response; partial output is preserved and the next request can use the fallback with the conversation's existing context. Persistent inference status and active model reporting continue to come from Android/native runtime readiness, not guessed from metadata alone.

## JNI protocol and lifetimes

`NativeIntelligence.dispatch(ByteArray): ByteArray` uses UTF-8 JSON, API version 1. Requests cap at 256 KiB before copying at JNI; arrays/indices/costs have per-operation limits. Replies are `{version:1, ok:true, result:{...}}` or a text-free typed error. The bridge validates protocol version and returned indices. No Java references survive a call. Rust state is a mutex-protected process-local singleton with no worker threads or retained request content.

| Operation | Purpose |
| --- | --- |
| `info`, `model_status` | Read version, admission and health diagnostics |
| `plan_model` | Select among Android-admitted candidates; does not open files |
| `model_loading`, `model_outcome`, `model_unloaded`, `model_reset` | Report actual adapter lifecycle/outcomes |
| `route` | Resolve admitted semantic route signals |
| `rank_passages`, `select_context` | Return indices into bounded Android-owned candidates |
| `acquire`, `accept`, `cancel`, `finish` | Own, fence and release one generation request |

Rust panics are contained at the dispatch/JNI boundary, and JNI failures can return to the legacy adapter. Critical protocol/state failure disables Rust for the process. This does not claim that an allocator abort, OS kill or arbitrary crash inside an existing C++/GPU driver is recoverable. Input text, documents, credentials and paths are not recorded by the new core. Binder/AIDL signatures, permissions and API version 2 remain compatible; status adds an optional `intelligence_engine` field and reports the target model while loading.

## Persistence and networking

Room schema **9** and DataStore remain authoritative. There is no destructive migration, new database, memory training, cloud inference provider or automatic model download. Deletion and per-client memory permissions remain enforced by Android. Recent/relevant memory queries are bounded; unusual queries can still miss a useful memory.

Exa's previously disclosed anonymous MCP endpoint remains the only discovery service. OkHttp/Jsoup retain DNS/redirect/robots checks, bounded concurrency, request/page deadlines, source cache limits and safe publisher reading. Search summaries never become publisher evidence. Existing citation verification and partial source reports remain. Full Rust HTTP/HTML, SQL persistence, cloud model streaming and direct llama C-FFI ownership are deliberately deferred; no placeholder implementation is presented as working functionality.

## Build and rollback

Install Rust using the official rustup distribution. `rust-core/rust-toolchain.toml` pins 1.99.1, Android targets and formatting/lint components; `Cargo.lock` pins registry versions/checksums. On Windows, native host builds require Visual C++ tools and Windows SDK libraries. Android uses the project's NDK **28.0.12433566**, API **24**, existing ARM64/x86_64 ABIs, hardened linking and at least 16 KB ELF alignment. Gradle builds `libbi_intelligence.so` and includes dependency notices/licenses from verified Cargo sources. Set `BI_RUST_CARGO`, `BI_PYTHON`, `CARGO_HOME`, `RUSTUP_HOME` or `CARGO_TARGET_DIR` if tools/caches are outside PATH. No compiler/SDK installer runs as part of Gradle.

```
python scripts/build_rust_core.py --test
./gradlew -PbiValidationPackage=true -PbiIpcValidation=true :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :sample-client:assembleDebug :sample-client:assembleDebugAndroidTest
```

Default mode is RUST after native initialization; inability to load the library uses LEGACY. Internal build switches are `-PbiRustCore=false` for complete policy rollback, or `-PbiRustMODEL_ROUTER=false`, `-PbiRustQUERY_ROUTER=false`, `-PbiRustREQUEST_GATE=false`, `-PbiRustCONTEXT=false`, `-PbiRustPASSAGE_RANKING=false` for individual components. A rollback build still packages the library but does not load/use disabled policy. Increment Play versionCode and run the same regressions before releasing a rollback. Test-only mode changes must occur at idle request boundaries. Legacy implementations remain until parity and a measurable benefit justify removing them.

## Validation and performance scope

Host Rust tests cover model health/recovery, quotas, missing status, contention, cancellation, protocol errors and bounded selection. JVM regressions exercise Android policy and library-unavailable rollback. Device tests execute the real JNI library, compare routes/excerpts/context with legacy mode, verify Room reopening/deletion and run real llama.cpp CPU streaming/cancellation/reload. The isolated sample client checks the existing Binder/ContentProvider contract under another UID.

Performance reports distinguish local generation, loading, extraction, bridge overhead and end-to-end research. Rust does not make the unchanged inference kernels intrinsically faster. No battery, crash-rate, GPU/NPU throughput or universal accuracy improvement is claimed without measurements. Validation results and remaining limits accompany the release record.

See the [version 26 validation and measured limits](2.5/RUST_VALIDATION.md).
