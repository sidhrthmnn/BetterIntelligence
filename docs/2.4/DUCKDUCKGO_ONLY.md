# DuckDuckGo Instant Answer only — 23 / 2.4.0-beta3

The user selected DuckDuckGo's official, free Instant Answer API and requested
removal of all other web search engines. Production discovery now calls only
`https://api.duckduckgo.com/`; Exa, Mwmbl, generic fallback and HTML/lite search
transports are deleted. Old source cards and historical evaluation reports are
retained, without enabling or contacting those providers.

## Actual API integration

- Key-free JSON GET with `q`, `format=json`, `no_html=1`, `no_redirect=1`,
  `skip_disambig=1`, and the static attribution parameter `t=betterintelligence`.
- Focused topics preserve the public question's subject; a shorter named subject
  can be tried when needed. Case-equivalent duplicate queries are not repeated;
  at most three distinct API calls share the existing 24-request HTTP budget.
- Parse `AbstractURL`, `DefinitionURL`, `Results` and bounded nested related topics.
  Internal DuckDuckGo result URLs, arbitrary HTML links, images and bang redirects
  never become publisher targets. JSONP and non-JSON bodies are not executed.
- Publisher pages retain HTTPS/public-DNS validation, publisher restrictions,
  cancellation, body-size limits, bounded reads, actual date/claim checks and
  article follow-ups. Explicit public URLs remain readable without discovery.
- The documented API is called directly; it is not an HTML crawler. Publisher
  policy checks still apply to every publisher page and redirect.

## Honest topic summaries

Instant Answer is not a full search-results service. It can supply useful topic
summaries even when the linked publisher cannot be read. For stable topics only,
the retrieved `AbstractText` or `Definition` can then be used as **INSTANT_ANSWER**
evidence, bounded to complete sentences and 1,000 bytes. A successful, relevant
publisher read takes precedence over the API summary.

These summaries have a distinct source kind and no fabricated publication date.
The prompt, persisted answer footer, source cards and source dialog disclose that
the publisher page was not independently verified. API summaries cannot establish
live news, weather, casting, current officeholders or explicit verification
requests. Unsourced scalar `Answer` fields and arbitrary search snippets are not
treated as publisher evidence. Unsupported current information remains unavailable.

The UI includes DuckDuckGo's official logo, linked “Results from DuckDuckGo”
attribution, and the underlying publisher links. The icon is bundled locally;
viewing sources does not fetch a remote branding image. See
[brand/source notice](../../third_party/duckduckgo-brand-NOTICE.md).

## Permission and compatibility

Web & grounding names only DuckDuckGo and explains the API's limited coverage.
Consent 5 requires enabling web research once after the updated disclosure;
consent for previous providers does not silently permit a new destination.
Private history, memory and TypeRight/Binder requests remain local. AIDL, models,
native inference, hardware selection and model ownership are unchanged.

## Live public endpoint probes

Twelve English topic queries were sent to the actual API with no key or account.
Nine returned article references; weather, Vulkan documentation and Kotlin
coroutines returned no article response. A France country reference is not current
France news. These are API response observations, not verified-answer scores.
[Recorded probes](duckduckgo-live-probes.json).

| Topic | Actual API response |
| --- | --- |
| Photosynthesis | Article reference |
| Paris | Article reference |
| Jane Austen | Article reference |
| Avengers Doomsday | Article reference and official site |
| Prime Minister United Kingdom | Role reference and official site |
| Weather London | Empty |
| France | Country reference; cannot establish current news |
| Vulkan Android documentation | Empty |
| Kotlin coroutines | Empty |
| Quantum computing | Article reference |
| Mount Everest | Article reference |
| Kerala | State reference |

The API's publisher-authored reference explicitly distinguishes Instant Answers
from full search results:
https://github.com/duckduckgo/duckduckgo-publisher/blob/master/share/site/duckduckgo/api.tx

## Validation

The complete automated suite passes **223 tests**, with zero failures/errors.
Debug and instrumentation APKs compile and release lint passes. The first run
exposed one outdated test expectation: the documented API makes one request,
not the old crawler's robots-plus-search pair. Its expected count was corrected.

The isolated x86-64 Android emulator protocol passes route, provenance and
no-alternative-engine assertions across eleven public English questions.
[Recorded emulator results](duckduckgo-emulator.json). Photosynthesis, Paris and
Jane Austen produce explicitly labelled API summaries. Eight current/technical
questions do not yield usable evidence on that connection; they remain failures,
not verified answers. The current-prime-minister publisher read succeeded during
an earlier URL-only development run but timed out on the final run. This is an
API coverage/publisher-access limit, not a claim of broad search accuracy.

The API summaries' timestamps are retrieval times only. No live observations,
casting or incumbent names are inferred from undated summaries. The actual
emulator wall times and failures remain in the record rather than being omitted.

Signed-bundle verification and review-submission details are recorded with the
release and its pull request. No physical GPU/NPU or large-model answer-quality
benchmark is claimed by this provider-only change. The user's phone, production
chats and model files were not changed; validation ran on the emulator.
