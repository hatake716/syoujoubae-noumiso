"""Scientific-data checks: coverage, actual mesh coordinates, complete graph, provenance."""
from pathlib import Path
import gzip
import hashlib
import json
import re
import sqlite3
import struct
import tempfile
from contextlib import contextmanager
import numpy as np
import pyarrow.feather as feather

ROOT=Path(__file__).resolve().parents[1]
ASSETS=ROOT/'app/src/main/assets/atlas'
MODELS=ROOT/'atlas_models/src/main/assets/atlas'

@contextmanager
def database():
    raw=ROOT/'data/source/atlas.db'
    temporary=None
    if raw.exists():
        connection=sqlite3.connect(raw)
    else:
        temp=tempfile.NamedTemporaryFile(suffix='.db',delete=False)
        for part in sorted((ASSETS/'database').glob('part-*')):
            temp.write(part.read_bytes())
        temp.close()
        temporary=Path(temp.name)
        connection=sqlite3.connect(temp.name)
    try:
        yield connection
    finally:
        connection.close()
        if temporary is not None: temporary.unlink()

def decode(blob):
    if blob is None:return []
    raw=gzip.decompress(blob);p=0;previous=0;result=[]
    def read():
        nonlocal p
        value=0;shift=0
        while p<len(raw):
            b=raw[p];p+=1;value|=(b&127)<<shift
            if not b&128:return value
            shift+=7
            assert shift<=35
        raise ValueError('Truncated varint')
    while p<len(raw):
        previous+=read();result.append((previous,read()))
    return result

def test_all_90_meshes_have_specific_explanations_and_citations():
    m=json.loads((ASSETS/'meshes.json').read_text())
    r=json.loads((ASSETS/'regions.json').read_text())
    keys={x['key'] for x in r['regions']}
    assert len(m)==90 and len(keys)==50
    assert {re.sub(r'\([LR]\)$','',x['name']) for x in m}==keys
    for region in r['regions']:
        assert all(region[f] for f in ['name','summary','mechanism','observe','limits','sources'])
        assert all(s in r['sources'] for s in region['sources'])

def read_mesh(path):
    raw=path.read_bytes()
    assert raw[:4]==b'MCN2'
    chunks=struct.unpack_from('<I',raw,4)[0];offset=8
    vertices=[];faces=[];base=0
    for _ in range(chunks):
        nv,ni=struct.unpack_from('<II',raw,offset);offset+=8
        assert 0<nv<=60000 and 0<ni<=60000 and ni%3==0
        data=np.frombuffer(raw,dtype=[('position','<f4',3),('normal','i1',4)],count=nv,offset=offset);offset+=nv*16
        indices=np.frombuffer(raw,'<u2',count=ni,offset=offset).reshape(-1,3);offset+=ni*2
        assert indices.max()<nv
        assert np.isfinite(data['position']).all()
        assert np.max(np.abs(data['normal'].astype('int16')))<=127
        vertices.append(data['position']);faces.append(indices.astype('uint32')+base);base+=nv
    assert offset==len(raw)
    return np.concatenate(vertices),np.concatenate(faces)

def test_mesh_binary_layout_and_same_coordinate_frame():
    for m in json.loads((ASSETS/'meshes.json').read_text()):
        v,f=read_mesh(MODELS/'meshes'/f"{m['id']}.bin")
        assert len(f)==m['originalTriangles']==m['triangles']
        assert np.allclose(v.min(axis=0),m['min'],atol=.001)
        assert np.allclose(v.max(axis=0),m['max'],atol=.001)
        assert 0<=v[:,0].min()<v[:,0].max()<1000
        assert -600<v[:,1].min()<v[:,1].max()<=0
        small_v,small_f=read_mesh(ASSETS/'meshes/interactive'/f"{m['id']}.bin")
        assert len(small_f)==m['interactiveTriangles']<=20000
        assert np.max(np.abs(small_v.min(axis=0)-v.min(axis=0)))<5
        assert np.max(np.abs(small_v.max(axis=0)-v.max(axis=0)))<5

