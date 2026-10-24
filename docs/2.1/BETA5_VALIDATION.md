# Beta5 validation — 24 October 2026

Implementation: [direct web research and session context](DIRECT_WEB_RESEARCH.md).
Version: **2.1.0-beta5 / 10**. Production package: `com.aistudio.betterintelligence.mobile`.

## Build and artifact identity

- Debug/native app and complete instrumentation APK compiled.
- **133 JVM/Robolectric tests passed, zero failures/errors.** Coverage includes HTML discovery, blocked/challenged pages, robots deduplication, redirect permission before fetch, body limits/cancellation, date/byline extraction, numerical citation support, supported date format equivalence, unknown source IDs, model catalogue integrity/memory geometry, long-session notes and isolation, persistence, editing, Markdown and scrolling.
- `lintRelease` passed. Signed `bundleRelease` passed. Bundletool validation and manifest inspection passed; minimum SDK 24, target SDK 36, correct production package and version 10.
- JAR signature verified with the expected self-signed upload key. This normal upload-key trust warning does not change the app's HTTPS certificate validation.

Signed bundle: `D:\Codex\BetterIntelligence-2.1.0-beta5-v10.aab`, **90,307,768 bytes**.
SHA-256: `D2999FBB25264F52AA2EC0D6D9C452AB42A0907F50685861158350DA7AC050F1`.

Device-test APK: `D:\Codex\BetterIntelligence-beta5-device-validation.apk`, **125,108,282 bytes**.
SHA-256: `9C72E1C1A7A342F67DCE889CEB63881C1ECD9EF6F5A11B5402BE9100166112A5`.

The phone ran the same final production sources in the separately named `.validation` debug variant, with the client service not exported. It was not installed over the Play-signed production app. The signed production bundle was validated separately. Subsequent Play submission: [beta5 release record](BETA5_RELEASE.md).

## Connected HONOR results

Physical HONOR VKP-NX9, Android 16, approximately 12 GB physical RAM. The user closed unused apps, increasing available RAM from approximately 2.7 GB to 4 GB before the larger check. Production app data and chats were preserved.

**Eight substantive instrumentation tests/phases passed on the final APK:**

| Check | Result |
| --- | --- |
| Actual public HTML discovery and publisher reads | Passed; DuckDuckGo HTML, IANA, Android documentation and other publisher pages; no search/weather/grounding API or RSS calls |
| Qwen 3 1.7B GGUF | Passed full SHA verification, real CPU generation, `102` arithmetic, `Paris`, visible thought-channel absence, resource release |
| Recent publisher answer through actual local Qwen generation | Passed correct publication/update dates and usable source citation |
| SmolLM3 3B GGUF | Passed full SHA verification, real CPU generation, arithmetic, capital answer, visible thought-channel absence, resource release |
| Chat product | Passed same-chat recall, other-chat isolation, edit/regenerate/draft behavior, cancellation, client-facing core without chat history, public IANA reading/local answer, private URL rejection, web-off behavior, restored history |
| Keyboard | Passed real IME inset checks, multiline composer, gap/overlap within 3 dp and visibility after dismissal |
| Process restart, prepare | Passed saving draft, interrupted reply, model selection, client approval and separate-session context notes |
| Process restart, restore | Passed after actual force-stop; different process IDs (25197 → 25324), identical saved session notes and isolation from the active second session; synthetic fixture cleaned afterward |

Final grouped discovery/Qwen/recent-answer run: 3 tests, 46.324 s. SmolLM3: 9.333 s. Chat product: 138.055 s. Keyboard: 2.882 s. Process prepare/restore: 0.451/1.213 s. These durations include fixture work, not just inference.

## Recent-answer failure found and fixed

Question: when was the **October 2026 Android Security Bulletin** published and updated? Independently checked [Google's original bulletin](https://source.android.com/docs/security/bulletin/2026/2026-10-01?hl=en): **published October 5, updated October 8, 2026**.

The first real-device run exposed a reader bug: the visible date line was in a bare `<em>` after malformed paragraph markup, so it was absent from extracted evidence. Qwen then guessed patch/retrieval-like dates. The numerical guard rejected that answer; it was not counted as a successful grounded response.

The reader now preserves visible publication/update bylines, excludes site-wide notice banners, and places the byline before the bounded body passages. The next run returned the correct dates but spelled its citation as "source ID 1". BI now normalizes that explicit attribution only when ID 1 actually exists. The final run returned the correct dates and a working `[1]` citation; fabricated dates and unknown source IDs remain rejected by regression tests.

This is one measured successful recent-answer case, not evidence that every current question will be answered correctly.

## Measured new-model CPU probes

Two deterministic requests per model, four threads, 2,048 context, maximum 128 output tokens, temperature 0. Full file SHA and native geometry were checked on the phone. Both models released native resources after the fixture.

| Model | Mean first token | Mean decode rate | Peak app PSS observed | Model load |
| --- | ---: | ---: | ---: | ---: |
| Qwen 3 1.7B Q4_K_M | 1,005 ms | 19.12 tokens/s | 1,752 MB | 424 ms |
| SmolLM3 3B Q4_K_M | 1,883 ms | 11.52 tokens/s | 2,274 MB | 554 ms |

These are short CPU probes, not sustained throughput, cold-storage, battery-life or universal model-quality benchmarks. Memory sampling is approximate app PSS. Android telemetry reported nominal thermal state during these short checks. The new models' **Vulkan GPU performance was not measured**. Existing Vulkan/CPU fallback and LiteRT selection remain; this update adds no NPU claim or runtime.

## Remaining limits

- A mobile app does not build an independent index of the whole web. Public HTML discovery still depends on a website being available and permitting access. JavaScript-only/login/paywall/challenge pages can be unreadable; the app returns insufficient evidence rather than bypassing them. Weather has no API fallback.
- Citation formatting and numerical support are deterministic checks, not general semantic verification. In the IANA chat probe, Qwen correctly stated the documentation/registration purpose but also made an incorrect negative claim that the page lacked web-service details. The supplied evidence did contain those details. A source citation is not a guarantee of faithful paraphrasing; the UI continues to say "Sources cited · Check the evidence".
- Qwen recalled the synthetic session identifier but over-explained its meaning. SmolLM3 answered the arithmetic correctly but did not obey "number only" once. These optional GGUF choices are not promoted as universally better than the existing Gemma recommendation. Important writing/facts still need review.
- Per-session notes retain bounded quoted history and selected proposals; context/token limits still constrain recall. They do not expose other chats or Binder client data. No remote summarization or embedding API is used.

Local raw synthetic reports, build logs, recent-answer evidence and PixelCopy screenshots are retained under `D:\Codex\bi-direct-*` and `D:\Codex\bi-beta5-*`. Screenshots capture only the validation app window; unrelated phone notifications are excluded. No private phone text or bulk publisher HTML is committed.
