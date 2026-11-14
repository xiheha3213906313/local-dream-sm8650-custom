#!/usr/bin/env python3
"""Package SDK-exported compatible graphs and mapped base tensors. Standard library only."""
import argparse
import hashlib
import json
import pathlib
import shutil
import struct
import tempfile
import zipfile


def safe(root, name):
    path = (root / name).resolve()
    if not path.is_relative_to(root.resolve()):
        raise ValueError(f'Path escapes input directory: {name}')
    return path


def sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def export(args):
    mapping = json.loads(args.mapping.read_text())
    targets = mapping['targets']
    if not targets or not mapping['base_files']:
        raise ValueError('targets and base_files must be nonempty')
    with args.base_weights.open('rb') as source:
        header_size = struct.unpack('<Q', source.read(8))[0]
        if header_size > 64 * 1024 * 1024:
            raise ValueError('Safetensors header too large')
        header = json.loads(source.read(header_size))
        tensors = {}
        offset = 0
        for target in targets:
            name = target['base_tensor']
            tensor = header[name]
            if tensor['dtype'] not in ('F32', 'F16', 'BF16'):
                raise ValueError(f'Unsupported base tensor type: {name}')
            if tensor['shape'] != target['shape'] or not target['aliases']:
                raise ValueError(f'Shape or aliases invalid: {name}')
            if ('qnn' in target) == ('storage' in target):
                raise ValueError('Each target must have either qnn or storage')
            target.pop('base_file', None)
            if name not in tensors:
                length = tensor['data_offsets'][1] - tensor['data_offsets'][0]
                tensors[name] = dict(dtype=tensor['dtype'], shape=tensor['shape'], data_offsets=[offset, offset + length])
                offset += length
        output_header = json.dumps(tensors, separators=(',', ':')).encode()
        output_header += b' ' * (-len(output_header) % 8)
        with tempfile.TemporaryDirectory(prefix='local-dream-lora-') as temporary:
            root = pathlib.Path(temporary)
            with (root / 'base.safetensors').open('wb') as dest:
                dest.write(struct.pack('<Q', len(output_header)))
                dest.write(output_header)
                for name in tensors:
                    start, end = header[name]['data_offsets']
                    source.seek(8 + header_size + start)
                    remaining = end - start
                    while remaining:
                        chunk = source.read(min(1024 * 1024, remaining))
                        if not chunk:
                            raise ValueError(f'Truncated tensor: {name}')
                        dest.write(chunk)
                        remaining -= len(chunk)
            runtime = root / 'runtime'
            runtime.mkdir()
            if args.runtime_dir:
                for path in args.runtime_dir.iterdir():
                    if path.is_symlink() or not path.is_file():
                        raise ValueError('Runtime files must be regular files at the root')
                    shutil.copyfile(path, runtime / path.name)
            manifest = dict(format_version=1, base_files={name: sha256(safe(args.model_dir, name)) for name in mapping['base_files']}, targets=targets, runtime_dir='runtime')
            if 'resolution' in mapping:
                manifest['resolution'] = mapping['resolution']
            (root / 'lora_manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2))
            args.output.parent.mkdir(parents=True, exist_ok=True)
            pending = args.output.with_suffix(args.output.suffix + '.pending')
            try:
                with zipfile.ZipFile(pending, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=1, allowZip64=True) as archive:
                    for path in sorted(root.rglob('*')):
                        if path.is_file():
                            archive.write(path, path.relative_to(root))
                pending.replace(args.output)
            finally:
                pending.unlink(missing_ok=True)
    print(args.output)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--model-dir', type=pathlib.Path, required=True)
    parser.add_argument('--base-weights', type=pathlib.Path, required=True)
    parser.add_argument('--mapping', type=pathlib.Path, required=True)
    parser.add_argument('--runtime-dir', type=pathlib.Path)
    parser.add_argument('--output', type=pathlib.Path, required=True)
    export(parser.parse_args())
