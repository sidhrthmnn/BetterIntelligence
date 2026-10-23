# Chat reliability and context update — 2.1.0-beta4

## Investigation and implementation

The supplied HONOR screenshot shows a genuine malformed decode: repeated `<unusedN>` and multimodal markers passed through as assistant text. The prior output path was LiteRT native conversation → `MessageCallback` → `Message.toString()` → `InferenceEngine` → ViewModel stream → periodic Room update → final assistant message. There was no common corruption check, so a native decoder returning nonsense could be displayed and saved as complete.

The [pinned 0.18.0 Message source](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.0/kotlin/java/com/google/ai/edge/litertlm/Message.kt) establishes that `toString()` joins content; it is not JSON serialization. This is **not** evidence that string conversion itself caused the reported corruption. The adapter now explicitly accepts only `Content.Text`; tool calls, multimodal objects and thinking channels are not answer text. LiteRT supplies the tokenizer and model chat template; the old hash-based `Tokenizer.encode` is not involved in LiteRT/GGUF generation.

The current [0.18.1 release](https://github.com/google-ai-edge/LiteRT-LM/releases/tag/v0.18.1) fixes a source-build embedding ABI issue, not this text-generation failure. The tested Android dependency remains 0.18.0. Upstream [Gemma GPU report 4001](https://github.com/google-ai-edge/LiteRT-LM/issues/4001) identifies FP16 digit corruption and an FP32 workaround. BI uses the public `EngineConfig.activationDataType` override for the exact SHA-verified standard E2B/E4B packages on GPU, with a separate compiled cache, additional shared-memory headroom and a new backend-policy cache version. This addresses a known precision hazard; it does not establish the cause of every malformed response on every device.

The standard Gemma packages remain pinned to immutable revisions and SHA-256 values. Their existing 1024-token CPU minimum, exported allocation accounting, text-only operation and CPU fallback remain. No tokenizer files are silently substituted, converted or downloaded.

`ModelOutputValidator` fails requests rather than removing control tokens and relabelling the result successful. A small stream look-ahead holds obvious suspect markers and catches sustained control streams, excessive replacement characters and pathological repetition. Ordinary text streams normally with a short buffer. Code and quoted examples remain text. The validation is shared by synchronous, streaming, GGUF, LiteRT and Binder paths. LiteRT's own session applies it before callbacks and benchmark qualification, so bad output cannot validate a GPU configuration.

An invalid LiteRT session is reset before reuse. GPU execution failure can retry once on CPU only if no text has been delivered; after visible text the request fails with a manual retry. Cancellation still waits for native `onDone`/`onError` before releasing a conversation. A failed chat response is an ERROR with a short explanation and regenerate/retry action; it cannot persist a corrupted stream as a successful answer.

## Chat and references

Assistant text renders directly on the page background. User bubbles are bounded to the available width. Streaming and completed answers share Markdown rendering; code blocks and tables scroll internally. The persisted pending assistant ID is used as the list key throughout the stream-to-final transition, preventing the separate streaming row from duplicating the saved answer.

The jump control is a circular arrow with an accessibility label. User input or accessibility scrolling disables following. Visibility uses approximately 160 dp to show and 48 dp to hide; content growth while following does not itself show the arrow. Content changes are frame-coalesced and scroll to the bottom sentinel, rather than requesting a remeasure from every layout change.

Only source IDs returned by BI's evidence parser can be activated as citations. Inline IDs identify their associated source. A collapsed Sources count expands to titles/domains, and source details preserve page-reported publication, update, search-reported and retrieval dates. Arbitrary model URLs do not become trusted links.

## Conversation context

Room schema 9 adds a context summary and its source-message boundary to each conversation. Migration 8→9 adds fields without rewriting messages, models or client approvals; 7→8→9 remains supported. Drafts, history, summaries, names and timestamps stay local. Conversations can be renamed and deleted independently.

Summaries are extractive quotations of older user statements, prioritising explicit subjects/preferences and recent older context. They do not ask a model to invent a compressed history. Newer messages override those quotations. Edits and regeneration invalidate the cached summary. The context manager reserves the output budget, selects recent complete turns and relevant earlier turns, and retains chronological order. GGUF uses the loaded vocabulary's real token counts plus a chat-template reserve; the native full-template limit remains authoritative. LiteRT 0.18 exposes no public tokenizer in its Kotlin Engine/Session API, so its admission estimate remains conservative UTF-8 accounting and the runtime's actual limit. This is a documented estimate, not exact LiteRT token counting.

Conversation summaries are separate from optional global memory snippets. The existing global memory control remains explicit and off by default. Binder clients do not receive chat history or chat summaries.

## Focused research and weather

Auto avoids ordinary creative/rewriting requests. A contextual follow-up can reuse a title from a public source previously retrieved in the same conversation; private stored messages and global memories are not copied into search requests. The existing bounded RSS discovery, public DNS, HTTPS/redirect checks, response limits and cancellation are retained. Publisher reads check cached robots policy and reject `nosnippet`, CAPTCHA/login walls and hidden/advertising/cookie boilerplate. Sources are ranked by relevance and date, with a modest government/university tie-breaker and duplicate-excerpt removal. These heuristics do not prove publisher accuracy or source primacy.

Current weather without a city asks for one instead of generating a guess. A typed city uses Open-Meteo's public geocoding/current-data endpoints only with web access enabled. Numerical answers are formatted directly from a successfully fetched, recent data response and include its timestamp, resolved city/country, source and attribution. Device GPS is never requested. Unknown/stale/unavailable data fail visibly. This is modelled weather data, not a sensor reading at the user's home. The API's non-commercial usage limits apply; advertising, subscriptions or commercial distribution require a suitable provider arrangement. Ordinary inference still runs locally.

## Validation records

Actual build, device results, release hash and limitations are recorded separately when validation completes. No mocked or emulator result should be described as physical GPU evidence. The supplied screenshot is evidence of a failure, not proof of a particular driver, package or precision fault.
