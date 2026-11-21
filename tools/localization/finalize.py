from pathlib import Path
import json
ROOT=Path(__file__).resolve().parents[2];p=ROOT/'app/src/main/java/rs/battery/lab'
def edit(file,a,b):
 f=p/file;s=f.read_text(encoding='utf-8');assert a in s,(file,a);f.write_text(s.replace(a,b),encoding='utf-8')
edit('WeatherTracker.kt','val labels = listOf("S", "SI", "I", "JI", "J", "JZ", "Z", "SZ")','val labels = if (AppLanguage.code == "en") listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW") else listOf("S", "SI", "I", "JI", "J", "JZ", "Z", "SZ")')
edit('MainActivity.kt','contentDescription = "Snimanje merenja"','contentDescription = UiStrings.text("Snimanje merenja")')
edit('MainActivity.kt','stateDescription = if (enabled) "Snimanje uključeno" else "Snimanje isključeno"','stateDescription = UiStrings.text(if (enabled) "Snimanje uključeno" else "Snimanje isključeno")')
edit('MainActivity.kt','}, "Pošalji merenja za analizu"))','}, UiStrings.text("Pošalji merenja za analizu")))')
edit('OfflineAtlasView.kt','if(contentDescription!=desc) contentDescription=desc','if(contentDescription!=UiStrings.text(desc)) contentDescription=UiStrings.text(desc)')
edit('SerbiaMap.kt','contentDescription = accessibilityLabel','contentDescription = UiStrings.text(accessibilityLabel)')
for file in ['PlannedElevation.kt','VoltageSocModelPicker.kt']:
 f=p/file;s=f.read_text(encoding='utf-8')
 # Full-line semantic assignments, including templates. No data logic is modified.
 lines=s.splitlines()
 for i,line in enumerate(lines):
  if line.strip().startswith(('contentDescription = "','stateDescription = "')):
   before,value=line.split(' = ',1);lines[i]=before+' = UiStrings.text('+value+')'
 s='\n'.join(lines)+'\n'
 s=s.replace('contentDescription = "Izaberi SOC model"; stateDescription = model.title','contentDescription = UiStrings.text("Izaberi SOC model"); stateDescription = UiStrings.text(model.title)')
 s=s.replace('contentDescription = "Kriva napona prema SOC-u: ${model.title}" }','contentDescription = UiStrings.text("Kriva napona prema SOC-u: ${model.title}") }')
 f.write_text(s,encoding='utf-8')
f=ROOT/'app/src/main/assets/pack/pack.html';s=f.read_text(encoding='utf-8');s=s.replace('<script src="pack.js"></script>','<script src="pack.js"></script><script src="i18n.js"></script>');f.write_text(s,encoding='utf-8')
f=ROOT/'app/src/main/assets/pack/pack.js';s=f.read_text(encoding='utf-8');s=s.replace("toLocaleString('sr-Latn-RS')","toLocaleString(q.get('lang')==='sr'?'sr-Latn-RS':'en-GB')");f.write_text(s,encoding='utf-8')
f=ROOT/'tools/localization/extra-en.json';d=json.loads(f.read_text(encoding='utf-8'))
for k in ['S','I','J','Z']:d.pop(k,None)
d.update({'Pozicija modula {0}':'Module {0} position','BMS · položaj':'BMS · position','oznaka na slici':'position on image','Baterija · BMS prosek':'Battery · BMS average','BMS blok B{0}':'BMS block B{0}','Zbir 6 naponskih grupa':'Sum of 6 group voltages','Sonda T{0}':'Probe T{0}','G{0}–G{1} · po 2 paralelne ćelije':'G{0}–G{1} · 2 parallel cells per group'})
f.write_text(json.dumps(d,ensure_ascii=False,indent=2),encoding='utf-8')
print('Language wiring complete')
