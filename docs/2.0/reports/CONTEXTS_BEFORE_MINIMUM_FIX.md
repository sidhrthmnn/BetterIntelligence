# Physical HONOR candidate performance

These are recorded validation-app measurements, not estimates or quality rankings. Read the raw JSON alongside each row for model revision, checksum, APK hash, device fingerprint and failure details.

| Model | Requested | Context requested / configured | Status / actual | Visible replies | Init ms | Warm decode tok/s | Warm TTFT ms | Warm request ms | Peak app PSS MiB | Thermal max | File mappings after close |
|---|---|---|---|---|---:|---:|---:|---:|---:|---:|---|
| gemma4-e2b | AUTO | 1024 / 1024 | complete / GPU | 6 / 6 | 2806 | 40.4 | 194.0 | 503.0 | 1362.5 | 0 | false |
| gemma4-e2b | AUTO | 2048 / 2048 | complete / GPU | 6 / 6 | 2818 | 40.8 | 192.0 | 525.0 | 1383.5 | 0 | false |
| gemma4-e2b | AUTO | 512 / 512 | failed / — | 0 / 0 | 2814 | — | — | — | 835.0 | 0 | false |
| gemma4-e2b | CPU | 1024 / 1024 | complete / CPU | 6 / 6 | 2776 | 26.7 | 557.0 | 1004.0 | 1363.9 | 0 | false |
| gemma4-e2b | CPU | 2048 / 2048 | complete / CPU | 6 / 6 | 2774 | 24.7 | 623.0 | 1114.0 | 1390.1 | 0 | false |
| gemma4-e2b | CPU | 512 / 512 | failed / — | 0 / 0 | 8238 | — | — | — | 832.5 | 0 | false |
| gemma4-e2b | GPU | 1024 / 1024 | complete / GPU | 6 / 6 | 12724 | 40.9 | 113.0 | 415.0 | 825.6 | 0 | false |
| gemma4-e2b | GPU | 2048 / 2048 | complete / GPU | 6 / 6 | 5686 | 40.8 | 122.0 | 403.0 | 830.2 | 0 | false |
| gemma4-e2b | GPU | 512 / 512 | complete / GPU | 6 / 6 | 12666 | 40.7 | 115.0 | 402.0 | 832.9 | 0 | false |
| gemma4-e4b | AUTO | 1024 / 1024 | complete / GPU | 6 / 6 | 737 | 19.1 | 369.0 | 1037.0 | 2856.1 | 0 | false |
| gemma4-e4b | AUTO | 2048 / 2048 | complete / GPU | 6 / 6 | 689 | 18.3 | 327.0 | 994.0 | 2906.5 | 0 | false |
| gemma4-e4b | AUTO | 512 / 512 | failed / — | 0 / 0 | 819 | — | — | — | 534.8 | 0 | false |
| gemma4-e4b | CPU | 1024 / 1024 | complete / CPU | 6 / 6 | 641 | 15.4 | 1152.0 | 1932.0 | 2870.9 | 0 | false |
| gemma4-e4b | CPU | 2048 / 2048 | complete / CPU | 6 / 6 | 1398 | 15.1 | 1753.0 | 2539.0 | 2904.3 | 0 | false |
| gemma4-e4b | CPU | 512 / 512 | failed / — | 0 / 0 | 12831 | — | — | — | 739.7 | 0 | false |
| gemma4-e4b | GPU | 1024 / 1024 | complete / GPU | 6 / 6 | 17008 | 19.2 | 276.0 | 893.0 | 842.8 | 0 | false |
| gemma4-e4b | GPU | 2048 / 2048 | complete / GPU | 6 / 6 | 15322 | 18.8 | 283.0 | 915.0 | 838.7 | 0 | false |
| gemma4-e4b | GPU | 512 / 512 | complete / GPU | 6 / 6 | 12403 | 19.0 | 283.0 | 913.0 | 797.9 | 0 | false |
| qwen3-06b | AUTO | 1024 / 1024 | complete / CPU | 6 / 6 | 1027 | 36.6 | 378.0 | 823.0 | 968.1 | 0 | false |
| qwen3-06b | AUTO | 2048 / 1280 | complete / CPU | 6 / 6 | 2179 | 36.5 | 382.0 | 822.0 | 969.0 | 0 | false |
| qwen3-06b | AUTO | 512 / 512 | complete / CPU | 6 / 6 | 2134 | 37.3 | 388.0 | 815.0 | 970.6 | 0 | false |
| qwen3-06b | CPU | 1024 / 1024 | complete / CPU | 6 / 6 | 2090 | 44.8 | 291.0 | 851.0 | 865.6 | 0 | false |
| qwen3-06b | CPU | 2048 / 1280 | complete / CPU | 6 / 6 | 2130 | 36.7 | 312.0 | 739.0 | 863.8 | 0 | false |
| qwen3-06b | CPU | 512 / 512 | complete / CPU | 6 / 6 | 2551 | 36.8 | 324.0 | 733.0 | 952.0 | 0 | false |
| qwen3-06b | GPU | 1024 / 1024 | complete / GPU | 6 / 6 | 3059 | 42.1 | 191.0 | 702.0 | 705.9 | 0 | false |
| qwen3-06b | GPU | 2048 / 1280 | complete / GPU | 6 / 6 | 5508 | 47.1 | 189.0 | 672.0 | 704.2 | 0 | false |
| qwen3-06b | GPU | 512 / 512 | complete / GPU | 6 / 6 | 10668 | 40.2 | 230.0 | 800.0 | 737.5 | 0 | false |

