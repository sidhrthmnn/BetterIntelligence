# Validation and interpretation

Release source: `baf837eb15924b428df511d8beee0c8056b9d620`, version 15 / 2.3.0-beta1. Later report-only commits do not change the app. The starting main was `b90955b` / 2.2.0-beta4.

## Automated regression checks

All **167 JVM/Robolectric tests passed**, with zero failures, errors or skipped tests. Debug application, Android test APK, release lint and signed release bundle were built with JDK 21, SDK 36.1, NDK 28 and the pinned native dependencies. Release lint has existing warnings; no lint errors. The validation build has a different package and a non-exported Binder service, preserving the installed production app and its data.

The suite includes routing/OFF/consent, public-network restrictions, source injection, redirects and request budgets, source extraction, numbers/units/negation/subjects/comparisons, unsupported links/actions, qualifiers, context isolation, persisted sessions, cache clearing, cancellation/ownership/concurrency, client access, downloads and hardware admission. AIDL, Binder, native code, inference runtime and model catalogue have no diff from this main. Existing client contract tests passed. The actual TypeRight application was **not** physically re-exercised in this update; an isolated validation package deliberately cannot stand in for the exported production Binder identity.

Defined deterministic before/after fixtures, using the previous main's rules rather than a model grader:

| Fixture | Previous rules | Updated rules | Meaning |
| --- | --- | --- | --- |
| Search routing | 19/28 | 28/28 | Expected choices on a fixed regression suite |
| Claim checks | 10/24 | 24/24 | Synthetic accepted/rejected claims, not factual answer accuracy |
| Existing 40-case retrieval corpus | Retained | 40/40, 52/52 expected facts | Mock retrieval coverage, not complete-answer correctness |

The JSON fixture reports accompany this document. Numbers from these small suites are not general accuracy estimates. The prior app was not rebuilt and timed on the phone in this run, so no before/after native speedup is claimed.

## Paired real-device protocol

HONOR VKP-NX9, Android 16 / API 36, about 11,238 MB reported system RAM. Test-only application; production installation untouched. Gemma 4 E2B `.litertlm` and Qwen 3 1.7B Q4_K_M GGUF weights were staged and their SHA-256 checked. CPU mode, four threads, 2048 context, 160 maximum output tokens, temperature zero, top-k one, top-p one. Identical model/settings/history for OFF and ON, alternating order, one run, model reused across fresh conversations. The 22 public questions are Android test assets; reference facts and quality annotations are outside the APK. There is no answer lookup in the app.

OFF makes zero research requests. The direct HTTPS URLs are identical in both variants and can hint at the subject offline. ON includes actual public HTML discovery/reads under the existing budget. Retrieval, inference, total time, generation telemetry, 250 ms sampled process PSS and Android thermal status are recorded separately. Failure or a necessary clarification skips model generation, matching app behaviour. TTFT and generation speed are aggregated only for answers that actually generated; this avoids counting abstentions as fast model output. PSS does not capture every shared driver allocation. Charging, an uncontrolled network and a single run do not establish energy consumption, sustained performance or a universal engine winner.

Raw model drafts and the final displayed answer are separate in the local test records. A stale name removed by the live-fact safeguard is **not** credited as improved model knowledge. Citation existence is not a correctness score. See [paired results](WEB_ON_OFF.md), [measurements and reviews](web-on-off-measurements.json) and [independently maintained references](reference-snapshots.json).

Final Gemma run: 44 answers, instrumentation passed in **263.437 seconds**, initial model load **3235 ms**, successful unload with no matching model mapping left in `/proc/self/maps`. No inference failures, thermal status remained 0 (nominal).

| Measured quantity | Web OFF | Web ON |
| --- | ---: | ---: |
| Answers / actual research HTTP requests | 22 / 0 | 22 / 61 |
| Median total answer time | 5730.5 ms | 7066.5 ms |
| Total retrieval / model time | 1 / 106213 ms | 47891 / 86251 ms |
| Generated-answer median first token | 1249 ms | 2439 ms |
| Generated-answer median decode | 26.68 tokens/s | 27.03 tokens/s |
| Maximum sampled process PSS | 1724.8 MB | 1716.9 MB |
| Agent quality points (not accuracy) | 23/44 | 26/44 |
| Reviewed correct complete answers | 3 | 6 |
| Appropriate subject clarifications | 1 | 1 |
| Reviewed safe abstentions | 10 | 7 |

The three additional complete answers are independently checked current officeholder/CEO/prize facts. The other complete outputs are arithmetic, JSON and Manglish. Counting abstentions as correct would exaggerate these results. Web wins six paired quality reviews, offline wins three, thirteen tie; inspect the individual annotations. Four unsubstantiated visible assertions were observed offline (including implying unread linked documents had been consulted), versus zero observed in final web answers; this limited audit is not a guarantee of zero hallucinations. Three web research operations were unavailable and four answers had insufficient evidence. Median total latency increased by about 1.34 seconds for the additional current-fact usefulness. Shorter/omitted web answers and fewer generation calls explain lower summed inference time; this is **not** a native speedup.

