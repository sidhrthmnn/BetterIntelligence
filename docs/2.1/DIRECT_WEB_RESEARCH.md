# Direct web research and session context — beta5

## What runs in BI

BI owns the query planner, public HTML link parser, publisher reader, passage extractor, ranking, bounded evidence prompt and citation checks. There is no external search, scraping, weather, geocoding, grounding or cloud-model API in this research path. Existing Hugging Face model discovery/download functionality is separate and unchanged.

Discovery still needs a public website: BI parses DuckDuckGo's non-JavaScript HTML results. This is not an independent global web index on the phone. Bing's robots policy blocks its search-page crawler path, so BI does not scrape that interface. Public HTML markup can change, be rate-limited or show a challenge; BI then fails visibly. It never bypasses CAPTCHA, paywalls, sign-in or publisher restrictions. A supplied public HTTPS link can be read without a search query.

The old Bing RSS feed and Open-Meteo API paths are removed. Weather without a city asks for one; a typed city goes through ordinary public-page research and freshness checks. If readable, sufficiently recent evidence is absent, BI cannot verify current conditions and does not invent them. Old stored weather/source attributions remain visible.

## Retrieval and evidence

1. Consent and per-chat Off/Auto/On controls are checked before research. Credentials and private/local URLs are rejected. An upgrade asks for consent again because the search destination changed; settings disclose DuckDuckGo and direct publisher requests.
2. A bounded query preserves original search words while using mild inflection matching for local relevance. Relative dates select a public HTML freshness filter. At most one broader date-filter retry is made for an empty, readable result page, never for a challenge or denied request.
3. BI parses only result links, excludes ads, decodes known search redirects, removes known tracking keys/fragments and retains meaningful URL parameters. Search snippets are never evidence.
4. At most eight publisher candidates, at most two per registrable domain, are read in waves of four with two concurrent reads. Earlier results from distinct publishers are preferred. Reading stops once enough sources exist. A supplied landing page may lead to one relevant same-origin child.
5. HTTPS/public-DNS checks, bounded response size, cancellation, timeout and redirect limits remain. Robots permission is checked **before each redirect target is read**. Policy fetches are deduplicated per origin; a failed/HTML-challenged policy does not grant crawl permission.
6. `WebPageExtractor` retains article headings and neighbouring paragraphs in publisher order. It removes navigation, hidden UI, ads, forms and scripts, rejects short challenge/login/paywall pages, and honours `nosnippet` and nonnegative `max-snippet` limits in HTML or response headers. Plain-text pages are supported. Structured date metadata is read before scripts are removed, never used as replacement article text.
7. Topic, requested year, freshness and duplicate-excerpt checks determine the final maximum of three sources. For a current query, a legitimate page-reported update can qualify an older document. Reported dates remain unverified publisher claims; a recent metadata date does not prove every statement on the page is current.
8. The model receives only bounded, attributed, untrusted excerpts. Persisted evidence is clipped to what was supplied in the prompt. Model-generated URLs/unknown source IDs cannot create sources. Explicit prose such as "source ID 1" is normalized to a citation only for an existing source ID. A final numerical check rejects values absent from that paragraph's cited evidence, including citing an unrelated source that happens to be in the source list; equivalent supported date formats are normalized. This is not general semantic entailment or a guarantee of factual accuracy.

Publisher content remains untrusted. A local small model can misinterpret a source, misunderstand negation or produce an incorrect non-numerical claim. Websites can contain inaccurate information or prompt injection. The app uses instruction separation and deterministic guards, without claiming those solve every such failure. Public sites may forbid access; a visible insufficient-evidence response is preferable to guessed live information.

## Context for each session

Each chat has its own persisted messages, draft and compact local notes in Room. Notes are independent of optional global memory and survive process restarts/model changes. No session context is passed into Binder requests or copied into public search; contextual web follow-ups can use an already retrieved public source title from the same conversation.

The local summarizer quotes older user statements, retaining early project anchors and recent decisions/corrections. If a following user references an assistant proposal, a short explicitly **unverified** quote can also be retained. It never asks a remote or local model to invent a summary. The last six full turn pairs remain available separately. Notes are stored within 4,000 characters; the prompt selects question-relevant lines within its smaller token/character allowance and keeps their chronology so newer corrections override older statements. Exact full recall is limited by the selected model/context and cannot be guaranteed.

Editing/regenerating invalidates notes. Old note-format caches are rebuilt locally without a database schema migration or conversation deletion. Deleting a session removes its history, draft and notes. Memory settings now distinguish automatic session context from the optional shared memory toggle.

## Additional model choices

Two optional Q4_K_M GGUF downloads use the existing llama.cpp CPU/optional Vulkan runtime; no new inference library is added:

| Model | Pinned download | Estimated RAM at 2,048 context | Purpose |
| --- | ---: | ---: | --- |
| Qwen 3 1.7B | 1,282,439,584 bytes | 1,801 MB | Compact multilingual chat, rewriting and summaries |
| SmolLM3 3B | 1,915,305,312 bytes | 2,373 MB | Larger multilingual assistant for chat and summaries |

Estimates include quantized weights, conservative F16 KV and runtime overhead; GPU offload shares system RAM and adds overhead. Current free RAM/pressure still determines admission. They are additional choices, not a claim of better answers than every existing Gemma/LiteRT option. No weights download automatically and no default model changes. Exact revision, SHA-256, licence and native geometry are pinned in both catalogue copies.

Sources: [Qwen publisher model card](https://huggingface.co/Qwen/Qwen3-1.7B), [Qwen GGUF quantizer](https://huggingface.co/bartowski/Qwen_Qwen3-1.7B-GGUF), [llama.cpp maintainers' SmolLM3 package](https://huggingface.co/ggml-org/SmolLM3-3B-GGUF). Both use Apache-2.0. BI uses the embedded declared non-thinking generation prefix for Qwen3/SmolLM3 ChatML templates that advertise `enable_thinking`, avoiding a hidden-reasoning budget before normal chat output. This is scoped to those templates, not applied to arbitrary imports.

Device and build results: [beta5 validation report](BETA5_VALIDATION.md).
