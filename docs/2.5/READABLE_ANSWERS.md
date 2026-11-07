# Readable, stable web answers (beta 31)

## Observations from the installed app

With the owner’s permission, the connected HONOR’s recent chats were inspected in the app. No private database or transcript was exported. Four public question shapes exposed distinct defects:

- A running-shoe recommendation included image labels, scores, controls and prose flattened from an editorial ordered-list card.
- A film title after “Malayalam movie” containing “I’m” went local instead of researching. A descriptive follow-up could lose the requested release intent.
- An OTT question received a theatrical release date as a fully supported answer. A follow-up repeated the same date.
- An ambiguous company name produced valuations for multiple unrelated companies instead of a clarification.

These are public-topic regressions, not verified assertions about the films, shoes or companies. Automated fixtures use fictional publisher facts.

## Changes

Web drafts are buffered until their evidence check completes. Local answers still stream, with their existing line geometry so scrolling remains stable. Useful attributed source summaries no longer acquire a blanket failure apology. Short source reports and pages with generic titles can contribute when their actual body identifies the requested subject; entity, quantity, freshness, polarity, condition, conflicting-evidence and source-access checks remain in place. A cinema date is explicitly identified as insufficient for an OTT date rather than being presented as the requested release.

Only the observed extraction defect was changed: rich editorial list cards retain paragraph/heading boundaries, decorative photo labels and page controls are excluded, while data-table value bindings are retained. Exact repeated passages with the same citations are collapsed, retaining an original longer qualification.

Markdown has distinct heading sizes, soft-line handling for completed answers, linked multi-source references and linked table citations. HTML and model-authored URLs still do not execute or establish citations.

## Optional illustrations

Read publisher pages can supply an Open Graph illustration and alt text. Only a cited, actually read source can offer **Show source image**. Opening a saved conversation never downloads it automatically. Web consent is checked on request and every redirect, including robots requests; revocation cancels loading. The shared HTTPS/public-DNS transport rejects private addresses, checks publisher robots policy, permits JPEG/PNG/WebP only, limits downloads to 1 MiB and decoding to bounded dimensions, samples to at most 768 pixels, and has an 8-second overall deadline. Images stay in memory and are never sent to the local text model. Missing, oversized or blocked images show a small unavailable notice without changing the answer. Images are illustrations, not verified visual evidence. Existing source JSON remains readable without a database migration.

## Validation

Results are recorded after the final checks below; device tests use the separate BI Validation installation and preserve the production installation and its chats.

- Final Kotlin run: **319 tests, 318 passed, one opt-in live benchmark skipped, zero failures**. All 17 new regression methods passed. Android debug/test APKs and release lint passed. Rust: **31 passed**.
- Physical HONOR VKP-NX9 / Android 16, real Qwen3.5 2B GGUF on CPU: 18 distinct relevant methods passed across the initial suite and final targeted rerun. The initial complete suite passed 17/18; the recommendation case exposed a repetitive partial answer. After fixing source-summary recovery, the final two methods passed: all three controlled chat questions retained valid citations and exposed no unchecked draft; grouped/table citation links, image opt-in/revocation and formatting passed. Other native/core, cancellation, context, navigation, IME and live-research checks passed in the complete run. No emulator tests were needed.
- Five live public questions all yielded some cited information (four partial summaries, one fully cited answer); this was retrieval plus deterministic synthesis, not a general accuracy score. A simulated Exa rate limit still retrieved an actual Android publisher page through BI's existing fallback. Two publisher images decoded on the phone; two were unavailable without affecting the answer. Raw user conversations were not exported or committed.
- The final real-model recommendation used the actual source's review details instead of repeating only the product name. Same-citation deduplication preserves signs, units and longer qualifications; it does not generate new facts.
- Signed 31 / 2.5.0-beta6 bundle: bundletool and upload-signature verification passed; production package and Binder manifest are correct; 20 native libraries retain at least 16 KiB ELF alignment. Licence checks used identical committed app-assets licence references because user-owned root-model deletions were preserved. The bundle was built from 7147187; subsequent report commits do not change application source.

Detailed scope and measurements: [readable-validation.json](readable-validation.json), [bundle-v31-validation.json](bundle-v31-validation.json). Web drafts now show progress until checking finishes; this deliberately delays displaying provisional prose. Deterministic checks are not universal semantic entailment or a guarantee that a publisher is correct. Missing/blocked source facts and unavailable images are not invented.
