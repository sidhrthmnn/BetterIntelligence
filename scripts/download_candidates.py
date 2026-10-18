"""Download the pinned 2.0 research manifest, resumably, outside the repository.

Usage: python scripts/download_candidates.py --directory D:/Codex/bi-models/candidates
No weights, credentials or machine paths are written to source control.
"""
import argparse
import hashlib
import json
import pathlib
import shutil
import subprocess


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(8 * 1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--directory', type=pathlib.Path, required=True)
    args = parser.parse_args()
    root = pathlib.Path(__file__).resolve().parents[1]
    manifest = json.loads((root / 'docs/2.0/model-candidates.json').read_text(encoding='utf-8'))
    target = args.directory.resolve()
    if target == root or root in target.parents:
        raise SystemExit('Choose storage outside the Git checkout')
    target.mkdir(parents=True, exist_ok=True)
    for model in manifest['models']:
        if not model.get('file'):
            continue
        path = target / model['file']
        if path.exists() and path.stat().st_size == model['bytes'] and digest(path) == model['sha256']:
            print('VERIFIED', model['id'], flush=True)
            continue
        part = path.with_suffix(path.suffix + '.part')
        remaining = max(0, model['bytes'] - (part.stat().st_size if part.exists() else 0))
        if shutil.disk_usage(target).free < remaining + 2 * 1024**3:
            raise SystemExit('Not enough storage with 2 GiB reserve')
        url = f"https://huggingface.co/{model['repo']}/resolve/{model['revision']}/{model['file']}"
        print('DOWNLOADING', model['id'], model['bytes'], flush=True)
        download = subprocess.run(['curl.exe' if __import__('os').name == 'nt' else 'curl', '--fail', '--location',
                        '--retry', '4', '--retry-delay', '5', '--continue-at', '-', '--output', str(part), url])
        if download.returncode:
            print('FAILED_DOWNLOAD', model['id'], 'curl exit', download.returncode, flush=True)
            continue
        if part.stat().st_size != model['bytes'] or digest(part) != model['sha256']:
            raise SystemExit(f"Checksum mismatch: {model['id']}; partial retained for inspection")
        part.replace(path)
        print('VERIFIED', model['id'], flush=True)


if __name__ == '__main__':
    main()
