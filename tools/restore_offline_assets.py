"""Restore the large offline map layers from a downloaded Battery Lab APK."""
import argparse, hashlib, shutil, zipfile
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
PREFIXES=('atlas/','streets/','relief/','route-heights/','contours/')
FILES={'serbia-roads.graph','atlas_vectors.atlasdata'}

def restore(apk,checksums=None):
    apk=Path(apk)
    if checksums:
        entries={parts[1].lstrip('*'):parts[0].lower() for line in Path(checksums).read_text().splitlines() if len(parts:=line.split())==2}
        expected=entries.get(apk.name)
        with apk.open('rb') as stream:actual=hashlib.file_digest(stream,'sha256').hexdigest()
        if expected is None or expected!=actual:raise ValueError('APK checksum missing or mismatched')
    destination=(ROOT/'app/src/main/assets').resolve();count=total=0
    with zipfile.ZipFile(apk) as archive:
        if 'AndroidManifest.xml' not in archive.namelist():raise ValueError('Expected an Android APK')
        for entry in archive.infolist():
            if not entry.filename.startswith('assets/') or entry.is_dir():continue
            name=entry.filename[len('assets/'):]
            if name not in FILES and not name.startswith(PREFIXES):continue
            target=(destination/name).resolve()
            if not target.is_relative_to(destination):raise ValueError('Unsafe archive path')
            total+=entry.file_size
            if total>1024**3 or entry.file_size>128*1024**2:raise ValueError('Unexpected map data size')
            target.parent.mkdir(parents=True,exist_ok=True)
            with archive.open(entry) as source,target.open('wb') as output:shutil.copyfileobj(source,output)
            count+=1
    if not count:raise ValueError('No Battery Lab offline assets found')
    print(f'Restored {count} map files ({total/1024**2:.1f} MiB).')

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk',type=Path);parser.add_argument('--checksums',type=Path)
    args=parser.parse_args();restore(args.apk,args.checksums)
