# Runtime, model and source notices

Better Intelligence includes llama.cpp (MIT), Google LiteRT-LM (Apache-2.0), Vulkan headers (Apache-2.0 or MIT) and SPIR-V headers (per-file licences). Full runtime notices are packaged in `app/src/main/assets/licenses/`. Android and Kotlin dependencies retain their original licences.

Models download separately and are not bundled in the APK. Exact publishers, immutable revisions and SHA-256 hashes are recorded in `app/src/main/assets/model-library.json`. Gemma 3 uses [Gemma terms and use restrictions](https://ai.google.dev/gemma/terms). Gemma 4 and Qwen packages use Apache-2.0. Phi-4 Mini uses MIT. Research candidate LFM2.5 uses the [LFM Open License v1.0](https://huggingface.co/LiquidAI/LFM2.5-2.6B/blob/main/LICENSE); download availability does not imply that every candidate is included in the curated release catalogue. Review the linked model publisher's terms for your intended use.

Wikipedia source text is available under [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/) and Wikimedia terms. Each retained excerpt identifies and links its source article; revision dates are shown when available. Excerpts may be shortened. AI answers are generated interpretations, not Wikipedia-authored text or independent verification. Brave search excerpts are labelled separately and link to the publisher. Provider requests use the documented APIs and remain subject to provider terms.

The launcher illustration is the image supplied by the app owner. The interface design and Compose components are original project code.
