#!/usr/bin/env python3
"""Build the atlas exclusively from the public MaleCNS v1.0 CC BY 4.0 data.

Run inside tools/requirements.txt environment. Raw downloads are ignored by Git.
Coordinates: original nm -> (x, -z, y) micrometres; no anatomical deformation.
"""
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
import argparse
import collections
import gzip
import hashlib
import json
import sqlite3
import struct
import urllib.parse

import numpy as np
import pyarrow.feather as feather
import pyarrow.compute as pc
import requests
from geometry_assets import convert_mesh

ROOT = Path(__file__).resolve().parents[1]
RAW = ROOT / 'data/source'
OUT = ROOT / 'app/src/main/assets/atlas'
MODEL_OUT = ROOT / 'atlas_models/src/main/assets/atlas'
BASE = 'https://storage.googleapis.com/flyem-male-cns/'
FLAT = BASE + 'v1.0/connectome-data/flat-connectome/'
ROI = BASE + 'rois/fullbrain-roi-v4/'
SKEL = BASE + 'v1.0/segmentation/skeletons-malecns/skeletons-precomputed/'


def sha(path):
    with path.open('rb') as f:
        return hashlib.file_digest(f, 'sha256').hexdigest()


def fetch(url, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.exists():
        for attempt in range(3):
            try:
                with requests.get(url, timeout=(15, 180), stream=True) as r:
                    r.raise_for_status()
                    tmp = path.with_suffix(path.suffix + '.part')
                    with tmp.open('wb') as f:
                        for chunk in r.iter_content(1 << 20):
                            f.write(chunk)
                    tmp.replace(path)
                break
            except requests.RequestException:
                if attempt == 2:
                    raise
    return path.read_bytes()


def meshes():
    props_path = RAW / 'roi-properties.json'
    props = json.loads(fetch(ROI + 'segment_properties/info', props_path))['inline']
    items = list(zip(props['ids'], props['properties'][0]['values']))
    records = []

    def convert(item):
        ident, name = item
        manifest_url = ROI + 'mesh/' + ident + ':0'
        manifest = json.loads(fetch(manifest_url, RAW / 'roi' / (ident + '.json')))
        assert len(manifest['fragments']) == 1
        fragment = manifest['fragments'][0]
        source_url = ROI + 'mesh/' + urllib.parse.quote(fragment)
        source_file = RAW / 'roi' / fragment
        raw = fetch(source_url, source_file)
        target = MODEL_OUT / 'meshes' / f'{ident}.bin'
        detail = convert_mesh(raw,target,OUT / 'meshes/interactive' / f'{ident}.bin')
        record = dict(id=int(ident), name=name, source=source_url, sourceSha256=sha(source_file),
                      sha256=sha(target),format='MCN2',**detail)
        print('ROI', name, detail['triangles'], 'original triangles;',detail['interactiveTriangles'],'interactive',flush=True)
        return record

    # fast-simplification uses mutable native state: run each mesh sequentially.
    # Parallel downloads are safe for skeletons below, but simplification is not.
    records = [convert(item) for item in items]
    (OUT / 'meshes.json').write_text(json.dumps(records, ensure_ascii=False, indent=2))


def catalog():
    fetch(FLAT + 'body-annotations-male-cns-v1.0-minconf-0.5.feather', RAW / 'annotations.feather')
    table = feather.read_table(RAW / 'annotations.feather')
    neurons = table.filter(pc.equal(table['status'], 'Traced')).to_pylist()
    target = OUT / 'neurons.db'
    if target.exists():
        target.unlink()
    db = sqlite3.connect(target)
    db.executescript('''
        CREATE TABLE neurons (id INTEGER PRIMARY KEY, type TEXT NOT NULL, instance TEXT NOT NULL,
        superclass TEXT NOT NULL, class TEXT NOT NULL, side TEXT NOT NULL, nerve TEXT NOT NULL,
        status TEXT NOT NULL, dimorphism TEXT NOT NULL, synonyms TEXT NOT NULL, brain INTEGER NOT NULL);
        CREATE INDEX neuron_type ON neurons(type COLLATE NOCASE);
        CREATE INDEX neuron_class ON neurons(class);
        CREATE INDEX neuron_superclass ON neurons(superclass);
    ''')
    for n in neurons:
        sc = n['superclass'] or ''
        brain = not sc.startswith('vnc_') and sc not in ('ENS', 'efferent_ascending')
        db.execute('INSERT INTO neurons VALUES (?,?,?,?,?,?,?,?,?,?,?)',
                   (n['bodyId'], n['type'] or '未分類', n['instance'] or '', sc, n['class'] or '',
                    n['somaSide'] or n['rootSide'] or '', n['entryNerve'] or n['exitNerve'] or '',
                    n['statusLabel'] or n['status'], n['dimorphism'] or '', n['synonyms'] or '', int(brain)))
    db.commit()
    db.execute('VACUUM')
    db.close()
    return neurons


def skeletons(neurons):
    # Deterministic, stratified preview. Each branch is authentic; the selection is NOT a census.
    groups = collections.defaultdict(list)
    for n in neurons:
        if (n['superclass'] or '').startswith(('cb_', 'ol_', 'visual_')) and n['type']:
            groups[(n['class'] or n['superclass'], n['type'])].append(n)
    chosen = []
    # Central-brain type diversity, plus both optic lobes. Individual full skeletons stay intact.
    by_class = collections.defaultdict(list)
    for key, ns in sorted(groups.items()):
        by_class[key[0]].append(sorted(ns, key=lambda x: x['bodyId'])[0])
    for key, ns in sorted(by_class.items()):
        stride = max(1, len(ns) // 32)
        chosen.extend(ns[::stride][:32])
    # Useful known cells for offline exploration.
    for pattern in ['DNp01', 'DNge104', 'EPG', 'PEN', 'PFL3', 'MBON01', 'APL', 'DPM', 'T4a', 'Mi1']:
        chosen.extend([n for n in neurons if n['type'] == pattern][:2])
    chosen = list({n['bodyId']: n for n in chosen}.values())

    def download(n):
        ident = n['bodyId']
        src = RAW / 'skeletons' / str(ident)
        raw = fetch(SKEL + str(ident), src)
        nv, ne = struct.unpack_from('<II', raw)
        assert len(raw) >= 8 + nv*12 + ne*8
        v = np.frombuffer(raw, '<f4', count=nv*3, offset=8).reshape(-1, 3).copy() / 1000
        v = v[:, [0, 2, 1]]
        v[:, 1] *= -1
        e = np.frombuffer(raw, '<u4', count=ne*2, offset=8+nv*12).reshape(-1, 2)
        assert np.isfinite(v).all() and e.max() < nv
        dest = OUT / 'skeletons' / f'{ident}.bin'
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(raw)
        return n, len(e), sha(src)

    records = []
    with ThreadPoolExecutor(max_workers=6) as pool:
        for n, edges, digest in pool.map(download, chosen):
            ident = n['bodyId']
            records.append(dict(id=ident, type=n['type'], group=n['class'] or n['superclass'], source=SKEL+str(ident), sha256=digest,
                                previewEdges=edges))
    # The renderer uploads all original edges directly from the existing skeletons.
    # No second expanded copy in the APK, Java heap, or retained native buffers.
    (OUT / 'preview.bin').unlink(missing_ok=True)
    (OUT / 'skeletons.json').write_text(json.dumps(records, ensure_ascii=False, indent=2))
    print('Offline skeletons', len(records), 'full overview edges',sum(r['previewEdges'] for r in records),flush=True)


def connections():
    # Entire chemical connection graph BETWEEN the traced neurons in this atlas.
    # No weight threshold and no top-K omission. Non-traced fragments are outside this atlas.
    source = RAW / 'connections.feather'
    fetch(FLAT + 'connectome-weights-male-cns-v1.0-minconf-0.5.feather', source)
    annotations = feather.read_table(RAW / 'annotations.feather')
    ids = annotations.filter(pc.equal(annotations['status'], 'Traced'))['bodyId']
    table = feather.read_table(source)
    mask = pc.and_(pc.is_in(table['body_pre'], value_set=ids), pc.is_in(table['body_post'], value_set=ids))
    table = table.filter(mask)
    result = {'sourceRows': 151856684, 'edges': len(table), 'synapses': pc.sum(table['weight']).as_py(),
              'source': FLAT + 'connectome-weights-male-cns-v1.0-minconf-0.5.feather', 'sourceSha256': sha(source)}
    # Store one gzipped adjacency list per neuron per direction inside SQLite.
    # Records are unsigned LEB128 delta-partner-ID + weight, sorted by partner ID.
    db = sqlite3.connect(OUT / 'neurons.db')
    db.executescript('DROP TABLE IF EXISTS connections; CREATE TABLE connections(id INTEGER PRIMARY KEY, outputs BLOB, inputs BLOB, nout INTEGER DEFAULT 0, nin INTEGER DEFAULT 0, wout INTEGER DEFAULT 0, win INTEGER DEFAULT 0);')
    db.executemany('INSERT INTO connections(id) VALUES (?)', [(i.as_py(),) for i in ids])
    for own, other, direction, count, total in [('body_pre','body_post','outputs','nout','wout'),('body_post','body_pre','inputs','nin','win')]:
        t = table.sort_by([(own,'ascending'),(other,'ascending')])
        owner = t[own].to_numpy()
        data = np.column_stack([t[other].to_numpy(),t['weight'].to_numpy()]).astype('<u4')
        starts = np.r_[0, np.flatnonzero(np.diff(owner))+1, len(owner)]
        for begin,end in zip(starts[:-1], starts[1:]):
            packed = bytearray()
            previous = 0
            for partner, weight in data[begin:end].tolist():
                delta = partner - previous
                previous = partner
                for value in (delta, weight):
                    while value >= 128:
                        packed.append((value & 127) | 128)
                        value >>= 7
                    packed.append(value)
            compressed = gzip.compress(packed, compresslevel=6, mtime=0)
            db.execute(f'UPDATE connections SET {direction}=?,{count}=?,{total}=? WHERE id=?',
                       (compressed,int(end-begin),int(data[begin:end,1].sum()),int(owner[begin])))
        db.commit()
        print('Adjacency indexed', direction, flush=True)
    db.execute('VACUUM')
    db.close()
    (OUT / 'connections.json').write_text(json.dumps(result, indent=2))
    return result


def provenance():
    assets = {str(p.relative_to(folder)): dict(bytes=p.stat().st_size, sha256=sha(p), module=module)
              for folder,module in [(OUT,'base'),(MODEL_OUT,'atlas_models')] for p in sorted(folder.rglob('*')) if p.is_file()}
    manifest = dict(dataset='male-cns:v1.0', checked='2026-09-08',
                    license='CC-BY-4.0', licenseUrl='https://creativecommons.org/licenses/by/4.0/',
                    source='https://male-cns.janelia.org/download/',
                    attribution='FlyEM (HHMI Janelia), University of Cambridge Department of Zoology, MRC Laboratory of Molecular Biology, Google Research; Male CNS project contributors.',
                    modifications='Original ROI triangles preserved with indexed chunks and signed-byte normals; 20k-triangle interaction companions; rigid coordinate rotation and nm-to-micrometre conversion; deterministic 523-cell overview with all their original edges; annotations limited to status Traced; full chemical adjacency between these neurons compressed into SQLite.',
                    assets=assets)
    (ROOT / 'data/provenance/manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2))


def package():
    """Split the SQLite file below GitHub's per-file limit; Android copies it atomically."""
    source = OUT / 'neurons.db'
    folder = OUT / 'database'
    folder.mkdir(exist_ok=True)
    for p in folder.glob('part-*.dat'):
        p.unlink()
    with source.open('rb') as f:
        i = 0
        while chunk := f.read(40 * 1024 * 1024):
            (folder / f'part-{i:02}.dat').write_bytes(chunk)
            i += 1
    source.replace(RAW / 'atlas.db')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('steps', nargs='*', default=['meshes','catalog','skeletons','connections','package','provenance'])
    args = parser.parse_args()
    RAW.mkdir(parents=True, exist_ok=True)
    OUT.mkdir(parents=True, exist_ok=True)
    for step in args.steps:
        if step == 'skeletons':
            t = feather.read_table(RAW / 'annotations.feather')
            skeletons(t.filter(pc.equal(t['status'], 'Traced')).to_pylist())
        else:
            globals()[step]()
