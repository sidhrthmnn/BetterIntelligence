# BI 2.0 development measurements

Measured on the physical HONOR VKP-NX9, Android 16 / Snapdragon 8 Elite / Adreno 830, 18 October 2026. This is an unfinished release evaluation, not a published 2.0 release. Raw synthetic outputs, actual backend, immutable model identity, APK hash, context, initialization, memory and thermal fields are in `candidates/`.

## Proofreading: 791 completed requests

Six distinct packages completed 113 fixed synthetic cases across seven backend cases (Qwen 3 0.6B was tested on both CPU and GPU), with fresh conversations and deterministic decoding. LiteRT GPU cases genuinely used OpenCL; Phi-4 Mini and the small Qwen GGUF used CPU. The dataset and protocol are in `../QUALITY.md`. These are direct inference tests, **not installed TypeRight end-to-end results**. The generic multilingual proofreading prompt also differs from the existing English-only IPC prompt.

| Mobile package | Actual backend | Exact reference / 113 | Reference-edit precision / recall | False changes / 50 unchanged cases | Median full response ms |
| --- | --- | ---: | --- | ---: | ---: |
| Gemma 4 E2B | GPU | 98 | 0.810 / 0.986 | 14 | 251 |
| Gemma 4 E4B | GPU | 105 | 0.872 / 0.986 | 7 | 580 |
| Qwen 3 0.6B | GPU | 68 | 0.433 / 0.652 | 22 | 313 |
| Qwen 3 0.6B | CPU | 58 | 0.393 / 0.638 | 29 | 629 |
| Qwen 2.5 1.5B | GPU | 90 | 0.693 / 0.884 | 15 | 549 |
| Phi-4 Mini | CPU | 85 | 0.637 / 0.942 | 24 | 3,961 |
| Qwen 2.5 0.5B GGUF | CPU | 71 | 0.545 / 0.696 | 19 | 1,055 |

The JSON summary is authoritative for exact timings and includes category metrics. Reference-edit matching penalizes valid alternatives such as contractions; capitalization accounts for several Manglish changes. These proxies are not semantic accuracy. Literal checks cover only the 15 cases with explicit invariants, not all 113 cases. English-only metrics exclude Malayalam/Manglish/mixed categories and any Malayalam-script long passage.

Manual inspection of nonmatching responses found material issues:

- E2B: missed the `return` → `returns` correction in `proof-053`; echoed the instruction in `proof-056`; transliterated two Malayalam sentences and several mixed-language passages.
- E4B: changed tense in `proof-053`; returned an unrelated market-shopping passage for the already-correct technical sentence in `proof-054`. Its higher overall reference-match count does not establish safer English proofreading.
- Qwen 2.5 1.5B: some valid contraction differences, but changed `there` to `here` in `proof-039`, changed a plural question to singular in `proof-071`, and translated/distorted the Malayalam long passage.
- Qwen 3 0.6B: missed several grammar/typo corrections, removed meaning-bearing words, changed version formatting, and mixed scripts in the Malayalam long passage. Its smaller download is not evidence of acceptable proofreading quality.
- Phi-4 Mini: changed `can` to `can't` in `proof-043`, invented `9:00 PM` from `ninr`, changed date/currency formatting, and distorted the Malayalam long passage. It is not a safe proofreading winner in this experiment.

## Curated choices and separate chat review

The library now recommends **Balanced: Gemma 4 E2B**, **Advanced: Gemma 4 E4B**, and **Fast: Qwen 3 0.6B**. Balanced is the first suitable-device recommendation; it does not override an existing active model or download automatically. Fast is a small-download/resource tradeoff, with an explicit weaker-writing/multilingual warning, not the accuracy winner. Advanced improves measured multilingual responses but uses more memory and is slower. Other packages and existing GGUF downloads remain accessible.

All 84 chat outputs were reviewed separately against their 12-case criteria; see `CHAT_REVIEW.md`. E2B/E4B had the strongest practical combination in this cohort. Both can still make unsupported ambiguity assumptions. Small Qwen GGUF invented current weather and a fictional-news URL, and failed arithmetic; it remains available for compatibility, outside the primary recommendations.

E2B is the provisional preferred English-polish model. The actual service prompt was tightened after a measured digit-to-word failure; five real Binder checks now preserve numbers, negation, technical names, URLs and emails. The 113-case generic multilingual experiment uses a different prompt. Installed TypeRight 2.0 validation and latency remain mandatory; own-UID tests do not establish that gate. Every proposed edit needs review; no semantic correctness guarantee is made.

## Native CPU / Vulkan recovery

The corrected native regression passed in **26.112 seconds**, with 16 recorded CPU/GPU-fallback samples across SmolLM2 135M and Qwen 2.5 0.5B, repeated switching, same-file reuse and native weight unmapping. On this phone the Vulkan shader compiler returned `vk::Device::createComputePipeline: ErrorUnknown`; every failed attempt returned to CPU. **No successful GGUF Vulkan inference is claimed on this HONOR.** LiteRT OpenCL GPU success is a separate runtime result.

An earlier native experiment was deliberately stopped after stalling in upstream's shader completion wait. Symbolized stacks showed `ggml_vk_load_shaders` → `ggml_pipeline_request_descriptor_sets` → model warm-up in `NativeGguf.prepare`. The pinned upstream code left `compile_pending` set after a compile exception. The checked CMake patch retains/rethrows that failure, destroys partial Vulkan handles and notifies waiters. The interrupted experiment is not counted as a pass.

Measured CPU medians with two repeats and 16-token requests:

| GGUF Q4_K_M | Threads | Decode tokens/s | First-token ms |
| --- | ---: | ---: | ---: |
| SmolLM2 135M | 2 | 91.9 | 166.0 |
| SmolLM2 135M | 4 | 169.1 | 86.5 |
| Qwen 2.5 0.5B | 2 | 23.8 | 577.5 |
| Qwen 2.5 0.5B | 4 | 43.3 | 298.0 |

Small samples establish execution and help bound thread selection; they are not sustained battery/thermal benchmarks.

## Interpretation limits

CPU/GPU share device RAM. Process PSS omits some GPU/driver allocations; system-wide GPU/DMA-BUF readings cannot be attributed solely to BI. Battery readings while charging do not measure inference energy. Reported throughput is actual runtime token accounting; callback chunks are not tokens. Model memory planning and fixed-context allocation requirements are estimates, separately identified from measured PSS.

Qwen 3.5 hybrid packages require fresh executors for independent conversations on LiteRT-LM 0.18, adding roughly 40–56 seconds of measured request initialization in this cohort. LFM's 96-token and separate 512-token proofreading probes exhausted the thought budget without visible output; the larger probes took about 38-47 seconds on CPU/GPU. Math/counting did return visible text. This is a practical latency/budget limitation at tested settings, not file incompatibility. Qwen 3.5 4B GPU was not attempted because its exact export has a publisher-reported device reboot risk. Missing Gemma 3 LiteRT authorization and the absent Qwen 2.5 0.5B `.litertlm` export are documented in the candidate manifest; existing GGUF support is retained.