def test_all_original_triangles_and_positions_are_preserved_in_order():
    checked=0
    for m in json.loads((ASSETS/'meshes.json').read_text()):
        source=ROOT/'data/source/roi'/f"{m['name']}.ngmesh"
        if not source.exists():continue
        raw=source.read_bytes();nv=struct.unpack_from('<I',raw)[0]
        original=np.frombuffer(raw,'<f4',offset=4,count=nv*3).reshape(-1,3).astype('float64')/1000
        original=original[:,[0,2,1]].copy();original[:,1]*=-1
        faces=np.frombuffer(raw,'<u4',offset=4+nv*12).reshape(-1,3)
        assert hashlib.sha256(raw).hexdigest()==m['sourceSha256']
        v,f=read_mesh(MODELS/'meshes'/f"{m['id']}.bin")
        assert len(f)==len(faces)
        assert np.array_equal(v[f],original[faces].astype('<f4'))
        checked+=1
    if not checked:
        import pytest
        pytest.skip('Original raw mesh cache is not present')

def test_overview_uses_all_edges_and_gpu_geometry_stays_in_budget():
    skeletons=json.loads((ASSETS/'skeletons.json').read_text())
    edges=sum(struct.unpack_from('<II',(ASSETS/'skeletons'/f"{s['id']}.bin").read_bytes())[1] for s in skeletons)
    assert edges==sum(s['previewEdges'] for s in skeletons)==2237417
    meshes=list((ASSETS/'meshes').rglob('*.bin'))+list((MODELS/'meshes').rglob('*.bin'))
    # MCN2 headers make this an upper bound; individual neuron gets a separate reserve.
    gpu_upper=sum(p.stat().st_size for p in meshes)+edges*24
    assert gpu_upper+32*1024*1024<256*1024*1024
    assert sum(m['triangles'] for m in json.loads((ASSETS/'meshes.json').read_text()))==7712880

def test_offline_skeletons_are_original_source_bytes_with_valid_edges():
    for s in json.loads((ASSETS/'skeletons.json').read_text()):
        raw=(ASSETS/'skeletons'/f"{s['id']}.bin").read_bytes()
        assert hashlib.sha256(raw).hexdigest()==s['sha256']
        nv,ne=struct.unpack_from('<II',raw)
        assert len(raw)>=8+nv*12+ne*8
        vertices=np.frombuffer(raw,'<f4',offset=8,count=nv*3)
        edges=np.frombuffer(raw,'<u4',offset=8+nv*12,count=ne*2)
        assert np.isfinite(vertices).all() and max(edges)<nv

def test_all_graph_totals_and_reciprocal_direction_samples():
    with database() as db:
        assert db.execute('pragma quick_check').fetchone()[0]=='ok'
        assert db.execute('select count(*) from neurons').fetchone()[0]==165122
        nout,nin,wout,win=db.execute('select sum(nout),sum(nin),sum(wout),sum(win) from connections').fetchone()
        assert nout==nin==25563197
        assert wout==win==124025046
        # Every sampled out-edge must be present as an incoming edge with the same weight.
        ids=[r[0] for r in db.execute('select id from neurons order by id').fetchall()][::997]+[10001,10539,10013]
        for ident in ids:
            row=db.execute('select outputs,inputs,nout,nin,wout,win from connections where id=?',(ident,)).fetchone()
            outputs,inputs=decode(row[0]),decode(row[1])
            assert (len(outputs),len(inputs),sum(w for _,w in outputs),sum(w for _,w in inputs))==row[2:]
            for partner,weight in sorted(outputs,key=lambda x:-x[1])[:3]:
                reverse=decode(db.execute('select inputs from connections where id=?',(partner,)).fetchone()[0])
                assert (ident,weight) in reverse

def test_selected_connections_exactly_match_the_original_feather():
    source=ROOT/'data/source/traced-connections.feather'
    if not source.exists():
        import pytest
        pytest.skip('Optional 1GB source verification; run prepare_data first to download source data')
    import pyarrow.compute as pc
    t=feather.read_table(source)
    with database() as db:
        for ident in [10001,10539,10013,556329]:
            rows=t.filter(pc.equal(t['body_pre'],ident)).to_pylist()
            expected=sorted((x['body_post'],x['weight']) for x in rows)
            actual=decode(db.execute('select outputs from connections where id=?',(ident,)).fetchone()[0])
            assert actual==expected

def test_packaged_database_is_exact_split_of_generated_database():
    source=ROOT/'data/source/atlas.db'
    parts=sorted((ASSETS/'database').glob('part-*'))
    assert parts and all(p.stat().st_size<=40*1024*1024 for p in parts)
    if source.exists():
        joined=hashlib.sha256()
        for part in parts:joined.update(part.read_bytes())
        with source.open('rb') as f:assert joined.hexdigest()==hashlib.file_digest(f,'sha256').hexdigest()
