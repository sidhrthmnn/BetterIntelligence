# Mobile text models after v27

Development validation on 4 November 2026. These are downloadable catalogue additions, not weights bundled in the APK. Each uses a fixed repository revision, exact size and SHA-256. The four files were downloaded and checksum verified before device staging.

| Model | Publisher/conversion | Quantization | Download bytes | Architecture |
| --- | --- | --- | ---: | --- |
| LFM3 1.7B QAD Q4 | LiquidAI | Q4_0 | 1,049,615,680 | lfm3 |
| Qwen 3.5 2B Q4 | Unsloth conversion | Q4_K_M | 1,280,835,840 | qwen35 |
| Qwen 3.5 4B Q4 | Unsloth conversion | Q4_K_M | 2,740,937,888 | qwen35 |
| Gemma 4 E2B QAT Q4 | Google | Q4_0 | 3,349,516,256 | gemma4 |

Sources: [LiquidAI GGUF](https://huggingface.co/LiquidAI/LFM3-1.7B-GGUF), [Qwen 2B conversion](https://huggingface.co/unsloth/Qwen3.5-2B-GGUF), [Qwen 4B conversion](https://huggingface.co/unsloth/Qwen3.5-4B-GGUF), [Google corrected-vocabulary QAT package](https://huggingface.co/google/gemma-4-E2B-it-qat-q4_0-gguf).

LFM3 uses the LFM Open License v1.0, including its commercial revenue threshold; the exact licence is bundled beside the model notices and in Android assets. It is not labelled Apache-2.0. The Qwen conversions and Google package retain their published Apache-2.0 metadata.

## Runtime and memory

LFM3 requires the proven llama.cpp runtime's new architecture support. BI now pins llama.cpp **v0.8.0**, archive SHA-256 `51b1e025d7b12e83fa55736e65bc254acd9d23e49f546be7531368fef82210ee`. The source-checked Android allocation compatibility and Vulkan failure cleanup patches still apply. CPU and optional Vulkan libraries compile for both supported ABIs. The runtime identity changes the backend decision-cache key.

The native formatter now applies the existing publisher-declared non-thinking switch to qwen35 and LFM3 as well as the earlier supported ChatML families. LFM3 uses its own compact `<think></think>` generation prefix, matching [LiquidAI's template](https://huggingface.co/LiquidAI/LFM3-1.7B/blob/main/chat_template.jinja). This reduces simple-task reasoning overhead; it does not guarantee the model will never emit thought markers or obey a formatting instruction. Existing Gemma 4 text formatting remains scoped to that architecture.

Memory geometry is read from the actual GGUF headers rather than assuming every hybrid layer has a full attention cache:

| Model | KV bytes per token, estimate | KV at 2048 tokens |
| --- | ---: | ---: |
| LFM3 1.7B | 22,528 | 44 MiB |
| Qwen3.5 2B | 12,288 | 24 MiB |
| Qwen3.5 4B | 32,768 | 64 MiB |
| Gemma 4 E2B QAT | 71,680 | 140 MiB |

LFM3 uses its actual per-layer KV-head array, including zero-attention convolution layers. Qwen3.5 uses its full-attention interval and recurrent-layer mask. Recurrent state remains covered by runtime headroom; Gemma sliding-window layers retain a conservative full-window upper bound. Quantized weights, context cache, fixed runtime allowance, percentage overhead and the existing Android memory reserve remain in the total. These are **estimates**, not guarantees or separately allocated GPU VRAM. Android integrated GPUs share physical memory with the CPU.

New models are available for manual primary/fallback selection. They do not silently displace existing automatic recommendations or the default secondary model. The user can choose one fallback and enable it; the redundant Clear standby button is removed. Normal memory, thermal, runtime and model-lifecycle checks remain.

## Physical-device comparison

`MobileModelComparisonTest` runs only in BI Validation. The phone is an HONOR VKP-NX9, Android 16, approximately 11 GiB physical RAM reported by Android (marketed as 12 GB). All four models loaded, generated and unloaded through the normal BI engine without bypassing memory admission.

Configuration: **CPU only**, context 2048, four requested CPU threads, greedy/temperature zero, at most 128 generated tokens, a separate unmeasured warmup and two repetitions of seven matched synthetic tasks. The corpus covers grammar, subtraction, an age-order constraint, public/private JSON route selection, synthetic-source accuracy and an app-name follow-up with explicit conversation history. JSON routing is scored only and never executes a tool or bypasses web privacy policy.

Reported tokens/s is native decode telemetry. TTFT is time to the first generated token, including a thought token if the model emits one; it is not necessarily time to a useful final answer. End-to-end elapsed time includes normal BI orchestration. Peak process PSS is sampled every 250 ms and can miss short peaks; it is not total device RAM or an exact model allocation. Load times are warm-filesystem single observations. Android thermal status is categorical, not an energy measurement.

The initial LFM3 run exposed unwanted reasoning overhead; the publisher-supported fast prefix was added before the final comparison. Another test-harness issue was found in Android regex parsing of JSON; the final scorer parses the entire requested JSON object directly, rejecting prose-wrapped objects. No model answer was edited to improve a score.

Final measurements are in `mobile-model-comparison.json` and the table below. Criteria are narrow instruction-following checks, not a general intelligence ranking, statistical benchmark or proof that a model never hallucinates. Raw answers are retained so the rubric can be audited.

| Model | Single load | Median TTFT | Median decode | Median request | Peak sampled PSS | Criteria matched |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| LFM3 1.7B QAD Q4 | 498 ms | 1960 ms | 16.75 tokens/s | 2622.5 ms | 1362 MiB | 10/14 |
| Qwen3.5 2B Q4 | 1242 ms | 2300 ms | 13.04 tokens/s | 2838 ms | 1595 MiB | 12/14 |
| Qwen3.5 4B Q4 | 2883 ms | 5924 ms | 5.87 tokens/s | 7203.5 ms | 3061 MiB | 14/14 |
| Gemma 4 E2B QAT Q4 | 3044 ms | 2712 ms | 8.56 tokens/s | 3176 ms | 3551 MiB | 14/14 |

All final samples reported Android thermal status 0 or 1 (none/light); this short run cannot establish sustained thermal or battery performance. Sequential fixed model order and charging/device state can affect measurements; there is no statistically controlled speed ranking.

LFM3 gave the correct public/private routing JSON in both repetitions and had the smallest process footprint. Its arithmetic and context-follow-up outputs contained extra reasoning/closing-thought markers and failed the strict answer-only criterion. The non-thinking prefix helped simple tasks but did not eliminate spontaneous reasoning. Qwen2B answered the subtraction question **6**, rather than **5**, in both repetitions; that is a real quality failure, not a parser error. Qwen4B and Gemma QAT matched all seven narrow criteria twice, at higher memory cost. These observations support offering choices, not replacing users' existing defaults or claiming universal reasoning quality.

## Acceleration and modality limits

The physical phone's Adreno 830 Vulkan runtime is detected, but model preflight on this driver returned `vk::Device::createComputePipeline: ErrorUnknown`. BI correctly retried on CPU, reported **CPU / zero GPU layers**, and completed inference. The native resource test also verified unload/reload, no retained native weight mappings, history handoff and independent-request isolation. `native-080-phone.json` records this outcome. **GPU execution is not claimed as verified on this phone.**

GGUF weights are not automatically Qualcomm NPU models. This change adds no NPU support or new vendor binary. Existing NPU format/device compatibility rules remain.

All four additions are currently **text only in BI**. Qwen/Gemma family multimodal capabilities do not mean their GGUF text file enables images, video or audio in this app. No image projector, encoder or UI was silently downloaded or labelled functional. In particular, the QAT Gemma download is larger than Qwen2B and LFM3 despite its E2B label; the Models screen shows its actual estimated RAM.

The tiny SmolLM2 135M lifecycle fixture passed fallback/cancellation plumbing tests, but produced poor general-knowledge prose. That is not presented as answer-quality validation or a recommendation. The larger models are compared separately. Sustained battery use, long-context workloads, GPU speed, NPU execution and multimodal quality remain unmeasured here.

## Reproducing

Build with the configured Android SDK/NDK, CMake and Rust toolchain:

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintRelease -PbiValidationPackage=true -PbiIpcValidation=true
```

Install only the validation APK and test APK. Stage the four checksum-verified files in that package's private `files/models` directory, preserving the Play Store installation. Close unrelated apps and leave the device unlocked. Run:

```text
adb shell am instrument -w -e mobileComparison true -e class com.example.MobileModelComparisonTest com.aistudio.betterintelligence.mobile.validation.test/androidx.test.runner.AndroidJUnitRunner
```

Use `-e comparisonModel <filename>` for one model. Export `files/mobile-comparison.json` with `adb exec-out run-as` from the validation package. For web lifecycle checks use `webReliability=true`, optionally `reliabilityModel=<staged filename>`. Source-reader tests make real public requests; transient network failures must remain classified and must not be hidden by relaxing assertions.
