# Runtime, model and source notices

Better Intelligence includes llama.cpp (MIT), Google LiteRT-LM (Apache-2.0), Vulkan headers (Apache-2.0 or MIT) and SPIR-V headers (per-file licences). Full runtime notices are packaged in `app/src/main/assets/licenses/`. Android and Kotlin dependencies retain their original licences.

Models download separately and are not bundled in the APK. Exact publishers, immutable revisions and SHA-256 hashes are recorded in `app/src/main/assets/model-library.json`. Gemma 3 uses [Gemma terms and use restrictions](https://ai.google.dev/gemma/terms). Gemma 4 and Qwen packages use Apache-2.0. Phi-4 Mini uses MIT. Research candidate LFM2.5 uses the [LFM Open License v1.0](https://huggingface.co/LiquidAI/LFM2.5-2.6B/blob/main/LICENSE); download availability does not imply that every candidate is included in the curated release catalogue. Review the linked model publisher's terms for your intended use.

Public web research parses DuckDuckGo's public HTML search page, then reads publisher pages directly subject to robots and snippet restrictions. It uses no external search, grounding or weather API. Search snippets are not cited as page evidence. Retained excerpts identify and link the publisher; their content remains subject to the publisher's rights and terms. AI answers are generated interpretations, not publisher-authored text or independent verification. Public page markup can change or access can be blocked. Legacy saved Wikipedia excerpts remain attributed under [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/) and Wikimedia terms; the old Wikipedia/Brave integrations and Bing feeds are no longer active.

The launcher illustration is the image supplied by the app owner. The interface design and Compose components are original project code.

Old saved weather answers may contain [Open-Meteo](https://open-meteo.com/) data attributed under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). Their existing attribution remains visible. New requests do not call Open-Meteo or any weather API; they require sufficiently current publisher page evidence or remain unverified.
