# Version 36 (2.7.0) validation

App source: `6a035d1`. Tested with a separately packaged validation installation; the phone's normal Play Store app and its private data were not changed.

## Automated checks

- Kotlin: 397 cases, no failures or errors, one intentional skip. Includes new reinstall-caller and preference migration checks; existing schema, native lifecycle, download and client policy regressions remain.
- Rust: 31 host tests passed, plus formatting and Clippy.
- Android debug and signed release builds passed. Debug/release lint: zero errors, 142 warnings (existing localization and other advisory items).
- Signed AAB verified with jarsigner and bundletool. Custom bundle checks passed for both packaged ABIs, 16 KB load alignment, optional Vulkan dependencies, manifest version/components, license notices and absence of embedded model weights.

## Reinstall regression

On the API 36 x86_64 emulator, an unchanged existing sample client passed before BI removal, then failed with SecurityException after only the baseline BI app was uninstalled/reinstalled. Android retained the client's manifest declaration but lost its granted custom normal permission. Version 36 passed both upgrade-with-lost-grant and fresh BI reinstall checks, with the client's install/update time unchanged.

The implementation resolves caller identity from Binder UID and PackageManager declarations, then retains BI approval, scopes, request ownership, blocking and rate limits. It does not accept caller identity supplied in request extras. Reapproval is expected after a clean BI uninstall because Android deletes BI's private approvals.

## Physical phone

HONOR VKP-NX9, Android 17:

- Model hub navigation, absence of chat controls, single Wi-Fi preference and persistence: three UI tests passed in 8.861 s.
- Real SmolLM2 135M Q4_K_M CPU model: provider preparation/polishing and Binder polishing passed (client test 4.214 s).
- Removed and installed only the disposable BI validation package, leaving the same client installed. Fresh approval/denial checks passed. Reapproval followed by real provider/Binder inference passed again (4.229 s). Client update time was unchanged.
- All seven screens in light/dark themes were checked again for clean screenshots: one instrumentation test passed in 11.508 s. Store images are unmodified physical-phone captures.
- No production-app uninstall, model deletion or private chat inspection was needed. No physical-phone network settings were changed. A later screenshot-format attempt temporarily changed the test viewport to 1080x1920; its active-window guard rejected the captures and the physical resolution was restored in a finally block. Original full-resolution, visually checked phone captures were used instead.

The test model was staged from an existing local, SHA-verified copy; it was not downloaded over cellular data. The test client's model/setup permission applied only to this isolated installation.

## Limits

No new GPU/NPU benchmark or Neuron app changes are claimed. A client must reconnect a dead Binder using its normal lifecycle; BI cannot resurrect another process's cached remote object. Android's existing unmetered scheduler policy can exclude metered Wi-Fi hotspots when Wi-Fi-only is on. Legacy private conversation tables are retained non-destructively for upgrade compatibility, but no chat or research UI/orchestration remains active.

The emulator also produced unrelated background System UI/launcher ANR dialogs during screenshot capture; those images were rejected rather than used as store assets. Physical-phone tests provide the release's actual native inference evidence.

AAB SHA-256: `e108ff4e7bda9a0ec372649a699c308f91a1e024a77e6ec8e62e34a592d4eeec` (94,546,279 bytes).

## Play submission

Version 36 was submitted to **production only**, at 100% rollout across the existing targeted countries. Play Console showed **Changes in review**, with quick checks running and managed publishing off. The updated short/full descriptions and four real phone screenshots are included. The empty closed Alpha draft created during preparation was discarded at the user's request; no closed-beta or other-track update was submitted. Publication was not waited for. See `release-v36.json`.
