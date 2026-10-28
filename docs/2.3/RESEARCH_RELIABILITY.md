# Research answer reliability (19 / 2.3.0-beta4)

## Changes

- Public place references such as “Who is the mayor there?” resolve from this
  conversation's retrieved source titles. A different conversation does not
  inherit the location. Missing places trigger a clarification instead of a guess.
  A source-confirmed public place is retained in optional source metadata so a
  generic next-page title does not erase it. Old source heading paths are also
  readable; no database or Binder migration is required.
- Planning, evidence preparation and final verification use the same resolved
  question. A checked response is saved once rather than checked again against
  an unresolved follow-up.
- Temperature questions use web research in Automatic mode. Weather prompts ask
  for temperature, units, location and observation time before secondary details.
- The HTML reader preserves labelled BBC observation temperatures and today's
  forecast high/low, with Celsius/Fahrenheit distinguished by the visible DOM's
  unit labels. Photo-gallery readings are excluded. There is no weather API.
- Rolling forecast pages are discovered without a news-publication filter;
  today's forecast or a dated observation can establish weather freshness without
  inventing a publication date. Office-holder lookups likewise do not require
  the role page to have been published within the last month.
- Relevant office sections rank ahead of differently qualified roles such as a
  Lord Mayor, deputy or former office-holder. Invisible word separators are
  removed from extracted text. Reference lists and share widgets are excluded.
- A generated claim must still pass subject, citation, number, unit, polarity
  and qualification checks. A source for one city cannot substantiate another.
- If generated prose cannot pass checks but useful passages were retrieved, BI
  can display short **verbatim source excerpts**, clearly labelled as quoted
  evidence. It does not assign inferred citations to the rejected model prose.
  Weather excerpts can also supply a temperature omitted from a partial answer.
  Unsupported comparisons, conflicting numerical reports and unrelated passages
  do not gain this fallback. Missing temperature is stated explicitly.
- Streaming web prose is labelled as a draft while checking is pending.
- Current-role checks recognise CM/PM and “who's”, and reject a historical holder
  as an answer to a current-role question. Generic news asks for a topic before
  discovery. Historical durations and public scientific explanations can use
  evidence in Automatic mode. Local prompts preserve conversational tasks,
  allow harmless games and avoid unsupported tool/capability claims.
- Representative finance examples cannot be presented as product-specific
  payments while dropping the example purchase amount and qualifications.
  Short visible price fields bind to their nearest matching product card, and an
  explicit locale path can establish market scope without supplying any price.
  Finance qualifications are not applied to unrelated illustrative documentation.
- Static publisher `noscript` fallbacks are read as HTML content; scripts and
  iframes are still removed. Byte-budget clipping preserves paragraph boundaries
  so one product/role's passage cannot be merged into the next.
- English inflection matching handles registered/registration and transferred/
  transfer consistently. New conversation IDs are published after their
  suspending activation work, so an immediate first send is not silently dropped.

No Binder/AIDL, model loader, download or TypeRight interface changes are needed.

## Validation

`ResearchReliabilityTest` covers public follow-up resolution, conversation
isolation, temperature routing, units/time/forecast preservation, exclusion of
photo readings, source-subject mismatches, fallback attribution, missing data,
unsupported comparisons and qualified office sections. It runs with the normal
JVM suite. Optional `BI_RELIABILITY_CAPTURE` points at captured public HTML for
additional diagnostics; no captured private conversation is a test fixture.

`ResearchReliabilityDeviceTest` is opt-in (`researchReliability=true`) and requires
the `.validation` package plus a checksum-verified `gemma-4-E2B-it.litertlm` in its
models directory. It uses the real chat ViewModel, public HTML websites and local
CPU inference. It records final persisted answers, draft text, resolved queries,
sources and timings in `files/research-reliability-phone.json`. Cases cover
weather, mayor/temperature follow-ups, IANA restrictions, UK PM/Kerala CM,
UK product pricing, news clarification, arithmetic, unrelated-source refusal,
cross-chat location clarification and a controlled multi-turn game.

## Limits

These checks are deterministic safeguards, not general semantic entailment or a
guarantee that a publisher is correct. Extraction depends on public page markup;
blocked, inaccessible or changed pages can still prevent an answer. Observations
are labelled with their reported time; forecast highs/lows are not described as
current measurements. The phone evaluation is a small functional sample, not a
general accuracy or performance benchmark.
