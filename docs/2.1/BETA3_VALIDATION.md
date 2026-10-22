# 2.1.0-beta3 (version code 8)

Web grounding and model library/download usability update. Validation completed 21–22 October 2026. Implementation details: [grounding](GROUNDING.md) and [model downloads](MODEL_DOWNLOAD_UI.md).

## Build and automated checks

- `:app:assembleDebugAndroidTest :app:assembleDebug :app:bundleRelease :app:lintRelease :app:testDebugUnitTest -PbiValidationPackage=true`: **BUILD SUCCESSFUL**, 5m 13s. Kotlin/KSP incremental compilation was disabled for this verification build.
- **99 JVM tests**, zero failures/errors. Release lint passed. Tests include query relevance/date handling, HTTPS tracking-link extraction, bounded evidence prompts, marker-only answer rejection, model lifecycle ordering/RAM labels and saved CPU thread preferences.
- The final Android fixture-only rebuild passed in 56 seconds; production compilation remained up to date and the release artifact did not change.
- Signed AAB: **90,260,034 bytes**, SHA-256 `1f1f8eb350a77fd84d8d7622bd589397ed6650d5037adc03dfdfb89ec2977ebc`.
- Validation debug APK SHA-256: `d5a9392975bf559a708637655b973961da3e949302498721a4002533fc155928`.
- Upload certificate SHA-256: `35:8B:05:89:A6:5E:FB:73:C6:85:DC:16:81:DE:D7:2C:C3:8A:76:6F:51:15:FE:08:DD:3C:E4:F0:76:2F:F1:8A`. The self-signed upload signing-chain warning is expected and unrelated to HTTPS server verification.
- Play Console accepted **8 (2.1.0-beta3)**, API 24+, target 36, two ABIs, native debug symbols attached. Supported device counts did not decrease. The sole validation warning concerns the absent deobfuscation map; release obfuscation is disabled.
- On **22 October 2026**, **CB3 - Better research and model downloads** was submitted to the existing **Closed testing - Alpha** track at 100% of that track. Publishing overview confirms **Changes in review**. Google's quick checks are still running and will forward the change for review on success. Managed publishing remains off, so an approved release can publish automatically. Approval/publication was not awaited.

## Live Android acceptance

**3 instrumented tests passed in 181.297 seconds** on an isolated Android 16/API 36 x86_64 emulator with 6 GB RAM and four virtual cores:

- `GroundingDeviceTest`: public website retrieval and private-destination restrictions.
- `GroundingRecentAnswerDeviceTest`: actual Compose chat, live search/page retrieval, local inference, persisted answers/source cards and a private-URL refusal.
- `ModelDownloadUiDeviceTest`: model visibility and downloaded/ready-to-use state in the real Compose screen.

Recent-answer inference used **Qwen 2.5 0.5B Instruct GGUF Q4_K_M, CPU, four threads, 2048 context, 64-token maximum, temperature 0**. The following values are measured emulator results, not estimated values or phone performance benchmarks. End-to-end latency includes research and prompt processing; generation throughput is the engine's recorded metric.

| Question | Actual result | Citation status | End-to-end | First token | Generation |
| --- | --- | --- | --- | --- | --- |
| 2026 physics Nobel, automatic search | Correct: Francis Halzen; retrieved source cards retained | Inline citation missing, honestly labelled | 68,062 ms | 61,593 ms | 5.35 tokens/s |
| 2026 chemistry Nobel, automatic search | Explicit unavailable response after marker-only output was rejected | Not marked cited; source cards retained | 50,982 ms | 49,479 ms | 6.13 tokens/s before rejection |
| 2026 physics Nobel, supplied IceCube page | Correct: Francis Halzen, with `[1]` | Evidence cited | 32,031 ms | 29,339 ms | 6.86 tokens/s |
| Private `127.0.0.1` URL | Deterministic unavailable response | Insufficient evidence, no source cards | No model call | N/A | Zero generated tokens |

The reference facts were independently checked against [CERN](https://home.cern/cern-congratulates-nobel-prize-winner-francis-halzen/), [IceCube](https://icecube.wisc.edu/news/awards/2026/10/francis-halzen-icecube-principal-investigator-wins-2026-physics-nobel-prize/) and the [Royal Swedish Academy of Sciences](https://www.kva.se/en/news/the-nobel-prize-in-chemistry-2026/). The chemistry reference is Henri B. Kagan and Kenso Soai; the app did **not** produce that answer in the final run. Retrieved chemistry pages included inconsistent text. The guard rejects empty/marker-only output, but does not automatically adjudicate publisher conflicts or prove semantic correctness. Names-only assertions in the fixture are supplemented by manual answer inspection and do not constitute general fact-checking.

A prior live `ModelDownloadUiDeviceTest` run passed in **40.745 seconds**, downloading and verifying the complete public **SmolLM2 135M file, 105,454,144 bytes**, and confirming automatic promotion into My models and the ready-to-use action. The final three-test run reused that installed model and checked its completed state. It did not download it again.

## Practical limits

No physical phone was connected for this update. No new Vulkan/NPU, battery, thermal or sustained phone inference measurements are claimed. The native runtime and Binder interface are unchanged. Existing secure model download trust/checksum behaviour is preserved.

Public search feeds and publisher pages remain external dependencies. Tiny models can miss inline citations or decline despite readable evidence; source cards, missing-citation labels and honest refusal remain visible. This is improved retrieval and failure handling, not guaranteed correctness for arbitrary recent questions. Important answers still need source inspection.
