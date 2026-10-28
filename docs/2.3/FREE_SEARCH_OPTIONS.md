# Key-free search alternatives

Checked 28 October 2026. Only DuckDuckGo and the optional Mwmbl backup are included
in this release. The other services below were researched, not silently added as
query destinations. None is advertised as unlimited guaranteed search.

| Option | Key/account requirement | Suitability and limits |
| --- | --- | --- |
| [DuckDuckGo HTML/Lite](https://duckduckgo.com/duckduckgo-help-pages/features/non-javascript) | Neither for public non-JavaScript pages | Primary broad discovery. Markup, challenges and rate limits can interrupt access. A denied HTML request is not retried through challenge evasion. |
| [Mwmbl anonymous API](https://developer.mwmbl.org/) | Neither for the anonymous tier | Included as optional backup: 1,000 requests/month per public IP and 1 request/second. No paid overages, embedded key or shared developer account. A live casting query returned candidate links; publisher reads are still required. |
| [SearXNG](https://docs.searxng.org/) | Public instances can be key-free; self-hosting requires an operator | Viable configurable future option. Public availability, trust and rate limits vary; many instances disable JSON. The inspected search.inetol.net robots policy disallowed q-bearing query URLs, so BI does not add it as a crawler fallback. See [public/private instance limits](https://docs.searxng.org/own-instance.html) and [formats](https://docs.searxng.org/dev/search_api.html). |
| [Wiby JSON search](https://wiby.me/json/) | No key | Genuine key-free alternative, oriented toward lightweight independent pages. Requires a Wiby link with results. A live exact casting query returned HTTP 200 with no hits, so it is not the broad factual default. [Index scope](https://wiby.me/about/). |
| [Marginalia](https://about.marginalia-search.com/article/api/) | Shared public development key exists; private keys available | Public access often hits rate limits; free non-commercial access has licensing restrictions. Unsuitable as an assumed unrestricted commercial/mobile default. |
| [Jina Search](https://docs.jina.ai/) | Search requires a key | Its lower-rate anonymous Reader is page reading, not key-free web search. Not included. |
| [Mojeek](https://www.mojeek.com/about/terms.html) | Authorised API use | Public page scraping is prohibited without consent. Not included as a key-free scraping workaround. |
| [Stract](https://stract.com/) | Current product is media/outreach discovery | Its current official site does not present the historical open general-search service as a supported anonymous endpoint. Not assumed available from old recommendations. |

Primary publisher pages remain valuable independent of search. A user-supplied
public HTTPS link can be read without sending their question to any search engine.
The app checks policy before pages/redirects, retains budgets and never solves
captchas, rotates identities, signs in or purchases access for discovery.
