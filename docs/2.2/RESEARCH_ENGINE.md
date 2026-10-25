# Better Intelligence 2.2 research engine

## Audit of beta5 (842b46e)

The audit covered PublicWebResearch, ResearchQuery, Grounding, HtmlDiscovery, WebPageExtractor, ResearchRobots, CitationSupport, ConversationalResearch, AiCoreViewModel, source UI and the beta5 validation records. Existing transport, local inference, session memory and source storage were retained.

| Beta5 limitation | Change in 2.2 |
| --- | --- |
| One keyword query and all-question matching excluded useful single-facet documents | Deterministic intent/entity planner, question decomposition and up to three focused searches |
| Discovery website embedded in orchestration | DiscoveryProvider interface and HtmlSearchDiscovery adapter; direct URL reading remains separate |
| Domain suffix gave an authority bonus | Removed suffix bonus; document-type cues, subject relevance, coverage, completeness and publisher diversity guide selection |
| Repeated high-overlap results could crowd out requested facts | Greedy collection selection rewards missing facets and distinct registrable publishers |
| Generic windows and prefix truncation could omit a late answer or table meaning | Heading-aware paragraphs, table headers with rows, and focused excerpt re-selection for smaller prompts |
| Unknown citation IDs were silently removed from otherwise accepted prose | Reject unknown IDs, unsupported numbers and substantial uncited paragraphs; literal code remains excluded from marker parsing |
| Search failures collapsed to one message | Discovery unavailable, inaccessible sources, timeout and insufficient relevant evidence have different user messages |
| Follow-ups only recognised English pronouns | Malayalam references included; only a short existing public title is reused, with credential/control/instruction-pattern checks |
| No reproducible retrieval comparison | Frozen test-only beta5 algorithms and 40-case synthetic fixture corpus, plus separate opt-in live physical-device probes |

The beta5 report already documented a cited but semantically inaccurate IANA answer. This release does not relabel valid citations as verification. Numeric support and passage relevance remain narrower than full claim entailment.

## Planning and discovery

ResearchPlanner identifies factual, recent, explanation, comparison, multi-part and direct-URL requests. It extracts quoted names, compared products (including dotted/lowercase names), versioned names and identifiable capitalised entities/locations. This is a lightweight heuristic, not a general named-entity recogniser. Non-English scripts are retained in outgoing queries; English inflection matching applies only to Latin words. Single-digit and decimal versions are retained. Malayalam morphology and Chinese segmentation remain limited.

Technical model comparisons independently request runtime/format compatibility, RAM/quantisation, and measured speed/latency/benchmark conditions. Multi-part questions produce smaller focused queries with a public subject where needed. No second model, embedding network or cloud planner is allocated. Entity/facet matching is lexical and deterministic; there is no unmeasured claim of neural semantic ranking.

HtmlSearchDiscovery alone knows DuckDuckGo's public HTML URL and recognised HTML/lite result layouts. Search snippets never become evidence. Unknown markup, challenge or denial stops discovery; there is no CAPTCHA, sign-in, paywall, proxy or access-policy bypass. Canonicalisation removes known tracking parameters/fragments while retaining meaningful identifiers. At most one reformulation is attempted for empty readable results within the three-search total. Direct public URLs do not send the question to discovery.

## Mobile budgets and transport

- Three discovery pages maximum, including reformulation.
- Eight publisher candidates maximum; at most two from one registrable domain.
- Two concurrent publisher reads in waves of four; early stop requires enough sources and requested facet coverage.
- At most one relevant same-origin child read per operation.
- Production SearchHttp session: **24 HTTP requests total**, including robots and every redirect hop; quota checked before sending the next request.
- Coordinator deadline 30 s; discovery operation 5 s, publisher operation 7 s, robots fetch 3 s. Parent cancellation propagates to the underlying OkHttp call.
- Existing public HTTPS URL rules, public DNS resolution, manual redirect validation, 1 MiB response limit and 4-hop limit retained.
- Robots checks occur before every page/redirect read. Per-origin policy locks and bounded policy cache are retained; inaccessible/challenged policy is not crawl permission.

