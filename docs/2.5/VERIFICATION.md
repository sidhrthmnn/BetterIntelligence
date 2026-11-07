# Evidence-first verification — version 30

The fast path uses deterministic claim checks and the already-collected evidence. There is no separate judge model, embedding download, new search provider or extraction rewrite. A different phrase or missing citation alone no longer forces a generic verification failure.

## Version 29 failure causes

- Citation parsing rejected uncited supported sentences before attempting a factual source mapping. Parenthesised source-ID labels were not recognised consistently.
- The planner misidentified attribute phrases such as “needs 4” as model-version identities. That made “needs 4 GB” fail against “requires 4 GB”. Reviewed canonical vocabulary and directed relationship templates now work alongside exact identity/value/condition checks.
- Comparison recovery exited before extracting individual subject/value claims. Different sources could not contribute a concise comparison after a bad draft.
- Source summaries unconditionally said remaining details could not be verified. A fully answered factual question still looked like a failure.
- Repair was restricted to comparisons and ran before evidence-first recovery. Recovery now runs first; optional correction is considered only for a still-insufficient result, using the already-loaded model, within token, context, time, memory and thermal limits.
- A blank or invalid native response could bypass recovery or remain in an error state despite useful evidence. Existing evidence can now survive these generation failures, with cancellation handled separately.

## Thin checks and verified citation assembly

Each atomic sentence is checked against its actual supplied passages. The general overlap threshold is not lowered globally. Instead, a small reviewed vocabulary supports equivalents such as needs/requires, memory/RAM and pictures/images, while explicit directed relationships handle supported word-order changes. Unknown predicates, wrong subjects, swapped values, wrong units/scales, negation, missing conditions, dates, historical/current roles and comparison direction remain guarded. Numerical table rows retain their subject binding. A CPU-memory measurement in a GPU section is not CPU inference.

Explicit ID formats are normalised. Unknown IDs remain invalid. For an uncited claim, BI attaches an ID only after the same complete checks succeed and the requested subject is explicit; it does not guess from similarity alone. Supplied but unrelated IDs are removed from an accepted claim’s mapping. Code does not create new sources or URLs.

Recovery keeps relevant, self-contained supported claims and selects complete original evidence claims for missing requested facts. It verifies each selected claim before assembling citations. Separate comparison subjects retain separate values, units and qualifications. If only one subject or part of a question is supported, the response names the missing information. A complete source-backed factual answer does not receive the old generic failure suffix. Unsupported comparative winners are not inferred from mere specification differences.

No trained on-device entailment model is packaged or validated for this project. The deterministic vocabulary/templates are not represented as universal semantic inference. Arbitrary paraphrases may still require an extractive answer. This avoids adding an unverified second model or expensive model-as-judge pass.

## Inspection of installed-app failures

Two failed conversations on the connected HONOR were inspected through the installed app UI; no conversation database or raw private transcript was exported to the repository.

- The public company user-count comparison had a publisher-access failure and no usable live evidence. A verifier cannot manufacture counts when pages are inaccessible. A subsequent local answer incorrectly discussed API/permission restrictions; that is distinct from evidence rejection.
- The company-loan question had two sources but retained a tangential explanation about a credit line without the requested amount. One source title concerned a different company’s bridge loan to acquire a stake. The regression corpus uses anonymised companies and **synthetic figures** to test borrower attribution, credit limits versus outstanding debt, missing requested amounts, weekly/monthly users and million/billion scales. These fixture figures are not financial facts about real companies.

A source citation number must never count as the numerical answer to “how much”. An unrelated borrower’s amount or a facility limit must not become the requested company’s debt. Publisher blocking is reported separately from claim verification; no access controls are bypassed.

## Diagnostics and privacy

New diagnostics contain bounded aggregate counts: model-context source count; generated/accepted/rejected claims; missing/invalid citations; assembled citations; lexical, entity, polarity, numerical, qualification and relationship mismatches; recovery/correction attempts; recovered claims; generation failure; and the final verification result. Exports use fixed category/key allowlists, never arbitrary reason text. No raw prompts, claims, passages, credentials, URLs or user conversation text are added to diagnostics. Existing consent, deletion and cancellation controls remain.

## Appearance update

Only Light mode and Dark mode are selectable. Dark mode uses the former AMOLED black palette. Legacy DARK and AMOLED preferences resolve to that one dark mode. The old separate slate-dark palette option is removed. Light mode uses `app/src/main/res/drawable-nodpi/bi_avatar_light.png`, preserving the B mascot and its animated eyes without a dark square tile.

