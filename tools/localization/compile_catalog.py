from pathlib import Path
import json,re
ROOT=Path(__file__).resolve().parents[2]
inventory=json.loads((ROOT/'tools/localization/inventory.json').read_text(encoding='utf-8'))
entries={}
def formatted(text):
    n=0
    def sub(m):
        nonlocal n
        if m[0]=='%%':return '%'
        out='{'+str(n)+'}';n+=1;return out
    return re.sub(r'%%|%(?:\d+\$)?[.\d]*[dfs]',sub,text)
for line in (ROOT/'tools/localization/en.tsv').read_text(encoding='utf-8').splitlines():
    if not line or line.startswith('#'):continue
    key,value=line.split('\t',1);source=inventory[int(key)]['sr'];value=value.replace('\\n','\n')
    source,value=formatted(source),formatted(value)
    assert sorted(re.findall(r'\{\d+\}',source))==sorted(re.findall(r'\{\d+\}',value)), (key,source,value)
    entries[source]=value
    if source.strip()!=source:entries[source.strip()]=value.strip()
extras=ROOT/'tools/localization/extra-en.json'
if extras.exists():entries.update(json.loads(extras.read_text(encoding='utf-8')))
for a,b in list(entries.items()):
    entries.setdefault(a.lower(),b[:1].lower()+b[1:])
target=ROOT/'app/src/main/assets/i18n/en.json';target.parent.mkdir(exist_ok=True)
target.write_text(json.dumps(entries,ensure_ascii=False,indent=2),encoding='utf-8')
print(f'{len(entries)} presentation entries written')
