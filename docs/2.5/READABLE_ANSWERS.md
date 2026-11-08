# Relevant, readable web answers (beta 31)

## Observed failures

The owner authorised inspection of the installed app's failed chats. Only public question shapes were reused; no private database or transcript was exported or committed. A first 14-request phone replay had valid citations but still needed six repairs: the wrong film edition, an interest rate instead of a loan amount, image captions instead of a cause, unrelated film credits and FAQ prompts, another company's owners, and a ranking without the person's name. Automated citation checks alone did not establish that these answers addressed the question. The replay now checks these specific fields and its final prose is reviewed manually against the read pages.

## Answer pipeline

- Grounded drafts wait behind progress text until checking finishes. Local replies continue streaming with stable line geometry.
- Useful, attributed source summaries remain visible when model synthesis is unusable or incomplete. Missing information is described precisely rather than replacing supported information with a generic apology. No extra model run or search is needed for these repairs.
- Film identity, language and OTT intent survive follow-ups and prompt shrinking. An explicitly identified edition takes priority over an unidentified same-title page. Cinema dates do not become streaming dates; tentative windows retain their qualifications and an exact-date gap.
- Cause, cast, ownership and global-ranking questions prioritise the requested relation. A related experiment is not substituted for the reported failure mechanism; cast replies retain individually supported actor relations. A hotel acquisition does not identify that hotel group's owners, and a regional wealth leader does not establish the global leader.
- Financial partial answers distinguish a reported credit facility from actual drawn debt, retain conditions such as “undrawn at closing”, and identify the missing total. Reports are dated when their publication/update provenance is known. Labelled page dates are checked against the cited source metadata; dates asserted about the subject still require body evidence.
- Body text can identify a public subject even when a page title is generic. Whole source sentences can supply genuine wording variations without assigning invented citations to generated claims. Existing entity, numeric attribution, polarity, conditions, conflicts, public-address, robots and permission checks remain.
- Exact India/London/UK/UTC/GMT or device-local clock requests use the device clock and zone. They do not require generated time values or a network lookup. Reviewed typing corrections retain the question's intent. Mentioning a film's language does not force that reply language; explicit language requests still work.

## Presentation and extraction

Completed answers use readable headings, short paragraphs, clean bullets and tables whose cells retain the full checked values and conditions. Repeated same-citation passages collapse while longer qualifications, signs and units remain. A publisher's first-person review is attributed to that publisher. Grouped citations have separate targets and citations in tables are clickable. **Open website** exposes the actual validated source URL; model-authored URLs do not create trusted links.

The observed extraction defects were repaired without replacing discovery: editorial ordered-list cards preserve paragraph boundaries; bare article text beside headings and line breaks remains readable; flattened sentence/FAQ boundaries are separated for summaries. Decorative labels, image filenames, unrelated footer controls and long invisible watermark runs do not consume the evidence budget. Data-table bindings and individual Indic joiners remain.

## Optional illustrations

Read publisher pages can supply an Open Graph illustration and alt text. Only a cited, actually read source offers **Show source image**. Opening a saved conversation does not download images automatically. Consent is checked on request and every redirect, including robots reads; revocation cancels loading. The existing HTTPS/public-DNS transport rejects private addresses and checks robots policy. JPEG/PNG/WebP downloads have a 1 MiB cap, an 8-second deadline, bounded decoding and a maximum sampled size of 768 pixels. Images stay in memory and are never sent to the text model. Blocked, oversized or unavailable images leave the answer intact. Illustrations are not verified visual evidence. Stored source JSON remains backwards compatible without a database migration.

## Validation

- Final local Kotlin suite: **342 tests, 341 passed, one optional live benchmark skipped, zero failures**; all 40 focused readable-answer methods passed. Android validation APK compilation passed. Rust: **31 tests, formatting and Clippy with warnings denied passed**. The initial GitHub formatter failure was repaired in existing test-only code.
- The physical HONOR / Android 16 exercised the original four topics plus ten earlier public requests. After manual review and targeted reruns, all 14 handled their request usefully: ten live web/model answers, two exact clock responses and two necessary clarifications. This is cumulative across the full replay and final finance/identity reruns, not one 14-request run on the final APK. The signed build uses the final committed app tree.
- Three additional controlled real-Qwen questions checked source recovery and that unchecked drafts never appear. Citation/website/image UI checks passed. An earlier broader phone suite passed 18 distinct relevant methods across its full run and targeted repair, covering native/core, cancellation, context, navigation and IME. Those runtime checks were not all repeated after presentation-only changes. No emulator tests ran.
- The fixed-source recovery regression replay also passed against identical questions, drafts and evidence using the frozen V29 recovery helper. This is a deterministic recovery comparison, not an old-app native speed benchmark.
- Signed **31 / 2.5.0-beta6**, built from **b0ed8e9**, passed bundletool, upload-signature, production package/Binder manifest and 20 native-library checks. Both ABIs retain at least 16 KiB ELF load alignment and optional Vulkan packaging. Licence validation used identical committed app-assets references while preserving the owner's root-model deletions. Later report commits do not alter application code.

The isolated BI Validation installation preserves the production app, its chats and its models. Live samples are an exercised regression set, not a general factual-accuracy benchmark. The CPU test deliberately uses a small output budget to exercise evidence recovery; several model drafts reach their token limit before a useful attributed source answer is assembled. This change does not establish a GPU/NPU performance result. Publishers can be wrong, some pages cannot be read, and an ambiguous title may still need confirmation. Existing saved answers are not rewritten automatically.

Detailed scope: [readable-validation.json](readable-validation.json), [bundle-v31-validation.json](bundle-v31-validation.json).