The built-in image-generation tool produced the project asset; no API key or fallback CLI was used. Final prompt: “Create a polished LIGHT THEME version of this exact app mascot base. Same square canvas and same B silhouette placement/scale as reference: B bounds x .273 to .735, y .238 to .772 relative to canvas. Keep B gradient cyan-blue-purple-cream and the central dark NAVY FACE area (do not add eyes: the app animates them). Replace every part of the black and navy background tile with a seamless solid very pale off-white #FAF9FC matching an Android light toolbar. There must be no black square, no dark rounded tile, no glow, no border, no shadows, no speckles, no cutout edge debris. Clean smooth antialiased vector-like B on completely uniform #FAF9FC background, high contrast. Exact original composition and B shape, no text or new details.” The final asset is opaque pale-background artwork, not a transparent cutout. Earlier transparent trials were not included.

## Validation scope

Test-only frozen v29 claim verification, citation-number checks, answer recovery and checkAnswer wrapper receive the same questions, evidence and drafts as the revised code. Search, extraction and common source-brief/topic helpers are shared and unchanged. This is a verification/recovery comparison, not a full old/new APK, search-quality or hardware-backend benchmark.

The controlled corpus distinguishes draft acceptance from final recovery: a bad or fabricated draft must remain rejected even when BI extracts a different, correct answer from its source. Publisher-blocked/no-evidence and historical-only controls must not become confident answers. Native tests use real Qwen3.5-2B Q4 CPU generation and real token counting in the isolated validation installation. Its actual draft is assessed by both verifiers, with no extra native generation for the baseline.

Final measured results and package/submission details are recorded below and in `release-v30.json` after validation. Process PSS samples are not transient peak allocations; no battery or new GPU/NPU performance claim is made.

## Exact implementation and test paths

- `app/build.gradle.kts`
- `app/src/androidTest/java/com/example/ChatFirstNavigationTest.kt`
- `app/src/androidTest/java/com/example/ProcessRestorationDeviceTest.kt`
- `app/src/androidTest/java/com/example/V29CitationSupport.kt`
- `app/src/androidTest/java/com/example/V29ClaimVerification.kt`
- `app/src/androidTest/java/com/example/V29EvidenceAnswer.kt`
- `app/src/androidTest/java/com/example/V29GroundingPolicy.kt`
- `app/src/androidTest/java/com/example/VerificationDeviceTest.kt`
- `app/src/main/java/com/example/MainActivity.kt`
- `app/src/main/java/com/example/data/SettingsPreferencesRepository.kt`
- `app/src/main/java/com/example/grounding/CitationSupport.kt`
- `app/src/main/java/com/example/grounding/ClaimLanguage.kt`
- `app/src/main/java/com/example/grounding/ClaimVerification.kt`
- `app/src/main/java/com/example/grounding/EvidenceAnswer.kt`
- `app/src/main/java/com/example/grounding/EvidenceRecovery.kt`
- `app/src/main/java/com/example/grounding/EvidenceRepair.kt`
- `app/src/main/java/com/example/grounding/Grounding.kt`
- `app/src/main/java/com/example/grounding/ResearchDiagnostics.kt`
- `app/src/main/java/com/example/grounding/VerificationDiagnostics.kt`
- `app/src/main/java/com/example/ui/AiCoreViewModel.kt`
- `app/src/main/java/com/example/ui/components/BiAvatar.kt`
- `app/src/main/java/com/example/ui/screens/SettingsScreen.kt`
- `app/src/main/java/com/example/ui/theme/Theme.kt`
- `app/src/main/res/drawable-nodpi/bi_avatar_light.png`
- `app/src/test/java/com/example/AnswerQualityTest.kt`
- `app/src/test/java/com/example/GenerationPreferencesTest.kt`
- `app/src/test/java/com/example/ResearchFollowThroughTest.kt`
- `app/src/test/java/com/example/ResearchReliabilityTest.kt`
- `app/src/test/java/com/example/V29CitationSupport.kt`
- `app/src/test/java/com/example/V29ClaimVerification.kt`
- `app/src/test/java/com/example/V29EvidenceAnswer.kt`
- `app/src/test/java/com/example/V29GroundingPolicy.kt`
- `app/src/test/java/com/example/VerificationRecoveryTest.kt`
- `app/src/test/resources/verification-corpus.json`

## Measured HONOR results (7 November 2026)

HONOR VKP-NX9, Android 16, approximately 12 GB RAM. Production installation and its conversations were preserved. Tests used the separate `.validation` app and checksum-verified local Qwen3.5-2B Q4 weights on CPU.

