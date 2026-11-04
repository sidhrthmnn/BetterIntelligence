# Web research reliability after v27

Development change, 4 November 2026. Baseline: `22a2f72` (v27 / 2.5.0-beta2). The feature branch is intended for pull-request review; it has not been released as a new Play build.

## Root causes

* Discovery had an eight-second outer limit, shorter than its HTTP call budget. A usable but slow MCP reply could be cancelled before parsing.
* MCP results can arrive as JSON, SSE events, multiple text blocks, structured results or resource links. A narrow layout parser missed valid replies. Waiting for a persistent SSE connection to close also delayed a complete reply unnecessarily.
* Broad exception wrapping lost network/HTTP distinctions. Empty discovery, blocked publisher pages, weak evidence and an unavailable provider could all end up presented as a web outage.
* Successful earlier publisher reads needed to survive a later slow page. Discovery links and provider summaries must remain distinct from actually read evidence.
* The deterministic source-summary footer began “These sources…”. Citation checking treated that uncited uncertainty sentence as an unsupported factual claim, rejecting otherwise useful attributed paragraphs. The footer now explicitly states what could not be verified; the citation boundary remains strict.
* Publisher footnote links, such as Wikipedia's reference markers, could be mistaken for BI citation IDs. Only actual reference DOM nodes are removed during extraction; arbitrary brackets and array indices are preserved.

## Timing and transport

| Stage | v27 | Revised |
| --- | --- | --- |
| First discovery attempt | 8 s enclosing stage | 12 s |
| Transient retry | No controlled stage-aware retry | At most one, 18 s |
| Retry delay | None | 450 ms plus 0–149 ms jitter |
| Publisher read | 6 s | 8 s |
| Research work | 26 s | 55 s shared monotonic deadline |
| Coordinator limit | 30 s | Remaining work plus up to 1 s cleanup, never above 60 s |
| HTTP connect / read / call defaults | 8 / 10 / 18 s | 8 / 18 / 60 s, each clipped to the current stage and shared deadline |

The deadline is inherited by discovery, backoff, robots and publisher reads. A request cannot receive a fresh full budget by changing stage. Publisher reads remain at most two concurrent, eight admitted pages, 24 transport requests and three distinct discovery queries per turn. Cancellation cancels OkHttp calls and saves a stopped message rather than an incomplete streaming state.

Only transient transport/timeouts and HTTP 408/500/502/503/504 receive one retry. Empty or malformed results, authentication failures, cancellation and HTTP 429 do not trigger a retry loop. Rate limiting honours Retry-After via a bounded circuit; repeated provider failures receive a cooldown. Empty results, parser failures and offline DNS do not incorrectly open an outage circuit.

## Provider and BI fallback

**Exa remains the primary discovery service.** Its anonymous MCP endpoint needs no user API key but has provider-controlled availability and rate limits. No alternative hosted search service is silently substituted. The parser supports JSON-RPC errors, terminal SSE responses, known structured result envelopes, split text blocks, Markdown links and resource links. Provider highlights are routing hints, never publisher evidence.

The fallback is **BI's on-device source directory and small link index**, not a global search engine. It currently covers reviewed entry points for TfL, London weather, Android/Vulkan, LiteRT, Kotlin, Rust, neural networks, UK inflation, Marvel, film reviews, news and selected phone specifications. It follows relevant same-host links, ranks them locally, keeps at most 96 titles/URLs for 15 minutes, and never sends the question to another search engine. A more focused official Vulkan setup guide replaced a generic graphics landing page after the phone test exposed insufficient relevant content.

The fallback runs only when no useful primary evidence was accepted, the topic has a directory entry, permission remains enabled and the existing request/time budget allows it. It does not override an explicit requested URL or bypass robots, denials, HTTPS/public-DNS checks, paywalls or publisher extraction rules. Its candidates still have to pass the normal reader, subject relevance, date, evidence and citation checks. Limited coverage means it cannot replace Exa for arbitrary queries or guarantee breaking news.

