# Models for mobile

The library offers nine instruction models in Q4_K_M GGUF format, from tiny downloads to higher-capacity options for high-memory Android devices. Four-bit quantization reduces weight storage; actual memory, latency and battery use still depend on the phone, context length and model architecture. This app currently runs llama.cpp on the CPU. These selections do not imply NPU acceleration or measured performance on a physical phone.

| Model | Download, MiB rounded up | Estimated runtime memory | Suggested use |
| --- | ---: | ---: | --- |
| SmolLM2 135M | 101 | 512 MB | Smallest download, basic snippets; limited correction quality |
| Gemma 3 270M | 242 | 768 MB | Short focused tasks; limited open-ended conversation |
| SmolLM2 360M | 259 | 768 MB | Lightweight English chat and edits |
| Qwen 2.5 0.5B | 469 | 1,024 MB | Compact multilingual starter model |
| Gemma 3 1B | 769 | 1,792 MB | English chat and short rewriting |
| SmolLM2 1.7B | 1,007 | 2,048 MB | English rewriting and summarization with more capacity |
| Qwen 2.5 1.5B | 1,066 | 2,048 MB | More capacity for editing and multilingual tasks |
| Qwen 3 4B Instruct 2507 | 2,382 | 4,096 MB | Higher-capacity writing, coding and multilingual chat |
| Qwen 2.5 7B Instruct | 4,467 | 6,144 MB | Larger assistant for devices with substantial free RAM |

For AI Polish, start with Qwen 2.5 0.5B on a constrained device and try Qwen 2.5 1.5B or SmolLM2 1.7B when more memory is available. This is a selection heuristic, not a proofreading benchmark. Small general-purpose models can miss errors or change meaning; review results before replacing text. The current shared polish prompt is optimized for English.

## Why these additions

- Google presents [Gemma 3 270M](https://developers.googleblog.com/en/introducing-gemma-3-270m/) as a small, efficient model for focused tasks, rather than complex conversation. Its small weights make it useful to offer alongside the existing tiny models. The catalog uses [Unsloth's Q4_K_M conversion](https://huggingface.co/unsloth/gemma-3-270m-it-GGUF), not Google's gated QAT artifact.
- The [official Qwen 2.5 1.5B GGUF repository](https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF) provides Q4_K_M weights. Its [model card](https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct) describes multilingual instruction following; it gives users a larger option than the existing 0.5B model without jumping to a multi-billion-parameter download.
- Hugging Face describes [SmolLM2](https://huggingface.co/HuggingFaceTB/SmolLM2-1.7B-Instruct) as a family intended for on-device use, with rewriting and summarization examples. The catalog uses [the publisher's 1.7B GGUF](https://huggingface.co/HuggingFaceTB/SmolLM2-1.7B-Instruct-GGUF) for users who want more English writing capacity.

- [Qwen 3 4B Instruct 2507](https://huggingface.co/Qwen/Qwen3-4B-Instruct-2507) is a non-thinking model with improved instruction following and writing capabilities. The app uses [Bartowski's single-file Q4_K_M conversion](https://huggingface.co/bartowski/Qwen_Qwen3-4B-Instruct-2507-GGUF). Its estimated 4 GB runtime budget includes context and overhead; actual requirements vary.
- [Qwen 2.5 7B Instruct](https://huggingface.co/Qwen/Qwen2.5-7B-Instruct) offers a larger instruction model. The catalog uses [Bartowski's single-file conversion](https://huggingface.co/bartowski/Qwen2.5-7B-Instruct-GGUF) because the official Q4_K_M release is split. It needs an estimated 6 GB for inference, plus the app's reserve for Android. Total installed RAM alone does not establish compatibility, and CPU answers can be slow.

All nine catalog downloads have exact byte sizes, SHA-256 checksums and immutable upstream revisions in [library.json](../models/library.json). Source and license links are available in each model's Details dialog. SmolLM2 and these Qwen models use Apache-2.0. Gemma requires its terms and use-policy acceptance before in-app download or activation.

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
