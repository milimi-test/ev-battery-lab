from pathlib import Path
import re
root=Path(__file__).resolve().parents[2];p=root/'app/src/main/java/rs/battery/lab'
for f in p.glob('*.kt'):
    if f.name=='AppLanguage.kt':continue
    s=f.read_text(encoding='utf-8')
    for old,new in [('Text','LText'),('Icon','LIcon'),('Image','LImage')]:
        s=re.sub(r'(?<![\w.])'+old+r'\(',new+'(',s)
    s=s.replace('.drawText(','.drawUiText(').replace('.measureText(','.measureUiText(')
    s=s.replace('Locale("sr", "RS")','AppLanguage.locale')
    f.write_text(s,encoding='utf-8')