Official protocol references: [Exa MCP server](https://github.com/exa-labs/exa-mcp-server), [MCP transports](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports), [MCP tool results](https://modelcontextprotocol.io/specification/2025-11-25/server/tools).

## Recovery, progress and privacy

Failures distinguish provider timeout/rate limit/access denial/invalid response/unavailability, offline network, no results, blocked/slow/unavailable publishers, insufficient evidence, verification failure and user cancellation. Old stored status IDs still render with neutral labels.

General questions can receive explicitly unverified local knowledge after web failure. Mixed requests answer only their general component and identify the missing current facts. Current or source-specific facts are not invented. Useful source summaries retain attribution and complete supporting sentences when model synthesis fails verification.

Progress advances through searching, reading, checking and preparing without moving backwards during reformulation. After eight seconds in one phase, a small “Taking a little longer” message appears. Stop remains available.

Web permission remains off by default. Enabling research discloses Exa, the outgoing public question and the device IP; publisher reads also reveal the device IP. Existing private-data/credential restrictions, deletion and retention controls remain. No new analytics or cloud model was added. The directory stores titles and links locally, not answers or conversation history.

Advanced research tools expose a copyable allowlisted diagnostic report: categorical provider/failure/HTTP class, duration, retries, discovered/read/attempted counts and timeout stage. Exports exclude questions, URLs, conversation/message IDs, publisher bodies, credentials and exception strings. The HTTP class currently describes discovery, not every publisher request. Clear/delete fences prevent an older in-flight write from restoring removed diagnostics or index entries.

## Live comparison

`web-reliability-live.json` records 48 interleaved desktop runs: 12 fixed public questions × two trials × two implementations. The corpus covers news, weather, transport, Doom casting, a film review, phone specifications, Android documentation, a runtime comparison, a capital, an ambiguous film title and two negative controls. The negative controls are excluded from the 20 healthy trials per implementation.

The old transport/parser/reader/research/fallback-summary classes are test-only v27 snapshots. The old final outage mapping is reconstructed from v27 recovery rules. The benchmark invokes deterministic synthesis and citation verification, **not a native LLM**. Its `reportedStatus` field is the recovery-policy result for a failed answer, not a complete UI interaction for successful answers.

| Observed measure | v27 | Revised |
| --- | ---: | ---: |
| Discovery returned candidates | 20/20 | 20/20 |
| Accepted publisher evidence | 18/20 | 18/20 |
| Deterministic supported/partial answer | 16/20 | 16/20 |
| Supported answers passing citation boundary | 0/16 | 16/16 |
| False provider-outage classifications | 2 | 0 |
| Median retrieval + deterministic synthesis | 2263.5 ms | 1736 ms |
| p95, nearest-rank | 4895 ms | 5331 ms |
| Rate limits observed | 0 | 0 |

These are a small healthy-network sample, not a guaranteed success rate or speedup. The revised p95 was slower. Healthy Exa discovery was already good in this run; the strongest measured gain was retaining valid cited summaries and avoiding false outage labels. Neither implementation resolved the ambiguous film confidently; broad news did not produce an acceptable deterministic summary in either trial. Empty-result handling and timed/error conditions are covered separately with deterministic fixtures. Search-engine links alone are not counted as a supported answer.

A second complete 48-run pass on the final source tree hit actual anonymous-provider limits. It is preserved separately as `web-reliability-live-rate-limit.json`, rather than replacing or hiding either run:

| Second pass, including limited requests | v27 | Revised |
| --- | ---: | ---: |
| Discovery returned candidates | 16/20 | 17/20 |
| Accepted publisher evidence | 14/20 | 15/20 |
| Deterministic supported/partial answer | 12/20 | 13/20 |
| Supported answers passing citation boundary | 0/12 | 13/13 |
| False provider-outage classifications | 2 | 0 |
| Median / p95 | 1921.5 / 7767 ms | 1788 / 5925 ms |
| Trials observing provider rate limits | 5 | 3 |

The second pass did **not** achieve 95% discovery across all requests. The first healthy pass met the target; anonymous service quota remains an external limitation. Different interleaved requests share provider capacity, so these counts do not establish that the new client receives a higher quota. BI's limited directory could not recover every rate-limited topic. It correctly retained a partial film-review read in one baseline trial and classified real limits instead of inventing evidence.

## Implementation and verification

Primary changes: `ResearchReliability`, `ExaDiscovery`, `SearchHttp`/`GroundingCoordinator`, `DiscoveryCircuit`, `PublicWebResearch`, `BiDiscovery`, `ResearchRobots`, `AnswerRoutingPolicy`, `ReadableSourceSummary`, `WebPageExtractor`, `ResearchDiagnostics`, `AnswerSourceStatus`, `AiCoreViewModel`, and the research settings screen. Model/runtime and visual changes are described in `MOBILE_MODEL_COMPARISON.md` and `../branding/ASSETS.md`.

The final debug and instrumentation APKs compile for arm64-v8a and x86_64 with the pinned native runtime. The debug APK contains the expected 20 native libraries and passes 16 KiB ZIP alignment. **All 291 JVM tests and release lint pass on the final source tree**, including the second 48-run live corpus. One intermediate lint run hit a Kotlin FIR analysis crash while the source file was being refined; the final unchanged-tree rerun completed normally without suppressing lint.

Physical HONOR validation uses only the separate `.validation` installation:

* `WebReliabilityDeviceTest`: both methods pass, with real native LFM3 local/mixed fallback after a simulated 429, no invented live transport status, cancellation persistence and safe diagnostic export. Earlier Smol135M fixture results are preserved separately and are lifecycle tests, not successful general-knowledge quality tests.
* Live phone reads: Android Vulkan, London weather and Doom casting each supplied two accepted sources and a citation-valid partial deterministic answer. Retrieval took 5687 / 6746 / 2866 ms respectively. These are real phone HTTP reads plus deterministic synthesis, not live model-grounded answer-quality measurements.
* With Exa deliberately forced to 429 but publisher requests left live, BI's directory supplied one accepted official Android source through the normal reader.
* The four-model comparison completes 56 measured native text generations; rubric failures are reported, not hidden. See `MOBILE_MODEL_COMPARISON.md`.
* Chat navigation, draft recreation, three themes and the real software-keyboard inset checks pass. The final screenshots in `screenshots/` were inspected for the icon, RAM labels, downloaded state and concise research controls.
* All nine Rust policy/JNI/context/database/native tests pass with the opt-in inference fixture enabled. Six real native CPU generations confirm Rust/legacy output parity; the test also checks cancellation, reuse and unload/reload. The Q8 fixture's local/staged SHA-256 is `5a1395716f7913741cc51d98581b9b1228d80987a9f7d3664106742eb06bba83`.
* Native CPU/offload-failure/history/unload/reload checks pass. GPU preflight fails on this driver and CPU fallback is verified; no GPU performance claim is made.

No AIDL signature or TypeRight contract changed. The production chat database and model files are untouched. A separate end-to-end TypeRight app run is not claimed for this change.

`validation-web-models.json` records the build hashes, suites and measurement scopes. The validation app and staged models remain on the phone; native contexts are unloaded at the end of each test.

Remaining limits: anonymous Exa may rate-limit, publishers can deny reads or depend on JavaScript, extraction and relevance are lexical rather than semantic entailment, the BI directory covers a small set of topics, and no model is guaranteed to interpret every source correctly. Existing grounded-answer checks and memory/runtime safety checks remain enabled.
