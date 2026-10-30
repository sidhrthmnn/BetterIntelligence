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

Fitting evidence to a small context now removes publisher subscription/player controls before passage ranking and rechecks the shortened evidence. Movie reviews prioritise a critic's actual rating or assessment; unfolding election questions prioritise reported counts or coalition status. If a generated response preserves only side facts, a clearly labelled verbatim source passage can retain the requested facet without inventing a paraphrase or citation. Numerical current-news answers include the actual cited publisher report times and distinguish reported snapshots from live counts. Documentation bodies need not literally repeat the word “documentation”, and a direct page-summary request does not require the publisher to repeat “Summarize”.

Follow-ups retain a bounded topic already used for public research in the same conversation. They do not copy unsent private chat history into discovery. The additive `publicQuery` source metadata is backward compatible; old source cards retain their original provenance. Binder, TypeRight polishing, model resources and native inference backends are unchanged. Connected-app inference never invokes research.

Web consent is version 6 because the service destination changed. Users must enable **Web & grounding** once after reading the Exa disclosure.

Weather observations retain their explicit reported time. Recent GMT/UTC observations can cross a local date boundary when their actual age is at most six hours; their date is never rewritten. The requested city's title ranks ahead of a nearby reporting location. Adjacent desktop/mobile timestamp text is separated before date checks.

## Android validation, 29–30 October 2026

[Sanitised device results](android-exa-validation.json) record **242 JVM tests with no failures/errors/skips** and **nine passing instrumented test methods** on a connected HONOR VKP_NX9. Testing used the isolated validation package; the production Play installation, chats and model selection were preserved. Temporary validation apps and their model copy were removed afterward.

The actual chat flow used the checksum-verified Gemma 4 E2B LiteRT-LM model on CPU, 2048 context and four threads:

| Question shape | Observed outcome |
| --- | --- |
| Doom casting | Named Robert Downey Jr. with a read-page citation |
| Unfolding Israel election, lowercase place name | Retained a reported vote-count/seat snapshot, citation and actual update time |
| Jailer 2 review | Retained the cited critic rating instead of only cast details |
| Pret UK latte price | Returned the listed amount from a read pricing table |
| Price follow-up in the same chat | Kept the item, country, amount and source citations |
| London weather after midnight | Reported temperature, units and original observation time; forecast low remained separately labelled |

All six replies completed with read-page sources. These include generated answers, partially supported answers with unsupported details omitted, and explicitly quoted source passages. This is **not six independently verified real-world facts or six fully generated answers**. The Pret amount came from a third-party reference, not a live till price; news counts are dated snapshots and critic ratings are attributed opinions.

Eight additional live Android discovery/reading cases all retrieved actual pages: casting, elections, reviews, release dates, prices, weather, Android Vulkan documentation and Kotlin coroutines documentation. Request traces used Exa for discovery, with no DuckDuckGo/Mwmbl search requests. Other phone checks covered same-chat recall, new-chat isolation, regeneration, editing, cancellation, independent client-facing inference without chat history, direct-page summaries, blocked private URLs, offline calculation, restoration, keyboard insets, three themes, settings, a real verified model download and safe active-model deletion.

Recorded Gemma local-generation metrics ranged from approximately **18–26 tokens/s** and **3.9–8.2 seconds to first token**. These exclude discovery/page reading and are short validation samples, not end-to-end latency, an engine comparison or sustained-performance measurements. Native SmolLM2 135M GGUF CPU generation, repeated model reuse/unload and native weight unmapping also passed. This phone's Adreno Vulkan driver rejected a compute pipeline (`ErrorUnknown`); BI fell back to CPU and reported zero offloaded layers. GPU inference was **not verified** in this run. NPU execution was not tested by this web-research change.

## Release verification

[PR 14](https://github.com/sidhrthmnn/BetterIntelligence/pull/14) merged the tested code into main. [Android checks run 89](https://github.com/sidhrthmnn/BetterIntelligence/actions/runs/40978138288) passed JVM regressions, release lint, real ARM64 CPU/Vulkan compilation and optional Qualcomm QNN profile compilation; compilation does not establish hardware NPU execution.

The signed production AAB is package `com.aistudio.betterintelligence.mobile`, version **24 / 2.4.0-beta4**, SHA-256 `27dd8c1d318b4a434760d03aaf29dc3d501754a05332e326e3e54514c36e6855`. Bundletool validation, signature verification and the repository's manifest/native packaging checks passed. All 18 packaged native libraries meet at least 16 KB ELF load alignment; only the optional Vulkan library depends on the Vulkan loader. The bundle contains no model weights. A packaged DEX scan found Exa's endpoint and none of the former DuckDuckGo, Mwmbl, Bing RSS or Brave search API endpoints. Historical source-card attribution links remain available for saved chats.

[Release record](release-v24.json): version 24 was uploaded to **Closed testing – Alpha**, release 18. On 30 October 2026 at approximately 00:27 UTC, Play Console showed **Changes in review**, with quick checks running before review. Managed publishing was **off**. Submission was queued successfully; Google approval/publication was not awaited. The only preview warning concerned the absent deobfuscation map; minification is disabled for this release.

## Discovery measurements

[Ten live public discovery probes](exa-discovery-comparison.json) returned five Exa links per question, including original Marvel/Disney casting pages, election reporting, actual Jailer 2 reviews, Ramayana release coverage, UK Pret menu/pricing pages, weather publishers, government role pages and official Android/Kotlin documentation. Measured search latency ranged from about 0.6 to 4.1 seconds on the development computer. These are **link-discovery observations, not ten correct generated answers or device performance measurements**.

Direct raw DuckDuckGo Instant Answer requests for five failed question shapes (Doom, Israel election, Jailer 2, Ramayana release, Pret UK latte price) returned empty `Answer`, `AbstractText`, `Definition`, `Results` and `RelatedTopics`. Returning that payload verbatim could not answer those questions. The provider documents that this is not a full search-results API and that many detailed queries are blank.

SearXNG is a viable open-source, self-hosted alternative. Four sampled public services returned HTTP 429 or human/security checks to automated requests; no challenge was bypassed. This measures availability, not the result quality of a private SearXNG server. Bing RSS returned links but repeatedly lost the query's specific subject in the probes, so it was rejected. Neither experimental path is packaged. The user selected Exa after reviewing the hosting and free-service tradeoffs.

## References

- [Exa MCP: key-free access, tools and limits](https://exa.ai/docs/get-started/exa-mcp)
- [DuckDuckGo's API scope and empty detailed queries](https://github.com/duckduckgo/duckduckgo-publisher/blob/master/share/site/duckduckgo/api.tx)
- [SearXNG search API and public-instance limitations](https://docs.searxng.org/dev/search_api.html)
- [SearXNG installation](https://docs.searxng.org/admin/installation-searxng.html)
