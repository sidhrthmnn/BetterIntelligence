# Answer quality audit

Base: latest main `b90955b` (2.2.0-beta4/code 14), fetched 26 October 2026. The earlier PR #3 has already been merged. Work continues on this newer main, preserving its hierarchical extraction, weather/officeholder ranking, recheck follow-ups, source dialog and RAM-aware primary/secondary model changes.

## Remaining weaknesses confirmed in source

- AUTO uses a broad substring list and all comparisons/years. It misses population/price questions without a freshness keyword, yet searches static RAM-versus-storage comparisons and historical questions.
- The final production validator checks citation existence and pooled numbers/paragraphs. A number in a different source, changed units, reversed negation and swapped comparison subjects can pass. Any failed number discards the entire answer.
- Evidence has heading/table provenance but the last length limit can cut a sentence before a qualification. Prompt sizing repeatedly constructs nine evidence plans and can repeat previous grounded answers in chat history.
- The public-source follow-up helper misses "What about memory usage?" and usually retains only one source title. It must continue to exclude private messages from outgoing queries.
- Offline instructions are generic; there is no bounded decimal arithmetic support or explicit current-information limitation when research was not performed.
- Existing 40-case retrieval results are evidence coverage, not complete-answer accuracy. The recorded HONOR Qwen 1.7B comparison was rejected for unsupported conclusions; CPU prefill/inference was slow. No new engine upgrade on main resolves arbitrary semantic entailment.

## Existing components retained

Public HTML discovery behind `DiscoveryProvider`, robots/access restrictions, public-network DNS/redirect checks, HTTPS validation, 1 MB bodies, 24 actual HTTP requests, two concurrent page reads, 30-second research deadline, five-minute/eight-query RAM cache, session-scoped Room history/notes, shared inference ownership, CPU/GPU admission/fallback and unchanged TypeRight Binder/AIDL contracts. No cloud model, paid search or grounding service is introduced.

The new routing and claim fixture reports compare defined deterministic behaviour; the separate live paired harness compares Web OFF/ON with identical model/settings. Neither substitutes retrieval coverage or matching citation IDs for factual correctness. Live answers require an independent reference review, including uncertainty and omissions.
