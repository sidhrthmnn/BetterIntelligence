# Better Intelligence 1.2 validation

## Measured handset result

Device: HONOR VKP-NX9, Android 16, Snapdragon 8 Elite / Adreno 830, Vulkan
driver 2150760522.  The production Play package was not modified; all native
tests used the isolated `com.aistudio.betterintelligence.mobile.validation`
package.

| Model and mode | Result |
| --- | --- |
| SmolLM2 135M Q4_K_M, CPU, two threads | 91.2 tokens/s, 171 ms first token |
| SmolLM2 135M Q4_K_M, CPU, four threads | 143.8 tokens/s, 87 ms first token |
| Q4_K_M Vulkan attempt | Vulkan compute-pipeline creation returned `ErrorUnknown`; CPU fallback completed safely. |
| SmolLM2 135M Q8_0 Vulkan attempt | Eight layers were genuinely placed on Vulkan, but deterministic output differed from CPU.  The new correctness gate rejected it and reran on CPU. |

The measurements are short, controlled 16-token runs and are not sustained
throughput claims.  The device remained nominal at 30 C during the initial
measurements.  GPU is therefore **not selected** for either tested model on
this firmware.  A different device/model combination must pass layer placement,
deterministic decode, resource checks and the performance comparison before GPU
can be selected and cached.

## Automated checks

- `:app:testDebugUnitTest`: passed.
- Isolated phone `HardwareAccelerationDeviceTest`: passed for Q8_0 and Q4_K_M,
  including CPU fallback, model cleanup and repeated shared-model loading.
- Isolated phone `ChatKeyboardTest`: passed for single- and multiline keyboard
  insets.
- Release bundle build: passed for arm64-v8a and x86_64.
- `bundletool validate`: passed.  `jarsigner -verify -strict` verified the
  upload signature; its self-signed upload-key warning is expected locally.

## Known limitation

No compatible LiteRT model/runtime is packaged in 1.2, so NPU remains disabled.
The planned LiteRT-LM versus llama.cpp comparison requires a separate compatible
model artifact and will use this phone after the 1.2 release is available to
internal testers.
