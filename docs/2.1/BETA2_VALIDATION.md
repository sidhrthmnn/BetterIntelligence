# 2.1.0-beta2 (version code 7)

Model download certificate compatibility update, 21 October 2026. See [the scoped trust policy, certificate provenance and device test instructions](DOWNLOAD_TLS.md).

## Build and regression checks

- `:app:bundleRelease :app:lintRelease :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest -PbiValidationPackage=true`: **BUILD SUCCESSFUL**, 8m 47s.
- **84 JVM tests**, zero failures/errors/skips. Includes four new download security cases and two existing verified-resume cases.
- Release lint passed. Existing unrelated warnings remain.
- Signed release AAB: **90,246,625 bytes**, SHA-256 `8f0bcdbc2790035f700bb68644ec0717bb7c952f542af9e2834c742568eb0aff`.
- JAR signature verified. Upload certificate SHA-256: `35:8B:05:89:A6:5E:FB:73:C6:85:DC:16:81:DE:D7:2C:C3:8A:76:6F:51:15:FE:08:DD:3C:E4:F0:76:2F:F1:8A`. The self-signed upload certificate warning is expected; this signing certificate is separate from HTTPS server trust.
- Play Console accepted the bundle as **7 (2.1.0-beta2)**, API 24+, target 36, two ABIs, native debug symbols attached, in the existing **Closed testing - Alpha** track.

## Live Android validation

**3 instrumented tests passed in 60.595 seconds** on a clean Android 16/API 36 x86_64 emulator, installed version 7 in the isolated validation package.

- Verified live TLS chains for `huggingface.co`, `cas-bridge.xethub.hf.co` and `us.aws.cdn.hf.co` with only the bundled Amazon root, without system roots.
- Confirmed self-signed, expired and wrong-host BadSSL certificates still raise SSL exceptions with the app's normal trust policy.
- All **17 catalogue models** returned a valid HTTPS 4,096-byte partial download, including both Gemma 4 LiteRT models and Qwen 3 1.7B. The live file redirects resolved to `us.aws.cdn.hf.co`. This is a transfer-start test, not full multi-gigabyte model verification or inference qualification.
- Fully downloaded, parsed, SHA-256 verified and atomically installed **SmolLM2 135M, 105,454,144 bytes** using the actual model downloader. Its synthetic test file was removed afterwards.
- An initial test fixture failed JUnit initialization because a coroutine test inferred a non-void return type. The fixture was corrected, rebuilt successfully in 1m 28s, and all three tests above passed on rerun. Production code and the uploaded release bundle did not change during that correction.
- The existing emulator was too full to install the validation update. A separate clean validation AVD was created; existing model files and user app data were preserved.

No physical phone was connected for this update. The screenshot establishes a certificate-path failure but cannot by itself identify whether the affected phone has a missing root, a wrong clock, or HTTPS interception. The scoped public root addresses the provider-chain compatibility case; invalid/intercepted certificates still fail securely with actionable guidance. No GPU/NPU or model-performance claims change in this release.
