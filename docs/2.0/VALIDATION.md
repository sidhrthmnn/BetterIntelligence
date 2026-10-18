# 2.0 validation record

This development branch is not a published 2.0 release. Main and the Play-installed 1.3 / code 4 application remain unchanged until the release gates pass.

## Foundation checks, 18 October 2026

- JDK 21, Android SDK 36.1, NDK 28.0.12433566, pinned llama.cpp 0.7.0 and LiteRT-LM 0.18.0.
- `:app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintRelease` succeeded for the isolated x86_64 validation build. **67 JVM tests, zero failures/errors**, including the real schema 7 migration, conversation isolation, unchanged Binder contract, fallback/cache/resource policies, resumable downloads, bounded HTTP response reading and cancellation that closes a real local test socket.
- Android 16 emulator, **320 dp width, font scale 1.5**: `ChatFirstNavigationTest` and `ChatKeyboardTest` passed (2 tests, 24.524 s). All three themes/settings destinations remained navigable, the draft survived Activity recreation, and the composer/IME gap or overlap was at most 3 dp. The screenshots and installed APK hash are under `screenshots/emulator-320dp-font150/`.
- Earlier physical HONOR foundation run: navigation/themes, SwiftKey IME gap test, and real English/Malayalam Wikipedia retrieval passed. That run predates the final HTTP ownership refinement; it is not a substitute for the pending final product regression.
- Physical candidate CPU/GPU/AUTO experiments are in progress. GPU memory failures are retained as failures, not counted as accelerated inference. A clean-cache controlled AUTO rerun is required where an intentionally interrupted experiment left a retry cooldown.

## Still required before release

- Complete model/context/performance experiments, 113-case proofreading outputs, separate chat-quality review and justified final model categories/defaults.
- Run `ChatProductDeviceTest` with staged E2B weights to verify actual generated grounded replies, history, editing, regeneration, cancellation and isolation. The test is opt-in; compilation alone does not establish a pass.
- Final CPU/GPU/native switching, no-network inference, process-death and actual installed TypeRight end-to-end checks. Companion source/installation/data have not been modified.
- Final signed full-ABI AAB, 16 KB/native/package validation, repository CI and release tag.
- Play-delivered internal update and production-package TypeRight regression; verify privacy/Data Safety and actual Console submission status.

Both browser-control runtimes currently return `failed to write kernel assets: The system cannot find the path specified (os error 3)`. No 2.0 Play Console upload, declaration, promotion or publication has been performed.
