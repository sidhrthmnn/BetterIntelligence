# 2.2 research validation

Release status and signed artifact identities: [closed-beta submission](RELEASE.md).

## What is measured

The offline comparison uses 40 explicit synthetic questions/documents in `ResearchQualityEvaluationTest`, covering factual/current questions, comparisons, multipart requests, Android documentation, numbers/dates, tables, Malayalam/Chinese/Spanish, public-context follow-ups, ambiguity, conflicting reports, inaccessible pages, challenges, changed markup, hidden instructions and missing answers. Expected values are test assertions; no evaluation answer lookup is shipped in production.

The test-only beta5 snapshot is pinned to `842b46e`. Its planning/filtering/extraction/selection algorithms remain unchanged; package/shared-type adaptations are documented in its README. Both versions receive the same per-question synthetic search results and publisher documents. This isolates retrieval quality, not the diversity or reliability of a real search index. Nine cases require abstention and 31 have retrievable evidence. There are 52 expected evidence points. See [raw offline measurements](research-quality-offline.json).

The corpus is not a 40-answer LLM factual-accuracy benchmark. Latency is a single ordered JVM/Robolectric run with fake HTTP and JIT/order effects, not Android network latency or sustained performance. Request counts in that corpus are logical mock-transport calls. Live HTTP counts below include actual robots/redirect requests. Citation guards have separate positive/negative regression tests; neither they nor retrieval coverage measure general semantic entailment.

| Recorded offline metric | Beta5 | 2.2 |
| --- | ---: | ---: |
| Passing corpus cases, including abstentions | 30/40 | 40/40 |
| Answerable cases yielding relevant evidence | 22/31 | 31/31 |
| Expected evidence points covered | 29/52 | 52/52 |
| Total logical fixture HTTP calls | 183 | 200 |
| Median fixture retrieval time on Windows/JVM | 4.43 ms | 8.23 ms |

The additional query/ranking work has a cost; these numbers are not a claim of faster web search or universal factual accuracy. **146 JVM/Robolectric tests passed, zero failures/errors**, including positive/negative citation, native admission/cache/resource-policy, context/memory, model and real-socket transport regressions. Debug APK, Android instrumentation APK and release lint passed in the final verification build. `git diff --check` passed.

## Initial physical audit and correction

HONOR VKP-NX9, Android 16, approximately 12 GB RAM. A separate `.mobile.validation` package was used; the Play installation/data were preserved. Qwen 3 1.7B Q4_K_M, verified SHA-256 `72c5c3cb38fa32d5256e2fe30d03e7a64c6c79e668ad84057e3bd66e250b24fb`, CPU four threads, context 2,048, temperature 0, maximum 180 output tokens. No GPU/NPU or LiteRT performance claims are made by this web-pipeline test.

The first opt-in physical run completed ten actual retrieval/generation probes (five questions × beta5/2.2) in **225.475 seconds**, released native resources, and passed the three exact-reference answers on both pipelines:

