# Balanced local answers and optional research (unreleased)

This work changes source behavior on main; it is not a Play release. No model,
runtime or search-provider dependency is upgraded and no model is downloaded
automatically. Existing chats and Binder/AIDL contracts remain intact.

## Root causes

The beta5 `FactualResearch.publicLookup` matched question words, so ordinary
definitions, history and programming explanations went through discovery.
`sendChatMessage` then returned on `SEARCH_FAILED` before invoking the installed
model. These independent choices made an optional network operation a requirement
for otherwise local conversation. Grounded history was also discarded entirely.

`AnswerRoutingPolicy` now owns LOCAL / WEB_GROUNDED / CLARIFICATION decisions.
`ResearchRouting` and `FactualResearch.publicLookup` are compatibility facades.
There is no additional LLM classification run.

| Intent | Default | If research is unavailable |
| --- | --- | --- |
| Definition, established history, explanation, reasoning, writing, calculation | Local model | No network wait or web-error message |
| Weather, current officeholder, price, recent event, current compatibility | Research | Brief honest limitation; no invented current name/number |
| Explicit search for general background | Research | Useful on-device answer with a local-knowledge disclosure |
| Explicit verification or supplied public source | Research | Cannot claim verification; offer another public source |
| General explanation plus a current component | Research | Explain the local portion; identify the unchecked current part |
| Missing public subject/location | Brief clarification | No speculative location or private-history discovery |

Question words, a generic technical comparison, and phrases such as current code
or recent messages do not independently trigger search. Explicit offline requests
and the global permission are respected. The hidden legacy ON mode remains an
explicit force-research input for compatibility/testing; the chat UI uses AUTO.
English and Malayalam rules are deterministic and finite, not general semantic
intent recognition. Novel ambiguous phrasing can still be misclassified.

## Recovery and context

`ResearchFailure` distinguishes timeout, blocked discovery, unavailable discovery,
inaccessible pages and insufficient passages. A per-provider-instance circuit
skips repeated outages for one minute and explicit denial for five; the existing
Mwmbl quota backoff remains. No captcha solving, identity rotation or paid fallback.
Direct public HTTPS reads remain independent of discovery.

Ordinary requests start with one query. Extra planned queries run only when read
passages leave gaps. Reads use waves of two with the existing eight-page,
24-HTTP-request and 30-second limits. Titles/snippets never establish evidence.
Public cache hits do not renew their own expiry: weather lasts one minute,
changing facts five minutes, other requested source material fifteen minutes.
Explicit rechecks bypass the cache. Disabling permission still blocks cached use.

Local inference keeps the conversation and bounded older summary. Grounded
inference keeps bounded task context and preferences, separately labelled from
source evidence; old assistant source IDs are removed. That history is passed
only to the local engine, never discovery. Source-followup expansion uses the
existing public-source metadata. Fresh matching source passages can be reused.

Interrupted researched output is kept as an explicitly unverified partial draft;
it does not become a verification refusal or a completed verified answer.
On-device, web-supported, partially supported and unavailable statuses remain
compact. The response-token preference is respected within the context budget.

## GPU and shared memory

LiteRT keeps its validated OpenCL path and SHA-bound Gemma FLOAT32 override. GGUF
keeps Vulkan and actual offloaded-layer reporting. CPU fallback remains available.
No manufacturer-based preference or historical measurement is seeded as a new
device validation result.

The previous in-memory selection stamp omitted failure-cooldown state: once CPU
was selected, a later request could reuse CPU indefinitely after cooldown expiry.
Both selection stamps now include quarantine state. Known memory/thermal failures
retain previously successful GPU measurements and can be reconsidered after at
least 30 seconds, actual required memory recovery, no low-memory signal and a
thermal state below moderate. Driver/correctness failures invalidate validation.
An unfinished native attempt is quarantined across configuration changes for
24 hours for that firmware/runtime, preventing a layer/context change from
bypassing the crash guard. Unknown process termination is conservatively treated
as unsafe; Advanced's existing reset remains explicit and requires an idle engine.

GPU executor identity ignores CPU-only thread-count changes, avoiding needless
reloads. The process-wide admission and model-owner gates remain. Switching waits
for native completion/cancellation; no second model is introduced.

