#!/usr/bin/env python3
"""Seal a completed local Skill bundle. The application rejects files that differ from this manifest."""
from pathlib import Path
import hashlib
import json
import os
import sys

root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parents[1] / 'skills'
if root.is_symlink():
    raise SystemExit('Symlink root is not allowed')
files = {}
for path in sorted(root.rglob('*')):
    if path.is_symlink():
        raise SystemExit('Symlinks are not allowed')
    if path.is_file() and path.name not in {'bundle.json', '.bundle.tmp'}:
        files[path.relative_to(root).as_posix()] = hashlib.sha256(path.read_bytes()).hexdigest()
if not files:
    raise SystemExit('Empty bundle')
manifest = root / '.bundle.tmp'
manifest.write_text(json.dumps({'release': 'camping-v1', 'files': files}, ensure_ascii=False, indent=2) + '\n')
os.replace(manifest, root / 'bundle.json')
print(f'Sealed {len(files)} files; rebuild the backend image to use this release')
