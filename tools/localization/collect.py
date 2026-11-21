"""Inventory Kotlin string templates without changing protocol or persistence strings."""
from pathlib import Path
import json, re

ROOT = Path(__file__).resolve().parents[2]

def strings(s):
    found=[]
    def code(i, end_brace=False):
        depth=0
        while i<len(s):
            if s.startswith('//',i):
                j=s.find('\n',i); i=len(s) if j<0 else j+1
            elif s.startswith('/*',i):
                j=s.find('*/',i+2); i=len(s) if j<0 else j+2
            elif s[i]=='"': i=string(i)
            elif s[i]=="'":
                i+=1
                while i<len(s):
                    if s[i]=='\\': i+=2
                    elif s[i]=="'": i+=1;break
                    else:i+=1
            elif s[i]=='{':depth+=1;i+=1
            elif s[i]=='}':
                if end_brace and depth==0:return i+1
                depth-=1;i+=1
            else:i+=1
        return i
    def string(i):
        start=i;triple=s.startswith('"""',i);mark='"""' if triple else '"';i+=len(mark);parts=[];n=0
        while i<len(s):
            if s.startswith(mark,i):
                found.append((start,i+len(mark),''.join(parts)));return i+len(mark)
            if not triple and s[i]=='\\':
                ch=s[i+1:i+2];parts.append({'n':'\n','r':'\r','t':'\t','"':'"','\\':'\\','$':'$'}.get(ch,'\\'+ch));i+=2
            elif s.startswith('${',i):
                i=code(i+2,True);parts.append('{'+str(n)+'}');n+=1
            elif s[i]=='$' and re.match(r'[A-Za-z_]',s[i+1:i+2]):
                m=re.match(r'\$[A-Za-z_][\w]*',s[i:]);i+=len(m[0]);parts.append('{'+str(n)+'}');n+=1
            else:parts.append(s[i]);i+=1
        return i
    code(0);return sorted(found)

if __name__=='__main__':
    records={}
    for f in (ROOT/'app/src/main/java/rs/battery/lab').glob('*.kt'):
        for a,b,text in strings(f.read_text(encoding='utf-8')):
            # Keep all words for human review; protocol codes and file/URL identifiers stay out.
            if len(text)<2 or not re.search(r'[A-Za-zčćžšđČĆŽŠĐ]',text):continue
            if '\\' in text or text.startswith(('http','file:','app:','window.','android.')):continue
            if re.fullmatch(r'[\w./:%$#-]+',text) and not re.search(r'[čćžšđČĆŽŠĐ]',text) and text[:1].islower():continue
            records.setdefault(text,[]).append(f.name)
    items=[{'id':i,'sr':s,'files':sorted(set(fs))} for i,(s,fs) in enumerate(sorted(records.items()))]
    out=ROOT/'tools/localization/inventory.json';out.write_text(json.dumps(items,ensure_ascii=False,indent=2),encoding='utf-8')
    print(f'{len(items)} unique strings/templates; {sum(len(x["sr"]) for x in items)} characters')
