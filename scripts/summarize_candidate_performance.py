"""Summarize only recorded measurements; missing/failed runs remain explicit."""
import argparse
import json
import pathlib
import statistics


def median(samples, field):
    values = [sample[field] for sample in samples if field in sample]
    return f'{statistics.median(values):.1f}' if values else '—'


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--reports', type=pathlib.Path, required=True)
    parser.add_argument('--output', type=pathlib.Path, required=True)
    args = parser.parse_args()
    lines = ['# Physical HONOR candidate performance', '',
        'These are recorded validation-app measurements, not estimates or quality rankings. '
        'Read the raw JSON alongside each row for model revision, checksum, APK hash, device fingerprint and failure details.', '',
        '| Model | Requested | Context requested / configured | Status / actual | Init ms | Warm decode tok/s | Warm TTFT ms | Warm request ms | Peak app PSS MiB | Thermal max | File mappings after close |',
        '|---|---|---|---|---:|---:|---:|---:|---:|---:|---|']
    failures = []
    for path in sorted(args.reports.glob('*-performance-*.json')):
        data = json.loads(path.read_text(encoding='utf-8'))
        samples = [sample for sample in data.get('samples', []) if sample.get('status') == 'complete']
        warm = [sample for sample in samples if sample.get('round') == 1]
        decode = [sample for sample in warm if sample.get('id') == 'decode']
        actual = ', '.join(sorted({sample.get('actualBackend', '?') for sample in samples})) or '—'
        states = [data.get(key, {}) for key in ['before', 'afterInitialization', 'after']]
        states += [sample.get(key, {}) for sample in samples for key in ['before', 'after']]
        thermal = max([state['thermalStatus'] for state in states if state.get('thermalStatus', -1) >= 0], default=None)
        peak = data.get('peakProcessPssKb')
        configured = data.get('configuredContext', '—')
        context = f"{data.get('requestedContext', '—')} / {configured}"
        row = [path.name.split('-performance-')[0], data.get('requestedBackend', '?'), context,
            f"{data.get('status', '?')} / {actual}", str(data.get('initializationMs', '—')),
            median(decode, 'tokensPerSecond'), median(warm, 'firstTokenMs'), median(warm, 'durationMs'),
            f'{peak / 1024:.1f}' if peak is not None else '—', str(thermal) if thermal is not None else '—',
            str(data.get('modelMappingsRetainedAfterClose', 'unmeasured')).lower()]
        lines.append('| ' + ' | '.join(row) + ' |')
        if data.get('status') != 'complete':
            failures.append(f"- {path.name}: {data.get('failure', data.get('reason', data.get('status', 'unknown')))}")
    lines += ['', '## Failures and excluded runs', '', *(failures or ['None recorded in this directory.']), '',
        '## Interpretation', '',
        '- Warm decode uses the repeated counting prompt; TTFT and request duration use the three repeated prompts. '
        'Actual runtime token counts are used. Init is a fresh process/session load, with OS file/compiler caches potentially already warm.',
        '- AUTO first requests include controlled tuning; later requests reuse the decision. '
        'A prior interrupted experiment may leave a crash cooldown, so record whether the controlled run reset the cache. '
        'Normal app launches retain it.',
        '- PSS is sampled every 250 ms and can miss short peaks. It excludes some GPU/driver allocations. '
        'Driver GPU/DMA-BUF readings in raw reports are system-wide and cannot be attributed to BI. CPU and GPU share RAM.',
        '- Thermal is Android thermal status; battery temperature is a separate sensor. '
        'The phone was charging and running ordinary background services. Charge-counter changes do not establish app energy usage.',
        '- A released model-file mapping does not prove all driver allocations are released. '
        'Cancelled/interrupted runs and previous-policy reports belong in a separate superseded directory.',
        '- Quantization, architecture and packaged graph differ across models and engines. '
        'No table row establishes that one engine is intrinsically superior or that output quality is acceptable.', '']
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text('\n'.join(lines), encoding='utf-8')
    print(f'Wrote {args.output}')


if __name__ == '__main__':
    main()
