# Models for mobile

The library offers sixteen curated downloads: eleven Q4_K_M GGUF models, three established LiteRT-LM models (Qwen 3 0.6B and Gemma 4 E2B/E4B), and two experimental LiteRT-LM models (LFM 2.5 1.2B and SmolLM3 3B). Gemma 4 E2B and E4B are available in both formats. GGUF uses llama.cpp CPU/Vulkan; `.litertlm` uses LiteRT-LM CPU/OpenCL, with [automatic selection and CPU fallback](HARDWARE_ACCELERATION.md). See the [physical-phone Gemma 4 comparison](LITERT_LLAMA_COMPARISON.md) for measured latency, speed, output checks and limits. No NPU acceleration is implied.

| Model | Download, MiB rounded up | Estimated RAM at 2K context | Suggested use |
| --- | ---: | ---: | --- |
| SmolLM2 135M | 101 | 410 MB | Smallest download, basic snippets; limited correction quality |
| Gemma 3 270M | 242 | 553 MB | Short focused tasks; limited open-ended conversation |
| SmolLM2 360M | 259 | 615 MB | Lightweight English chat and edits |
| Qwen 2.5 0.5B | 469 | 786 MB | Compact multilingual starter model |
| Gemma 3 1B | 769 | 1,138 MB | English chat and short rewriting |
| SmolLM2 1.7B | 1,007 | 1,727 MB | English rewriting and summarization with more capacity |
| Qwen 2.5 1.5B | 1,066 | 1,463 MB | More capacity for editing and multilingual tasks |
| Qwen 3 4B Instruct 2507 | 2,382 | 3,116 MB | Higher-capacity writing, coding and multilingual chat |
| Qwen 2.5 7B Instruct | 4,467 | 5,192 MB | Larger assistant for devices with substantial free RAM |
| Gemma 4 E2B LiteRT mixed precision | 2,469 | 2,374 MB | Mobile text polishing/chat; validated GPU on supported phones |
| Gemma 4 E4B LiteRT mixed precision | 3,490 | 3,590 MB | More capable mobile text model; requires more free RAM |
| Gemma 4 E2B GGUF Q4_K_M | 2,963 | 3,596 MB | Gemma 4 through llama.cpp |
| Gemma 4 E4B GGUF Q4_K_M | 4,747 | 5,718 MB | Gemma 4 through llama.cpp on high-memory devices |
| Qwen 3 0.6B LiteRT INT4 | 332 | 894 MB | Fast/basic English tasks; measured quality limits |
| LFM 2.5 1.2B LiteRT INT4 GPU (experimental) | 703 | ~1.1 GB at 4K (planning estimate) | Experimental local chat, **not HONOR benchmarked** |
| SmolLM3 3B LiteRT INT4 (experimental) | 1,912 | ~2.8 GB at 4K (planning estimate) | Experimental local chat, **not HONOR benchmarked** |

For AI Polish on the measured HONOR phone, prefer the validated Gemma 4 E2B/E4B options before older unbenchmarked models. Smaller models may be appropriate for constrained phones, but the published 2.0 quality and integration tests show important differences. Neither new experimental model is a qualified TypeRight replacement yet. Small general-purpose models can miss errors or change meaning; review results before replacing text. The current shared polish prompt is optimized for English.

For the two experimental models, consult [the test plan and upstream package verification](EXPERIMENTAL_MODELS.md); the **Experimental** filter exposes them without making them automatic defaults.

## Why these additions