The old GPU qualification compared free-form CPU/GPU answers byte for byte. On
this phone both produced correct arithmetic with different wording, incorrectly
disqualifying the GPU. Policy 7 uses computed numeric probes, validates each CPU
reference and GPU result, rejects wrong/extra numbers and malformed output, and
then scores measured latency, decode speed, initialization, memory and thermals.
It does not insert answers into user responses or assume GPU is faster. These
bounded probes are compatibility checks, not a general model-quality guarantee.

Memory estimates retain quantized/text weight bytes, KV allocation, fixed export
floors, runtime and shared-GPU overhead. No GPU memory is added to Android's RAM.
Gemma 4's SHA-bound minimum is 1024, with 2048 also supported; tier decisions now
report that actual floor rather than claiming a 512-token allocation. Fixed
exports retain their allocation estimate and the 512 MB reserve remains.
The staged E4B header reports no dynamic context support and no usable maximum;
its existing conservative fixed-allocation floor is retained.

## NPU and MTP

The existing Qualcomm QNN/SM8750 build profile remains off by default. The
[current E4B publisher listing](https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/tree/main)
has CPU/GPU/web packages and no matching SM8750 E4B NPU artifact (checked
28 October 2026). The [existing NPU protocol](../2.3/QUALCOMM_NPU.md) still requires
matching model metadata, QAIRT/dispatch, driver and physical decode validation.
No GGUF or ordinary LiteRT file is represented as an NPU graph.

