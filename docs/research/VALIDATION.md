# Validation record

Date: 8 November 2026. Baseline: main `9128d48` (including version 29 extraction and subsequent fixes). This is a feature-branch review, not a Play release.

## Automated checks

* Android validation debug APK and instrumentation APK: arm64-v8a and x86_64 native builds.
* Kotlin: 369 tests, zero failures/errors, one optional test skipped (JUnit XML), including event planning, extraction, source failure/recovery, ranking, venue context, temporal verification, citations and response budgets.
* Android lint: passed. Existing Kotlin nullability/deprecation and SDK-tool metadata warnings remain.
* Rust: 31 tests passed (2 unit, 29 integration), `cargo fmt --check`, and Clippy with warnings denied passed. Rust source was audited and not changed.

The eight requested public-question scenarios use deterministic clocks and mocked publisher/provider responses. The PM, Android-release and weather values in those fixtures are deliberately synthetic. They are not live factual answers or measured production reliability rates.

Commands (using the project's Android/JDK/NDK and Rust toolchains):

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug -PbiValidationPackage=true -PbiIpcValidation=true --no-daemon --no-configuration-cache
python scripts/build_rust_core.py --test
```

## Physical HONOR checks

All tests used `com.aistudio.betterintelligence.mobile.validation` on the HONOR VKP-NX9 running Android 16. The normal Play installation, chats, app permissions and downloaded models were preserved.

* `SimpleUiDeviceTest`: passed all ten destinations in light and true-black dark; twenty captures. Research diagnostics remained reachable through the expandable tools panel.
* `ModelDiscoveryCardUiTest`: passed search, result card, version selection and download callback at 1.3× font scale. Low available RAM did not disable downloading for later use. This uses a synthetic file and does not measure network download reliability.
* Both `ChatFirstNavigationTest` methods passed: model/research navigation, unsent draft after navigation and activity recreation, and both appearances.
* Qwen 3.5 2B Q4_K_M, CPU, four requested threads: the fixed 16-token request stopped at 16; chat expansion emitted 189 tokens and finished the 1–50 sequence once, with an expanded 256-token ceiling and no repeated prefix. This measures continuation correctness, not a GPU/NPU speedup.

The opt-in tests require `simpleUi=true` or `webReliability=true` where applicable. They refuse to run against the production package. Public-question test artifacts stay in the validation app; production diagnostics export only aggregate categories and counts.

## Live research

The exact public regression is `When is the next event in O2 Greenwich?`. Live Exa discovery, permitted publisher reads and local Qwen inference are exercised together. Publisher markup was also captured separately on the development machine and passed through the extractor without bundling copyrighted HTML in the repository.

Initial phone checks reproduced both the generic event-verification failure and an incomplete third-party answer. They exposed premature stopping on a later timed event, loss of district evidence during ranking, arbitrary profile selection, and false conflicts between floating/offset representations. Those cases now have deterministic regressions.

Final live result: **passed**, 8 November 2026 at approximately 20:30 UTC. The final evidence contained the official calendar, its directly read directions/profile page, and Songkick. BI returned a fully timed official listing plus an earlier/same-day date-only third-party lead, with valid mapped citations and an explicit limit on precise ordering. It did not fabricate the missing time or return the generic verification failure.

The physical test asserted an upcoming event whose name appears in the final answer, the official calendar's presence and valid final citations. Qwen's recorded generation latency was **52,305 ms** at **13.44 tokens/s**; the entire instrumentation method took **66.111 seconds**, including setup, discovery, reads, generation and UI assertions. These are observed CPU measurements, not estimates or a controlled before/after speed benchmark. First-token latency was not collected for this event run.

## Browser prototype

The self-contained model-browser prototype was exercised at 320 and 390 CSS pixels: search/details, filters, simulated download/pause/resume/completion, low/high RAM previews and light/dark appearance. No horizontal overflow or browser-console errors were observed. Its model/device values are illustrations; Android cards use actual repository and device state.

## Practical limits

A finite publisher sample cannot establish an exhaustive global event calendar. Missing start times, zones or conflicting halls/dates remain explicit partial answers. There is no bypass for robots restrictions, unavailable robots policy, CAPTCHA, private networks, TLS validation, login-only or JavaScript-only pages. Additional discovery providers need a permitted configured implementation; no new provider key or endpoint is silently installed.

Only the named live event query and local-model budget check are new physical inference measurements in this change. The full eight-question matrix is fixture-based, and no population-level answer-success rate or GPU/NPU performance claim is inferred from it.
