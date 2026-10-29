# Search discovery and relevance — version 22 / 2.4.0-beta2

Measured 29 October 2026. Public synthetic questions only; no private phone
conversation export, credentials or production data is included in this report.

## What the phone showed

The user-authorized visible BI chat contained “What's happening in france now”,
a model response saying it lacked real-time access, and an “On-device” footer.
Automatic research and the global web permission were enabled when inspected.
The saved turn's age was not established, so this alone does not prove that the
version 21 router generated it with those settings. Version 21's policy already
recognized the explicit word “now”. Its implicit variant “What's happening in
France?” did miss current-event intent, and a local current-data disclaimer had
no bounded recovery search.

Live Android checks also exposed evidence-relevance failures: conversational
“happening” became a required focus word, and the instruction “Search” could be
mistaken for a named entity. Articles need not repeat either word to answer the
question. Those requirements are removed without accepting stale news or search
summaries as evidence. News intent is limited to public current-event questions;
concept explanations, writing, private context and offline instructions remain
local. An eligible public factual response that explicitly lacks current data
can make one recovery search after its first native generation has finished.

## Twenty-eight paired English discovery queries

The same 28 English query strings were sent to DuckDuckGo's official Instant Answer API
and Mwmbl. Exa's official anonymous hosted search was tested on all 28 as well.
Two Malayalam probes from the original 30-question run are excluded from this
comparison and remaining device acceptance checks at the user's request.
Exa additionally received a retrieval objective requesting original sources,
the question's language and current information as of the test date. Requests
were sequential within each provider, with pacing and bounded timeouts. This is
one connection and one run, not an unbiased search-engine ranking benchmark.

| Measured result | DuckDuckGo Instant Answer | Mwmbl | Exa anonymous search |
| --- | ---: | ---: | ---: |
| Queries tested | 28 | 28 | 28 |
| Nonempty abstract | 8 | Not applicable | Not applicable |
| Nonempty candidate list | See raw response categories | 28 | 28 |
| Median request latency | 171 ms | 136.5 ms | 1529 ms |

These counts are **not correct-answer rates**. A category/disambiguation link,
stale headline, irrelevant hit or unreadable page does not establish an answer.
DuckDuckGo was called with `format=json`, `no_html=1`, `skip_disambig=1`; abstract,
answer, result and related-topic fields are distinguished in the raw record.
The Instant Answer endpoint did not provide abstracts for the full current-news,
weather, current-officeholder or Doctor Doom casting questions. Entity wording
sometimes helped: “Avengers Doomsday” yielded a summary and Marvel's official
site, and “Prime Minister United Kingdom” yielded a role summary and GOV.UK.
“London weather” produced a climate summary, which is not today's temperature.

| Query | DuckDuckGo observation | Mwmbl observation | Exa observation |
| --- | --- | --- | --- |
| What's happening in France now | Empty | Ukraine/time/webcam noise | France news hubs and reporting; page/date checks still required |
| France news | Empty | Reuters, ABC, France 24 | BBC, Reuters and other news publishers |
| What is the latest Android version | Empty | Stale Android 4.3 headlines | Android's maintained feature/update pages |
| Android releases | Empty | Official Android page mixed with app release noise | Android/AOSP release documentation |
| Who is playing Doctor Doom in Avengers Doomsday | Empty | Polygon casting article plus character biography | Marvel's announcement and Disney cast page |
| Avengers Doomsday | Film abstract/official Marvel link | Film reference and review pages | Official Marvel film page |
| current UK prime minister | Empty | Former officeholders and unrelated results | GOV.UK current role page |
| Prime Minister United Kingdom | Role abstract/GOV.UK link | Thatcher biography and tag page | GOV.UK current role page |
| Weather in London today | Empty | Delhi and unrelated London results | Met Office and BBC London weather |
| London weather | Climate abstract, not live weather | London Ontario mixed with UK pages | Met Office and BBC London weather |
| Vulkan Android documentation | Empty | Firefox and other documentation noise | Android NDK Vulkan documentation |
| Android Vulkan | Empty | OpenXR/Unreal documentation and older reporting | Official Android Vulkan documentation |
| Kotlin coroutines | Empty | Libraries, books and mixed references | Kotlin's official coroutine documentation |
| Photosynthesis | Topic abstract | Topic reference and mixed results | Topic reference and science resources |
| What is photosynthesis | Topic abstract | Smithsonian and reference pages | Reference and science resources |
| Capital of France | Empty | Capital list mixed with unrelated places | EU France country page and references |
| Paris | City abstract | City website and references | City/tourist references |
| GPU | Disambiguation/category results, no abstract | GPU references and mixed results | GPU definition references |
| What is RAM | Empty | Person-name noise, then RAM definition | NIST, Microsoft and memory references |
| Difference between RAM and storage | Empty | RAM/CPU and RAM/ROM comparisons | Memory/storage explanations |
| quantum computing | Topic abstract | IBM and reference pages | NIST, NASA and references |
| Who wrote Pride and Prejudice | Empty | Book references | Book references and educational pages |
| Jane Austen | Biography abstract | Biography mixed with product noise | Biography/book references |
| Mount Everest height | Empty | Reference and dated measurement reports | Guinness, Reuters and AP measurement reports |
| capital of Kerala | Empty | Kerala government and references | India's national portal and Kerala profile |
| Kerala news | Empty | Kerala news publishers | Kerala news publishers |
| Gemma 4 E2B Android | Empty | Model references and unrelated first hit | Google's Gemma/LiteRT documentation |
| Why is the sky blue | Empty | Unrelated first hits, then references | Met Office, NOAA and UCAR explanations |

