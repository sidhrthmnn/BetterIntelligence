"""Refresh KV geometry from existing local GGUF headers; never downloads weights."""
import json
import math
import struct
import sys
from pathlib import Path


def header(path):
    with path.open("rb") as f:
        def number(fmt):
            return struct.unpack("<" + fmt, f.read(struct.calcsize(fmt)))[0]

        def string():
            return f.read(number("Q")).decode("utf-8")

        def value(kind):
            if kind == 8:
                return string()
            if kind == 9:
                element, count = number("I"), number("Q")
                for _ in range(count):
                    value(element)
                return None
            return number({0: "B", 1: "b", 2: "H", 3: "h", 4: "I", 5: "i", 6: "f", 7: "?", 10: "Q", 11: "q", 12: "d"}[kind])

        assert f.read(4) == b"GGUF"
        assert number("I") in (2, 3)
        number("Q")
        count = number("Q")
        metadata = {}
        for _ in range(count):
            key, kind = string(), number("I")
            metadata[key] = value(kind)
        return metadata


root = Path(__file__).resolve().parents[1]
catalog = json.loads((root / "models/library.json").read_text())
directories = [root / "models", *(Path(p) for p in sys.argv[1:])]
for model in catalog:
    if model["filename"].endswith(".litertlm"):
        # LiteRT containers are not GGUF. Their source model cards specify
        # resident text decoder/embedding bytes; audio/vision stay unloaded.
        weights_mb = math.ceil(model["textWeightsBytes"] / 1048576)
        model["ramMb"] = weights_mb + math.ceil(model["kvBytesPerToken"] * 2048 / 1048576) + 256 + int(weights_mb * 0.08)
        continue
    path = next((p / model["filename"] for p in directories if (p / model["filename"]).is_file()), None)
    if not path:
        raise SystemExit(f"Missing {model['filename']}; provide an existing weights directory")
    meta = header(path)
    arch = meta["general.architecture"]
    layers = meta[f"{arch}.block_count"]
    heads = meta[f"{arch}.attention.head_count"]
    kv_heads = meta.get(f"{arch}.attention.head_count_kv", heads)
    k = meta.get(f"{arch}.attention.key_length", meta[f"{arch}.embedding_length"] // heads)
    v = meta.get(f"{arch}.attention.value_length", k)
    model["layers"] = layers
    model["kvBytesPerToken"] = layers * kv_heads * (k + v) * 2
    weights_mb = math.ceil(model["bytes"] / 1048576)
    model["ramMb"] = weights_mb + math.ceil(model["kvBytesPerToken"] * 2048 / 1048576) + 256 + int(weights_mb * 0.08)
    print(model["filename"], layers, model["kvBytesPerToken"])
text = json.dumps(catalog, indent=2, ensure_ascii=False) + "\n"
(root / "models/library.json").write_text(text, encoding="utf-8")
(root / "app/src/main/assets/model-library.json").write_text(text, encoding="utf-8")
