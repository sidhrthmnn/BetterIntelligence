# 2.0 validation record

This branch is a development candidate. The Play-installed 1.3 / code 4 app and TypeRight 213 have not been replaced by diagnostic builds.

## Completed checks, 18 October 2026

- JDK 21, Android SDK 36.1, NDK 28.0.12433566, pinned llama.cpp 0.7.0 and LiteRT-LM 0.18.0.
- Latest ARM64 build: JVM tests, debug app/test assembly and release lint passed in 3 min 17 s: **74 JVM tests, zero failures/errors**. Coverage includes schema 7 migration, conversation isolation, Binder contract/readiness, literal-preserving polish, memory/context planning, identity, backend fallback/cache/resource policies, downloads and bounded/cancellable HTTP.
- Physical HONOR: **5 product/native tests passed in 87.386 s**. Real E2B history/edit/regenerate/cancel/recreation, independent conversations, cited Wikipedia, missing Brave credentials and web-off replies; navigation/themes; SwiftKey IME gap at most 3 dp; live English/Malayalam retrieval; native CPU/GPU-fallback switching/history/cleanup. APK SHA-256: `f3195c99a6c18955940c26138fb1968ae2527b88376c657438999c784e9e57f6`.
- Separate real Binder/shared-engine test passed in **26.439 s**. Five exact polish cases preserve digits, negation, identifiers, URLs/time and emails. Readiness, cancellation, busy status, reuse, unload and switching passed. Own-UID service tests are **not installed TypeRight compatibility proof**.
- Native prompt-token history checks: SmolLM2 12 without history / 43 with history / 12 independent; Qwen 0.5B 11 / 41 / 11. Weight mappings disappear after release. All Vulkan attempts on this driver fell back after `createComputePipeline: ErrorUnknown`; no GGUF GPU success is claimed.
- Independent process-death phases passed (0.513 / 1.330 s, different PIDs): draft, stopped partial response, model and app approval restored. Offline test passed (15.668 s): local math succeeds with no active network; failed Wikipedia search has no fabricated sources. Connectivity restored afterward.
- Active-model deletion/caller approval: 2 tests passed (0.612 s). Busy deletion rejected, idle deletion retained chats. Simulated Android thermal-status throttling exercised conservative CPU selection; override cleared. This is not sustained physical-heating evidence.
- Android 16 x86_64 emulator: 2 UI tests passed (30.007 s), 320 dp / 150% font. All themes/settings, recreation and keyboard geometry passed. Captures identify their APK and precede the last curated-category/polish refinements; HONOR captures are the latest product evidence.
- CPU/optional Vulkan libraries compile for ARM64 and x86_64. Compilation is not GPU execution evidence.
- Full-ABI signed 2.0/code 5 builds passed (7 min 4 s, then 4 min 4 s with the Binder context correction), including all 74 JVM tests and release lint. Bundletool validation, upload signature verification and native-content checks passed: both ABIs, 16 KB ELF alignment, optional Vulkan linkage, unchanged production authority/permission and no bundled model weights. Final source-commit provenance and release-gate tests are still being completed before upload.
- All six minimum-context regression configurations completed: E2B/E4B CPU, GPU and AUTO with a requested 512 promoted to 1024. The 27 earlier context runs retain four original CPU/AUTO failures. E4B's later AUTO choice favored CPU after a measured thermal-status increase during GPU validation; raw GPU requests passed. See `reports/MINIMUM_CONTEXT_FIXED.md` and the conservative-selection caveat.
- Foundation CI [39134772071](https://github.com/sidhrthmnn/BetterIntelligence/actions/runs/39134772071), commit `2f2f38f3b7707e6e6261cdffe61cb4beb566528d`, passed JVM/lint/ARM64 CPU+Vulkan. Follow-up changes require new CI.
- **791 proofreading requests and 84 separate chat responses**: six distinct packages / seven backend cases. See reports and manual review. Edit-reference scores are proxies, not semantic accuracy. Smaller-context/clean-AUTO follow-ups are recorded separately, including failures.

## Open release gates

- Finish the updated real Binder context/GPU-polish regression, final source provenance and internal release upload.
- Final repository CI, release commit and tag.
- Internal Play-delivered update; actual installed TypeRight approval/readiness, insertion/undo, boundaries, cancellation, reconnect/restart, consecutive requests, switching and isolation. No TypeRight installation/data replacement.
- Play-signed upgrade preservation, privacy/Data Safety review and confirmed Console status before production rollout.

Browser access recovered. Internal testing visibly showed latest **4 (1.3)** and the **Testers** list selected (25 users). Version 2.0/code 5 is being prepared. No 2.0 upload/publication is claimed here.
