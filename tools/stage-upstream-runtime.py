#!/usr/bin/env python3
"""Stage the ABI-v5 core and licensed QNN runtime from the pinned official APK.
Does not copy/replace the custom DiT engine or its DSP skels.
"""
import hashlib
from pathlib import Path
import sys
import zipfile

EXPECTED = '704582052cb28e67eb6bfe1b943e48b18ac0f15e0804579d84447515d9a1b281'
ROOT = Path(__file__).resolve().parents[1]
if len(sys.argv) != 2:
    raise SystemExit('Usage: python3 tools/stage-upstream-runtime.py /path/to/official-alpha7.apk')
apk = Path(sys.argv[1])
if hashlib.sha256(apk.read_bytes()).hexdigest() != EXPECTED:
    raise SystemExit('APK SHA256 mismatch: expected the pinned official basic v3.0.0-alpha.7 release')
with zipfile.ZipFile(apk) as archive:
    names = archive.namelist()
    selected = ['lib/arm64-v8a/libstable_diffusion_core.so']
    selected += [n for n in names if n.startswith(('assets/qnnlibs/', 'assets/licenses/qnn-2.51.0.260929/')) and not n.endswith('/')]
    if len(selected) < 5:
        raise SystemExit('Runtime package is incomplete')
    for name in selected:
        target = ROOT / 'app/src/main' / (name.replace('lib/arm64-v8a/', 'jniLibs/arm64-v8a/', 1) if name.startswith('lib/') else name)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(archive.read(name))
        print(target.relative_to(ROOT))
