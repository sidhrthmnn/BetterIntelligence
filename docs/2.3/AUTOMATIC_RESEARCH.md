# Automatic factual research (20 / 2.3.0-beta5)

> Historical beta5 behavior. Version 22 uses [Exa/Mwmbl discovery with updated permission](../2.4/SEARCH_DISCOVERY_EVALUATION.md). The descriptions below document the earlier release.

The casting report exposed two independent failures: Auto's narrow changing-fact
rules skipped an ordinary factual question, and the extractive fallback accepted
a matching theory headline when no actual casting passage was available.

## Behaviour

- All conversations open with Automatic research, including legacy Off/On rows.
  Per-conversation mode menus are removed. The global web permission remains.
- Public factual questions research by default, including casting, authorship,
  history, definitions and public explanations. Personal context, games, creative
  or supplied-text transformations, arithmetic and explicit offline requests stay
  local. Routing remains deterministic and language/phrasing coverage is finite.
- Casting searches retain production/character terms and add actor/cast intent.
  A currently phrased role lookup does not demand a newly published announcement.
  A request for recent casting news still uses date filtering.
- Recognised official Marvel/Disney publishers receive a bounded relevance bonus
  for Marvel casting questions, like the existing Android/runtime primary-source
  rules. No actor name or casting answer is embedded in production code.
- Theory/rumour headlines cannot confirm casting. Retrieval must include an actual
  actor/role passage. A headline alone cannot become an answer or fallback quote.
- Sentence checking preserves suffixes and initials such as Jr., Dr. and A.; they
  no longer split a named person's cited statement into incomplete fragments.
- Definition requests prioritise actual defining passages over importance/FAQ
  tangents and incomplete reasons. Evidence fitting prefers a complete definition;
  if generated prose misses it, a labelled exact definition can be quoted.
  Capital-city searches disambiguate the requested city from capital punishment
  and ask for the city/country without unrequested historical detours.
- DuckDuckGo HTML/Lite remain the primary discovery path. Optional anonymous Mwmbl
  search is a separate permitted backup; source pages are still read directly.
  Snippets never become evidence. Captchas/denials are not bypassed.
- Mwmbl anonymous requests use no key/account and cannot incur paid overages.
  Its published allowance is 1,000 requests/month per IP, about one per second.
  Shared networks share this quota. The app serialises requests and backs off on
  429/denials. No free public provider is an unlimited availability guarantee.
- Consent version 2 continues permitting DuckDuckGo. Version 3 permits the named
  backup as well. Existing enabled users can explicitly enable the backup in Web
  settings; adding a destination never silently revokes working primary consent.

Binder/TypeRight input, stored private history and saved memories are not sent to
discovery. Native inference, model downloads and acceleration are unchanged.

## Validation

`AutomaticGroundingTest` adds public/local routing cases, exact screenshot-query
coverage, cited person-name boundaries, headline/theory refusal, real-passage
selection, parser validation, fallback consent, challenge handling and anonymous
quota backoff. The existing routing corpus now expects research for stable
public factual explanations and historical questions.

`AutomaticGroundingDeviceTest` is opt-in (`automaticResearch=true`). It requires
the isolated validation package and checksum-verified Gemma 4 E2B. Twelve saved
answers exercise the real composer, old/new conversation defaults, live casting,
authorship, directing, photosynthesis, IANA, weather, calculation, local context,
capital lookup and refusal of unrelated evidence. Assertions and reference names
are never sent in model prompts. Results are written locally to
`files/automatic-grounding-evaluation.json`. Passing results must be recorded
after execution; this document does not claim a completed hardware benchmark.

The checks are lexical safeguards, not general semantic proof. Publisher pages
can be unavailable, inaccurate or changed; a model can still misunderstand novel
requests. Source dates, qualifiers and observations must remain visible.
