# Qualcomm QNN NPU integration — physical validation pending

BI now has a real LiteRT-LM `Backend.NPU(nativeLibraryDir)` execution path using Qualcomm QNN/QAIRT HTP. It is **opt-in at build time**, off in ordinary builds and Play releases until physical validation succeeds. No NPU speed or correctness result is claimed. CPU/Vulkan GGUF and CPU/OpenCL LiteRT remain the default.

## Packaging

Build with Java 21 and `-PbiQualcommNpu=true --no-configuration-cache`. Gradle resolves official `com.qualcomm.qti:qnn-runtime:2.44.0` from Maven Central and verifies SHA-256 `a6b9b9f07f7cb968a9df70d83b032463975a90317a366b1c559bcb7f4532b4e3`. It extracts only HTP, QNN System, v79 Stub and v79 Skel, preserving LICENSE and NOTICE in app assets. Unused GPU/DSP and on-device compiler libraries are omitted; this is an AOT model path.

The dispatch bridge comes from Google's [official Gemma 3 NPU sample](https://github.com/google-ai-edge/litert-samples/tree/9ec0c9c0377cf59f84d5000bebd049c98f291e72/samples/litert/qualcomm/gemma3/npu), pinned to that revision and SHA-256 `9c039e24d7b80c5a213455cb29f9c2766125dca5f135fa9111c3ef224792e95f`. Its Apache license is included. Binaries stay in generated outputs, not Git or standalone distribution. Android AArch64 runtime/dispatch ELF LOAD alignment was inspected as 16 KB; the separate Hexagon skel is not an Android AArch64 executable.

Libraries are extracted into the app native library directory. `libcdsprpc.so` is an optional OEM driver dependency. The x86_64/older Android CPU/GPU paths remain available.

## Compatibility and selection

Eligibility requires the validation build, ARM64 Android 12+, `Build.SOC_MODEL` SM8750, text-model metadata declaring NPU / QUALCOMM / the same SM8750, all packaged libraries, and a resolvable FastRPC driver. Branding and filenames do not prove compatibility. Only v79/SM8750 is initially packaged; other Snapdragon generations need their own tested runtime profile.

The existing process-wide lifecycle gate protects the single shared session, including external Binder clients. AIDL stays unchanged. Switching happens between requests. A standard GGUF or CPU/GPU LiteRT file does not gain an NPU graph from QNN.

AUTO tests initialization, a deterministic short arithmetic answer and an input exceeding 128 tokens to exercise multiple prefill chunks. It compares genuinely compatible CPU/GPU/NPU paths by latency, speed, memory and thermal state. Cached results include firmware, file identity, checksum when available, QNN/dispatch/LiteRT policy, context and threads. Unfinished native attempts leave a persistent marker and cooldown. NPU availability is reported only after real decode validation.

RAM remains shared; admission includes weights, context/KV, runtime and FastRPC overhead. Allocation estimates need physical measurement. A file with a CPU graph can fall back. An NPU-only file gives a clear error asking for a standard CPU/GPU model; configured repository-level secondary recovery remains available. Partial generation is preserved rather than replaying another backend into it.

## First model to validate

Google's [Gemma 3 1B IT](https://huggingface.co/litert-community/Gemma3-1B-IT/tree/d448835d84411a08f577e25f3afebe528402d73b) includes `Gemma3-1B-IT_q4_ekv1280_sm8750.litertlm`, 689,291,264 bytes, SHA-256 `1904ceff9591e7a140df3a672c800e8e7bee8337526484b00f69ccef4fa2d60a`. Obtain access through the publisher's Gemma license/access flow before staging/importing it. BI does not silently download or convert it, and it is not an ordinary recommended catalog entry yet.

Gemma 4's previously published SM8750 artifact was removed from its current listing; upstream reports also include runtime-version and correctness problems. It is not presented as validated. Existing Gemma 4 CPU/GPU files keep their current paths.

## Physical checklist before Play enablement

1. Connect the unlocked HONOR SM8750 phone. Build isolated app/test packages with `-PbiValidationPackage=true -PbiIpcValidation=true -PbiQualcommNpu=true --no-configuration-cache`. Preserve the Play app and data.
2. Stage the exact licensed model in the validation app's `files/models/`, verify its full checksum, and run:

```text
adb shell am instrument -w -r -e testQualcommNpu true -e npuModel Gemma3-1B-IT_q4_ekv1280_sm8750.litertlm -e npuSha256 1904ceff9591e7a140df3a672c800e8e7bee8337526484b00f69ccef4fa2d60a -e class com.example.QualcommNpuDeviceTest#compiledModelRunsThroughQnnAndUnloadsCleanly com.aistudio.betterintelligence.mobile.validation.test/androidx.test.runner.AndroidJUnitRunner
```

3. Save `files/qualcomm-npu-validation.json` plus native logs proving HTP execution. Check long/short correctness, repeated requests, cancellation, unload/reload, and sustained thermal/memory behavior. Native driver crashes cannot be caught as Kotlin exceptions; quarantine prevents repeated automatic attempts after restart but does not isolate a crashing driver. Keep untested exports out of production.
4. Compare equivalent tasks with CPU/GPU on the phone and exercise TypeRight polish/edit/undo and fallback. Verify the signed bundle before enabling the flag for Play.

Unit tests/emulator checks can verify rejection, SoC mismatch, cache invalidation, cooldown and preserved CPU/Binder behavior. They cannot establish Qualcomm NPU speed or correctness.

References: [Google NPU LLM guide](https://developers.google.com/edge/litert/next/litert_lm_npu), [Kotlin API](https://github.com/google-ai-edge/LiteRT-LM/blob/main/docs/api/kotlin/getting_started.md), [Qualcomm runtime Maven distribution](https://repo.maven.apache.org/maven2/com/qualcomm/qti/qnn-runtime/2.44.0/).
