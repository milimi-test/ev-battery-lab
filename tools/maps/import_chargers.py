"""Validate and merge manually sourced chargers. Dry-run by default; never fetches data."""
import argparse
from datetime import date
import json
import math
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[2]
DEFAULT = ROOT / 'app/src/main/assets/charging-stations.json'

def validate(row):
    if not isinstance(row, dict): raise ValueError('Each station must be an object')
    for key in ('id','name','sourceLabel','sourceUrl','checked','sockets'):
        if not isinstance(row.get(key), str) or not row[key].strip(): raise ValueError(f'Missing {key}')
    if row['id'].startswith('example:'): raise ValueError('Replace example fields with a real, sourced station before importing')
    for key, lo, hi in [('lat',-90,90),('lon',-180,180)]:
        value=row.get(key)
        if isinstance(value,bool) or not isinstance(value,(int,float)) or not math.isfinite(value) or not lo<=value<=hi: raise ValueError(f'Invalid {key}')
    if row.get('kind','unknown') not in ('ac','dc','unknown'): raise ValueError('kind: ac, dc or unknown')
    if row.get('fee','unknown') not in ('free','paid','unknown'): raise ValueError('fee: free, paid or unknown')
    date.fromisoformat(row['checked'])
    if not row['sourceUrl'].startswith('https://'): raise ValueError('sourceUrl must be an HTTPS source link')
    power=row.get('powerKw')
    if power is not None and (isinstance(power,bool) or not isinstance(power,(int,float)) or not math.isfinite(power) or power<=0): raise ValueError('powerKw must be positive or null')
    output=dict(row)
    output['free']=output.get('fee')=='free'
    output['compatible']=any(s in output['sockets'] for s in ('CCS2','Type 2','Šuko','Schuko'))
    output.setdefault('kind','unknown');output.setdefault('fee','unknown')
    for key in ('operator','price','access','hours','address','notes'):output.setdefault(key,'')
    return output

def merge(catalog, additions, replace=False):
    rows=list(catalog['stations']); index={r['id']:i for i,r in enumerate(rows)}
    if len(index)!=len(rows): raise ValueError('Existing catalogue has duplicate IDs')
    seen=set();added=updated=0
    for raw in additions:
        row=validate(raw);key=row['id']
        if key in seen: raise ValueError(f'Duplicate input ID: {key}')
        seen.add(key)
        if key in index:
            if not replace: raise ValueError(f'{key} already exists; use --replace to update it')
            rows[index[key]]=row;updated+=1
        else:index[key]=len(rows);rows.append(row);added+=1
    out=dict(catalog);out['stations']=rows
    if 'count' in out:out['count']=len(rows)
    out['localEditsDate']=date.today().isoformat()
    return out,added,updated

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('input',type=Path,help='JSON array of sourced stations')
    parser.add_argument('--catalog',type=Path,default=DEFAULT)
    parser.add_argument('--replace',action='store_true',help='Allow updating existing IDs')
    parser.add_argument('--write',action='store_true',help='Write catalogue and keep a .bak copy')
    args=parser.parse_args()
    data=json.loads(args.input.read_text(encoding='utf-8'))
    if not isinstance(data,list):raise ValueError('Input must be a JSON array')
    out,added,updated=merge(json.loads(args.catalog.read_text(encoding='utf-8')),data,args.replace)
    print(f'Valid: {added} new, {updated} updated, {len(out["stations"])} total')
    if args.write:
        backup=args.catalog.with_suffix(args.catalog.suffix+'.bak');shutil.copy2(args.catalog,backup)
        temp=args.catalog.with_suffix(args.catalog.suffix+'.writing')
        temp.write_text(json.dumps(out,ensure_ascii=False,separators=(',',':')),encoding='utf-8');temp.replace(args.catalog)
        print(f'Written {args.catalog}; backup: {backup}')
    else: print('Dry-run only; add --write to save')
if __name__=='__main__':main()
