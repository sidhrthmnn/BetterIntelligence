# Short-polish latency experiment

The TypeRight 222 prompt made the model evaluate 176 input tokens for each of two short diagnostic inputs. The model spent about 41 seconds evaluating the input on this CPU emulator before producing its first output. Retaining a loaded model alone does not avoid this work: the GGUF executor evaluates the supplied system/user prompt on every independent request.

`PolishLatencyBenchmarkTest` is opt-in (`benchmarkPolish=true`) and uses a separately staged Qwen 3 1.7B Q4_K_M file in the validation app. Its SHA-256 was verified as `72c5c3cb38fa32d5256e2fe30d03e7a64c6c79e668ad84057e3bd66e250b24fb`. It uses the actual existing llama.cpp CPU JNI implementation, two threads, 2048 context, zero temperature and a 256-token output cap. A one-token warm-up precedes measurement. Each of two held-out inputs is evaluated with three prompts; variant order reverses for the second input. These are six single trials on Android 16/API 36 x86_64 with 5 GB configured RAM, not a physical-phone performance estimate.

| Prompt | Input tokens | Mean time to first output | Mean completion wall time |
| --- | ---: | ---: | ---: |
| TypeRight 222 | 176 | 41.050 s | 43.854 s |
| Compact instructions | 83 | 19.333 s | 22.467 s |
| Compact plus one editing example (selected) | 97 | 21.916 s | 24.635 s |

The selected prompt reduced mean wait by **43.8%** and mean first-output time by **46.6%** in these samples. The shortest prompt was rejected because its corrections were weaker. The selected prompt fully corrected `he dont know where the keys is.` to `He doesn't know where the keys are.` The second sample was improved but still grammatically imperfect (`We were waiting outside since ten minutes.`). This is a small diagnostic set, not a general accuracy result. Raw timing, input and output are in [the measurement record](polish-latency-comparison.json).

TypeRight 223 adopts the selected prompt, retaining JSON input, bounded read-only neighbors when present, private-field/editor-session protections and authoritative completed BI output. BI's application/runtime code does not change in this experiment; no new BI bundle is required. No model is silently replaced, no shared text cache is added and no partial output is inserted into the editor. Existing GGUF and LiteRT processing-mode selection remains in BI. GPU/NPU speed was not measured; the emulator GPU is software and excluded, and this build has no usable NPU runtime.
