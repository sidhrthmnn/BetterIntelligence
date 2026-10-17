# Gemma 4 on Android: LiteRT-LM versus llama.cpp

Measured 17 October 2026 on one physical HONOR VKP-NX9, Android 16, Snapdragon 8 Elite / Adreno 830, 11.24 GiB reported RAM. This is a comparison of deployable **engine + quantized model packages**. LiteRT mixed 2/4/8-bit and GGUF Q4_K_M are different quantizations of the same underlying instruction model; the difference cannot be attributed to runtime alone.

## Results

All values below are measured, not estimates. Each successful configuration completed five prompts repeated three times (15 responses), with four CPU threads, 2,048 context, greedy sampling, thinking disabled, and at most 96 output tokens. Warm statistics omit only the first response. TTFT is measured from request start to the first callback, including conversation/context setup. TPS comes from each runtime's actual decode counters. P95 uses nearest rank over 14 warm samples and is only descriptive at this sample size.

| Model/package | Backend | Init ms | Warm median TTFT ms | Warm P95 TTFT ms | Warm median decode tok/s | Peak process PSS MiB |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| E2B LiteRT-LM 0.18.0 | CPU | 7,657 | 544 | 838 | 28.93 | 1,582 |
| E2B LiteRT-LM 0.18.0 | GPU / OpenCL | 12,094 | 117 | 123 | 38.68 | 970 |
| E2B llama.cpp 0.7.0 Q4_K_M | CPU | 2,671 | 1,767 | 2,156 | 10.61 | 3,286 |
| E4B LiteRT-LM 0.18.0 | CPU | 13,768 | 1,742 | 1,999 | 14.51 | 3,177 |
| E4B LiteRT-LM 0.18.0 | GPU / OpenCL | 17,455 | 301 | 568 | 18.41 | 1,027 |
| E4B llama.cpp 0.7.0 Q4_K_M | CPU | 4,638 | 4,437 | 5,448 | 5.31 | 4,672 |
| E2B/E4B llama.cpp Q4_K_M | Vulkan, 8 requested layers | Failed | — | — | — | — |

Both Vulkan attempts failed with `vk::Device::createComputePipeline: ErrorUnknown` on this phone. They are failures, not GPU performance results. Production keeps CPU fallback. No claim is made that all Android Vulkan devices fail.

**PSS is process memory, not total CPU + GPU memory.** Driver allocations may be absent, especially on the OpenCL path. The lower GPU PSS does not prove lower total RAM consumption. Android CPU/GPU share RAM. The app uses conservative shared-memory estimates and current memory pressure to admit configurations; it never adds GPU heap capacity to system RAM.

## Output checks and decision

The five prompts cover English proofreading, `17 × 6`, Malayalam proofreading, Manglish preservation and counting. All successful CPU/GPU runs returned `102` for arithmetic. English answers corrected “their” to “there” and “minute” to “minutes”; Manglish remained Latin-script Malayalam. E4B produced the more natural Malayalam edit `ഞാൻ നാളെ ഓഫീസിൽ വരില്ല, കാരണം എനിക്ക് പനി ഉണ്ട്.` The complete responses are retained in [raw reports](validation/1.3/).

These checks found no obvious quality regression in the successful LiteRT paths. They are a small smoke dataset, not a multilingual accuracy score or a general ranking of models. No quality percentages are invented. All runs stayed at Android thermal status 0; SoC power, GPU energy, long-session battery drain and long-term throttling were not measured. Cold initialization is slower for LiteRT in this experiment; subsequent GPU initialization can benefit from the runtime cache, which was not cleared between every run.

For these Gemma 4 mobile packages on this phone, **LiteRT-LM is the better interactive choice**: both its CPU and OpenCL GPU paths completed with correct basic outputs and substantially better latency/throughput. Version 1.3 therefore adds real LiteRT-LM alongside the existing GGUF runtime. Existing GGUF models and clients remain supported. Users choose a model file; BI owns hardware selection. This is not a universal claim that LiteRT beats llama.cpp for every model/device.

## Reproduce

Use `scripts/download_gemma_benchmarks.ps1` to download all four immutable, SHA-256-pinned artifacts. Verify the transferred files on the phone. Install the isolated validation build, leaving the Play app/data intact. Stage a model in `files/models/` with `adb shell run-as <validation-package> cp /data/local/tmp/<filename> files/models/<filename>`.

Run `GemmaEngineBenchmarkTest` with instrumentation arguments `gemmaModel=<filename>`, `gemmaRuntime=litert|llama`, `gemmaBackend=CPU|GPU`, and `gemmaRounds=3`. Each invocation uses a fresh instrumentation process and writes a JSON report in the validation app's private files, including errors and raw output. Pull reports using `run-as ... cat`, then use `python scripts/summarize_gemma_benchmarks.py <report-directory>`. Keep the device idle, record firmware, and repeat across phones and longer sessions before generalizing.

The opt-in `LiteRtIntegrationDeviceTest` (`gemmaIntegration=true`) exercises the production shared core, old Binder methods, AUTO, cancellation, refusal to unload/switch during generation, same-file reuse, resource cleanup and switching back to GGUF.

## Sources and compatibility limits

- [Google LiteRT-LM Android API](https://developers.google.com/edge/litert-lm/android).
- [E2B publisher model card](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm) and [E4B model card](https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm): mobile quantization, CPU/GPU graphs, text-only resident weight estimates and Apache-2.0 licensing. Exact revisions/checksums are in the catalog and download script.
- [Gemma 4 prompt format](https://ai.google.dev/gemma/docs/core/prompt-formatting-gemma4). The native app formatter explicitly handles Gemma 4's new turn tokens; other GGUF models retain their existing templates.

Vision/audio are not exposed by this text app. Raw TFLite, `.task`, arbitrary vendor NPU files and GPU-only files without a CPU fallback are not accepted as standard LiteRT models. NPU is disabled until a matching compiled artifact, vendor driver/runtime and real execution tests exist. No automatic conversion or hidden model download is performed.