## Failures and excluded runs

- gemma4-e2b-performance-AUTO-512.json: com.google.ai.edge.litertlm.LiteRtLmJniException: Status Code: 13. Message: ERROR: [third_party/odml/litert_lm/runtime/executor/llm_litert_compiled_model_executor.cc:849]
└ Failed to invoke the compiled model
└ Failed to allocate tensors
- gemma4-e2b-performance-CPU-512.json: com.google.ai.edge.litertlm.LiteRtLmJniException: Status Code: 13. Message: ERROR: [third_party/odml/litert_lm/runtime/executor/llm_litert_compiled_model_executor.cc:849]
└ Failed to invoke the compiled model
└ Failed to allocate tensors
- gemma4-e4b-performance-AUTO-512.json: com.google.ai.edge.litertlm.LiteRtLmJniException: Status Code: 13. Message: ERROR: [third_party/odml/litert_lm/runtime/executor/llm_litert_compiled_model_executor.cc:849]
└ Failed to invoke the compiled model
└ Failed to allocate tensors
- gemma4-e4b-performance-CPU-512.json: com.google.ai.edge.litertlm.LiteRtLmJniException: Status Code: 13. Message: ERROR: [third_party/odml/litert_lm/runtime/executor/llm_litert_compiled_model_executor.cc:849]
└ Failed to invoke the compiled model
└ Failed to allocate tensors

## Interpretation

- Warm decode uses the repeated counting prompt; TTFT and request duration use the three repeated prompts. Actual runtime token counts are used. Init is a fresh process/session load, with OS file/compiler caches potentially already warm.
- A completed runtime call can still return no visible reply. Thinking models can exhaust a short output budget in their thought channel. Explicit larger-budget probes have a maxN filename suffix and must be reviewed separately; blank replies are not evidence of a usable answer.
- AUTO first requests include controlled tuning; later requests reuse the decision. A prior interrupted experiment may leave a crash cooldown, so record whether the controlled run reset the cache. Normal app launches retain it.
- PSS is sampled every 250 ms and can miss short peaks. It excludes some GPU/driver allocations. Driver GPU/DMA-BUF readings in raw reports are system-wide and cannot be attributed to BI. CPU and GPU share RAM.
- Thermal is Android thermal status; battery temperature is a separate sensor. The phone was charging and running ordinary background services. Charge-counter changes do not establish app energy usage.
- A released model-file mapping does not prove all driver allocations are released. Cancelled/interrupted runs and previous-policy reports belong in a separate superseded directory.
- Quantization, architecture and packaged graph differ across models and engines. No table row establishes that one engine is intrinsically superior or that output quality is acceptable.
