# Hardware acceleration (1.2)

## Actual capabilities

One process-wide llama.cpp model serves chat, polish, provider and Binder clients. The AIDL files, transaction ordering, permissions and service addresses are unchanged. Concurrent inference receives the existing busy/retry response. CPU/GPU selection stays inside BI; client apps do not allocate their own model. A model cannot be switched, deleted or unloaded during generation. Cancelling a Flow cancels that specific request, rather than a later client's request.

CPU uses ggml's portable ARMv8 NEON kernels on arm64-v8a. No SVE2/dot-product acceleration is claimed for this build. x86_64 uses its portable baseline rather than requiring AVX from every device. Automatic CPU threads prefer the highest-frequency cluster when sysfs exposes it, otherwise half the available logical cores, capped at four. Explicit thread choices are bounded by available cores and eight threads. Battery saver or severe thermal status reduces threads; threads can be reduced between native decode batches. PSS is sampled at request boundaries and once while the inference context is still allocated, instead of on every token.

The app packages **real ggml Vulkan kernels**, not a delegate label. `libggml-vulkan.so` is an optional module loaded through ggml's backend registry. Only this module depends on Android's `libvulkan.so`. CPU libraries load on API 24+ without a Vulkan dependency. The optional module links the API 29 Vulkan loader interface; a missing loader/symbol, Vulkan version below 1.2, software device, missing compute queue or missing 16-bit storage results in CPU availability only. Logical-device creation must also succeed. Android Vulkan feature declarations or chip manufacturer names are not proof of compatibility.

On a compatible device, the chosen layer count reaches JNI `prepare`, `llama_model_params.n_gpu_layers` and an explicit Vulkan device list. Actual layer placement is read from the pinned loader's `offloaded N/M layers` report. A real decode/warm-up must succeed before GPU is reported ready. Its graph callback additionally checks that at least one completed matrix operation has an output buffer belonging to the Vulkan device; weights merely placed in GPU memory are insufficient. This callback is only used during warm-up, not normal generation. Operators unsupported by Vulkan can still execute on CPU: GPU means real layer offload, not that every operation runs on GPU. GPU load/context/decode errors release the context/model through RAII, then reload CPU. An answer is retried automatically only before any output tokens were emitted; partial streamed output is preserved and the next request can use CPU.

