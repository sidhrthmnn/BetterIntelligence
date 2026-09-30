"""Download selected, pinned GGUF files and verify bytes before installing them."""
import argparse
import hashlib
import json
from pathlib import Path
import urllib.request

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument("names", nargs="*", help="Filenames; omit to download all four")
args = parser.parse_args()
library = json.loads((root / "models/library.json").read_text(encoding="utf-8"))
if set(args.names) - {item["filename"] for item in library}:
    parser.error("Unknown library filename")
for item in library:
    if args.names and item["filename"] not in args.names:
        continue
    target = root / "models" / item["filename"]
    def valid(path):
        if not path.is_file() or path.stat().st_size != item["bytes"]:
            return False
        with path.open("rb") as stream:
            return hashlib.file_digest(stream, "sha256").hexdigest() == item["sha256"]
    if not valid(target):
        partial = target.with_suffix(".gguf.part")
        try:
            print("Downloading", item["filename"], flush=True)
            urllib.request.urlretrieve(item["url"], partial)
            if not valid(partial):
                raise RuntimeError("Model size or SHA-256 does not match pinned source")
            partial.replace(target)
        finally:
            partial.unlink(missing_ok=True)
    print("Verified", item["filename"], flush=True)
