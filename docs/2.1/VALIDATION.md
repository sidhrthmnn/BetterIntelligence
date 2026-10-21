# 2.1.0-beta1 (version code 6)

Built on 21 October 2026 for the first closed testing release. Includes PR #2's opt-in LFM 2.5 and SmolLM3 packages, the pinned Qwen 3 1.7B LiteRT package, and optional public-page research replacing Wikipedia/Brave. Experimental models do not change validated recommendations or activate/download automatically.

## Build evidence

- `:app:bundleRelease :app:lintRelease :app:testDebugUnitTest`: successful, 13m 56s; 80 JVM tests, zero failures/errors. Release lint completed successfully.
- Signed AAB: 90,244,650 bytes; SHA-256 `0d693527d6f7a7a7601c106d2e4806e66fc9f14f4a9cc4e8ac560aa1afab6047`.
- Upload certificate SHA-256: `35:8B:05:89:A6:5E:FB:73:C6:85:DC:16:81:DE:D7:2C:C3:8A:76:6F:51:15:FE:08:DD:3C:E4:F0:76:2F:F1:8A`.
- Gradle's ABI-injected debug APK is under `app/build/intermediates/apk/debug/`; the older `outputs/apk/debug/` file is not this build. Verify `output-metadata.json` and installed version before running fixtures.
- Chat/navigation/theme fixture passed on the version 6 validation app after correcting the emulator display configuration. The new Qwen UI fixture's title matcher was corrected to match the abbreviated card title; that revised fixture has not yet been rerun.

## Foreground service review video

[Google Drive recording](https://drive.google.com/file/d/1p_uATWbwavUy7KrdrWCRATryMYjYAJbR/view?usp=drivesdk), shared read-only with anyone holding the link.

60.56 seconds, 1080x1920 H.264, captured from the actual version 6 app on an x86_64 Android emulator. A user presses Send with web access off, backgrounds the app, views the ongoing inference notification, presses Stop Generation, and returns to the saved Response stopped state. Android diagnostics confirmed `isForeground=true`, notification 1001 and dataSync type while active; cancellation removed the foreground notification. The recording uses a real installed SmolLM2 135M GGUF on CPU, not simulated output. MP4 fast-start metadata was added without changing the recorded frames.

The declaration maps to **Local processing > Other**, not backup/restore. Device/app data and existing model files were preserved. No new physical-phone GPU/NPU benchmarks were performed. Qwen 1.7B, LFM 2.5 and SmolLM3 performance and proofreading quality remain unqualified on BI's target phone. Public search feeds and website readability can change; failures remain labelled model-only answers. The HTTPS in-app offensive-response reporting form requested from the developer is still outstanding.
