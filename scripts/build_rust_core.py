"""Reproducible Android Rust build using the same pinned NDK as the app.

No SDK downloads, PATH/profile edits, cargo-ndk plugin or moving binary artifacts.
Cargo checks registry checksums against Cargo.lock. Set BI_RUST_CARGO if Cargo is
not on PATH; CARGO_HOME/RUSTUP_HOME/CARGO_TARGET_DIR are honoured.
"""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
CRATE = ROOT / "rust-core"
parser = argparse.ArgumentParser()
parser.add_argument("--output", type=Path)
parser.add_argument("--abis", default="arm64-v8a,x86_64")
parser.add_argument("--test", action="store_true")
args = parser.parse_args()
env = os.environ.copy()
cargo = env.get("BI_RUST_CARGO") or shutil.which("cargo")
if not cargo and env.get("CARGO_HOME"):
    cargo = str(Path(env["CARGO_HOME"]) / "bin" / ("cargo.exe" if os.name == "nt" else "cargo"))
if not cargo or not Path(cargo).is_file():
    sys.exit("Install the Rust toolchain specified in rust-core/rust-toolchain.toml; set BI_RUST_CARGO if needed.")

if os.name == "nt" and "windows-gnu" not in env.get("RUSTUP_TOOLCHAIN", "") and not shutil.which("link.exe"):
    vswhere = Path(os.environ.get("ProgramFiles(x86)", "C:/Program Files (x86)")) / "Microsoft Visual Studio/Installer/vswhere.exe"
    if vswhere.is_file():
        install = subprocess.check_output([str(vswhere), "-latest", "-products", "*", "-requires", "Microsoft.VisualStudio.Component.VC.Tools.x86.x64", "-property", "installationPath"], text=True).strip()
        setup = Path(install) / "VC/Auxiliary/Build/vcvars64.bat"
        if setup.is_file():
            # Capture environment privately; never print inherited credentials.
            result = subprocess.run(f'cmd.exe /d /s /c ""{setup}" >nul && set"', env=env, capture_output=True, text=True, check=True)
            for line in result.stdout.splitlines():
                key, sep, value = line.partition("=")
                if sep and key.upper() in {"PATH", "LIB", "LIBPATH", "INCLUDE"}:
                    env[key.upper()] = value + (";" + env[key.upper()] if key.upper() in {"LIB", "INCLUDE"} and env.get(key.upper()) else "")

def run(command, **kwargs):
    return subprocess.run(command, cwd=CRATE, env=env, check=True, **kwargs)

if args.test:
    run([cargo, "test", "--locked", "--all-targets"])
    run([cargo, "fmt", "--all", "--", "--check"])
    run([cargo, "clippy", "--locked", "--all-targets", "--", "-D", "warnings"])
    sys.exit(0)

if args.output is None:
    parser.error("--output is required for Android compilation")
sdk = env.get("ANDROID_HOME") or env.get("ANDROID_SDK_ROOT")
if not sdk:
    prop = ROOT / "local.properties"
    if prop.is_file():
        sdk = next((s.partition("=")[2].strip().replace("\\:", ":").replace("\\\\", "\\") for s in prop.read_text().splitlines() if s.startswith("sdk.dir=")), None)
if not sdk:
    sys.exit("Set ANDROID_HOME to the Android SDK used by Gradle.")
host = {"win32": "windows-x86_64", "darwin": "darwin-x86_64"}.get(sys.platform, "linux-x86_64")
toolchain = Path(sdk) / "ndk/28.0.12433566/toolchains/llvm/prebuilt" / host
clang = toolchain / "bin" / ("clang.exe" if os.name == "nt" else "clang")
if not clang.is_file():
    sys.exit("Install the project's pinned NDK 28.0.12433566 before building Rust.")
targets = {"arm64-v8a": ("aarch64-linux-android", "aarch64-linux-android24"), "x86_64": ("x86_64-linux-android", "x86_64-linux-android24")}
abis = args.abis.split(",")
if not abis or any(a not in targets for a in abis):
    sys.exit("Only the app's arm64-v8a and x86_64 ABIs are supported.")
metadata = json.loads(run([cargo, "metadata", "--locked", "--format-version=1"], capture_output=True, text=True).stdout)
target_dir = Path(metadata["target_directory"])
for abi in abis:
    target, android_target = targets[abi]
    env["CARGO_TARGET_" + target.upper().replace("-", "_") + "_LINKER"] = str(clang)
    env["CARGO_ENCODED_RUSTFLAGS"] = "\x1f".join(["-C", "link-arg=--target=" + android_target,
        "-C", "link-arg=-Wl,-z,max-page-size=16384", "-C", "link-arg=-Wl,-z,relro,-z,now"])
    run([cargo, "build", "--release", "--locked", "--target", target])
    destination = args.output / "jniLibs" / abi
    destination.mkdir(parents=True, exist_ok=True)
    shutil.copy2(target_dir / target / "release/libbi_intelligence.so", destination / "libbi_intelligence.so")

# Copy dependency notices from Cargo's checksum-verified source packages. No
# private files or model data are inspected. Build-time dependencies included.
licenses = args.output / "assets/licenses/rust-core"
licenses.mkdir(parents=True, exist_ok=True)
notice = ["BI Rust intelligence core 0.1.0 — Apache-2.0", "Third-party Cargo dependencies (locked versions):"]
for package in metadata["packages"]:
    if package["name"] == "bi-intelligence":
        continue
    label = package["name"] + "-" + package["version"]
    notice.append(label + ": " + (package.get("license") or "See included license"))
    source = Path(package["manifest_path"]).parent
    for file in source.iterdir():
        if file.is_file() and file.name.upper().startswith(("LICENSE", "COPYING", "NOTICE")) and file.stat().st_size < 1_000_000:
            dest = licenses / label
            dest.mkdir(exist_ok=True)
            shutil.copy2(file, dest / file.name)
(licenses / "NOTICE.txt").write_text("\n".join(notice) + "\n", encoding="utf-8")
