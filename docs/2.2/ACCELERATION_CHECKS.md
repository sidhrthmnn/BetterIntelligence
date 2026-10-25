# CPU, GPU and NPU status for 2.2

Checked on 25 October 2026 on the connected HONOR VKP-NX9 (Android 16, SM8750, Adreno). The web-engine update does not replace the existing inference runtimes. These short probes exercise their existing actual CPU/GPU paths, not mocked or emulator acceleration.

## The rule for every model

Automatic remains the default across GGUF and LiteRT downloads/imports and connected-app answers. It checks the packaged runtime, selected model, current shared RAM/thermal limits, real load/decode validation and cached measurements. It chooses a stable compatible backend, rather than enabling every processor by name.

- **GGUF:** llama.cpp uses CPU and, where the real Vulkan load/decode succeeds, offloads model layers to GPU. Partial offload leaves work on CPU. GPU layer/memory limits and CPU fallback remain. GGUF is not a portable NPU execution format.
- **LiteRT-LM:** a CPU or GPU graph/delegate must exist in the package and initialise successfully. CPU/GPU operation placement is owned by that runtime; it is not llama.cpp layer offloading. There is no universal switch to split every graph over CPU, GPU and NPU simultaneously.
- **NPU:** remains unavailable in this build. It requires an explicitly compatible compiled model, matching dispatch/runtime libraries and a proven device execution path. Hardware presence alone is insufficient. CPU/GPU functionality is retained independently.

Users can choose CPU Only or request GPU in Advanced settings. A GPU request can still safely fall back. Automatic's cache includes model/quantisation, firmware, context, runtime version, threads and layers; failed GPU attempts have a cooldown. These decisions are internal to BI; Binder clients do not need a new API.

## Actual Qwen probes

CandidateEvaluationTest, two rounds of arithmetic, proofreading and counting; temperature 0, four CPU threads, 32 output tokens maximum. Output length and content differ between tasks/backends; these mixed micro-probes are not a controlled sustained throughput or energy benchmark. Arithmetic was correct. The tiny LiteRT model only partly corrected the proofreading sentence, on both backends; speed does not establish writing quality.

| Model/configuration | Actual result | Median decode | Median first token | Initial setup | Peak app PSS |
| --- | --- | ---: | ---: | ---: | ---: |
| Qwen 3 0.6B LiteRT INT4, 1,280 context, GPU | Six requests completed on LiteRT GPU | 40.04 tokens/s | 201 ms | 8,456 ms | 852 MB |
| Same LiteRT file/context, CPU | Six requests completed on CPU | 37.89 tokens/s | 270 ms | 1,342 ms | 1,116 MB |
| Qwen 3 1.7B GGUF Q4_K_M, 2,048 context, GPU requested | Vulkan compute-pipeline creation failed; all six answers safely used CPU | **No GPU performance result** | Includes failed initialisation/fallback | Not a successful GPU setup | 2,509 MB during attempt/fallback |

The Vulkan diagnostic was `vk::Device::createComputePipeline: ErrorUnknown`. It is a real driver/runtime failure on this configuration, not evidence of working GGUF GPU inference. Resources were released and no native model mapping remained after the probes. The CPU-only Qwen 1.7B comparison also completed all six requests. It would be misleading to label the failed Vulkan request as GPU acceleration or force it for every model.

PSS is app-process memory; GPU driver allocations may be outside it. Lower app PSS is not extra GPU RAM. Android CPU/GPU share system memory. GPU has a significant first-load cost in this test; these results do not justify forcing it for every small request. No portable NPU/GPU power or battery-life measurement is claimed.

## What would enable NPU on this phone

[Google's LiteRT-LM NPU instructions](https://developers.google.com/edge/litert/next/litert_lm_npu#qualcomm-ai-engine-direct) list a **Gemma3-1B model compiled specifically for SM8750**, with associated Qualcomm QAIRT/QNN libraries and a Qualcomm dispatch library. The standard Qwen LiteRT file inspected in these tests declares **CPU/GPU**, not NPU. The built AAB contains `libggml-vulkan.so` and `liblitertlm_jni.so`, but does not package `libQnnHtp`, `libQnnSystem` or the Qualcomm dispatch library.

Supporting that route would require obtaining the licensed redistributable vendor libraries, adding the correct optional model/export and SoC checks, implementing NPU lifecycle/paired CPU fallback and then measuring actual output correctness, memory and sustained speed on hardware. It cannot enable NPU on every existing GGUF/LiteRT model, and is not labelled implemented by this release. No large model, conversion or vendor SDK is silently downloaded.
