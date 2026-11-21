"""Create a reproducible source bundle; excludes logs, measurements, SDKs and private keys."""
from pathlib import Path
import hashlib
import json
import zipfile

ROOT=Path(__file__).resolve().parents[1]
VERSION='0.22'
def main():
    output=ROOT/f'battery-lab-source-{VERSION}.zip'
    files=[]
    for folder in ['app/src','gradle','tools','analyzer','.github']:
        for f in (ROOT/folder).rglob('*'):
            if not f.is_file() or any(p in f.parts for p in ['__pycache__','reports','dist','build']):continue
            if f.suffix in ['.bak','.pbf','.sqlite','.writing','.tmp']:continue
            if 'raster-work' in f.parts:continue
            files.append(f)
    for name in ['build.gradle.kts','settings.gradle.kts','gradle.properties','gradlew','gradlew.bat','.gitignore',
                 'app/build.gradle.kts','README.md','UPUTSTVO_ZA_DORADU.md','design/VOZILA_021.md','design/JEZICI_022.md',
                 '.gitattributes','CONTRIBUTING.md','NOTICE.md','CHANGELOG.md']:
        files.append(ROOT/name)
    for f in files:
        if not f.is_file():raise FileNotFoundError(f)
    prefix=f'Battery-Lab-{VERSION}/'
    with zipfile.ZipFile(output,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as archive:
        for f in sorted(set(files)):
            name=prefix+f.relative_to(ROOT).as_posix()
            info=zipfile.ZipInfo(name,date_time=(2026,11,18,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED
            info.external_attr=(0o755 if f.name=='gradlew' else 0o644)<<16
            with f.open('rb') as source,archive.open(info,'w',force_zip64=True) as dest:
                while chunk:=source.read(1024*1024):dest.write(chunk)
    # CRC checks detect incomplete writes; required paths ensure this is a buildable project bundle.
    with zipfile.ZipFile(output) as archive:
        assert archive.testzip() is None
        assert all(prefix+p in archive.namelist() for p in ['gradlew.bat','gradle/wrapper/gradle-wrapper.jar',
            'app/build.gradle.kts','app/src/main/assets/i18n/en.json','UPUTSTVO_ZA_DORADU.md'])
    digest=hashlib.file_digest(output.open('rb'),'sha256').hexdigest()
    output.with_suffix('.sha256').write_text(digest+'  '+output.name+'\n',encoding='ascii')
    print(json.dumps({'archive':str(output),'files':len(set(files)),'bytes':output.stat().st_size,'sha256':digest}))
if __name__=='__main__':main()
