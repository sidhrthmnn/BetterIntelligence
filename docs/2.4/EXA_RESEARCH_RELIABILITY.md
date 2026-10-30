# Automatic research and Exa discovery

Version 24 / 2.4.0-beta4, 29 October 2026.

## What changed

The connected HONOR phone's conversation named **Error conversion** showed missed research for “Who is acting as doom in avengers doomsday” and “How is the election going on in israel”. A UK price follow-up lost the original item. A separate movie question produced a generic local refusal. The production installation reported version 23. These public question shapes are regression fixtures; the user's chat database, private history and UI dumps are not included in this repository.

Current casting, unfolding elections, movie reviews, prices and release dates now trigger research before generation. Other eligible public factual questions can make one recovery search if the local model admits it cannot answer. The system prompt provides a research-needed signal, with ordinary first-person uncertainty recognised as well. The outgoing query comes from the user's public question and a previously researched public topic, never from model-generated claims. Writing, calculations, credentials, personal context, explicit offline instructions and disabled web permission cannot be overridden by that signal. There is no recursive inference/search loop or second model allocated for classification.

This is a practical recovery mechanism, **not a calibrated confidence score**. A confident wrong local answer outside the recognised changing-fact categories can still be missed.

## Search and browsing

Exa's documented anonymous MCP `web_search_exa` is the only discovery service. It needs no API key or account and has free rate limits. No paid authentication, Exa Agent, cloud answer model, random public proxy, alternative-engine fallback, DuckDuckGo API or Bing RSS is used. Exa-generated library/weather pages are excluded.

Only titles and public publisher links are taken from search responses. Highlights, provider-generated summaries and search dates never become answer evidence. BI reads publishers using its existing bounded, script-free Android HTTP/HTML reader. Public-network/DNS checks, redirect checks, robots policies, extraction limits, cancellation and resource bounds remain. A question-bearing search POST is never replayed to a redirect destination. Sign-in, paywalls and CAPTCHA are not bypassed.

Each research attempt has at most three distinct discovery requests and a shared 24-HTTP-request budget. The reader completes within an internal 26-second budget, before the coordinator's 30-second deadline, preserving successful earlier page reads when a later page stalls. Retrieved passages are ranked, fitted to the selected model's real context budget and supplied to the local model with citation IDs. Existing evidence checks and bounded repair/extractive recovery still apply. A price answer must explicitly acknowledge a missing requested amount rather than presenting generic price background as a complete answer.

Follow-ups retain a bounded topic already used for public research in the same conversation. They do not copy unsent private chat history into discovery. The additive `publicQuery` source metadata is backward compatible; old source cards retain their original provenance. Binder, TypeRight polishing, model resources and native inference backends are unchanged. Connected-app inference never invokes research.

Web consent is version 6 because the service destination changed. Users must enable **Web & grounding** once after reading the Exa disclosure.

## Discovery measurements

[Ten live public discovery probes](exa-discovery-comparison.json) returned five Exa links per question, including original Marvel/Disney casting pages, election reporting, actual Jailer 2 reviews, Ramayana release coverage, UK Pret menu/pricing pages, weather publishers, government role pages and official Android/Kotlin documentation. Measured search latency ranged from about 0.6 to 4.1 seconds on the development computer. These are **link-discovery observations, not ten correct generated answers or device performance measurements**.

Direct raw DuckDuckGo Instant Answer requests for five failed question shapes (Doom, Israel election, Jailer 2, Ramayana release, Pret UK latte price) returned empty `Answer`, `AbstractText`, `Definition`, `Results` and `RelatedTopics`. Returning that payload verbatim could not answer those questions. The provider documents that this is not a full search-results API and that many detailed queries are blank.

SearXNG is a viable open-source, self-hosted alternative. Four sampled public services returned HTTP 429 or human/security checks to automated requests; no challenge was bypassed. This measures availability, not the result quality of a private SearXNG server. Bing RSS returned links but repeatedly lost the query's specific subject in the probes, so it was rejected. Neither experimental path is packaged. The user selected Exa after reviewing the hosting and free-service tradeoffs.

## References

- [Exa MCP: key-free access, tools and limits](https://exa.ai/docs/get-started/exa-mcp)
- [DuckDuckGo's API scope and empty detailed queries](https://github.com/duckduckgo/duckduckgo-publisher/blob/master/share/site/duckduckgo/api.tx)
- [SearXNG search API and public-instance limitations](https://docs.searxng.org/dev/search_api.html)
- [SearXNG installation](https://docs.searxng.org/admin/installation-searxng.html)
