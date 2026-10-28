# Research answer reliability (19 / 2.3.0-beta4)

## Changes

- Public place references such as “Who is the mayor there?” resolve from this
  conversation's retrieved source titles. A different conversation does not
  inherit the location. Missing places trigger a clarification instead of a guess.
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
weather, mayor/temperature follow-ups, IANA registration restrictions, arithmetic,
an unrelated-source refusal, cross-chat location clarification and its answer.

## Limits

These checks are deterministic safeguards, not general semantic entailment or a
guarantee that a publisher is correct. Extraction depends on public page markup;
blocked, inaccessible or changed pages can still prevent an answer. Observations
are labelled with their reported time; forecast highs/lows are not described as
current measurements. The phone evaluation is a small functional sample, not a
general accuracy or performance benchmark.