Qualcomm also publishes an [E4B GenieX/QAIRT route](https://github.com/qualcomm/ai-hub-models/blob/main/src/qai_hub_models/models/gemma_4_e4b_it/README.md).
This is a separate runtime/model packaging route, not a drop-in LiteRT artifact
for BI's existing adapter. Its [v0.60.0 release notes](https://github.com/qualcomm/ai-hub-models/releases/tag/v0.60.0)
describe E4B QAIRT as developer preview with correctness issues. This is a future
integration option, not evidence that the current BI build executes E4B on NPU.

The installed 0.18.0 Kotlin API exposes `hasSpeculativeDecodingSupport`, the engine
[experimental flag](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.0/kotlin/java/com/google/ai/edge/litertlm/ExperimentalFlags.kt)
and lazy conversation [speculative-decoding configuration](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.0/kotlin/java/com/google/ai/edge/litertlm/Config.kt).
Production now explicitly disables speculative decoding instead of inheriting an
unknown export default. The LiteRT cache policy changes accordingly.

`LiteRtSession` has a test-only explicit MTP opt-in for an actual packaged drafter,
GPU, cool device and at least 1 GB of remaining shared memory. It remains false in
all production constructors. A failed MTP request is replayed normally only before
any visible output; partial output is never duplicated. Native cancellation joins
before engine disposal. This margin is an experimental guard, not a measured
drafter-allocation guarantee. No MTP speedup is claimed without a successful,
comparable physical run and sustained memory/thermal validation.

## Reproducible validation

- `answer-routing-corpus.tsv`: over 100 manually labelled English/Malayalam queries.
- Frozen `balancedbaseline` sources: actual 81253eee routing for before/after counts.
- `BalancedAnswerRoutingTest`: corpus, privacy, unavailable/blocked/timeout/empty
  outcomes, local recovery, cache expiry, context and provider circuit recovery.
- Existing citation, contradiction, transport, native lifecycle, model and IPC
  regressions remain enabled. Updated labels reflect the requested balanced policy.
- `BalancedLocalDeviceTest` (`balancedResearch=true`): real composer and model,
  controlled outages, disabled permission, mixed questions, session recall and
  cancellation during a researched response. Validation package only.
- `BalancedBackendDeviceTest` (`balancedBackends=true`): actual package metadata,
  1024/2048 CPU/AUTO/GPU samples, resource guards and optional `experimentalMtp=true`.
- `BalancedLatencyDeviceTest` (`balancedLatency=true`): two alternating live pairs
  on the same phone, model and CPU settings. Legacy forced research is equivalent
  to beta5 AUTO for these chosen general questions. Outages are recorded, not
  removed from the comparison. This is a small sample, not general accuracy.
- `LiteRtIntegrationDeviceTest`: existing AIDL, polish semantics, shared model,
  context, cancellation, concurrent admission and switching. Test inputs are fixed
  synthetic English/Malayalam/Manglish fixtures; no keyboard text invokes search.

## Measured results — HONOR VKP-NX9, 28–29 October 2026

The isolated validation package used the connected physical Android 16 phone,
SM8750/Adreno 830, with 11,238 MB reported total RAM. Existing publisher-pinned
models were staged and fully checksummed; no private chats were read or modified.
The production app/data were preserved. Reports contain only synthetic/public
questions. Temperature is Android battery temperature where available; thermal
state is Android's reported status, not a direct GPU die measurement.

### Routing and chat

The 131 labelled routing cases all match the requested LOCAL/WEB/CLARIFICATION
labels. Of 88 labelled local cases, the frozen 81253eee router searched 56 and the
new router searches zero. These are corpus counts, not real-world accuracy rates.

Real composer/model checks cover local definitions, Paris, unavailable live
weather without an invented temperature, explicit verification failure, mixed
local/current questions, useful recovery after explicit general search, session
recall, disabled web permission, interrupted research and a subsequent correct
calculation. Interrupted output remains STOPPED/UNVERIFIED_PARTIAL. See
[chat-device.json](chat-device.json) and [routing-evaluation.json](routing-evaluation.json).

Two alternating path comparisons used Gemma 4 E2B CPU, four threads, 1024 context,
128 output-token limit and temperature zero on the same phone:

| Public question | Forced research path | Balanced local path | Discovery/reader requests |
| --- | ---: | ---: | --- |
| Explain recursion in one sentence | 6.387 s | 1.635 s | 6 versus 0 |
| Capital of France, city only | 8.716 s; refused verification | 0.697 s; Paris | 6 versus 0 |

The former path reproduces beta5's broad AUTO routing for these questions; it is
not a bit-identical old APK benchmark. One pair per question, uncontrolled live
network and short outputs do not establish general accuracy or sustained speed.
The researched recursion answer was narrowly about theorem provers; the local
answer gave the requested general definition. See [latency-device.json](latency-device.json).

### Actual backend execution

The initial low-memory run deferred E2B GPU qualification and skipped E4B:
3590 MB estimated versus 2663 MB safely available. After the user unloaded the
production model, the repeat had 4297 MB available / 3785 MB safe at E4B admission.
E4B then loaded and completed CPU/AUTO at 1024 and 2048 requested context.
Its GPU attempt still deferred for shared-memory headroom. This demonstrates
dynamic admission rather than rejection based on advertised 12 GB RAM. See the
separate [low-memory report](low-memory-backend-device.json). No new E4B GPU result
is claimed.

E2B genuinely executed through OpenCL GPU at both contexts and AUTO chose GPU at
2048 after qualification. All answers in these bounded arithmetic samples were
correct, with nominal Android thermal status:

| Model/context, warm sample | Backend | Wall / first token | Decode | Process PSS |
| --- | --- | --- | ---: | ---: |
| E2B / 1024 | CPU | 647 / 590 ms | 30.79 tokens/s | 1470 MB |
| E2B / 1024 | GPU | 303 / 261 ms | 34.83 tokens/s | 786 MB |
| E2B / 2048 | CPU | 755 / 686 ms | 25.10 tokens/s | 1477 MB |
| E2B / 2048 | AUTO → GPU | 320 / 281 ms | 31.66 tokens/s | 821 MB |
| E4B / 2048 | CPU | 1919 / 1784 ms | 13.92 tokens/s | 2920 MB |

Android process PSS does not include every GPU/driver allocation and is not total
device memory usage. Only 4–6 output tokens were generated in these Gemma samples;
the decode rates are too noisy to predict long-answer throughput. E2B's first
AUTO qualification at 2048 took 13.520 s; the following cached request took
320 ms. Cold initialization/qualification costs are retained in the raw report.

Qwen 0.6B passed the new CPU/GPU compatibility probes. AUTO selected CPU after
measuring both. Explicit qualified GPU mode then genuinely generated through
LiteRT OpenCL: warm arithmetic at 1280 context took 597 ms, 559 ms first-token
latency, 40.83 tokens/s and about 855 MB process PSS. The corresponding warm AUTO
CPU sample took 711 ms, 575 ms first token, 35.64 tokens/s and about 1021 MB PSS.
GPU initialization was 4673 ms. Short arithmetic
and these few samples do not justify forcing GPU for all requests. See
[backend-device.json](backend-device.json) and the separate raw
[gpu-probe.json](gpu-probe.json), which records correct CPU/GPU arithmetic with
different wording and demonstrates the old qualification problem.

GGUF Vulkan implementation is preserved; native build validation is separate
from physical GPU execution. No new physical GGUF GPU or NPU benchmark is claimed.
Both Gemma headers advertise a drafter. Initial low-memory attempts could not
admit the experimental MTP test; the later backend-only repeat did not opt into
MTP. Production MTP and NPU remain disabled. Historical E4B
results in `docs/validation/1.3` are not presented as measurements of this change.

### TypeRight and validation limits

The existing AIDL/Binder methods and single shared model remain unchanged. Polish
uses the original tested prompt for Latin text and a language-preserving prompt
when Malayalam script is present. A single shared multilingual prompt regressed
English meaning during development; the language-specific choice avoids changing
the tested English prompt. Neither approach proves complete semantic preservation.

At the user's explicit request, all polish-output rejection checks were removed:
no number/link/script/format/length/empty-output or output-token-limit rejection.
Binder and Android text actions return the model output without post-validation
or rewriting. Existing request limits, local permissions, hardware compatibility,
RAM admission and native lifecycle remain. The correction preview remains.

Final local checks pass: 208 JVM tests, zero failures/errors, fresh debug/release
Kotlin compilation, debug APK, instrumentation APK and release lint. Five obsolete
output-rejection tests were removed with the checks at the user's request; the
earlier 213-test run passed before that requested change.

The final physical Binder integration passes with eight semantic fixtures:
English grammar, negation, package identifier, URL/time, two emails, Malayalam,
Manglish and mixed script. It also exercises unchanged synchronous/streaming AIDL,
single-model reuse, bounded context, cancellation, concurrent admission, switching
to GGUF and native mapping cleanup. See [ipc-device.json](ipc-device.json).
The final 12-turn chat recovery run also passes against the rebuilt APK.

Development failures included an overbroad process-global provider circuit,
byte-equal GPU wording checks, and a shared multilingual polish prompt that
regressed English. Those were corrected and their relevant regressions rerun.
An additional phone repeat was temporarily rejected by low-RAM admission; after
the user unloaded the production model it completed. NPU/MTP and E4B GPU are
explicit remaining validation limits, not passing hardware results.

See [validation-summary.json](validation-summary.json) for the result index.
No Play release is created by this task.

## Files changed

| Area | Files |
| --- | --- |
| Central routing/recovery | `AnswerRoutingPolicy.kt`, `ResearchRouting.kt`, `FactualResearch.kt`, `ChatAnswerPolicy.kt`, `AiCoreViewModel.kt`, `ConversationalResearch.kt` |
| Bounded research/cache/status | `Grounding.kt`, `DiscoveryCircuit.kt`, `FreeSearchDiscovery.kt`, `HtmlDiscovery.kt`, `PublicWebResearch.kt`, `AnswerSourceStatus.kt` |
| UI | `ChatScreen.kt`, `SettingsScreen.kt` |
| Backend/resource lifecycle | `InferenceEngine.kt`, `SmartBackendSelector.kt`, `BackendValidation.kt`, `LiteRtSession.kt`, `ModelLibrary.kt`, `ModelMemoryPlanner.kt` |
| IPC/polish | `LocalAiAccess.kt`, `BrainTextActionActivity.kt` (language-specific instructions, raw output; AIDL unchanged) |
| JVM regressions | `BalancedAnswerRoutingTest.kt`, frozen `balancedbaseline` sources, `answer-routing-corpus.tsv`, `BackendValidationTest.kt`, `AccelerationTest.kt`, `PolishTextTest.kt`, updated routing/evidence expectations in five existing test files |
| Physical protocols | `BalancedLocalDeviceTest.kt`, `BalancedLatencyDeviceTest.kt`, `BalancedBackendDeviceTest.kt`, `LiteRtGpuProbeDeviceTest.kt`, extended `LiteRtIntegrationDeviceTest.kt`, updated `AutomaticGroundingDeviceTest.kt` |
| Documentation | `README.md`, `PRIVACY.md`, `docs/2.3/AUTOMATIC_RESEARCH.md`, this report and synthetic measurement JSON |

Remaining limits: deterministic routing can misread unfamiliar wording; public
discovery is still subject to network availability/rate limits; general model
answers can still be wrong; no sustained energy/thermal claim; current free RAM
blocked E4B GPU/MTP testing; compatible NPU artifact/runtime validation is
still required. Existing download, transport, citation and model-owner safeguards
remain enabled.
