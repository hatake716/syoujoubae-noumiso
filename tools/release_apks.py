#!/usr/bin/env python3
"""Build a complete signed universal APK from the asset-pack AAB; never log secrets."""
from pathlib import Path
import hashlib
import json
import os
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]

def main():
    props = dict(line.split('=',1) for line in (ROOT/'keystore.properties').read_text().splitlines()
                 if '=' in line and not line.lstrip().startswith('#'))
    props = {key.strip():value.strip() for key,value in props.items()}
    version = '1.0.2'
    out = ROOT/'artifacts'/f'release-{version}'
    out.mkdir(parents=True,exist_ok=True)
    bundletool = Path(os.environ.get('BUNDLETOOL_JAR',ROOT/'data/source/bundletool.jar'))
    aab = out/f'syoujoubae-noumiso-{version}.aab'
    shutil.copy2(ROOT/'app/build/outputs/bundle/release/app-release.aab',aab)
    with tempfile.TemporaryDirectory(prefix='atlas-signing-') as temp:
        password = Path(temp)/'store-pass';password.write_text(props['storePassword']);password.chmod(0o600)
        key_password = Path(temp)/'key-pass';key_password.write_text(props['keyPassword']);key_password.chmod(0o600)
        base = ['java','-jar',str(bundletool),'build-apks',f'--bundle={aab}',
                f'--ks={(ROOT/props["storeFile"]).resolve()}',f'--ks-key-alias={props["keyAlias"]}',
                f'--ks-pass=file:{password}',f'--key-pass=file:{key_password}','--overwrite']
        subprocess.run(base+[f'--output={out}/universal.apks','--mode=universal'],check=True)
        # Also validate real install-time split delivery on the emulator later.
        subprocess.run(base+[f'--output={out}/split.apks'],check=True)
    apk = out/f'syoujoubae-noumiso-{version}.apk'
    with zipfile.ZipFile(out/'universal.apks') as archive:
        apk.write_bytes(archive.read('universal.apk'))
    manifest = json.loads((ROOT/'data/provenance/manifest.json').read_text())
    with zipfile.ZipFile(apk) as archive,zipfile.ZipFile(aab) as bundle:
        for path,entry in manifest['assets'].items():
            apk_data=archive.read('assets/atlas/'+path)
            aab_data=bundle.read(entry.get('module','base')+'/assets/atlas/'+path)
            assert hashlib.sha256(apk_data).hexdigest()==entry['sha256'],path
            assert hashlib.sha256(aab_data).hexdigest()==entry['sha256'],path
    subprocess.run(['java','-jar',str(bundletool),'validate',f'--bundle={aab}'],check=True)
    files=[]
    for path in [apk,aab]:
        with path.open('rb') as f:digest=hashlib.file_digest(f,'sha256').hexdigest()
        files.append(dict(file=path.name,bytes=path.stat().st_size,sha256=digest))
    report=dict(versionName=version,versionCode=3,applicationId='io.github.hatake716.syoujoubae',minSdk=28,targetSdk=36,
                signerSha256='88aa69f3592696564c3aef72deac9a3157048818673e9ca575ce0cc409defb1e',
                highDetailDelivery='install-time asset pack atlas_models; universal APK includes the same assets',files=files)
    (ROOT/'docs/play'/f'release-{version}.json').write_text(json.dumps(report,indent=2)+'\n')
    (out/'SHA256SUMS').write_text(''.join(f"{entry['sha256']}  {entry['file']}\n" for entry in files))
    print(json.dumps(report,indent=2))

if __name__=='__main__':main()
