# Quality evaluation protocol

The 113 synthetic cases in `proofreading-cases.tsv` cover the requested English, Malayalam, Manglish, mixed-language, literal-preservation and meaning-sensitive categories. The generated instrumentation asset is the same dataset, with stable case IDs. No private keyboard or chat content is used.

References prefer minimal edits. Multiple rewrites can be grammatical: edit precision/recall against these references is a reproducible proxy, not a complete linguistic judgement. Unchanged-case false corrections, literal preservation and output formatting are reported separately. Malayalam and Manglish cases intentionally test preservation and resistance to unwanted translation. Semantic preservation needs manual review of the raw outputs, especially negation and names; a numeric invariant check is not proof of meaning preservation.

Run `CandidateEvaluationTest` with `candidateModel`, `candidateBackend`, `candidateContext` and `candidateQuality=true`. Quality uses deterministic decoding and fresh conversations. Performance uses repeated short math, proofreading and decoding prompts separately. Runtime token counters are used; callback chunks are never counted as tokens. Requested and actual backends are separate fields. Driver-memory deltas are system-wide and cannot be attributed solely to the app. Charging and background activity confound battery measurements.

No Fast/Balanced/Advanced or TypeRight winner is assigned until the real results and output quality are reviewed. The installed TypeRight release currently limits its proofreading path to English; multilingual model tests do not imply multilingual keyboard support.