Custom fixture transports need not emulate wire-level quota; production SearchHttp does. A request can fail before all eight pages are reached if redirects/policies exhaust its quota. No new dependencies or model RAM requirements are introduced.

## Extraction, selection and evidence prompt

The reader preserves heading context, neighbouring qualifications, exact dates/numbers/units/negation and table column headers with rows. Passage selection rewards both requested facets and coverage of the actual comparison subjects; generic platform words cannot qualify a different runtime's section. Visible publication/update bylines precede selected body passages. Structured date metadata is page-reported and can guide freshness, never substitutes for answer text or verifies a publication claim. Navigation, hidden content, ads, scripts, forms, banners and repeated blocks are removed. Existing snippet restrictions and unsupported-format/challenge rejection remain.

Collection ranking combines query/entity matches, facet coverage, identifiable document-type cues, excerpt completeness and independent publisher representation. A `.gov` or `.edu` suffix alone gives no bonus. Identical excerpts are deduplicated while preserving numeric differences. Different hosts may still share ownership or syndicate content; registrable-domain diversity is a practical approximation, not proof of independence.

A narrow contradiction heuristic identifies differing numeric values with the same explicit subject/predicate and unit across publishers. The prompt warns about these reports and asks the model to state uncertainty and compare conditions. It cannot recognise all contradictions or determine truth. Reported freshness does not prove an unchanged page or measurement is current.

GroundingPolicy re-focuses excerpts when reducing evidence for a small context. It compares the actual subject/facet coverage of fitting excerpt plans, instead of choosing the first plan with three severely clipped sources. It returns exactly the sources/excerpts actually supplied. Chat checks both UTF-8 byte allowance and the active engine's token count after system/history/output reserve. Sources are JSON data clearly labelled untrusted; the system says to ignore page instructions. Missing facets and detected conflicts produce concise cautions. This reduces injection risk but cannot guarantee a small local model will never follow hostile page text.

Final checks reject fabricated source IDs, numbers absent from the cited paragraph's supplied title/body, marker-only answers and substantial uncited paragraphs. Page metadata dates alone do not substantiate a date answer. These checks do not verify units, arbitrary semantic claims, negation or a publisher's accuracy. Uncited headings/short labels and explicit uncertainty are allowed. The UI continues to say **Sources cited · Check the evidence**, with expandable source cards that now show the actual excerpt.

## Privacy and compatibility

Off/Auto/On and global consent are checked before provider invocation. Auto now recognises explicit comparisons as research requests; creative requests remain local by default. Up to three queries derive from the current question. A contextual follow-up can use a short already retrieved public title from the same chat; private stored conversation messages/global memory never enter discovery. Obvious credentials/private URLs are blocked; the credential heuristic cannot detect all sensitive user-supplied material.

Temporary query/evidence cache remains RAM-only, eight entries/five minutes, with explicit deletion. Source excerpts attached to a conversation persist under existing deletion rules. Search questions/URLs/text are not application logs. Privacy disclosure now describes focused queries and request bounds.

No AIDL/Binder signatures, permissions, client scheduling, model loading, native GPU/CPU lifecycle, GGUF/LiteRT runtime, downloads, or chat-memory schema changed. Connected-app inference remains local and never calls web research. Existing scrolling, editing, draft persistence and streaming lifecycle remain in place; the source dialog's excerpt scrolls independently.

## Evaluation

See [validation and measured comparison](VALIDATION.md). The offline fixture suite measures retrieval evidence coverage and request/latency costs, not real-world factual accuracy. Live generation requires explicit instrumentation opt-in and actual device/model/network availability. Neither passing fixtures nor valid citation IDs establish universal answer accuracy. JavaScript-only, restricted, denied or unindexed sources can still be unavailable; BI gives an honest failure response.