- [Official October 2026 Android bulletin](https://source.android.com/docs/security/bulletin/2026/2026-10-01?hl=en): publication October 5, update October 8.
- [IANA example domains](https://www.iana.org/help/example-domains): unavailable for registration/transfer.
- [Android Vulkan getting-started guide](https://developer.android.com/ndk/guides/graphics/getting-started): Android 7.0/API 24 or higher and Vulkan-capable hardware.

References were independently read before scoring. A matching regex alone is not semantic review: final answers were also inspected against those primary pages. Both pipelines had two HTTP requests for each direct-source question. Before the final passage correction, 2.2 retrieval times were 363–4,817 ms, first token 12,007–21,637 ms and decode 16.72–18.06 tokens/s. The long first-token times are actual CPU prefill costs for several hundred prompt tokens, not the research-fetch time.

The broad runtime comparison exposed an irrelevant **ExecuTorch** section selected from a multi-runtime article while asking about LiteRT-LM/llama.cpp. The generated answer misattributed a runtime limitation; the final guard rejected the response rather than returning it as cited. This was not counted as a correct answer. Subject-aware passage coverage, exclusion of generic platform names as comparison subjects, and comparison of fitting evidence plans were added afterward, with a synthetic multi-runtime table regression. The initial run is retained separately in [initial live measurements](research-quality-live-initial.json); it is not the final corrected-build result.

The broad Vulkan discovery query retrieved real pages from separate publishers. Its generated response also added an unsolicited date-related statement and used a citation whose excerpt did not support every detail. Arbitrary non-numerical entailment remains a limitation. The app deliberately labels citations as **check the evidence**, not **verified**. Retrieval-only questions do not count towards exact-answer factual accuracy.

## Corrected physical run and final package checks

A subsequent run exposed an unrequested current-date statement on the IANA question. The guard rejected it because the source did not substantiate that date. BI now omits the current clock from static questions and omits empty source-date fields. An explicit relative-date clock, when needed, says it is not a source date. Another regression protects this distinction, including historical event questions.

After the passage and clock corrections, the **complete ten-probe physical run passed in 282.843 s**. Both pipelines again answered all **3/3 independently checked exact-reference questions** correctly with usable citations. See [corrected live measurements](research-quality-live.json). This is six checked answers, not ten semantically verified answers.

| Live five-question totals | Beta5 retrieval algorithm | 2.2 retrieval algorithm |
| --- | ---: | ---: |
| Exact reference questions answered correctly | 3/3 | 3/3 |
| Actual HTTP requests, including policies/redirects | 34 | 32 |
| Retrieval time | 11.888 s | 22.821 s |
| Local CPU inference time | 119.584 s | 117.921 s |

Live search/results/network timing are uncontrolled and query-dependent. The more focused searches did not produce a live speedup in this small sample. 2.2 retrieved relevant runtime-comparison pages, but the small local model still made unsupported compatibility/performance inferences in its raw comparison. **The final guard rejected that comparison and returned the visible unverifiable-answer response.** Complete, reliable prose comparisons remain a limitation; no general answer-accuracy improvement is claimed from the fixture score.

The final validation APK was installed separately on the HONOR. The real IME keyboard-gap/multiline/dismissal test passed in **2.956 s**. CPU/GPU probe outcomes and the actual NPU compatibility limits are recorded in [acceleration checks](ACCELERATION_CHECKS.md). Native cleanup was verified by the probe reports. The production Play-signed binary itself was not installed over the user's app; it is separately built/validated as an AAB.

The session-restoration harness was also corrected to await verification of a host-staged model, rather than immediately accepting an old catalogue row as installed. This is test setup synchronisation, not a change to production download/model management.

After correcting that harness setup, both real process-death phases passed on the final installed validation APK: prepare **1.570 s**, restore **1.448 s**, with an actual host force-stop between phases. Draft, interrupted reply, model choice, synthetic client approval and identical separate-session context notes were preserved. The earlier setup failure was not counted as a product pass.

## Reproducing the checks

Use the repository's documented JDK/Android SDK/native toolchain. Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintRelease -PbiValidationPackage=true --no-configuration-cache
```

Run `ResearchQualityEvaluationTest` alone to repeat the corpus; default JSON output is the app's `build/reports/research-22-comparison.json`. To choose a new path, pass JVM property `bi.research.report` to the test worker (for example through `JAVA_TOOL_OPTIONS`). Baseline snapshot files are shared only between JVM tests and Android instrumentation using AGP's supported Kotlin source directory configuration.

For live opt-in testing, install both validation APKs on an explicitly selected device, stage the verified Qwen GGUF in the validation package's `files/models/`, and run:

```text
adb -s <device> shell am instrument -w -r -e class com.example.Research22DeviceTest -e research22Live true com.aistudio.betterintelligence.mobile.validation.test/androidx.test.runner.AndroidJUnitRunner
```

Collect `files/research22-live.json` with `run-as`. Real sites can change, deny access or fail; report those failures instead of changing expected references to make a test pass. Live search queries in this probe are retrieval-only comparisons, with semantic quality inspected separately. The test always unloads the model in `finally`.

Privacy modes, credential/private-URL rejection, robots, denied redirects, shared HTTP quota, bounded bodies, socket cancellation, cache deletion, model catalogue/context contracts and chat-memory tests remain in the full automated suite. Binder/native runtime source and AIDL were not modified by this update.

## Changed areas and remaining limits

- `ResearchPlan.kt` and `EvidenceRanking.kt`: intent/entity/facet planning, collection selection and narrow conflict checks.
- `PublicWebResearch`, `ResearchQuery`, `HtmlDiscovery`, `WebPageExtractor`, `ResearchRobots`, `Grounding`, `CitationSupport`, `ConversationalResearch`: bounded orchestration, discovery interface, focused extraction, budgeted evidence and safe failure/citation handling.
- `AiCoreViewModel`, `SourceReferences`, `SettingsScreen`: stage messages, actual-context checks, failure notices, readable source excerpts and updated disclosure.
- Unit corpus/regressions, test-only beta5 snapshot, opt-in live device probes and restoration-harness synchronisation; version code **11**, version **2.2.0-beta1**.
- README/privacy/architecture/evaluation records. No model weights, new model catalogue entries, inference library upgrades or Binder signature changes.

Public HTML discovery still depends on an external website/index permitting access. No commercial search, scraping, grounding or cloud-model API is used. Ranking is deterministic lexical/entity/facet matching, not an embedding model. Publisher independence, freshness, arbitrary contradictions, non-English morphology and semantic claim support remain imperfect. Numeric/paragraph guards can also reject otherwise useful prose; citations never establish universal truth. NPU is unavailable in this package, and Qwen 1.7B Vulkan failed on this HONOR configuration with safe CPU fallback. These limitations are not labelled completed acceleration or semantic verification features.