Raw measurements: [DuckDuckGo/Mwmbl](search-ddg-mwmbl-28.json),
[Exa](search-exa-28.json). They contain public discovery candidates, not
verified publisher evidence or model-generated answer evaluations.

## Selection and actual implementation

Exa is the broad primary discovery service; Mwmbl remains an independent fallback
when primary discovery fails or its readable passages do not cover the question.
DuckDuckGo Instant Answer is not added as a universal web-search backend: it was
fast but often empty and supplied climate/role/entity information rather than
live answers. No DuckDuckGo HTML scraping, Google search scraping, Bing feeds,
proxy rotation, paid upgrade or cloud answer generation is introduced.

The app sends one documented `web_search_exa` JSON-RPC request to
`https://mcp.exa.ai/mcp`. It parses actual JSON/SSE replies and only takes the
result's Title/URL headers as candidates. Search highlights, summaries and
reported dates do not become evidence. BI reads actual publisher bodies through
its existing public-network, publisher-policy, cancellation and response-size
checks. Source cards preserve “Found via Exa/Mwmbl” where available. The pipeline
retains its three-discovery-call and shared 24-HTTP-request budgets, independent
direct HTTPS reading and bounded coordinator. Rate-limit failures cause backoff;
anonymous Exa access is not promised to be unlimited or permanently available.

Mwmbl now receives topic keywords instead of contraction fragments, generated
casting synonyms or an invented current year. Android precedes Vulkan in that
specific two-term query, and current-event questions include “news”. Measured
reformulation changed the France query from mostly irrelevant hits to France
news publishers. This improves discovery, not the freshness of the index itself.

Two bounded article follow-ups can read actual reports behind section pages.
Missing child links do not consume that budget, and author/tag/category links
are excluded. All follow-ups still share the same HTTP quota, publisher policies
and timeout; a directory or author profile is not promoted to a news answer.

The added destination requires **consent version 4**. Existing consent 3 remains
stored but does not silently allow Exa; the user enables Web & grounding once
after reading the revised disclosure. Disabled research is labelled explicitly.
TypeRight/other Binder requests remain local and do not invoke this code path.
No AIDL, model ownership, acceleration or polishing-output checks are changed.

## Alternatives considered

- [Exa's official anonymous MCP](https://github.com/exa-labs/exa-mcp-server#authentication)
  documents anonymous, rate-limited access. The production tool schema and
  real unauthenticated replies were checked; no API key or account was used.
- [Common Crawl](https://index.commoncrawl.org/) supplies an archive and URL-pattern
  index, not a current ranked full-text search service. Building a useful search
  index from that data requires separate infrastructure. It is not a drop-in
  mobile discovery provider.
- [Bing Search APIs](https://learn.microsoft.com/en-us/lifecycle/announcements/bing-search-api-retirement)
  retired on 11 August 2025. Azure grounding is not a universal key-free client API.
- [Google Custom Search JSON](https://developers.google.com/custom-search/v1/overview)
  is closed to new customers; existing customers need a key and have a limited
  transition period. Google/Bing search scraping is not used to bypass that.
- [SearXNG](https://docs.searxng.org/dev/search_api.html) can support a separately
  operated trusted instance. Public JSON availability and quotas vary; BI does
  not depend on an arbitrary third-party instance.
- Purili's public search endpoint was probed; the France query mostly returned
  irrelevant results on this run. It is not selected.

## Validation scope

The complete JVM suite passes: **218 tests, zero failures/errors**. Debug and
instrumentation APKs compile successfully. Release compilation/lint and bundle
validation are recorded separately with the release. The opt-in
`ResearchDiscoveryDeviceTest` exercises nine English public questions using the real
Android transport and publisher extraction in a separate validation package on
the connected HONOR. It does not allocate a second large model or read production
chat data. Initial results exposed the relevance/follow-up bugs described above.
The final phone protocol passes its network/route/source-provenance assertions;
**7 of 9 questions yield readable publisher evidence**, not seven verified
model-generated answers. [Final device record](research-device-english.json).

| Phone question | Actual result | Wall time |
| --- | --- | ---: |
| What's happening in France now | Dated RFI article body, via Exa | 11.254 s |
| What's happening in France? | Correctly routed to research; insufficient readable evidence on this run | 7.539 s |
| Doctor Doom casting | Marvel body/caption explicitly names Robert Downey Jr. as Victor von Doom/Doctor Doom | 5.127 s |
| Current UK prime minister | Two GOV.UK role/office pages | 2.646 s |
| London weather today | BBC observations include 12 °C (54 °F), place and observation time; forecast high/low distinguished | 11.733 s |
| Android Vulkan documentation | Two actual Android documentation pages | 19.344 s |
| Kotlin coroutines documentation | Timed out reading publisher material; no snippets promoted to evidence | 48.661 s |
| Photosynthesis | Three readable science/reference pages | 6.238 s |
| Latest Android version | One maintained Android documentation page; recency qualifications preserved | 10.094 s |

Both France variants route to research. Provider results and page access vary:
the implicit variant had readable evidence in an earlier run but not the final
one, while the exact reported “now” question succeeds after the follow-up fix.
The Kotlin case also shows that cancellation completion can exceed the nominal
30-second coordinator timeout; the measured wall time is reported rather than
claiming a strict end-to-end 30-second bound. The UI remains on a background
research path and returns an explicit timeout instead of a sourced answer.
This turn measures discovery, routing and publisher retrieval, not a new
large-model answer-accuracy or sustained inference-performance benchmark.

Remaining limits: deterministic intent and lexical support checks can still miss
unfamiliar wording, inflected languages or ambiguous entities. Anonymous service
coverage, source access and publication dates vary. Provider hit counts do not
measure final model correctness. No new NPU/GPU performance claim is made.
