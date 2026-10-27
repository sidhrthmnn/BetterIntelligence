# BI 18 / 2.3.0-beta3 — phone validation

Physical checks on 27 October 2026 used an HONOR VKP-NX9, Android 16/API 36, Snapdragon SM8750 and Adreno 830. All app/model fixtures were isolated validation packages; the Play apps, their models and keyboard configuration were preserved.

## CPU and TypeRight latency

The scheduler reports six cores at capacity 792 and two at 1024. The old 85%-of-maximum-frequency heuristic identified only the two prime cores. BI now prefers scheduler capacity when available, includes performance cores at >=75% of maximum, and retains the four-thread automatic cap plus power-save/thermal reductions. Missing capacity data uses the existing conservative frequency fallback. NEON/FMA/repacked ARM CPU inference remains enabled; no new instruction-set capability is claimed.

Real native Qwen 3 1.7B Q4_K_M checks measured 8.52 tokens/s with two threads and 16.10 with four for one short generation each. [CPU/Vulkan records](honor-cpu-vulkan.json) include initialization, first token, thermal state and cleanup checks. These are diagnostic samples, not sustained battery/thermal results.

Six prompt trials per thread setting used the same checksum-verified weights (`72c5c3cb38fa32d5256e2fe30d03e7a64c6c79e668ad84057e3bd66e250b24fb`), 2048 context, greedy decoding, 256 maximum output tokens, one-token warm-up and three synthetic editing inputs. The callback's thread policy matches the requested thread count.

| Fixed prompt | CPU threads | Mean first output | Mean completion |
| --- | ---: | ---: | ---: |
| TypeRight 222 | 2 | 12.847 s | 13.690 s |
| TypeRight 223 | 2 | 7.667 s | 8.479 s |
| TypeRight 222 | 4 | 6.496 s | 6.935 s |
| TypeRight 223 | 4 | 3.905 s | 4.340 s |

The shorter prompt reduced mean completion time by 38.1% at two threads. Four threads reduced the final prompt's time by another 48.8%. Combined, the controlled sample improved from 13.690 to 4.340 seconds (68.3%); this is not a claim about every cold load or typing task. See [two-thread raw results](polish-honor-two-threads.json) and [four-thread raw results](polish-honor-four-threads.json).

The agreement example became `He doesn't know where the keys are.` and the question became plain `What is the capital of France`, without answering it or returning JSON. The duration example still returned `We were waiting outside since ten minutes.` This remaining model-quality limit is preserved in the evidence.

TypeRight 223's real UI -> Binder -> BI -> Qwen check passed on the phone. Its initial cold/UI sample took 20.385 s, including preparation; that is separate from native steady-request measurements. Native-editor replacement and exact undo passed with synthetic completed callbacks. Temporary staging and test-harness attempts are not counted as inference failures.

## GPU and NPU limits

The phone's Vulkan driver rejected `createComputePipeline` with `ErrorUnknown` for the staged GGUF models. Production inference recovered to CPU. A fresh-process experiment disabling FP16, integer dot product and cooperative matrices still failed; these experimental flags are not applied to the app. No GGUF GPU success is claimed for this phone/driver.

The Qualcomm NPU model download requires Hugging Face sign-in/model access and returned HTTP 401. The user explicitly requested publication with CPU/GPU if that access blocks NPU testing. QNN remains disabled in the Play build; no NPU performance result is claimed. The [separate NPU checklist](QUALCOMM_NPU.md) remains applicable when licensed model access is available.

LiteRT CPU/OpenCL and final version-18 Binder/cancellation results are recorded with the release PR after completion. Closed-beta and TypeRight internal release status is verified separately in Play Console.
