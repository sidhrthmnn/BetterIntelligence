# Short-polish latency experiment

The TypeRight 222 prompt made the model evaluate 176 input tokens for each of two short diagnostic inputs. The model spent about 41 seconds evaluating the input on this CPU emulator before producing its first output. Retaining a loaded model alone does not avoid this work: the GGUF executor evaluates the supplied system/user prompt on every independent request.

`PolishLatencyBenchmarkTest` is opt-in (`benchmarkPolish=true`) and uses a separately staged Qwen 3 1.7B Q4_K_M file in the validation app. Its SHA-256 was verified as `72c5c3cb38fa32d5256e2fe30d03e7a64c6c79e668ad84057e3bd66e250b24fb`. It uses the actual existing llama.cpp CPU JNI implementation, two threads, 2048 context, zero temperature and a 256-token output cap. A one-token warm-up precedes measurement. Each of two held-out inputs is evaluated with three prompts; variant order reverses for the second input. These are six single trials on Android 16/API 36 x86_64 with 5 GB configured RAM, not a physical-phone performance estimate.

| Prompt | Input tokens | Mean time to first output | Mean completion wall time |
| --- | ---: | ---: | ---: |
| TypeRight 222 | 176 | 41.050 s | 43.854 s |
| Compact instructions | 83 | 19.333 s | 22.467 s |
| Compact plus one editing example (selected) | 97 | 21.916 s | 24.635 s |

The initial example-based candidate reduced mean wait by **43.8%** and mean first-output time by **46.6%** in these samples. However, a real TypeRight UI/Binder question test later exposed a JSON wrapper in that candidate. It was rejected and refined; those initial numbers are not the final product's results. Raw timing, input and output remain in [the initial measurement record](polish-latency-comparison.json).

## Final refined prompt — measured 27 October 2026

The final prompt explicitly edits the JSON **text field**, never answers questions, and returns only the edited value. It retains one editing example. A new six-trial run used the same CPU configuration, three inputs, baseline and candidate for each, with no simultaneous local compilation. Variant order reversed after the first input. These are single diagnostic trials, not a general accuracy or phone-performance claim.

| Prompt | Input tokens | Mean first-output time | Mean completion time |
| --- | ---: | ---: | ---: |
| TypeRight 222 | 174–176 | 41.592 s | 44.169 s |
| Final TypeRight 223 | 104–106 | 24.462 s | 27.013 s |

This final candidate reduced mean completion time by **38.8%** and mean first-output time by **41.2%**. Full outputs and timings are in [the final measurement record](polish-latency-refined-comparison.json).

- `he dont know where the keys is.` became `He doesn't know where the keys are.` The baseline left agreement errors.
- `what is teh capital of france` became `What is the capital of France`. It did not answer the question or return JSON. The baseline left the original unchanged.
- `we was waiting outside since ten minute.` became `We were waiting outside since ten minutes.` Agreement improved, but the duration wording is still incorrect. Small-model correction quality remains a limitation.

TypeRight 223 adopts this refined prompt, retaining JSON input, bounded read-only neighbors, private-field/editor-session protections and authoritative completed BI output. No model is silently replaced, no shared text cache is added and no partial output is inserted into the editor. This prompt experiment changes no native runtime. The separate [Qualcomm NPU integration](QUALCOMM_NPU.md) remains opt-in pending physical validation. No GPU/NPU speed was measured here.

To repeat the final native comparison, stage the verified model in the validation app and run `PolishLatencyBenchmarkTest#compareProofreadingPrompts` with `benchmarkPolish=true`, `polishVariants=baseline,compact_field_example` and `includeQuestion=true`. Export `files/polish-latency-comparison.json`. The real TypeRight UI/Binder test separately checks that its question output is plain edited text.
