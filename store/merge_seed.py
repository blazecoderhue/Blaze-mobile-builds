#!/usr/bin/env python3
"""Carry the seed catalog's verified identity facts (signer pins, known release hashes, asset rule, description, icon,
tags) onto the matching apps of the full catalog, by package name. Run after build_cat.py; writes both copies
(store/catalog.json and the bundled app asset). Seed pins were computed by Blaze with apksigner from official APKs."""
import json, os
here = os.path.dirname(os.path.abspath(__file__))
seed = {a['package']: a for a in json.load(open(os.path.join(here, 'seed.json')))['apps']}
path = os.path.join(here, 'catalog.json')
cat = json.load(open(path))
n = 0
for a in cat['apps']:
    s = seed.get(a['package'])
    if not s: continue
    for k in ('signer_sha256', 'releases', 'asset', 'description', 'icon', 'tags', 'min_sdk'):
        if k in s: a[k] = s[k]
    n += 1
out = json.dumps(cat, ensure_ascii=False, separators=(',', ':'))
for p in (path, os.path.join(here, '..', 'app', 'src', 'main', 'assets', 'store', 'catalog.json')):
    open(p, 'w').write(out)
print('merged seed facts into', n, 'apps of', len(cat['apps']), 'bytes', len(out))