Quality review: 0 wrong/unsupported/failure, 1 useful partial answer or appropriate uncertainty, 2 correct and sufficiently complete or an appropriate clarification. Explicit abstentions never count as correct factual answers. Reviews are agent assessments against primary pages and literal format requirements, not independent human ratings. Unsupported assertions in raw drafts are recorded separately from those surviving into the displayed answer. Semantics, completeness, citation relevance and safe uncertainty require review; the deterministic checks do not establish truth.

## Failure analysis retained

The first physical pilot returned a stale current officeholder offline, motivating the narrow live-data safeguard. An initial 44-answer run then exposed flattened nested tables, an annual-report signature date omitted from freshness evidence, surrounding fences in a JSON-only answer, and orphaned comparison clauses after omission. These led to labelled table cells with units/device/backend, explicit document-reported dates, strictly parsed JSON fence removal and subject/coherence checks. Final runs use the corrected app; earlier outputs are retained locally for diagnosis rather than silently re-labelled as successes.

Remaining observed limits include incomplete/truncated explanations at the controlled 160-token cap, limited complex comparison quality, rejection of translated Malayalam evidence without reliable name anchors, and unavailable/oversized public pages. Older maintained docs are labelled background and cannot prove today's latest release or newly added features. No access limits, robots rules, TLS checks or consent protections were bypassed to improve scores. The bounded optional same-model correction is excluded from the primary paired comparison, and can only improve a comparison if it is eligible and passes the same checks.

In particular, the fixed table reader now supplies the correct Gemma prefill/decode/memory columns, and the raw model reads them correctly in case 11. Conservative subject/wording checks still discard these valid facts. Case 15 retains only a CPU-backend fragment, and case 9 includes sourced desktop backends irrelevant to Android. These are explicit false-negative/completeness limits; neither `CITED` nor the quality totals should be advertised as comprehensive comparison intelligence. Automatic mode keeps the defined static explanation/calculation/transformation cases local, where this test often found longer useful responses.

## Secondary model and UI lifecycle

The [Qwen 3 1.7B GGUF subset](qwen/WEB_ON_OFF.md) completed six pairs / twelve answers in **221.296 seconds**, load **1610 ms**, no inference failures and successful native mapping cleanup. OFF used zero HTTP requests; ON used ten. Median total latency was 11.306 / 20.204 seconds; median generated TTFT 5.985 / 14.646 seconds; median decode 15.21 / 14.17 tokens/s; maximum sampled PSS 1710.1 / 1779.0 MB. Maximum thermal status was 1 (light). Quality was 6/12 / 8/12 points: both modes completed arithmetic and strict JSON, while ON removed unsubstantiated comparison/registry claims but returned four insufficient answers. This is a small compatibility/safety check; its different model and six-question mix cannot establish which engine is faster or better overall.

Physical HONOR UI regressions passed: real keyboard/composer inset test **2.897 seconds**; process-restoration prepare **0.326 seconds**, restore **1.240 seconds** after an actual validation-package force-stop. Distinct process IDs were asserted. Draft, stopped partial response, active conversation/model, appearance, synthetic client approval, saved session summary and separation from another session were checked, including the restored composer UI. The synthetic checkpoint was cleaned by the test. See [restoration record](process-restoration.json). This verifies persistence beyond Activity recreation; it does not mean unlimited or perfect semantic memory.

The final controlled records also passed independent structural assertions for all 44 records, unload/mapping cleanup, per-answer HTTP budget, OFF zero network, arithmetic, strict JSON/type preservation and Manglish negation/script. Product-source commit `baf837e` also passed the GitHub Android checks, including real ARM64 CPU/Vulkan native compilation. That is compilation evidence, not a new GPU execution measurement.

A supplemental Gemma run of cases 11 and 15 passed in **54.678 seconds** with repair enabled. No second generation was eligible/needed: the insufficient comparison did not pass admission, and the useful-partial rule excluded the other. See [eligibility record](repair-eligibility.json). There is therefore no claimed physical correction-quality gain or exercised correction-timeout path; JVM tests cover those admission branches. The experiment remains separate from the primary latency/quality totals.

## Reproduce

```powershell
# Build in a configured JDK/SDK/native host environment.
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintRelease :app:bundleRelease '-PbiValidationPackage=true'
# Install only the isolated validation application and test APK; stage licensed
# weights into its private files/models and verify SHA-256 before measuring.
adb shell am instrument -w -r -e class com.example.WebOnOffDeviceTest -e pairedResearch true -e pairedModel gemma-4-E2B-it.litertlm -e pairedSha256 181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c com.aistudio.betterintelligence.mobile.validation.test/androidx.test.runner.AndroidJUnitRunner
# Export the JSON with a binary-safe host redirect, then:
python scripts/report_web_on_off.py --input <exported-json> --reviews <reference-reviews-json> --output docs/2.3
```

For a secondary-model subset add `-e pairedIds 2,5,9,16,20,21` and the Qwen filename/SHA. A supplemental repair experiment adds `-e pairedRepair true`; it writes a separate report and allows up to 96 extra tokens/eight seconds, so its latency is not part of the primary comparison. Process-restoration testing uses prepare/restore instrumentation phases separated by an actual force-stop of the validation package, and requires a changed process ID. Never point these destructive test phases at the production package.

No E4B run, new GPU/NPU benchmark, battery measurement or long thermal soak is claimed. Existing CPU/GPU functionality remains; NPU remains unavailable without compatible compiled models and vendor support. Reports describe actual measurements, not estimates of untested hardware.
