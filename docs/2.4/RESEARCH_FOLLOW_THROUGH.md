# Research follow-through — version 25

The user's latest main commit (`044b87e`) was pulled before this change. Inspection of three conversations on the connected phone identified distinct problems: a public role lookup discarded useful reporting, a weather clarification lost the original task, and a live transit question was answered locally. No chat database or private UI dump is included here.

## Behaviour

- Public fact lookups, live service status and explicit website requests start research before local generation can refuse. Explanations, writing, private context, calculations, explicit offline instructions and disabled web permission retain their local behaviour.
- A weather question followed by a user-supplied place and an affirmative reply retains the original task and place, including conversations saved by an older build. The assistant's suggested city is never treated as the user's location. Changed tasks are not mistaken for place names.
- When useful publisher bodies are available but the full question cannot be settled, a brief source report retains up to two publishers' complete passages with citations and an incomplete-answer label. It does not invent a definitive conclusion. Search highlights, headline-only pages, betting markets and fan theories cannot establish casting facts. Conflicting values remain attributed rather than combined.
- The reader removes link-dominated navigation and publisher subscription controls. Report bodies and short official service-status values rank ahead of related-story links and station lists. A network page cannot lend another line's status to the requested line, and empty publisher templates are rejected. Explicit weather dates must match the requested day; an updated timestamp cannot turn next week's forecast into today's. Temperature units such as `16C` are recognised.
- Retrieval remains Exa-only, bounded and key-free, with BI's own public-page reader. Existing consent 6 remains valid. Connected-app inference, TypeRight polishing, AIDL/Binder and native model backends are unchanged.

## Validation

On 30 October 2026, **253 JVM tests passed with zero failures, errors or skips**, including eleven follow-through regressions. The debug app, Android test APK, signed production APK/AAB and release lint completed successfully. The opt-in `ResearchFollowThroughDeviceTest` passed one instrumented method containing six actual chat scenarios on the connected HONOR VKP_NX9, using checksum-verified Gemma 4 E2B LiteRT-LM on CPU, 2048 context and four threads. The installed validation APK hash matched the built file. [Sanitised measurements](android-research-follow-through.json) contain no private chats, UI dumps or publisher passages.

| Scenario | Observed final behaviour |
| --- | --- |
| Unconfirmed next-actor lookup | Two cited publisher reports about the casting process; full conclusion explicitly unconfirmed, no betting-market answer |
| Named live transit status | Retained the named line's actual reported service value, including its official page |
| Explicit website follow-up | Quoted the official line's short status instead of another line, station navigation or an empty template |
| Older raincoat → city → confirmation chat | Recovered the task and user-supplied city; retrieved dated forecast temperatures |
| Direct raincoat question | Retained forecast temperatures with units, without the contradictory missing-temperature notice or a forecast for next week |
| Previously failing Doom casting lookup | Completed a cited actor answer from read publisher evidence |

All six replies completed with read-page sources. These results include partial source reports, source excerpts and generated answers with unsupported details omitted; they are **not six complete independently verified facts**. Weather values were forecast values, not claimed live observations, and the clothing decision may still require user judgement. Short local-generation samples measured about 24.0–25.4 tokens/s and 2.8–6.7 seconds to first token, excluding search/page reading; these are not end-to-end or sustained-performance benchmarks.

The first launch attempt was safely refused for low RAM. Another attempt was interrupted when the validation package was updated outside the test. After restoring the exact build and obtaining enough memory, the final six-case run passed in 83.737 seconds. A successful earlier test run was also manually reviewed: it exposed a network-status section mismatch that was fixed and covered by the final regressions. This distinction prevents protocol success being mistaken for answer quality.

The signed production AAB is package `com.aistudio.betterintelligence.mobile`, version **25 / 2.4.0-beta5**, SHA-256 `8f820799268912a45abf37335eb701201bc9b04ae8f236dd22e390e5e8885bb9`. Bundletool, signature, manifest and native packaging validation passed. All 18 native libraries meet at least 16 KB ELF load alignment; only the optional Vulkan library depends on the Vulkan loader, and no model weights are bundled. The DEX endpoint scan found Exa and none of the former search API endpoints. Native backends and Binder interfaces did not change.

[PR 15](https://github.com/sidhrthmnn/BetterIntelligence/pull/15) merged into main as `8484678`. [Release record](release-v25.json): version 25 was uploaded to **Closed testing – Alpha**, release 19. Play Console showed **Changes in review**, with quick checks running before review and managed publishing **off**. Submission was queued successfully; Google approval/publication was not awaited. The preview's only warning concerned the absent deobfuscation map; minification is disabled.

Remaining limits: public pages can be blocked, stale, ambiguous or wrong; some questions have no confirmed answer. The source-report fallback quotes what a publisher actually says and labels the missing conclusion. These checks do not establish that every factual question will be routed correctly or every generated claim will be true. No new GPU/NPU performance claim is made by this release.
