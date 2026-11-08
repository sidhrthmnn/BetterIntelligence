# Scheduled public information and research recovery

## Audit and root causes

Baseline: main `9128d48`, containing version 29 extraction and the later readable-answer fixes.

The routing policy already recognized many public lookups and protected private,
offline and writing tasks. Rust consumes admitted routing signals and bounded
numeric passage scores; it does not independently decide web permission or fetch
publishers. This change retains those inference and permission boundaries. The separately requested UI update is documented in [SIMPLE_UI.md](../ui/SIMPLE_UI.md).

The event failure has several distinct causes:

* JSON-LD extraction allowed articles/products but omitted Event types. Generic
  passage selection lost the association between a calendar card and its start.
* Relative-date freshness checked publication/update dates. An old calendar with
  a valid upcoming occurrence could be rejected as stale.
* No temporal verifier selected starts after the current instant, distinguished
  date-only starts, or excluded cancelled/postponed occurrences.
* Discovery failure flags stopped later reformulations. The reviewed BI source
  directory is a limited fallback, not a global event index.
* Robots retrieval failures and explicit denials both became a Boolean false;
  several HTTP statuses became the same publisher-blocked failure.

## Flow

`AnswerRoutingPolicy → ResearchPlanner/EventQuery → DiscoveryChain → permitted
publisher read → EventEvidence → EvidenceRanking → EventEvidencePacket → local
generation → EventAnswer`.

The existing article/extraction/claim pipeline remains in use for ordinary
questions. Event queries use explicit occurrence records and deterministic
evidence-first answering. A weak native draft cannot invent or erase the schedule
records. The final citation IDs come from the read publishers, never snippets.

Event planning carries the public subject/aliases, requested fields and time
constraint. It prepares at most three distinct queries: schedule, official
calendar/fixtures, and venue/address/start information. No venue, performer, event
date or publisher URL is hardcoded. A separately read venue profile may establish
a district alias; its citation is retained alongside the event citation.

`EventEvidence` reads bounded static JSON-LD (`@graph`, ID references, nested
addresses, arrays of types, sub-events), event microdata/cards, and explicitly
labelled event tables. It never executes scripts. Series without dated
occurrences are not expanded. `datePublished`, `previousStartDate` and `doorTime`
never substitute for `startDate`. Source snippet/robots policies still apply.

Explicit UTC offsets are preserved. Unique-zone countries can establish a zone
for a floating local time; multi-zone countries cannot. DST folds/gaps without
an offset stay uncertain. Missing times stay missing. Cancelled/postponed or
already-started occurrences are excluded; date-only records produce a partial
answer rather than an invented midnight. Answers describe the earliest *verified
listing*, not a guarantee that every possible event was indexed.

A publisher can disagree with itself: structured markup may give a different
hall from its visible card. BI preserves both labels, attributes the visible one
and returns a partial answer. A calendar missing a district can follow a permitted
same-origin directions/profile link to establish that alias, with both source IDs
cited. A publisher-name/domain match is a ranking hint only, not certification. Verified district matches outrank unresolved venue pages. Profile selection favours read same-publisher location evidence; another page of that calendar cannot displace the alternative publisher’s incomplete lead.
Equivalent local clocks with an omitted zone are incomplete rather than contradictory. A conflict about an unrelated later occurrence does not erase the next verified start.
Date-only leads do not stop recovery merely because a later event has a time.
When a lead remains incomplete, BI retains it alongside a fully timed verified
listing and states that precise ordering is unresolved.

## Discovery and access recovery

`ConfiguredDiscovery(name, DiscoveryProvider)` is an installation-supplied
extension interface. Pass permitted implementations in
`PublicWebResearch(alternativeProviders = …)`. No new external provider, server,
private key, proxy or search-page scraper is bundled. The default remains Exa
plus BI's existing reviewed source directory. DuckDuckGo Instant Answer is not
used as a general search engine.

Provider caches/circuits are isolated by provider and query. Failed/empty primary
results can fall through to configured providers. One inherited monotonic
deadline includes provider waits, existing bounded Exa backoff/retry, robots,
publisher reads and recovery. The chain caps actual calls at six and distinct
query rounds at three. Primary timeouts reserve time for configured alternatives.
The HTTP session retains its 24-request cap and page/child quotas.

An inaccessible publisher does not invalidate earlier evidence. Research tries
other candidates and, when necessary, a different query within the remaining
budget. Explicit robots denial and unavailable robots policy both prevent reads,
but produce different diagnostics. HTTP 401/403/429/451, DNS, TLS, timeout,
challenge, unsupported content, empty extraction and relevance rejection remain
separate categories. No access-control or certificate bypass is used.

Diagnostics export aggregate route/provider categories, discovered/attempted/read
counts, extraction/rejection counters, access reasons, recovery count, verification
outcome and duration. It excludes queries, source content, URLs and credentials.

## Limits

Publisher markup can be incomplete, wrong or stale. Reading a publisher verifies
what it reports; it does not prove reality or global completeness. Conflicting
listings are attributed and remain partial. Recurrence expansion, arbitrary
JavaScript apps, login-only pages, CAPTCHA, PDF calendars and ambiguous unsupported
timezones are not silently inferred or bypassed. Without a functioning configured
discovery provider and accessible matching evidence, BI reports the precise gap.

Validation results and measured limits are in [VALIDATION.md](VALIDATION.md). Synthetic
fixtures are not live website or physical-device performance measurements.
