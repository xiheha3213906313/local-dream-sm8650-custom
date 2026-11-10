#!/usr/bin/env python3
"""Run production GroupNorm statistics on a Hexagon simulator after build.sh.

Usage: python3 tests/run_groupnorm_hvx_test.py [v75|v79]
Uses the matching native build's compile_commands.json for SDK include paths.
"""
import json
import pathlib
import shlex
import subprocess
import sys
import tempfile

here = pathlib.Path(__file__).resolve().parent
arch = sys.argv[1] if len(sys.argv) > 1 else 'v75'
if arch not in ('v75', 'v79'):
    raise SystemExit('Expected v75 or v79')
commands = here.parent / f'build/android/sdcpp/ggml/src/ggml-hexagon/htp-{arch}-prefix/src/htp-{arch}-build/compile_commands.json'
entry = next(c for c in json.loads(commands.read_text()) if c['file'].endswith('groupnorm-ops.c'))
original = shlex.split(entry['command'])
includes = []
for i, arg in enumerate(original):
    if arg.startswith('-I'):
        includes.append(arg)
    elif arg == '-isystem':
        includes.extend([arg, original[i + 1]])
compiler = pathlib.Path(original[0])
with tempfile.TemporaryDirectory(prefix='groupnorm-hvx-') as temp:
    executable = str(pathlib.Path(temp) / 'groupnorm-test')
    subprocess.run([str(compiler), f'-m{arch}', f'-mhvx={arch}', '-mhvx-length=128B',
                    '-O2', '-ffunction-sections', '-fdata-sections', '-Wl,--gc-sections',
                    *includes, str(here / 'groupnorm_hvx_test.c'), '-o', executable], check=True)
    subprocess.run([str(compiler.with_name('hexagon-sim')), f'-m{arch}', executable], check=True)