Twenty-six controlled cases comprise 19 answerable questions and 7 negative/control cases. Each implementation ran twice in alternating order (52 rows each) against identical evidence and drafts. The v29 final citation gate was frozen too. Rates below deduplicate the two trials where both agree.

| Outcome | Version 29 | Revised |
| --- | ---: | ---: |
| Answerable cases with some cited supported information (including partial) | 19/19 | 19/19 |
| Answerable cases rated fully cited/complete | 3/19 | 18/19 |
| Remaining genuinely partial answerable comparison | — | 1/19 |
| Final syntax/number citation validity on answerable trials | 38/38 | 38/38 |
| Negative/control cases incorrectly rated fully cited | 3/7 | 0/7 |
| Verification/recovery median / p95 over all 52 trials | 101.96 / 144.82 ms | 258.86 / 386.40 ms |
| Highest after-call process PSS sample | 478 MiB | 462 MiB |

“Some supported information” does not imply a complete answer. V29 frequently already had partial quotes but used a generic unverified suffix or lost the requested value. The revised result improves complete-answer verdicts while removing the tested false confident conclusions. The raw report’s `expectedFact` flag is an exact substring check, which undercounts some legitimate paraphrases; it is not used here as semantic fact recall. These are controlled fixtures, not a predicted general-web accuracy rate. PSS samples are not peak allocations or proof of a causal memory reduction.

Three actual Qwen generations (RAM requirement, negative image capability, two-model RAM comparison) were processed by both pipelines, with the same prepared sources and actual tokenizer. All three v29 answers were partial; all three revised answers were fully cited with valid source mappings. Final revised outputs were a concise conditional RAM statement, an explicit negative capability statement, and two separately cited comparison values. No second generation or judge model ran for verification. Baseline post-processing was 90.22 / 71.70 / 152.42 ms; revised was 352.95 / 172.82 / 452.24 ms. Shared native first-token times were 6.50 / 4.88 / 9.60 seconds, with 17.76 / 18.09 / 16.51 tokens/s. This is a reliability improvement, not a measured verification-speed improvement. The additional work remained under half a second in these three runs.

Raw controlled reports: `verification-device.json`, `verification-native.json`, `verification-web-native.json` and `verification-live.json`. They contain only synthetic prompts/facts, public live-test topics and safe diagnostics; no installed-app private transcript was committed. Failure counters distinguish missing syntax, invalid IDs, unsupported entities, values, polarity, conditions and relationships. Source-not-readable and no-source controls remain separate failures.

## Tests and limits

- Final full Kotlin/JVM suite: **302 tests, 301 passed, 1 existing opt-in live test skipped, 0 failures**. Real network tests ran separately on the phone. Release lint, debug APK and Android test APK builds passed.
- Rust host tests: **31 passed**. Native inference and Rust algorithms were not rewritten.
- **16 distinct relevant phone tests passed**: matched verification corpus, actual Qwen draft comparison, nine Rust/native lifecycle/policy tests, two navigation/theme/draft checks, keyboard insets, and two controlled/live web reliability tests. UI covers the two appearance choices and absence of the old separate DARK option.
- The first broad phone run exposed a genuine final-gate bug: a programmatic partial-answer notice was treated as an uncited factual paragraph. The notice/allowlist was corrected and a regression added. Initial SmolLM2-135M generation also returned an unreadable reply in a local mixed task with no web evidence; the final native web-recovery test passed with its supported Qwen model argument. This does not claim all tiny-model outputs are reliable. No evidence-backed recovery can manufacture missing sources or guarantee a local model’s knowledge.
- Two older tests that required a source-extract/source-brief flag were updated to require a fully cited answer and valid citations when the new mapping/recovery genuinely answers the requested facts. Existing wrong-role, wrong-backend, incorrect value/date, photo-weather, fabricated-action, cancellation and privacy assertions remain.
- The thin fast path has no separate semantic model. Deterministic equivalences and templates are bounded; unfamiliar paraphrases may use extractive recovery. Publishers can be inaccessible or incorrect. Unavailable live counts, current loans, or ambiguous user metrics must remain unresolved rather than guessed. Live-source outcomes and the final signed package/review status are recorded separately.

The final citation gate recognises exact app-owned omission notices, not arbitrary uncited factual prose. Exported diagnostics distinguish verified evidence recovery from partial source summaries. Search/extraction budgets, web consent, robots/access controls, Binder compatibility and native model ownership remain intact.

Release-only records: `docs/2.5/bundle-v30-validation.json` and `docs/2.5/release-v30.json`. Version 30 was submitted to Closed testing – Alpha with managed publishing off and quick checks pending. No new account declaration or ignore-issue exception was submitted for this version.
