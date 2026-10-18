# Runtime compatibility research

Research date: 18 October 2026. Publisher benchmarks are not Better Intelligence measurements.

- The immutable files, sizes and LFS SHA-256 hashes are in `model-candidates.json`. `download_candidates.py` resumes partial downloads, checks free space, and verifies complete hashes. Weights stay outside Git/APK.
- Gemma 4 E2B/E4B standard packages, Qwen3 0.6B non-thinking INT4, Qwen3.5 0.8B/2B INT8 and 4B mixed INT4, LFM2.5 2.6B INT4, Qwen2.5 1.5B INT8 and Phi-4 Mini INT8 downloaded successfully with checksum verification. Download success is not a runtime-compatibility result.
- Gemma3-1B-IT's pinned LiteRT file returned HTTP 401 without Hugging Face authorisation. It is not marked tested or offered as a working anonymous download. Existing Gemma GGUF support remains.
- The verified Qwen2.5-0.5B-Instruct repository has no `.litertlm` package. Its old `.tflite`/`.task` packages cannot be loaded as LiteRT-LM models. Existing GGUF support remains.
- Qwen3.5 and LFM use recurrent state. [LiteRT-LM issue 3165](https://github.com/google-ai-edge/LiteRT-LM/issues/3165) and the publishers' current cards report cross-conversation corruption when sharing an executor. BI recreates the executor before a new request for hybrid/unknown packages, after cancellation completes, without retaining a second model instance. Full chat history is passed explicitly as bounded role-separated turns. Only verified catalogue attention-only packages reuse the executor. Fresh-executor time must be included in end-to-end latency.
- The [Qwen3.5 4B card](https://huggingface.co/litert-community/Qwen3.5-4B) reports a Qualcomm phone hard reboot during an earlier GPU attempt. No stable HONOR evidence exists yet; the experiment runner does not attempt this large model's GPU path. It must not be advertised as validated GPU support. The 2026-10-16 tokenizer fix is part of the pinned current packages.
- LFM uses the [LFM Open License v1.0](https://huggingface.co/LiquidAI/LFM2.5-2.6B/blob/main/LICENSE), not Apache-2.0. Gemma3 uses Gemma terms. Gemma4 and Qwen use Apache-2.0; Phi-4 Mini uses MIT. Distribution must retain the relevant model terms and notices.
- Speculative decoding is exposed in 0.18.0 through `ModelInfo.Llm.hasSpeculativeDecodingSupport`, engine experimental flags and conversation configuration. Qwen3.5's publisher dropped MTP heads in these mobile files. No MTP/speculative speedup is claimed or enabled without a compatible package and measured correctness/stability.

## Grounding providers

Wikipedia uses [MediaWiki search](https://www.mediawiki.org/wiki/API:Search) and the Wikimedia page-summary API, with real article links, excerpts and revision timestamps. An update timestamp is not a publication date. Article text is CC BY-SA; source attribution is shown alongside each answer.

Optional Brave uses its [documented Web Search API](https://api-dashboard.search.brave.com/api-reference/web/search/get). The API key is encrypted using Android Keystore and excluded from backup. Search excerpts are explicitly labelled; BI does not claim to have read the entire linked page. Wikipedia is the default provider, and all web use requires opt-in. No provider is called from Binder, the API v2 provider or TypeRight inference.
