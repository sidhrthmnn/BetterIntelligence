# Research topics and readable grounded answers — version 27

Version 2.5.0-beta2 / 27. The audit started from main `8cbfe6f06d1275b4cb9dc675d787eb031e472692` and traced Compose input, Room history, routing, query planning, Exa discovery, publisher extraction, ranking, evidence fitting, local generation/repair, verification and final rendering.

## Root causes found

1. The review route recognised `how is … movie` but missed `how's … movie`. `Find review` was neither a recognised subjectless command nor a resolved follow-up. The Rust route policy received the already incorrect Android intent flags; changing inference backends would not fix this.
2. Follow-up expansion depended on recent source titles/public queries. A first local answer had no public topic to reuse. An unrelated retrieved title could then become the subject of a later query. User-authored topics were not represented explicitly.
3. Candidate relevance accepted any matching query word. Generic `review` could admit an iPhone page for a film request. There was no shared film identity/task requirement across discovery, a custom provider, cached evidence and verification.
4. Source fallbacks deliberately rendered blockquotes and internal retrieval headings. Their word-count/term-overlap requirements also rejected a short, useful labelled rating. A physical test reproduced `INSUFFICIENT_EVIDENCE` despite an Indian Express page visibly containing `Rating: 2 out of 5` for the requested film.
5. Audience comments and overview pages could satisfy the stopping rule before fuller criticism was read. Model output could then lead with an incidental comment while omitting the film's name and the overall assessment.
6. Enabled research still used the selective AUTO path, and fresh query/source caches could avoid a new lookup. The user's subsequent instruction requires a fresh public fact check before answering when grounding is enabled.

## Implementation

| Files | Change |
| --- | --- |
| `PublicResearchTopic.kt`, `ConversationalResearch.kt` | Bounded structured subject/type/language/intent, conversation ID and user message ID derived from at most 12 user turns. Resolve reviews, cast, ratings, release and sequel follow-ups. A new explicit task ends the prior topic. Assistant prose and wrong source cards cannot rename a film. |
| `AnswerRoutingPolicy.kt`, `LocalAnswerUncertainty.kt` | Normalise contractions; route named public review/fact requests; clarify missing subjects; recognise first-person uncertainty and search recommendations for one bounded recovery. Respect private/offline admission. |
| `ResearchQuery.kt`, `ResearchPlan.kt`, `ExaDiscovery.kt`, `PublicWebResearch.kt` | Retain the subject/language/task in up to three variations, prioritise critic-review discovery, and stop generic subjectless commands before network access. A nearby romanised spelling is only an explicitly unverified clarification candidate. It never becomes evidence or silently substitutes another film. |
| `FactualResearch.kt`, `EvidenceRanking.kt`, `Grounding.kt` | Require named entity alignment and review-specific body evidence at the discovery/reader/provider/verification boundaries. Reject explicit language conflicts. Distinguish obvious audience responses and overview pages when ranking. Enabled ON mode bypasses query/source reuse and checks again on each factual turn. |
| `EvidenceAnswer.kt`, `ReadableSourceSummary.kt`, `MovieRatingEvidence.kt`, `SourceBrief.kt` | Prefer verified, focused review prose. Preserve the requested film's scope. Fallbacks use short attributed complete sentences, preserving qualifiers, negation, units and timing. Labelled numeric rating fields have a separate bounded path tied to the matching film, publisher, value and explicit scale. No uncited model claim receives an invented citation. |
| `WebPageExtractor.kt` | Exclude decorative `rating icon`/`star icon` alt text while preserving actual readable rating values. Existing publisher, redirect, robots and private-network restrictions remain. |
| `ChatAnswerPolicy.kt`, `AiCoreViewModel.kt`, `AnswerSourceStatus.kt`, chat/settings UI | Request readable synthesis and attributed opinions; show enabled fact checking; retain actual final source status and partial-answer labels. Keep original user messages unchanged. |
| `ResearchDiagnostics.kt`, `PRIVACY.md` | Separate app-private cache of at most 48 public original/resolved queries, IDs, route/retrieval/verification outcomes and lexical relevance scores. No generated text, publisher passages, private history or detected sensitive query is logged. Clearing search data/disabling web and deleting a conversation remove the relevant records; a generation fence prevents in-flight writes from undoing a clear. |