**NPU is disabled.** No LiteRT-LM runtime or vendor delegate is packaged, and the model library contains GGUF files. An NPU chip alone cannot execute them. Future support needs a supported runtime, vendor drivers, an explicitly compatible compiled model/tokenizer and operation coverage, then actual runtime load/decode tests. Google documents [LiteRT-LM](https://developers.google.com/edge/litert-lm/overview) and [NPU delegates](https://developers.google.com/edge/litert/android/npu/overview); neither makes arbitrary GGUF files NPU-compatible. No conversion or large download happens implicitly.

## Automatic selection

Settings → Advanced settings → AI Processing Mode defaults to Automatic. CPU Only is always available on packaged ABIs. GPU is enabled after the native runtime probe; model compatibility is validated when a request loads its GPU configuration. NPU shows the actual missing requirements. A GPU layer limit can be set to automatic, 8, 16 or 32; resource limits can lower it further.

For a new compatible device/model/configuration, AUTO compares CPU and the memory-bounded GPU configuration. Both receive a one-token warm-up followed by the same deterministic eight-token trial. Trial generation has an eight-second **soft deadline**, checked between native batches; driver calls/model loading cannot be forcibly interrupted safely. User cancellation remains available. No benchmark output reaches chat or external clients. Under memory pressure or severe thermal conditions, tuning is deferred and CPU is used.

Candidates must complete, produce measurable decode speed, avoid low-memory status and remain below severe thermal status. The score is TTFT + estimated time for 16 decode tokens + load time amortised over 20 requests + small PSS/thermal/battery-temperature penalties. GPU must improve the CPU score by at least 10%; there is no backend ranking by name. This is an interactive-workload heuristic, not a guarantee of the fastest setting for every prompt or sustained workload. Manual benchmarking also compares CPU thread choices.

Decisions and samples persist for 30 days, bounded to 32 entries. Keys contain Android firmware fingerprint, ABI, GPU driver/API identity, model checksum plus file identity and quantisation, pinned runtime/policy version, context, CPU threads and layer count. Changing these invalidates the decision. Layer budgets are rounded in groups of four when partially offloaded to reduce cache churn. Failed GPU configurations have a 24-hour cooldown. A synchronous persistent attempt marker also prevents a process crash during GPU probing/loading/decoding from causing a restart loop. CPU remains usable. Native assertions, fatal driver failures and Android process termination cannot be recovered inside the same process; a later launch suppresses the unfinished GPU attempt.

## Shared memory and model compatibility

GPU heaps on Android are normally shared system RAM; their reported capacity is never added to available system RAM. Free heap figures come from the driver and may be a heap-size estimate if precise budget reporting is unavailable. The GPU weight budget uses the lesser of 55% of reported heap availability and 65% of system RAM above a 512 MB reserve, with another 128 MB for buffers. Partial offload is supported. Estimated current GPU weight allocations receive release credit when reevaluating the next configuration. CPU mmap weights receive no extra credit: Android's available-memory signal already accounts for reclaimable file cache.

Memory estimates use the quantized GGUF byte size, model-specific F16 K/V dimensions, context length, runtime/graph headroom, and extra GPU staging/buffer overhead. Catalog KV dimensions were read from the actual pinned GGUF headers; `scripts/update_catalog_memory.py` regenerates them from existing local weights without downloads. Imported model headers are recorded too. There is no fixed 6 GB runtime requirement or second 38%-of-total RAM deduction. In particular, Qwen 3 4B Q4 can be enabled on a 12 GB device when the current headroom is sufficient. This does not imply all 12 GB phones always have enough free RAM.

Estimates reserve 512 MB and at least 12% of total memory for the rest of the system. Inference reduces context toward 512 tokens when needed and reports an error if the prompt/output no longer fits; it never silently truncates input. Severe thermal status disables GPU at the next request boundary and reduces CPU threads. Emergency thermal status or less than 256 MB available stops generation. It never swaps weights underneath an active decode. Estimates and Android memory signals cannot guarantee that the OS or a driver will never run out of memory.

Hardware monitoring under Models → Advanced tools reports actual active backend, model, decode tokens/s, TTFT, CPU threads, offloaded layers, app PSS, thermal status, battery temperature and fallback events. Decode/prefill timing comes from llama.cpp counters. User-request TTFT includes context creation, initial tuning and any fallback; warmed candidate-trial TTFT excludes prior tuning. Initial model loading and backend reloading have separate measured fields, with zero reload time possible when reusing weights. Battery temperature is not a CPU/GPU temperature. PSS is app-process memory and may exclude some driver allocations. No portable GPU/NPU energy measurement is claimed.

## Build

Catalog files are fully SHA-256 verified on first discovery. To avoid re-reading
every installed model on each process start, successful checks are cached for
30 days using the expected digest, private path, size, modification time and
filesystem inode/change time. A changed file is checked again before activation.
The explicit **Verify** action always reads the full file and invalidates this
startup cache. The cache is a startup I/O optimization, not continuous file
integrity monitoring.

Use a fresh native staging directory when upgrading llama.cpp: CMake 3.22's
ExternalProject stamps can otherwise retain an older host shader generator.

Dependencies remain pinned: llama.cpp `v0.7.0`, Vulkan-Headers `v1.4.357` and SPIRV-Headers `vulkan-sdk-1.4.357.0`, all SHA-256 checked by CMake. Vulkan headers supply Vulkan-Hpp, which the NDK lacks. SPIR-V shaders are generated by the NDK's glslc and embedded in the Vulkan module. All packaged native libraries use 16 KB ELF alignment.

Use SDK 36.1, NDK 28.0.12433566, CMake 3.22.1 and JDK 21. The shader generator also requires a **host C++17 compiler** plus Ninja on PATH. On Windows, LLVM-MinGW works; use a short native staging path to avoid host compiler path limits. The toolchain is a build-only dependency, not shipped in the APK. For example:

```powershell
$env:PATH = "C:\tools\llvm-mingw\bin;$env:ANDROID_HOME\cmake\3.22.1\bin;$env:PATH"
$env:BI_NATIVE_BUILD_DIR = 'C:\bi-native'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
```

The signing script accepts `-NativeToolchainBin` and `-NativeBuildDirectory`. Keep signing credentials outside the repository. See [releasing](RELEASING.md).

## Validation and physical-device procedure

JVM tests exercise selection, TTL/configuration invalidation, failure cooldown/crash markers, thread and shared-memory budgets, NPU exclusion, incompatible files, GPU failure recovery, request exclusion, model switching and cancellation. Injected runtimes are test doubles, never evidence of hardware performance.

`HardwareAccelerationDeviceTest` runs real native CPU inference on the 135M and 0.5B models, records TTFT, decode speed, PSS, initialization and thermal fields, checks switching/reload/unload, and tests real Vulkan only when native probing finds a hardware GPU. It writes `files/acceleration-benchmark.json`. SwiftShader is explicitly rejected. Stage the unchanged catalog files in private `files/models/`, then run:

```sh
adb shell am instrument -w -r -e class com.example.HardwareAccelerationDeviceTest com.aistudio.betterintelligence.mobile.test/androidx.test.runner.AndroidJUnitRunner
adb shell run-as com.aistudio.betterintelligence.mobile cat files/acceleration-benchmark.json
```

For hardware acceptance, run this on physical ARM64 phones with Vulkan 1.2+, confirm nonzero layer offload and Vulkan decode in logcat, then compare CPU/GPU/AUTO with the same model, context and output length. Repeat for several minutes while recording system thermal status and available RAM. Test at least one device without Vulkan, low-memory conditions, driver initialization rejection and recovery after cancellation. Re-run the separate-UID `sample-client` provider/Binder tests in each processing mode. Model quality is not assessed by throughput measurements. Physical GPU speed, sustained thermal behaviour and energy savings must not be inferred from emulator results.

When a phone already has the Play app installed, build with `-PbiValidationPackage=true`
and install the debug and test APKs. This opt-in debug package is
`com.aistudio.betterintelligence.mobile.validation`, labelled **BI Validation**.
It uses the same engine and UI sources with separate private data, and its external
IPC/text-action components are disabled. Stage the two small test models in that
package's `files/models/` directory and run its `.validation.test` instrumentation
runner. Release builds always retain the production package and IPC addresses.