- Google presents [Gemma 3 270M](https://developers.googleblog.com/en/introducing-gemma-3-270m/) as a small, efficient model for focused tasks, rather than complex conversation. Its small weights make it useful to offer alongside the existing tiny models. The catalog uses [Unsloth's Q4_K_M conversion](https://huggingface.co/unsloth/gemma-3-270m-it-GGUF), not Google's gated QAT artifact.
- The [official Qwen 2.5 1.5B GGUF repository](https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF) provides Q4_K_M weights. Its [model card](https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct) describes multilingual instruction following; it gives users a larger option than the existing 0.5B model without jumping to a multi-billion-parameter download.
- Hugging Face describes [SmolLM2](https://huggingface.co/HuggingFaceTB/SmolLM2-1.7B-Instruct) as a family intended for on-device use, with rewriting and summarization examples. The catalog uses [the publisher's 1.7B GGUF](https://huggingface.co/HuggingFaceTB/SmolLM2-1.7B-Instruct-GGUF) for users who want more English writing capacity.

- [Qwen 3 4B Instruct 2507](https://huggingface.co/Qwen/Qwen3-4B-Instruct-2507) is a non-thinking model with improved instruction following and writing capabilities. The app uses [Bartowski's single-file Q4_K_M conversion](https://huggingface.co/bartowski/Qwen_Qwen3-4B-Instruct-2507-GGUF). Its approximately 3.1 GB CPU estimate at 2K context includes architecture-specific K/V cache and overhead; GPU buffers need extra shared RAM. Actual requirements vary.
- [Qwen 2.5 7B Instruct](https://huggingface.co/Qwen/Qwen2.5-7B-Instruct) offers a larger instruction model. The catalog uses [Bartowski's single-file conversion](https://huggingface.co/bartowski/Qwen2.5-7B-Instruct-GGUF) because the official Q4_K_M release is split. Its CPU estimate at 2K context is approximately 5.2 GB, plus the reserve for Android. GPU buffers require extra shared RAM. Total installed RAM alone does not establish compatibility, and CPU answers can be slow.

All sixteen catalog downloads have exact byte sizes, SHA-256 checksums and immutable upstream revisions in [library.json](../models/library.json). Source and license links are available in each model's Details dialog. SmolLM2, SmolLM3, Qwen and Gemma 4 use Apache-2.0; LFM 2.5 uses LFM Open License v1.0 (review its commercial-use conditions). Gemma 3 requires its terms and use-policy acceptance before in-app download or activation. LiteRT text-memory estimates exclude audio/vision models that are not loaded; memory-mapped embeddings, context and runtime headroom are included. GPU memory is shared system RAM, and process PSS may omit driver allocations.

## Choosing any compatible model

1. Open **Models → Find models**. Search a name or paste a public Hugging Face `owner/repository` or repository URL.
2. Select a single-file GGUF quantization. The chooser displays download size and a conservative memory estimate. Q4_K_M is a useful starting point; higher precision usually needs more storage and memory.
3. Review the publisher's license and model card. The download is pinned to the repository revision returned by Hugging Face. Where LFS supplies a SHA-256, the app verifies it; otherwise it reports that publisher verification is unavailable.
4. Alternatively, obtain a model yourself and choose **Import a GGUF file**. This also handles gated/private downloads without storing a Hugging Face access token in the app.
5. Open **My models**, then **Use model**. Approved consumer apps use the same selected model through the shared local service.

“Any model” means any compatible text instruction GGUF that fits the device. The bundled llama.cpp revision must support its architecture and chat template. Safetensors, ONNX, LiteRT/TFLite, adapters, multimodal projectors and split GGUF files are not interchangeable with this runtime. The search chooser hides projector and standard split-file names. Native loading still validates compatibility and reports unsupported models.

Storage determines whether a model may be downloaded. Available RAM, the supported 64-bit processor and low-memory state determine whether it may be activated. Memory is reserved for Android and other apps, so installed RAM is not the same as available model memory. Downloading a larger model does not guarantee it can run. The built-in benchmark can measure speed after activation.

## Developer verification

`python scripts/download_models.py` downloads all catalog weights and checks their sizes and hashes. New weight files are ignored by Git; the APK only includes the catalog. Unit tests parse every catalog weight present locally and require the original four Git LFS weights. To check the new models through the native runtime, stage their files into the app's private `files/models/` directory and run `GgufInferenceTest#newMobileCatalogModelsRunOnNativeCpuBackend`. This checks loading and nonempty token generation; it does not measure proofreading quality or phone performance.
