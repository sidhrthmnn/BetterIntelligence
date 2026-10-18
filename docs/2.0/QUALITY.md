# Quality evaluation protocol

The 113 synthetic cases in `proofreading-cases.tsv` cover the requested English, Malayalam, Manglish, mixed-language, literal-preservation and meaning-sensitive categories. The generated instrumentation asset is the same dataset, with stable case IDs. No private keyboard or chat content is used.

References prefer minimal edits. Multiple rewrites can be grammatical: edit precision/recall against these references is a reproducible proxy, not a complete linguistic judgement. Unchanged-case false corrections, literal preservation and output formatting are reported separately. Malayalam and Manglish cases intentionally test preservation and resistance to unwanted translation. Semantic preservation needs manual review of the raw outputs, especially negation and names; a numeric invariant check is not proof of meaning preservation.

Run `CandidateEvaluationTest` with `candidateModel`, `candidateBackend`, `candidateContext` and `candidateQuality=true`. Quality uses deterministic decoding and fresh conversations. Performance uses repeated short math, proofreading and decoding prompts separately. Runtime token counters are used; callback chunks are never counted as tokens. Requested and actual backends are separate fields. Driver-memory deltas are system-wide and cannot be attributed solely to the app. Charging and background activity confound battery measurements.

Measured category choices and their limits are recorded in `reports/README.md`; separate chat observations are in `reports/CHAT_REVIEW.md`. Installed TypeRight 2.0 validation remains pending. Its current proofreading path is English-only; multilingual model tests do not imply multilingual keyboard support.
