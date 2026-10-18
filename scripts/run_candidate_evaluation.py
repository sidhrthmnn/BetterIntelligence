"""Run opt-in synthetic experiments on the connected physical HONOR validation app.

Only the isolated BI validation process is stopped. TypeRight and Play BI are untouched.
Weights are checksum-verified before staging. Reports carry the installed APK hash.
"""
import argparse
import hashlib
import json
import pathlib
import re
import subprocess
import time

PACKAGE = 'com.aistudio.betterintelligence.mobile.validation'
RUNNER = PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner'


def sha256(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(8 * 1024**2), b''):
            h.update(chunk)
    return h.hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--adb', required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--directory', type=pathlib.Path, required=True)
    parser.add_argument('--reports', type=pathlib.Path, required=True)
    parser.add_argument('--models', nargs='*')
    parser.add_argument('--phase', choices=['performance', 'quality', 'chat'], default='performance')
    parser.add_argument('--retry-failures', action='store_true')
    parser.add_argument('--reset-auto-cache', action='store_true', help='Cold AUTO experiment only; warm requests still exercise cached decisions')
    parser.add_argument('--contexts', nargs='+', type=int, default=[512, 1024, 2048])
    args = parser.parse_args()
    root = pathlib.Path(__file__).resolve().parents[1]
    reports = args.reports.resolve(); reports.mkdir(parents=True, exist_ok=True)

    def adb(*arguments, timeout=600, check=True):
        result = subprocess.run([args.adb, '-s', args.serial, *map(str, arguments)], capture_output=True, timeout=timeout)
        if check and result.returncode:
            raise RuntimeError(result.stderr.decode(errors='replace'))
        return result.stdout.decode('utf-8', errors='replace').strip()

    device = adb('shell', 'getprop', 'ro.product.model')
    if device not in ['VKP-NX9', 'VKP_NX9']:
        raise SystemExit(f'Expected the authorised HONOR phone; found {device}')
    installed = adb('shell', 'pm', 'path', PACKAGE)
    if not installed.startswith('package:'):
        raise SystemExit('Install the isolated validation APK first')
    apk_hash = adb('shell', 'sha256sum', installed.split(':', 1)[1]).split()[0]
    adb('shell', 'run-as', PACKAGE, 'mkdir', '-p', 'files/models')
    models = json.loads((root / 'docs/2.0/model-candidates.json').read_text(encoding='utf-8'))['models']
    for model in models:
        if args.models and model['id'] not in args.models:
            continue
        if not model.get('file'):
            continue
        name = model['file']
        if not re.fullmatch(r'[A-Za-z0-9_.-]+', name):
            raise SystemExit('Unsafe filename in manifest')
        host = args.directory / name
        if not host.is_file():
            print('UNAVAILABLE', model['id'], flush=True); continue
        if host.stat().st_size != model['bytes'] or sha256(host) != model['sha256']:
            raise SystemExit(f'Checksum mismatch: {name}')
        print('STAGING', model['id'], flush=True)
        target = 'files/models/' + name
        existing = adb('shell', 'run-as', PACKAGE, 'sha256sum', target, check=False)
        if not existing.startswith(model['sha256']):
            adb('push', host, '/data/local/tmp/' + name)
            adb('shell', 'run-as', PACKAGE, 'cp', '/data/local/tmp/' + name, target)
            if not adb('shell', 'run-as', PACKAGE, 'sha256sum', target).startswith(model['sha256']):
                raise SystemExit('Device checksum mismatch')
            adb('shell', 'rm', '/data/local/tmp/' + name)  # Only the exact file just created by this script.
        backends = ['CPU', 'GPU', 'AUTO']
        contexts = args.contexts if args.phase == 'performance' else [2048]
        if args.phase in ['quality', 'chat']:
            # Select by actual end-to-end warm duration, requiring all probe responses complete.
            measured = []
            for backend in ['CPU', 'GPU']:
                file = reports / f"{model['id']}-performance-{backend}-2048.json"
                if file.is_file():
                    result = json.loads(file.read_text(encoding='utf-8'))
                    samples = result.get('samples', [])
                    if result.get('status') == 'complete' and samples and all(s.get('status') == 'complete' and s.get('response', '').strip() for s in samples):
                        warm = [s['durationMs'] for s in samples if s.get('round') == 1]
                        measured.append((sum(warm) / max(1, len(warm)), backend))
            if not measured:
                print('NO_STABLE_BACKEND', model['id'], flush=True); continue
            backends = [min(measured)[1]]
        for size in contexts:
            for backend in backends:
                path = reports / f"{model['id']}-{args.phase}-{backend}-{size}.json"
                if path.is_file() and json.loads(path.read_text(encoding='utf-8')).get('status') == 'complete':
                    print('ALREADY_COMPLETE', path.name, flush=True); continue
                if path.is_file() and not args.retry_failures:
                    print('PREVIOUS_FAILURE_OR_LIMIT', path.name, flush=True); continue
                # Do not repeatedly hit known dangerous upstream GPU paths in large hybrids.
                if backend in ['GPU', 'AUTO'] and model['id'] == 'qwen35-4b':
                    path.write_text(json.dumps(dict(model=name, status='not_run', requestedBackend=backend,
                        reason='Publisher reports Qualcomm device hard reboot during GPU initialization; CPU evaluated first. No stable HONOR GPU evidence.',
                        source='https://huggingface.co/litert-community/Qwen3.5-4B'), indent=2), encoding='utf-8')
                    continue
                adb('shell', 'am', 'force-stop', PACKAGE)
                print('RUNNING', model['id'], args.phase, backend, size, flush=True)
                result = adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', 'com.example.CandidateEvaluationTest',
                    '-e', 'candidateModel', name, '-e', 'candidateBackend', backend, '-e', 'candidateContext', size,
                    '-e', 'candidateSha256', model['sha256'],
                    '-e', 'candidateResetCache', str(args.reset_auto_cache and backend == 'AUTO').lower(),
                    '-e', 'candidateChatQuality', str(args.phase == 'chat').lower(),
                    '-e', 'candidateQuality', str(args.phase == 'quality').lower(), RUNNER, timeout=10800 if args.phase == 'quality' else 2400, check=False)
                (reports / (path.stem + '.instrumentation.txt')).write_text(result, encoding='utf-8')
                remote = f'files/candidate-{args.phase}-{backend}-{size}-{name}.json'
                raw = adb('shell', 'run-as', PACKAGE, 'cat', remote, check=False)
                try:
                    data = json.loads(raw)
                except ValueError:
                    data = dict(status='process_failed', model=name, requestedBackend=backend, instrumentation=result[-3000:])
                data['validationApkSha256'] = apk_hash; data['modelSha256'] = model['sha256']
                data['modelRevision'] = model['revision']
                path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
                print('RESULT', model['id'], backend, size, data.get('status'), data.get('failure', ''), flush=True)
                if data.get('after', {}).get('thermalStatus', -1) >= 3:
                    print('THERMAL_PAUSE_REQUIRED', flush=True)
                    return
                time.sleep(2)
        adb('shell', 'am', 'force-stop', PACKAGE)


if __name__ == '__main__':
    main()
