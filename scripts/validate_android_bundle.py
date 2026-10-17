"""Check the release's optional Vulkan packaging and 16 KB ELF alignment.

Run bundletool validate, jarsigner -verify and bundletool dump manifest separately.
This script validates the ZIP/ELF contents against that dumped XML manifest.
"""
import argparse
import hashlib
import json
import struct
import xml.etree.ElementTree as ET
from pathlib import Path
from zipfile import ZipFile

parser = argparse.ArgumentParser()
parser.add_argument("bundle", type=Path)
parser.add_argument("manifest", type=Path)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
manifest = ET.parse(args.manifest).getroot()
android = "{http://schemas.android.com/apk/res/android}"
assert manifest.attrib["package"] == "com.aistudio.betterintelligence.mobile"
assert manifest.find("application").attrib.get(android + "debuggable", "false") == "false"
application = manifest.find("application")
provider = next(p for p in application.findall("provider") if p.attrib[android + "name"].endswith("AiBrainContentProvider"))
service = next(s for s in application.findall("service") if s.attrib[android + "name"].endswith("LocalAiCoreService"))
permission = "com.aistudio.betterintelligence.mobile.permission.BIND_LOCAL_AI_CORE"
assert provider.attrib[android + "authorities"] == "com.aistudio.betterintelligence.mobile.provider"
assert provider.attrib[android + "permission"] == service.attrib[android + "permission"] == permission
assert provider.attrib[android + "exported"] == service.attrib[android + "exported"] == "true"
assert any(a.attrib[android + "name"] == "com.aistudio.betterintelligence.mobile.ACTION_BIND_AI_CORE"
           for f in service.findall("intent-filter") for a in f.findall("action"))
expected = {"libbetter_gguf.so", "libggml.so", "libggml-base.so", "libggml-cpu.so", "libggml-vulkan.so", "libllama.so", "libc++_shared.so",
            "libandroidx.graphics.path.so", "libdatastore_shared_counter.so"}
report = {"package": manifest.attrib["package"], "versionCode": manifest.attrib[android + "versionCode"],
          "versionName": manifest.attrib[android + "versionName"], "nativeLibraries": []}

with ZipFile(args.bundle) as archive:
    assert {name.split('/')[2] for name in archive.namelist() if name.startswith('base/lib/') and name.endswith('.so')} == {'arm64-v8a', 'x86_64'}
    assert archive.read("base/assets/model-library.json") == (root / "models/library.json").read_bytes()
    assert archive.read("base/assets/licenses/NOTICE") == (root / "models/NOTICE").read_bytes()
    assert archive.read("base/assets/licenses/LICENSE-Vulkan-MIT.txt") == (root / "models/LICENSE-Vulkan-MIT.txt").read_bytes()
    assert archive.read("base/assets/licenses/LICENSE-SPIRV-Headers.txt") == (root / "models/LICENSE-SPIRV-Headers.txt").read_bytes()
    assert not any(name.endswith(".gguf") for name in archive.namelist()), "Weights must download separately"
    for abi in ("arm64-v8a", "x86_64"):
        names = {Path(name).name for name in archive.namelist() if name.startswith(f"base/lib/{abi}/") and name.endswith(".so")}
        assert names == expected, (abi, names)
        for name in sorted(names):
            data = archive.read(f"base/lib/{abi}/{name}")
            assert data[:6] == b"\x7fELF\x02\x01", name
            phoff = struct.unpack_from("<Q", data, 32)[0]
            phsize, phnum = struct.unpack_from("<HH", data, 54)
            headers = [struct.unpack_from("<IIQQQQQQ", data, phoff + i * phsize) for i in range(phnum)]
            loads = [h for h in headers if h[0] == 1]
            assert loads and all(h[7] >= 16384 for h in loads), (name, "ELF alignment")
            dynamic = next(h for h in headers if h[0] == 2)
            entries = [struct.unpack_from("<qQ", data, offset) for offset in range(dynamic[2], dynamic[2] + dynamic[5], 16)]
            address = next(value for tag, value in entries if tag == 5)
            mapping = next(h for h in loads if h[3] <= address < h[3] + h[5])
            strings = address - mapping[3] + mapping[2]
            needed = [data[strings + value:].split(b"\0", 1)[0].decode() for tag, value in entries if tag == 1]
            assert ("libvulkan.so" in needed) == (name == "libggml-vulkan.so"), (name, needed)
            report["nativeLibraries"].append({"abi": abi, "library": name, "needed": needed, "minLoadAlignment": min(h[7] for h in loads)})

report["bundleSha256"] = hashlib.file_digest(args.bundle.open("rb"), "sha256").hexdigest()
print(json.dumps(report, indent=2))
