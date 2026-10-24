# Beta5 closed-beta submission

Submitted on **24 October 2026** at the app owner's request.

- Version: **2.1.0-beta5**, version code **10**.
- Track: **Closed testing — Alpha**, using its existing configured tester audience.
- Release: **CB5 - Direct web research and session context**.
- Rollout: 100% within that closed track, replacing version 9.
- Bundle: `BetterIntelligence-2.1.0-beta5-v10.aab`, 90,307,768 bytes.
- SHA-256: `D2999FBB25264F52AA2EC0D6D9C452AB42A0907F50685861158350DA7AC050F1`.
- Production code: `ca768d0`; [device/build validation](BETA5_VALIDATION.md) recorded in `4fc56c4`.

Google Play accepted **Send changes for review** and displayed **Changes in review**, with the CB5 release under Closed testing — Alpha. Automated quick checks were still running. Managed publishing remains off, so successful checks and approval lead to automatic publication. Publication was not awaited or claimed.

No supported devices were removed in the release preview. Native debug symbols are attached. The only warning was the absence of a deobfuscation map; this build does not enable R8 obfuscation.

Release notes:

> Improved public-page web research without search or weather APIs. Better article extraction, publication dates and source citations, with checks against unsupported numbers. Each chat keeps separate local context across restarts and model changes. Adds Qwen 3 1.7B and SmolLM3 3B GGUF downloads. Tested on HONOR. Enable web research again after updating to accept its new search destination.

Local submission screenshot: `D:\Codex\bi-beta5-closed-beta-submitted.png`.
