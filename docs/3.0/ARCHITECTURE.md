# Better Intelligence 3.0 runtimes

| Task types | Runtime and compatibility | Output |
| --- | --- | --- |
| `TEXT_GENERATION`, `TEXT_POLISH` | Existing llama.cpp GGUF and LiteRT-LM text engines. CPU, compatible Vulkan for GGUF, or compatible OpenCL for LiteRT. Existing text benchmarking and fallback still apply. | `text/plain` |
| `IMAGE_UNDERSTANDING` | CPU with pinned LiteRT-LM packages for Gemma 4 E2B, Gemma 4 E4B or LFM 2.5 Vision 450M. | `text/plain` |
| `AUDIO_TRANSCRIPTION`, `AUDIO_UNDERSTANDING` | CPU with Gemma 4 E2B or E4B LiteRT-LM and its bundled audio weights. Short mono PCM clips. | `text/plain` |
| `SPEECH_GENERATION` | An installed Android system speech engine and an offline voice matching the language. Android manages its processor and voice pack. | `audio/wav` |
| `EMBEDDINGS` | llama.cpp CPU with pinned BGE Small English F16. | `application/x-bi-embedding-f32` |

Media compatibility is tied to the exact tested package checksum. Hugging Face labels help find files. They do not establish support for a modality. The [Gemma 4 E2B package](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/tree/b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1) is Apache 2.0 and downloads on demand (2,588,147,712 bytes). Media activation requires about 3,000 MB of safe model RAM, plus Android's reserve. Image and audio tasks initialize the corresponding encoder explicitly through [LiteRT-LM's Kotlin API](https://github.com/google-ai-edge/LiteRT-LM/blob/main/docs/api/kotlin/getting_started.md). The [BGE model](https://huggingface.co/BAAI/bge-small-en-v1.5) is MIT, and the [pinned GGUF artifact](https://huggingface.co/ggml-org/models/blob/499bc8821c6b12b4e53c5bffcb21ec206f212d81/bert-bge-small/ggml-model-f16.gguf) is 67,308,128 bytes. BGE is an English encoder with a 512-token context.

## Routing and resources

`TaskRouter` verifies permission, formats, task state and queue space before work enters the runtime. It checks permissions again when execution begins. Per-capability settings determine the primary and fallback candidates. Automatic selection respects the requested capability, known package support, approved client preferences and the user's quality and processor settings. The existing repository and `InferenceEngine` coordinate legacy and new clients. A single queue owns v3 tasks and the client execution mutex serializes work with legacy inference. Native model changes use the repository and engine lifecycle locks. The router gives queued proofreading priority at task boundaries.

Text can stay warm for reuse, or unload after a task when the user disables the warm cache. Low-memory callbacks reclaim idle text sessions. For a Gemma media task, the engine releases idle text resources first and rechecks RAM and thermals before activating the media-specific session. It frees that session before starting the next task. The text preference remains available for lazy reload. BGE loads a small independent encoder and releases it after each request. Media tasks honor the configured memory ceiling and Battery Saver preference. Android speech uses [`Voice.isNetworkConnectionRequired`](https://developer.android.com/reference/android/speech/tts/Voice), checks installation and language, and returns a private read-only descriptor.

Models separates Installed, Downloads and Library. **AI assignments** configures supported capabilities individually. **Smart Setup** offers Essential, Balanced and Advanced suggestions sized to device RAM and storage, then asks before transferring any files. Hub downloads use the existing resumable background queue and pin revisions and known SHA-256 hashes. The app uses checksums to recognize the exact curated model even if a supported Hub file has a different name. The curated Library lists a small set of LiteRT language and media models plus BGE embeddings. Upgrades retain installed models, partial downloads and model IDs.

## Compatibility limits

- Media packages contain their own weights. Gemma 4 E4B requires about 3,750 MB of safe model RAM for media and LFM Vision about 1,100 MB for images. Qwen LiteRT and instruction GGUF downloads use the text path. Separate GGUF vision projectors, sharded packages, and custom multi-file dependencies are unavailable in this build.
- Image generation, editing, upscaling, background removal, live voice, video analysis or generation, and music and sound generation report `CAPABILITY_UNAVAILABLE`. These need dedicated validated models or media processing and are listed in the protocol for compatibility. Whisper, Piper/Kokoro, diffusion, video and audio-generation runtimes are candidates for independent validation. The build has no desktop execution endpoint.
- The production build ships CPU media and embeddings. No media GPU benchmarking, NPU or battery-consumption claim is made. Text hardware reporting follows actual runtime selection. Android voice execution reports `System-managed` because the service does not expose its processor.
