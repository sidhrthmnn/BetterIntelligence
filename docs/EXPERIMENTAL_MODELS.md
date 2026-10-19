# Experimental mobile models — added 19 October 2026

This branch proposes **two additional opt-in LiteRT-LM packages**. They are catalog entries and UI support only: the APK **does not include** model weights, no download/activation happens automatically, and neither model was tested on the physical HONOR VKP-NX9 as part of this change. The current measured defaults — Qwen 3 0.6B Fast, Gemma 4 E2B Balanced, Gemma 4 E4B Advanced — remain unchanged.

## Publisher-pinned packages

| Model | Publisher revision | Exact file | Bytes | SHA-256 | License |
|---|---|---|---:|---|---|
| LFM 2.5 1.2B Instruct | `4ef1641b562b70ffca66ce4fee7bef4446b374b6` | `LFM2.5-1.2B-Instruct_int4_gpu.litertlm` | 736220768 | `97e6a9700208f59fce2ceb993cd6c18ea77ada8c2a940f68332004f38a404671` | LFM Open License v1.0 |
| SmolLM3 3B | `7621a4f099a00b0518e0a6e7c5628af08ab4184e` | `SmolLM3-3B_q4_block32_ekv4096.litertlm` | 2004633520 | `bdd7eb05b6764fb5a5173bdbdd59354a26f66e4c20b040f0d3da3532071d18f6` | Apache-2.0 |

Sources: [LFM 2.5 publisher card and manifest](https://huggingface.co/litert-community/LFM2.5-1.2B-Instruct), [LFM pin](https://huggingface.co/litert-community/LFM2.5-1.2B-Instruct/commit/4ef1641b562b70ffca66ce4fee7bef4446b374b6), [SmolLM3 publisher card and manifest](https://huggingface.co/litert-community/SmolLM3-3B), [SmolLM3 pin](https://huggingface.co/litert-community/SmolLM3-3B/commit/7621a4f099a00b0518e0a6e7c5628af08ab4184e).

The LFM **`_int4_gpu` export** is intentionally selected: the publisher reports other INT4 packaging can fail to create its GPU graph. SmolLM3 includes a thought channel; model outputs may require particular budget/control handling. Reported Android CPU/GPU support is publisher-side, not proof of working execution in this app. Both packages use a **fixed 4096-token allocation**; requesting 512 or 2048 does not necessarily reduce runtime memory. The app's estimated RAM is a heuristic, not a measurement of all shared GPU buffers.

## Device qualification before promotion

1. Run Android JVM tests and lint, assemble debug and test APKs in CI. Confirm the two JSON catalogs are identical, verified checksums, new filters, and unchanged existing recommendation tests.
2. Install a **separate debug/diagnostic build** on the HONOR without removing, downgrading or modifying either Play-installed app. Preserve downloads, approvals, conversation history and TypeRight data.
3. Download each file explicitly over HTTPS and require exact byte count and SHA-256. Verify `.litertlm` text-generation package via the actual LiteRT-LM loader, not filename alone.
4. Measure **cold and warm** initialization, time-to-first-visible-token, visible tokens/s, wall time, peak memory and thermal status with CPU, GPU and AUTO. Record the *actual* selected backend, fallbacks and every load/inference failure. Run multiple independent short and long chats, cancel/retry, app restart and thermal fallback. A hidden thought-only answer, zero visible output, long initialization or crash is a failure, not a quality success.
5. Compare with the existing Gemma 4 E2B/E4B real outputs on the same phone/context and device temperature. Verify basic arithmetic, chat multi-turn recall, factual uncertainty, and offline behavior. No fabricated speed/quality claims.
6. Run the existing 113-case proofreading suite **plus real installed TypeRight JSON selected-span tests** on the new model, including English, Malayalam, Manglish and mixed text; names, quantities, negation, URLs, dates, emojis and selection boundaries. Count false edits and meaning changes, validate exact undo and failure recovery, and test a first cold Binder request. Reject any model that invents words or breaks selection behavior, regardless of raw spelling score.
7. If a candidate performs well, propose a **separate** PR promoting its label/recommendation. Do not auto-switch active models, change a known-good default, update Play, or merge this experimental PR without explicit review.

## UI behavior

The Library has a new **Experimental** chip with those two downloads. A model explicitly activated by the user is exposed to their approved connected apps, including TypeRight; that does **not** imply TypeRight validation. The default recommendation intentionally excludes experimental entries. CPU/GPU runtime selection still requires actual initialization and device validation.

## Known open risks

- LFM may spend token budget in a thought channel, as observed with a distinct LFM2.5 2.6B export in BI 2.0 tests.
- SmolLM3's multi-component embedder and GPU graph have not been exercised by the packaged LiteRT-LM 0.18.0 in this app.
- Shared process/GPU memory, driver compatibility, first-request latency, and safe proofreading are unverified.
- The LFM license requires checking commercial-use conditions, including the stated US$10M-revenue threshold.
