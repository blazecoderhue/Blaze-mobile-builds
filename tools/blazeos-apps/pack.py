#!/usr/bin/env python3
"""v0.33.2: packs the sample BlazeOS web apps (each folder: manifest.json + index.html) into
app/src/main/assets/blazeos/apps/<name>.zip with bridge.js added, and rewrites their entries in
app/src/main/assets/blazeos/catalog.json (sizes included). Run from the repo root: python3 tools/blazeos-apps/pack.py"""
import json, os, zipfile
HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, '..', '..', 'app', 'src', 'main', 'assets', 'blazeos')
cat_path = os.path.join(ASSETS, 'catalog.json')
cat = json.load(open(cat_path))
bridge = open(os.path.join(HERE, 'bridge.js'), 'rb').read()
for name in sorted(d for d in os.listdir(HERE) if os.path.isfile(os.path.join(HERE, d, 'manifest.json'))):
    src = os.path.join(HERE, name)
    files = {n: open(os.path.join(src, n), 'rb').read() for n in sorted(os.listdir(src))}
    files['bridge.js'] = bridge
    with zipfile.ZipFile(os.path.join(ASSETS, 'apps', name + '.zip'), 'w', zipfile.ZIP_DEFLATED) as z:
        for n, data in sorted(files.items()):
            z.writestr(zipfile.ZipInfo(n, (2026, 10, 18, 0, 0, 0)), data, zipfile.ZIP_DEFLATED)
    m = json.loads(files['manifest.json'])
    m['package'] = 'asset:blazeos/apps/%s.zip' % name
    m['size'] = sum(len(d) for d in files.values())
    cat['apps'] = [a for a in cat['apps'] if a['id'] != m['id']]
    cat['apps'].insert(0, m)
json.dump(cat, open(cat_path, 'w'), indent=1)
print('packed', [a['id'] for a in cat['apps']])