Global web permission remains off until enabled. Once enabled, public factual chat answers use ON/fresh checking, including explanations. Writing-only tasks, arithmetic, greetings, private context and explicit offline requests stay local. There is no new provider, paid API, dependency, schema migration, inference kernel or Binder change. Exa discovery and on-device Gemma/llama.cpp/LiteRT remain the existing components. The reader still caps discovery at three rounds, page requests at 24, publisher reads at eight, concurrency at two and elapsed research time at the existing deadline.

## Test coverage

`FilmResearchTopicTest` covers the supplied contracted question, same-chat review/cast/rating/release/sequel references, conflicting assistant/source content, explicit topic changes, conversation isolation, subjectless commands, query variants and spelling clarification, wrong-film/language/body rejection, consent/offline behavior, public explanations in ON mode, one recovery signal, unavailable discovery, fresh checking instead of cache reuse, readable partial summaries, short rating fields and bounded/deletable diagnostics. Existing regressions retain numerical/qualifier/date/citation checks. Tests that asserted the old blockquote labels were updated to assert readable prose and the same underlying facts; legacy cache testing explicitly uses AUTO, while ON is tested to make a fresh call.

`FilmResearchDeviceTest` uses the separate validation package and real Gemma E2B CPU inference. One scenario injects deterministic synthetic pages, including a poisoned iPhone review, to test control flow and generation/verification reproducibly. Those fixture ratings/cast names are not real film facts. The other scenario uses actual Exa discovery and read publisher pages for the original title/follow-up and an identifiable Tamil film/cast. The two scopes must not be conflated.

## Final validation on 3 November 2026

Product build revision: `7b5e695d1ed6a106f2a91a7d31e2645bfea70fa3`. Signed release AAB SHA-256: `e70fd59fba3aca85af1f02a649ce181558d659f694743dfe75f7918685d5cf85`.

- **272 JVM tests passed**, no failures/errors. Debug app, instrumentation APK, signed release APK/AAB and Android release lint completed successfully.
- **HONOR VKP-NX9, Android 16:** both film instrumentation tests passed, covering eight controlled turns and four live turns with real Gemma 4 E2B CPU inference, 2,048 context and four threads. The initial live failures (scope drift and a rejected short rating field) were fixed before the final successful run.
- Original `batha` and `Find review` both performed discovery and retained the title. No confidently matching film was found; the app offered `Baththa` only as an unverified spelling clarification. It did not substitute an iPhone review or claim the films were identical.
- Live Maharaja review returned the Indian Express's explicitly labelled **2 out of 5** with a citation and a fuller-assessment limitation. The next cast turn retained Maharaja and returned Vijay Sethupathi with a read source. These are measured observations, not estimated results.
- **Six existing live research turns passed:** Bond casting reports, Jubilee line status and its follow-up, a saved London raincoat conversation resumed with `Ok`, direct London weather (including temperature), and Doom's actor. Partial reporting remained visible; the Doom answer was cited. Live status/weather can change after this test.
- **Three additional phone UI tests passed:** draft retention/navigation/themes, model and research settings, and real keyboard/composer insets.
- Bundletool validation, upload signature verification and package/manifest checks passed. All 20 native libraries across arm64-v8a/x86_64 retain at least 16 KB ELF alignment and packaged dependencies. No model weights are bundled. Exa's endpoint is present; obsolete DuckDuckGo/Mwmbl/Brave discovery endpoints are absent.

Sanitised question/status/source records are in [research-topic-device.json](research-topic-device.json); publisher passages and production conversations are not committed. Tests used only the separate `.validation` installation and synthetic chats. The Play Store app and its chats/models were not replaced or cleared. This change does not alter AIDL/Binder or inference kernels, and does not claim new GPU/NPU measurements. GitHub Actions was still running when local validation completed; local results above are independent of CI.

## Remaining limits

Entity and opinion matching are bounded lexical heuristics, not a general entity-linking or truth system. Nearby spellings need user confirmation; arbitrary cross-script transliteration is not silently guessed. A title shared by multiple films can still require a language/year or explicit source. `Batha` is not automatically changed to a differently spelled title. Rating fields describe what that publisher lists, not a universal or current audience consensus. Critic/audience priority uses page-title/domain signals and does not certify a reviewer's identity.

Public discovery and publisher access can fail or change, and limited context can omit part of a review. Such cases must retain supported information or clarify the gap, rather than substitute an unrelated review. No universal accuracy, performance, GPU/NPU or battery improvement is claimed by this change.
