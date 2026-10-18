# Physical HONOR candidate performance

These are recorded validation-app measurements, not estimates or quality rankings. Read the raw JSON alongside each row for model revision, checksum, APK hash, device fingerprint and failure details.

| Model | Requested | Context requested / configured | Status / actual | Visible replies | Init ms | Warm decode tok/s | Warm TTFT ms | Warm request ms | Peak app PSS MiB | Thermal max | File mappings after close |
|---|---|---|---|---|---:|---:|---:|---:|---:|---:|---|
| gemma4-e2b | AUTO | 512 / 1024 | complete / GPU | 6 / 6 | 1672 | 40.0 | 148.0 | 499.0 | 1469.7 | 0 | false |
| gemma4-e2b | CPU | 512 / 1024 | complete / CPU | 6 / 6 | 2912 | 29.3 | 535.0 | 934.0 | 1478.8 | 0 | false |
| gemma4-e2b | GPU | 512 / 1024 | complete / GPU | 6 / 6 | 4023 | 41.2 | 114.0 | 410.0 | 948.5 | 0 | false |
| gemma4-e4b | AUTO | 512 / 1024 | complete / CPU | 6 / 6 | 1210 | 14.8 | 1854.0 | 2858.0 | 3024.2 | 1 | false |
| gemma4-e4b | CPU | 512 / 1024 | complete / CPU | 6 / 6 | 1304 | 15.5 | 1615.0 | 2372.0 | 2980.1 | 0 | false |
| gemma4-e4b | GPU | 512 / 1024 | complete / GPU | 6 / 6 | 8856 | 19.0 | 288.0 | 905.0 | 935.3 | 0 | false |

## Failures and excluded runs

None recorded in this directory.

## Interpretation

- Warm decode uses the repeated counting prompt; TTFT and request duration use the three repeated prompts. Actual runtime token counts are used. Init is a fresh process/session load, with OS file/compiler caches potentially already warm.
- A completed runtime call can still return no visible reply. Thinking models can exhaust a short output budget in their thought channel. Explicit larger-budget probes have a maxN filename suffix and must be reviewed separately; blank replies are not evidence of a usable answer.
- AUTO first requests include controlled tuning; later requests reuse the decision. A prior interrupted experiment may leave a crash cooldown, so record whether the controlled run reset the cache. Normal app launches retain it.
- PSS is sampled every 250 ms and can miss short peaks. It excludes some GPU/driver allocations. Driver GPU/DMA-BUF readings in raw reports are system-wide and cannot be attributed to BI. CPU and GPU share RAM.
- Thermal is Android thermal status; battery temperature is a separate sensor. The phone was charging and running ordinary background services. Charge-counter changes do not establish app energy usage.
- A released model-file mapping does not prove all driver allocations are released. Cancelled/interrupted runs and previous-policy reports belong in a separate superseded directory.
- Quantization, architecture and packaged graph differ across models and engines. No table row establishes that one engine is intrinsically superior or that output quality is acceptable.
