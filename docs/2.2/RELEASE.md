# 2.2.0-beta1 closed-beta submission

Recorded 25 October 2026.

- Package: `com.aistudio.betterintelligence.mobile`
- Version: **2.2.0-beta1**, code **11**
- Track: **Closed testing — Alpha**, track ID `4700162612789187101`
- Release: **2.2 CB1 - Intelligent web research**, release ID `6`
- Audience: existing **Testers** email list retained (26 members when checked), existing countries retained, 100% rollout within this closed track.
- Source commit: `9c36cd7eb53ad454e433261114ff9f92df29298c`
- Review PR: [#3](https://github.com/sidhrthmnn/BetterIntelligence/pull/3), open for review; no merge to main.

## Verified Play state

The signed AAB was uploaded, previewed, saved and **Send changes for review** was confirmed. Publishing overview showed **Changes in review**, **Closed testing — Alpha** and this full release name. Automated quick checks were still running; Play says changes go to review after those succeed. **Managed publishing is off**, so approval leads to automatic publication. Publication itself was not awaited or claimed.

Play preview confirmed version **11 (2.2.0-beta1)**, minimum API 24, target SDK 36, two ABIs and no supported-device removals. The only warning was the missing deobfuscation mapping; this project has release minification disabled. Native debug symbols were attached.

## Artifact identities and checks

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| Signed production AAB | 90,327,713 | `2ce5e8770602cbdcc1223fd5143b8f416856803ca2c9e5952ad3b43e48804036` |
| Final isolated validation APK | 125,190,448 | `5a1701ea4a1a141abd813811f6a60d7e289abd75b1beabf55c28b4b3df200119` |

The committed production bundle build succeeded, bundletool validation and manifest inspection succeeded, and jarsigner reported `jar verified` with the expected self-signed upload-key chain warning. The AAB was built from the source commit above; later documentation-only commits do not change those bytes.

Local artifacts: `D:\Codex\BetterIntelligence-2.2.0-beta1-v11.aab`, `D:\Codex\BetterIntelligence-2.2-device-validation.apk`. Submission screenshot: `D:\Codex\bi-22-closed-beta-submitted.png`. Build/signature logs use the `bi-22-committed-release-*` and `bi-22-final-*` names under that directory.

See [validation and measured limits](VALIDATION.md), [architecture](RESEARCH_ENGINE.md), and [CPU/GPU/NPU compatibility](ACCELERATION_CHECKS.md). All 146 local JVM tests passed. The separate GitHub `android` workflow at the source commit was still in progress when last inspected; it is not reported as passed.

After collecting the physical-device reports, the temporary validation application and test runner were removed. The installed package list then contained only the user's normal Better Intelligence package. The release record contains no private phone text, raw publisher HTML or model weights.
