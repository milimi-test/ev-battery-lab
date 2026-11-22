'use strict';
const D=JSON.parse(document.getElementById('data').textContent),$=id=>document.getElementById(id);
const palette=['#1769b3','#c56b09','#087f73','#8350a9','#b44365','#647619'];
const refColors=['#7448a0','#328348','#c05b30'];
let g,latest,lo,hi,tripIndex=null,cursorTime=null,pinned=false,drag=null,rawPage=0;
let selected=['vehicle_speed_kmh','battery_power_kw','instant_consumption_kwh_100km'];
const esc=s=>String(s??'—').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const fmt=(v,d=2)=>v==null?'—':typeof v==='number'?v.toLocaleString('en-GB',{maximumFractionDigits:d}):String(v);
const date=t=>new Date(t).toISOString().replace('T',' ').slice(0,19)+' UTC';
const median=a=>{a=a.filter(v=>v!=null).sort((a,b)=>a-b);return a.length?(a[(a.length-1)>>1]+a[a.length>>1])/2:null};
const dateHTML=s=>`<time class="${String(s).slice(0,10)===latest?'date-latest':'date-old'}">${esc(String(s).replace(/^(\d{4}-\d{2}-\d{2})T/,'$1 ').replace('+00:00',''))}</time>`;
const bounds=a=>a.reduce(([lo,hi],v)=>v==null?[lo,hi]:[Math.min(lo,v),Math.max(hi,v)],[Infinity,-Infinity]);
const distance=t=>t.odometer_km>0?t.odometer_km:t.speed_integrated_km;
function table(id,rows,cols){
 if(!rows.length){$(id).innerHTML='<div class="empty">No eligible data / Nema odgovarajućih podataka</div>';return}
 $(id).innerHTML='<div class="tablewrap"><table><thead><tr>'+cols.map(c=>'<th>'+esc(c[1])+'</th>').join('')+'</tr></thead><tbody>'+rows.map(r=>'<tr>'+cols.map(c=>'<td>'+(c[3]==='date'&&r[c[0]]?dateHTML(r[c[0]]):esc(fmt(r[c[0]],c[2]??2)))+'</td>').join('')+'</tr>').join('')+'</tbody></table></div>';
}
function download(rows,name){
 if(!rows.length)return;
 const keys=Object.keys(rows[0]),cell=v=>'"'+String(typeof v==='string'&&/^[=+\-@]/.test(v)?"'"+v:v??'').replaceAll('"','""')+'"';
 const data='\ufeff'+[keys.map(cell).join(','),...rows.map(r=>keys.map(k=>cell(r[k])).join(','))].join('\r\n');
 const url=URL.createObjectURL(new Blob([data],{type:'text/csv;charset=utf-8'}));let a=document.createElement('a');a.href=url;a.download=name;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
}
function activate(){
 g=D.vehicles[+$('vehicle').value];latest=g.end_utc.slice(0,10);rawPage=0;
 $('meta').innerHTML=dateHTML(g.start_utc)+' → '+dateHTML(g.end_utc)+` · ${fmt(g.readings,0)} readings / očitavanja · UTC`;
 $('version').textContent=D.version;
 const eligible=g.trips.filter(t=>t.kwh_per_100km!=null),km=eligible.reduce((s,t)=>s+distance(t),0),energy=eligible.reduce((s,t)=>s+t.energy_kwh,0);
 const h=g.health_pack;const cards=[['Recorded SOH / Očitan SOH',fmt(h.last_soh)+' %',`Change / Promena: ${fmt(h.change_pp)} pp`],['SOC measured / Izmereni SOC',fmt(g.stats.soc_percent.min,1)+'–'+fmt(g.stats.soc_percent.max,1)+' %',`${g.soc_pairs.length} unique voltage pairs`],['Consumption / Potrošnja',fmt(km?energy/km*100:null)+' kWh/100 km',`${fmt(km,0)} km · ${eligible.length} sufficiently covered trips`],['Cell imbalance / Razlika ćelija',fmt(g.stats.spread_mv.max,1)+' mV','Largest recorded ΔV / Najveća snimljena ΔV']];
 $('kpis').innerHTML=cards.map(c=>`<div class="kpi"><small>${esc(c[0])}</small><div class="value">${esc(c[1])}</div><small>${esc(c[2])}</small></div>`).join('');
 const packs=h.change_pp==null?'Not enough dated SOH readings / Nedovoljno SOH merenja kroz vreme.':Math.abs(h.change_pp)<.01?'No change in recorded pack SOH / Očitani SOH paketa se nije promenio.':`Recorded SOH ${h.change_pp<0?'decrease / pad':'increase / rast'}: ${fmt(Math.abs(h.change_pp))} pp. A BMS estimate change is not a laboratory degradation measurement.`;
 const weak=g.cells.slice().sort((a,b)=>a.median_deviation_mv-b.median_deviation_mv)[0],ready=g.wind.filter(w=>w.paired_delta!=null);
 const items=[['Battery trend / Stanje baterije',packs],['Cell groups / Ćelijske grupe',weak?`Group ${weak.group}: median deviation ${fmt(weak.median_deviation_mv,1)} mV from the pack mean, ${weak.readings} scans. This is an observation, not an automatic fault diagnosis. / Odstupanje nije automatska dijagnoza kvara.`:'No fresh group voltage scans. / Nema svežih očitavanja grupa.'],['SOC investigation / Provera SOC-a',`${g.soc_pairs.length} unique voltage scans, ${g.soc_pairs.filter(x=>x.low_current).length} at |I|≤5 A. Compare measured medians with three external laboratory references; no unverified buffer conclusion.`],['Comparable wind / Uporediv vetar',ready.length?`${ready.length} speed bins meet matched-data thresholds. Conditions remain observational.`:`${g.wind_pairs.length} matched segment pairs; no speed bin meets all thresholds. / Nema dovoljno uporedivih parova za zaključak o uticaju vetra.`]];
 $('findings').innerHTML='<h3 style="margin-top:0">Report findings / Glavni nalazi</h3><div class="findings-grid">'+items.map(([t,p])=>`<div class="finding"><h3>${esc(t)}</h3><p>${esc(p)}</p></div>`).join('')+'</div>';
 $('health-summary').innerHTML=`<h3 style="margin-top:0">${esc(packs)}</h3><p>Daily median BMS SOH: <b>${fmt(h.first_soh)}% → ${fmt(h.last_soh)}%</b>. Actual recorded group SOH is shown separately; pack SOH is never copied to individual cells. / SOH grupe prikazujemo samo ako je direktno očitan.</p>`;
 table('health-days',g.health_daily,[['day_utc','Day / Dan',0,'date'],['samples','Snapshots'],['soh_median','BMS SOH median %'],['battery_temp_median','Battery °C'],['spread_median_mv','Median ΔV mV']]);
 const health=g.health_groups.slice().sort((a,b)=>Math.min(0,a.bms_soh_change_pp??0)-Math.min(0,b.bms_soh_change_pp??0)||(a.comparable_shift_mv??0)-(b.comparable_shift_mv??0)||a.last_deviation_mv-b.last_deviation_mv);
 const healthCols=[['group','Group / Grupa'],['bms_soh_first','Earlier SOH %'],['bms_soh_last','Latest SOH %'],['bms_soh_change_pp','SOH Δ pp'],['last_deviation_mv','Latest Δ mean mV'],['comparable_shift_mv','Matched shift mV'],['early_samples','Earlier n'],['recent_samples','Latest n']];
 table('health-groups',health.slice(0,8),healthCols);
 table('health-all',g.health_groups,[['first_day','Earlier date',0,'date'],['last_day','Latest date',0,'date'],...healthCols]);
 const drops=health.filter(x=>x.bms_soh_change_pp!=null&&x.bms_soh_change_pp<-.01).length,measured=health.filter(x=>x.bms_soh_change_pp!=null).length;
 $('health-summary').innerHTML+=`<p>${dateHTML(h.first_day??'')} → ${dateHTML(h.last_day??'')} · ${measured} groups with dated SOH / grupa sa SOH kroz vreme; ${drops} with a lower last-day median / sa nižom poslednjom medijanom. BMS estimate changes (including increases) do not alone establish physical degradation or recovery.</p>`;
 table('cells',g.cells,[['group','Group'],['readings','Scans'],['min_v','Min V',4],['median_v','Median V',4],['max_v','Max V',4],['median_deviation_mv','Δ mean mV'],['lowest_count','Lowest count'],['bms_soh_last','BMS SOH %']]);
 table('probes',g.probes,[['probe','Probe'],['count','Scans'],['min','Min °C'],['median','Median °C'],['max','Max °C']]);
 table('soc-medians',g.soc_medians,[['soc_bin','Displayed SOC bin %'],['soc_median','Actual SOC median %'],['samples','Unique scans'],['days','Days'],['median_voltage_v','Median V',4],['q25_voltage_v','Q25 V',4],['q75_voltage_v','Q75 V',4],['low_current_samples','Low-I n'],['low_current_voltage_v','Low-I median V',4],['lg-m50_low_soc','LG low-I ref %'],['calb-nmc532_low_soc','CALB low-I ref %'],['nmc622-lp57_low_soc','NMC622 low-I ref %'],['lg-m50_soc','LG all-load ref %'],['calb-nmc532_soc','CALB all-load ref %'],['nmc622-lp57_soc','NMC622 all-load ref %']]);
 $('raw-day').innerHTML='<option value="all">All dates / Svi datumi</option>'+g.daily.map(d=>`<option value="${d.day_utc}">${d.day_utc}${d.day_utc===latest?' · latest / poslednji':' · earlier / ranije'}</option>`).join('');
 $('raw-summary').textContent=`${g.soc_readings.length} snapshots, no sampling or truncation / Sva očitavanja bez izostavljanja. 100 rows per page / redova po stranici. CSV contains all dates.`;
 $('reference-legend').innerHTML=D.references.map((r,i)=>`<span class="dot" style="--c:${refColors[i]}">${esc(r.name)} · reference / referenca</span>`).join('');
 $('sources').innerHTML=D.references.map((r,i)=>`<div class="reference-item" style="--c:${refColors[i]}"><b>${esc(r.name)}</b><br>${esc(r.kind)}<br>${esc(r.note)}<br><a href="${esc(r.source)}" target="_blank" rel="noreferrer">Paper / Rad</a> · <a href="${esc(r.data_source)}" target="_blank" rel="noreferrer">Numeric source / Izvor podataka</a></div>`).join('');
 const bc=[['group','Condition / Uslov'],['kwh_per_100km','kWh/100 km'],['distance_km','km'],['segments','Segments'],['evidence','Evidence']];
 for(const k of ['speed','temperature','terrain'])table(k,g[k],bc);
 $('wind-summary').innerHTML=`<p><b>${g.wind_candidates}</b> stable level-road candidates · <b>${g.wind_pairs.length}</b> one-to-one matched pairs / uparenih segmenata.</p>`+(ready.length?'':'<p class="notice">Insufficient comparable data. A higher average with tailwind across mixed trips is not evidence that tailwind increases consumption. / Nema dovoljno uporedivih merenja. Zbir različitih brzina i nagiba više se ne prikazuje kao efekat vetra.</p>');
 const windCols=[['speed_band','Speed / Brzina'],['candidates','Candidates'],['pairs','Pairs / Parovi'],['head_km','Head km'],['tail_km','Tail km']];
 if(ready.length)windCols.push(['head_median','Head kWh/100'],['tail_median','Tail kWh/100'],['paired_delta','Median head−tail']);
 windCols.push(['evidence','Evidence / Dokaz']);table('wind',g.wind.filter(x=>x.candidates||x.pairs),windCols);
 table('charges',g.charges,[['start_utc','Start UTC',0,'date'],['kind','AC/DC'],['battery_kwh','Battery kWh'],['coverage_percent','Coverage %'],['charger_kwh','Meter kWh'],['loss_percent','Loss %']]);
 table('daily',g.daily,[['day_utc','Day UTC',0,'date'],['readings','Snapshots'],['soc_min','SOC min %'],['soc_max','SOC max %'],['soh_last','SOH last %'],['battery_temp_median','Battery °C'],['spread_max_mv','Max ΔV mV']]);
 table('availability',Object.entries(g.stats).map(([k,s])=>({name:D.fields[k][0],unit:D.fields[k][1],...s,coverage:s.count/g.readings*100})),[['name','Signal'],['unit','Unit'],['count','Reads'],['coverage','Available %'],['min','Min',3],['median','Median',3],['max','Max',3],['last','Last',3]]);
 $('quality').innerHTML='<p>'+g.files.map(esc).join('<br>')+'</p><p>'+esc(`${g.duplicates} duplicate snapshots merged · ${g.malformed} malformed rows. GPS ${g.gps_points}, weather ${g.weather_samples}. Tire profile: ${g.tire||'unknown'}.`)+'</p><p>'+esc(g.hvac_recorded?'HVAC fields recorded; HVAC consumption not isolated.':'HVAC on/off was not recorded. / Nije snimljeno kada je klima bila uključena.')+'</p>';
 $('soc-model').innerHTML=D.references.map((r,i)=>`<option value="${i}">${esc(r.name)}</option>`).join('');
 $('annual-year').innerHTML='<option value="all">All imported dates / Svi datumi</option>'+[...new Set(g.monthly.map(m=>m.month.slice(0,4)))].map(y=>`<option>${y}</option>`).join('');
 showRaw();showMatrix();signals();fillTrips();drawSoc();drawHealth();showAnnual();
}
function showRaw(){let rows=g.soc_readings.filter(r=>$('raw-day').value==='all'||r.snapshot_utc.startsWith($('raw-day').value));const pages=Math.max(1,Math.ceil(rows.length/100));rawPage=Math.min(rawPage,pages-1);$('raw-page').textContent=`${rawPage+1} / ${pages} · ${rows.length} rows`;$('raw-prev').disabled=rawPage===0;$('raw-next').disabled=rawPage===pages-1;table('soc-readings',rows.slice(rawPage*100,rawPage*100+100),[['snapshot_utc','Snapshot UTC',0,'date'],['soc_percent','SOC %',3],['pack_voltage_v','Pack V',3],['mean_voltage_v','Mean group V',4],['min_voltage_v','Min V',4],['max_voltage_v','Max V',4],['current_a','Current A'],['battery_temp_c','Battery °C'],['voltage_utc','Voltage measured UTC',0,'date'],['voltage_age_s','Age s'],['unique_paired','Unique pair']]);}
function heat(v){if(v==null)return '#f0f4f5';const stops=[[-10,[216,233,246]],[0,[218,240,233]],[12,[239,241,214]],[25,[250,226,195]],[40,[244,204,186]]];let i=stops.findIndex(x=>x[0]>=v);if(i<=0)return `rgb(${stops[i===0?0:4][1]})`;let [a,c]=stops[i-1],[b,d]=stops[i],f=(v-a)/(b-a);return `rgb(${c.map((x,j)=>Math.round(x+(d[j]-x)*f))})`;}
function showMatrix(){
 const top=Math.max(120,...g.speed_grade.map(x=>x.speed_low));let rows='';
 for(let s=0;s<=top;s+=10){rows+=`<tr><th>${s===120?'120–130':s===130?'&gt;130–&lt;140':s+'–&lt;'+(s+10)}</th>`;for(let c=0;c<5;c++){let m=g.speed_grade.find(x=>x.speed_low===s&&x.grade_index===c);rows+=m?`<td class="${m.evidence==='limited'?'limited':''}" style="--cell:${heat(m.kwh_per_100km)}" title="${m.segments} segments · ${m.trips} trips"><strong>${fmt(m.kwh_per_100km,1)}</strong><span>${fmt(m.distance_km,1)} km · n=${m.segments}</span></td>`:'<td class="no-data"><strong>—</strong><span>No data / Nema</span></td>'}rows+='</tr>'}
 $('matrix').innerHTML='<div class="matrix-scroll"><table class="heatmap"><thead><tr><th>km/h</th><th>Down / Spust<br>&gt;3%</th><th>Down / Spust<br>1–3%</th><th>Level / Ravno<br>±1%</th><th>Up / Uspon<br>1–3%</th><th>Up / Uspon<br>&gt;3%</th></tr></thead><tbody>'+rows+'</tbody></table></div>';
}
function canvas(id,height){const c=$(id);if(height)c.style.height=height+'px';const r=c.getBoundingClientRect();if(r.width<2||r.height<2)return null;const d=devicePixelRatio||1;c.width=Math.round(r.width*d);c.height=Math.round(r.height*d);let ctx=c.getContext('2d');ctx.scale(d,d);ctx.font='12px Segoe UI, sans-serif';return [ctx,r.width,r.height];}
function plotGrid(ctx,w,h,xmin,xmax,ymin,ymax,yLabel){
 const l=65,r=28,t=30,b=45;const X=x=>l+(x-xmin)/(xmax-xmin||1)*(w-l-r),Y=y=>h-b-(y-ymin)/(ymax-ymin||1)*(h-t-b);
 ctx.fillStyle='#445e70';ctx.fillText(yLabel,8,15);ctx.textAlign='right';for(let i=0;i<=4;i++){let y=ymin+(ymax-ymin)*i/4,py=Y(y);ctx.strokeStyle='#e2eaef';ctx.beginPath();ctx.moveTo(l,py);ctx.lineTo(w-r,py);ctx.stroke();ctx.fillText(fmt(y,2),l-10,py+4)}ctx.textAlign='center';for(let i=0;i<=5;i++){let x=xmin+(xmax-xmin)*i/5;ctx.fillText(fmt(x,0),X(x),h-22)}ctx.textAlign='left';return {X,Y,l,r,t,b};
}
function refSoc(v,ref){if(!Number.isFinite(v)||v<ref.points[0][1]||v>ref.points.at(-1)[1])return null;for(let i=1;i<ref.points.length;i++){let [a,x]=ref.points[i-1],[b,y]=ref.points[i];if(v<=y)return a+(v-x)/(y-x)*(b-a)}return null}
function drawSoc(){
 const quiet=$('soc-subset').value==='quiet',ps=g.soc_pairs.filter(p=>!quiet||p.low_current),ref=D.references[+$('soc-model').value||0],probe=$('soc-probe').value.trim()===''?NaN:Number($('soc-probe').value);
 const c=canvas('soc-chart');if(c){const [ctx,w,h]=c,[minimum,maximum]=bounds(ps.map(p=>p.voltage_v));
 const min=Number.isFinite(minimum)?Math.min(3.3,minimum)-.03:3.2,max=Math.max(4.25,Number.isFinite(maximum)?maximum+.03:4.25);
 const {X,Y}=plotGrid(ctx,w,h,0,100,min,max,'Measured group voltage [V] / Izmereni napon');
 ctx.strokeStyle='#b54643';ctx.setLineDash([6,4]);ctx.beginPath();ctx.moveTo(X(0),Y(4.2));ctx.lineTo(X(100),Y(4.2));ctx.stroke();ctx.setLineDash([]);
 ctx.fillStyle='#a33634';ctx.textAlign='right';ctx.fillText('4.200 V · chosen comparison ceiling / referentna granica',w-25,Y(4.2)-7);ctx.textAlign='left';
 const step=Math.max(1,Math.ceil(ps.length/20000));for(let i=0;i<ps.length;i+=step){let p=ps[i];ctx.fillStyle=p.measurement_utc.startsWith(latest)?'#009b9270':'#c9893a42';ctx.beginPath();ctx.arc(X(p.soc_percent),Y(p.voltage_v),2.1,0,Math.PI*2);ctx.fill()}
 ctx.strokeStyle='#153449';ctx.lineWidth=3;ctx.beginPath();let prev=null;
 for(const b of g.soc_medians){let v=quiet?b.low_current_voltage_v:b.median_voltage_v;if(v==null){prev=null;continue}if(prev==null||b.soc_bin-prev>3)ctx.moveTo(X(b.soc_bin),Y(v));else ctx.lineTo(X(b.soc_bin),Y(v));prev=b.soc_bin}ctx.stroke();
 ctx.fillStyle='#173b55';ctx.textAlign='center';ctx.fillText('VEHICLE SOC [%] / SOC KOJI PRIKAZUJE VOZILO',w/2,h-3);ctx.textAlign='left';
 $('soc-summary').innerHTML=`<b>${ps.length} unique pairs / jedinstvenih parova</b><br><span class="hint">Median / Medijana ${fmt(median(ps.map(p=>p.voltage_v)),4)} V · ${quiet?'|I|≤5 A':'all loads / sva opterećenja'}${step>1?' · scatter sampled; all medians and CSV retained':''}</span>`;}
 const rc=canvas('reference-chart');if(rc){const [ctx,w,h]=rc,first=ref.points[0],last=ref.points.at(-1),{X,Y}=plotGrid(ctx,w,h,0,100,Math.min(2.45,first[1]-.06),Math.max(4.36,last[1]+.05),'Reference test voltage [V] / Napon ispitivanja');
 ctx.strokeStyle=refColors[+$('soc-model').value||0];ctx.lineWidth=3;ctx.beginPath();ref.points.forEach(([s,v],i)=>i?ctx.lineTo(X(s),Y(v)):ctx.moveTo(X(s),Y(v)));ctx.stroke();
 for(const [soc,v] of [first,last]){ctx.fillStyle=ctx.strokeStyle;ctx.beginPath();ctx.arc(X(soc),Y(v),5,0,Math.PI*2);ctx.fill()}
 ctx.strokeStyle='#b54643';ctx.setLineDash([6,4]);ctx.beginPath();ctx.moveTo(X(0),Y(4.2));ctx.lineTo(X(100),Y(4.2));ctx.stroke();ctx.setLineDash([]);ctx.fillStyle='#a33634';ctx.textAlign='right';ctx.fillText('4.200 V · comparison ceiling / izabrana granica',w-25,22);
 const converted=refSoc(probe,ref);if(converted!=null){ctx.strokeStyle='#147fa3';ctx.setLineDash([3,4]);ctx.beginPath();ctx.moveTo(X(0),Y(probe));ctx.lineTo(X(converted),Y(probe));ctx.lineTo(X(converted),Y(2.45));ctx.stroke();ctx.setLineDash([]);ctx.fillStyle='#147fa3';ctx.beginPath();ctx.arc(X(converted),Y(probe),5,0,Math.PI*2);ctx.fill()}
 ctx.fillStyle='#7448a0';ctx.textAlign='center';ctx.fillText('TEST SOC [%] / SOC PREMA SKALI ISPITIVANJA',w/2,h-3);ctx.textAlign='left';}
 table('soc-boundaries',D.references.map(r=>({name:r.name,empty:r.points[0][0]===0?r.points[0][1]:null,lowest:r.points[0][0],lowest_v:r.points[0][1],full:r.points.at(-1)[1],selected:4.2})),[['name','Reference / Ispitivanje'],['empty','Empty 0% / Prazna V',4],['full','Full 100% / Puna V',4],['lowest','First available %'],['lowest_v','First available V',4],['selected','Chosen full marker V',3]]);
 table('soc-mapping',D.references.map(r=>({name:r.name,voltage:Number.isFinite(probe)?probe:null,reference_soc:refSoc(probe,r)})),[['name','Voltage → reference SOC / Napon → SOC ispitivanja'],['voltage','Chosen V / Izabrano V',4],['reference_soc','TEST SOC % / SOC ISPITIVANJA %']]);
 const current=g.soc_endpoints.filter(x=>x.reference===ref.name);
 table('soc-endpoints',current,[['day_utc','Day UTC',0,'date'],['endpoint','Endpoint'],['vehicle_soc_median','Vehicle SOC %'],['median_voltage_v','Mean-group median V',4],['temperature_band','Battery °C'],['samples','n'],['reference_soc','TEST SOC %'],['reference_headroom_pp','Reference upper gap pp'],['reference_bottom_pp','Reference lower gap pp']]);
 if(!current.some(x=>x.endpoint==='lower'))$('soc-endpoints').innerHTML+='<p class="notice">Lower vehicle buffer: unavailable — no qualifying 0% endpoint. / Donji bafer vozila nije utvrđen; nema odgovarajućeg merenja pri 0% SOC.</p>';
 table('soc-conditions',g.soc_conditions.filter(x=>x.reference===ref.name),[['month','Month UTC'],['vehicle_soc_band','Vehicle SOC band %'],['vehicle_soc_median','Vehicle SOC median %'],['battery_temperature_band','Battery °C'],['load','Load / Struja'],['samples','n'],['median_voltage_v','Median V',4],['reference_soc','TEST SOC %'],['reference_minus_vehicle_pp','Test − vehicle pp']]);
}
function drawHealth(){
 const c=canvas('soh-chart');if(!c)return;const [ctx,w,h]=c,rs=g.health_daily.filter(d=>d.soh_median!=null);if(!rs.length)return;
 const a=Math.min(...rs.map(r=>r.soh_median)),b=Math.max(...rs.map(r=>r.soh_median));const {X,Y}=plotGrid(ctx,w,h,0,Math.max(1,rs.length-1),Math.max(0,a-1),Math.min(100,b+1),'Daily median BMS SOH [%]');
 // Dates replace numeric x ticks; a narrow explicit range reveals small changes without implying a large loss.
 ctx.fillStyle='white';ctx.fillRect(48,h-39,w-60,39);ctx.strokeStyle='#1b6988';ctx.lineWidth=2.5;ctx.beginPath();rs.forEach((r,i)=>i?ctx.lineTo(X(i),Y(r.soh_median)):ctx.moveTo(X(i),Y(r.soh_median)));ctx.stroke();
 rs.forEach((r,i)=>{ctx.fillStyle=r.day_utc===latest?'#00776f':'#a25a0a';ctx.beginPath();ctx.arc(X(i),Y(r.soh_median),4,0,Math.PI*2);ctx.fill();if(i===0||i===rs.length-1||rs.length<7){ctx.textAlign=i===0?'left':i===rs.length-1?'right':'center';ctx.fillText(r.day_utc,X(i),h-14)}});ctx.textAlign='left';
}
function signals(){selected=selected.filter(k=>g.stats[k]?.count);if(!selected.length)selected=['soc_percent'];$('signals').innerHTML=Object.entries(D.fields).filter(([k])=>g.stats[k].count).map(([k,[name,unit]])=>`<label><input type="checkbox" value="${k}" ${selected.includes(k)?'checked':''}> ${esc(name)} [${esc(unit)}]</label>`).join('');}
function fillTrips(){
 const min=+$('trip-length').value,rs=g.trips.map((t,i)=>({t,i})).filter(({t})=>(distance(t)||0)>=min).reverse();
 $('tripfilter').innerHTML=rs.map(({t,i})=>`<option value="${i}">${t.start_utc.slice(0,16).replace('T',' ')} UTC · ${fmt(distance(t),1)} km${t.start_utc.startsWith(latest)?' · LATEST':' · earlier'}</option>`).join('');
 if(rs.length){const chosen=rs.find(({t})=>(distance(t)||0)>=30)||rs[0];$('tripfilter').value=String(chosen.i);tripIndex=chosen.i}else tripIndex=null;
 table('trips',rs.map(x=>x.t),[['start_utc','Start UTC',0,'date'],['minutes','min'],['odometer_km','Odometer km'],['energy_kwh','Recorded kWh'],['coverage_percent','Coverage %'],['kwh_per_100km','kWh/100 km'],['soc_start','SOC start %'],['soc_end','SOC end %'],['capacity_estimate_kwh','Effective kWh']]);resetWindow();
}
function rangeInputs(){$('from').value=new Date(lo).toISOString().slice(0,19);$('to').value=new Date(hi).toISOString().slice(0,19)}
function resetWindow(){const t=tripIndex==null?null:g.trips[tripIndex];lo=t?Date.parse(t.start_utc):g.timeline[0].t;hi=t?Date.parse(t.end_utc):g.timeline.at(-1).t;if(hi<=lo)hi=lo+1000;cursorTime=null;pinned=false;rangeInputs();$('trip-summary').innerHTML=t?`${dateHTML(t.start_utc)} · <b>${fmt(distance(t),1)} km</b> · ${fmt(t.minutes,0)} min · <b>${fmt(t.kwh_per_100km)} kWh/100 km</b> · Coverage / Pokrivenost ${fmt(t.coverage_percent,1)}%`:'All recorded dates / Svi snimljeni datumi. Earlier days are amber; latest day is teal. / Stariji datumi su narandžasti, poslednji tirkizni.';drawSignals();}
function signalRange(k,list){const vals=list.map(r=>r[k]).filter(v=>v!=null);if(k==='soc_percent'||k==='soh_percent')return [0,100];let min=vals.length?bounds(vals)[0]:0,max=vals.length?bounds(vals)[1]:1;if(k==='vehicle_speed_kmh'){min=0;max=Math.max(10,Math.ceil(max/10)*10)}else{let pad=Math.max((max-min)*.12,Math.abs(max)*.0001,.005);min-=pad;max+=pad;if(k==='battery_power_kw'||k==='battery_current_a'){min=Math.min(0,min);max=Math.max(0,max)}}return [min,max];}
function drawSignals(){
 if(!$('trip-details').open)return;
 const overlay=$('mode').value==='overlay',keys=selected.filter(k=>g.stats[k].count).slice(0,overlay?2:6),c=canvas('chart',overlay?430:Math.max(230,keys.length*195+40));if(!c)return;
 const [ctx,w,h]=c,list=g.timeline.filter(r=>r.t>=lo&&r.t<=hi);const left=76,right=overlay?78:24,top=30,bottom=42,band=(h-top-bottom)/(overlay?1:keys.length||1);const X=t=>left+(t-lo)/(hi-lo)*(w-left-right);
 $('chart-note').textContent=overlay?'Two real axes: first selected signal on the left, second on the right. / Prvi podatak levo, drugi desno; ostali su u režimu panela.':'Shared time cursor, separate physical units. Up to 6 signals; each panel keeps its height. / Zajedničko vreme, stvarne jedinice, najviše 6 dovoljno visokih panela.';
 if(!keys.length){ctx.fillStyle='#526b7c';ctx.fillText('Choose signals / Izaberi podatke',30,60);$('legend').innerHTML='';return}
 let sample=null;if(cursorTime!=null){let best=Infinity;for(const r of list){let d=Math.abs(r.t-cursorTime);if(d<best){best=d;sample=r}}if(best>30000)sample=null}
 if(cursorTime==null&&list.length)sample=list.at(-1);
 keys.forEach((k,i)=>{
  const [min,max]=signalRange(k,list),y0=top+(overlay?0:i*band),Y=v=>y0+28+(max-v)/(max-min)*(band-45),color=palette[i];
  ctx.fillStyle=color;ctx.font='bold 12px Segoe UI';ctx.textAlign=overlay&&i===1?'right':'left';ctx.fillText(D.fields[k][0].split(' / ')[0]+' ['+D.fields[k][1]+']',overlay&&i===1?w-right: left,y0+12);ctx.font='12px Segoe UI';
  for(let n=0;n<=4;n++){let v=min+(max-min)*n/4,py=Y(v);if(!overlay||i===0){ctx.strokeStyle='#e2eaef';ctx.beginPath();ctx.moveTo(left,py);ctx.lineTo(w-right,py);ctx.stroke()}ctx.textAlign=overlay&&i===1?'left':'right';ctx.fillStyle=color;ctx.fillText(fmt(v,(max-min)<2?3:1),overlay&&i===1?w-right+10:left-10,py+4)}ctx.textAlign='left';
  ctx.save();ctx.beginPath();ctx.rect(left,y0+20,w-left-right,band-30);ctx.clip();ctx.strokeStyle=color;ctx.lineWidth=2;ctx.beginPath();let prev=null;
  for(const r of list){if(r[k]==null){prev=null;continue}if(!prev||r.t-prev.t>30000)ctx.moveTo(X(r.t),Y(r[k]));else ctx.lineTo(X(r.t),Y(r[k]));prev=r}ctx.stroke();
  if(sample&&sample[k]!=null){ctx.fillStyle=color;ctx.beginPath();ctx.arc(X(sample.t),Y(sample[k]),4,0,Math.PI*2);ctx.fill()}ctx.restore();
 });
 const ticks=w<650?3:5;ctx.font='12px Segoe UI';for(let i=0;i<=ticks;i++){let t=lo+(hi-lo)*i/ticks,s=date(t);ctx.fillStyle=s.startsWith(latest)?'#00776f':'#a25a0a';ctx.textAlign=i===0?'left':i===ticks?'right':'center';ctx.fillText(s.slice(11,19),X(t),h-21);ctx.fillText(s.slice(0,10),X(t),h-5)}ctx.textAlign='left';
 if(sample){ctx.strokeStyle='#15344960';ctx.setLineDash([4,3]);ctx.beginPath();ctx.moveTo(X(sample.t),top+20);ctx.lineTo(X(sample.t),h-bottom);ctx.stroke();ctx.setLineDash([])}
 $('tooltip').innerHTML=sample?dateHTML(date(sample.t))+` · ${pinned?'PINNED / ZAKLJUČANO':'UTC cursor / pokazivač'}`:'No sample near cursor / Nema bliskog uzorka';
 $('legend').innerHTML=keys.map((k,i)=>`<div class="signal-value" style="--c:${palette[i]}">${esc(D.fields[k][0])}<b>${fmt(sample?.[k],k.includes('voltage')?3:2)} <small>${esc(D.fields[k][1])}</small></b></div>`).join('');
}
function drawRoute(){if(!$('route-details').open)return;const ps=g.route;for(const id of ['route','elevation']){const c=canvas(id);if(!c)continue;let [ctx,w,h]=c;if(ps.length<2){ctx.fillStyle='#526b7c';ctx.fillText('Insufficient GPS / Nedovoljno GPS podataka',20,40);continue}let xs=[],ys=[],dist=0;ps.forEach((p,i)=>{if(id==='route'){xs.push(p.lon*Math.cos(ps[0].lat*Math.PI/180));ys.push(p.lat)}else{if(i&&p.t-ps[i-1].t<=120000)dist+=Math.hypot((p.lon-ps[i-1].lon)*Math.cos(p.lat*Math.PI/180),p.lat-ps[i-1].lat)*111.2;xs.push(dist);ys.push(p.alt)}});let valid=ys.filter(x=>x!=null),xmin=bounds(xs)[0],xmax=bounds(xs)[1],ymin=valid.length?bounds(valid)[0]:0,ymax=valid.length?bounds(valid)[1]:1,sx=(w-60)/(xmax-xmin||1),sy=(h-60)/(ymax-ymin||1);if(id==='route')sx=sy=Math.min(sx,sy);ctx.strokeStyle='#087f8c';ctx.lineWidth=2;ctx.beginPath();let prev=null;ps.forEach((p,i)=>{if(ys[i]==null){prev=null;return}let x=35+(xs[i]-xmin)*sx,y=h-25-(ys[i]-ymin)*sy;if(!prev||p.t-prev.t>120000)ctx.moveTo(x,y);else ctx.lineTo(x,y);prev=p});ctx.stroke();ctx.fillStyle='#526b7c';ctx.fillText(id==='route'?'N ↑ · GPS sketch / skica':`${fmt(ymin,0)}–${fmt(ymax,0)} m · 0–${fmt(dist,1)} km`,12,18)}}
function zoom(f){const span=Math.max(1000,Math.min((g.timeline.at(-1).t-g.timeline[0].t)||1000,(hi-lo)*f)),mid=cursorTime??(lo+hi)/2;lo=mid-span/2;hi=mid+span/2;rangeInputs();drawSignals()}
$('vehicle').innerHTML=D.vehicles.map((v,i)=>`<option value="${i}">${esc(v.name)}</option>`).join('');$('vehicle').onchange=activate;
$('raw-day').onchange=()=>{rawPage=0;showRaw()};$('raw-prev').onclick=()=>{rawPage--;showRaw()};$('raw-next').onclick=()=>{rawPage++;showRaw()};$('download-soc').onclick=()=>download(g.soc_readings,'soc-all-readings.csv');$('download-medians').onclick=()=>download(g.soc_medians,'soc-medians-references.csv');$('soc-subset').onchange=drawSoc;
$('trip-length').onchange=fillTrips;$('tripfilter').onchange=()=>{tripIndex=+$('tripfilter').value;resetWindow()};$('all-times').onclick=()=>{tripIndex=null;resetWindow()};$('reset').onclick=resetWindow;
$('trip-details').ontoggle=()=>{if($('trip-details').open)drawSignals()};$('route-details').ontoggle=drawRoute;$('mode').onchange=drawSignals;
$('signals').onchange=()=>{selected=Array.from($('signals').querySelectorAll(':checked')).map(x=>x.value);drawSignals()};
document.querySelectorAll('[data-preset]').forEach(b=>b.onclick=()=>{const presets={drive:['vehicle_speed_kmh','battery_power_kw','instant_consumption_kwh_100km'],battery:['soc_percent','cell_voltage_average_v','battery_current_a'],temperature:['battery_temp_avg_c','outside_temp_c','motor_coolant_temp_c']};selected=presets[b.dataset.preset];signals();drawSignals()});
$('apply').onclick=()=>{let a=Date.parse($('from').value+'Z'),b=Date.parse($('to').value+'Z');if(Number.isFinite(a)&&Number.isFinite(b)&&b>a){lo=a;hi=b;cursorTime=null;drawSignals()}};$('zoomin').onclick=()=>zoom(.5);$('zoomout').onclick=()=>zoom(2);
function pointerTime(e){const r=$('chart').getBoundingClientRect(),right=$('mode').value==='overlay'?78:24;return lo+(e.clientX-r.left-76)/Math.max(1,r.width-76-right)*(hi-lo)}
$('chart').onpointerdown=e=>{drag={x:e.clientX,lo,hi,moved:false};$('chart').setPointerCapture(e.pointerId)};
$('chart').onpointermove=e=>{if(drag){let dx=e.clientX-drag.x;if(Math.abs(dx)>4)drag.moved=true;if(drag.moved){let right=$('mode').value==='overlay'?78:24,shift=dx/Math.max(1,$('chart').clientWidth-76-right)*(drag.hi-drag.lo);lo=drag.lo-shift;hi=drag.hi-shift;rangeInputs()}}if(!pinned)cursorTime=pointerTime(e);drawSignals()};
$('chart').onpointerup=e=>{if(drag&&!drag.moved){pinned=!pinned;cursorTime=pointerTime(e)}drag=null;drawSignals()};$('chart').onpointercancel=()=>drag=null;$('chart').onpointerleave=()=>{if(!drag&&!pinned){cursorTime=null;drawSignals()}};
function redraw(){drawSoc();drawHealth();drawSignals();drawRoute();drawMonths()}window.addEventListener('resize',redraw);window.addEventListener('beforeprint',redraw);window.addEventListener('afterprint',redraw);activate();

function showAnnual(){
 const year=$('annual-year').value,months=g.monthly.filter(m=>year==='all'||m.month.startsWith(year)),km=months.reduce((a,m)=>a+m.distance_km,0),energy=months.reduce((a,m)=>a+m.net_kwh,0),cycles=months.reduce((a,m)=>a+(m.energy_equivalent_cycles||0),0),cs=g.cycle_summary;
 const cards=[['Covered driving / Pokrivene vožnje',fmt(km,0)+' km'],['Driving average / Prosek vožnje',fmt(km?100*energy/km:null)+' kWh/100'],['Recorded discharge / Snimljeno pražnjenje',fmt(months.reduce((a,m)=>a+m.discharge_kwh,0),1)+' kWh'],['FEC (kWh) / Puni ekvivalenti',fmt(cs.reference_kwh?cycles:null)]];
 $('annual-summary').innerHTML=cards.map(([l,v])=>`<div class="kpi"><small>${esc(l)}</small><div class="value">${esc(v)}</div></div>`).join('');
 table('monthly',months,[['month','Month UTC / Mesec'],['trips','Trips'],['covered_trips','≥95% coverage'],['distance_km','Covered km',1],['net_kwh','Driving kWh',1],['kwh_per_100km','kWh/100 km'],['outside_median_c','Outside °C'],['soh_median','SOH median %'],['capacity_median_kwh','Effective kWh'],['energy_equivalent_cycles','FEC (kWh)'],['covered_hours','Power hours']]);
 showConditions();drawMonths();showFecExample();
 const depth=new Map();for(const t of g.trips){if(t.coverage_percent<95||t.soc_drop==null||t.soc_drop<0||t.soc_drop>100)continue;const low=Math.min(90,Math.floor(t.soc_drop/10)*10);if(!depth.has(low))depth.set(low,[]);depth.get(low).push(t)}
 table('depth-windows',[...depth].sort((a,b)=>a[0]-b[0]).map(([low,ts])=>({band:low+'–'+(low+10),trips:ts.length,start:median(ts.map(t=>t.soc_start)),end:median(ts.map(t=>t.soc_end)),drop:median(ts.map(t=>t.soc_drop))})),[['band','Vehicle SOC fall pp / Pad SOC'],['trips','Trips / Vožnje'],['start','Median start SOC %'],['end','Median end SOC %'],['drop','Median drop pp']]);
 const labels={tires:'Winter − summer / Zimske − letnje',temperature:'Cold <5°C − 15–25°C / Hladno − umereno',rain:'Wet − dry / Kiša − suvo',snow:'Snow − dry / Sneg − suvo',hvac:'HVAC on − off / Klima uključena − isključena'};
 table('condition-matches',g.condition_matches.map(r=>({...r,effect:labels[r.effect]})),[['effect','Comparison / Poređenje'],['speed_band','km/h'],['pairs','Pairs / Parovi'],['treated_km','Condition km'],['control_km','Control km'],['delta_kwh_100km','Δ kWh/100 km'],['delta_percent','Δ %'],['evidence','Evidence / Dokaz']]);
 $('cycle-summary').innerHTML=`<p>Fixed reference / Fiksna osnova: <b>${fmt(cs.reference_kwh,1)} kWh</b> · ${esc(cs.reference_basis)}.</p><p>All imported dates / Svi datumi: <b>${fmt(cs.recorded_energy_efc)} FEC (kWh)</b> from ${fmt(cs.recorded_discharge_kwh,1)} kWh recorded discharge. Counter-covered intervals / Intervali brojača: <b>${fmt(cs.counter_energy_efc)}</b> cycles (${fmt(cs.counter_discharge_kwh,1)} kWh); ${cs.counter_resets} counter decreases / padova brojača, ${cs.rejected_counter_intervals} rejected jumps. A decrease or implausible jump disables the cumulative counter estimate. / Pad ili skok brojača isključuje njegov zbir. Neither series is asserted to be the complete lifetime cycle count. / Nijedan zbir nije automatski ukupan broj ciklusa od proizvodnje.</p>`;
 const capacity=cs.reference_kwh;table('capacity-reference',[100,90,80,70].map(percent=>({percent,kwh:capacity?capacity*percent/100:null})),[['percent','Reference retained energy / Preostala referentna energija %'],['kwh','Equivalent kWh / Ekvivalent kWh']]);
 $('aging-sources').innerHTML=D.aging_research.map(r=>`<div class="reference-item" style="--c:#21788e"><b><a href="${esc(r.url)}" target="_blank" rel="noreferrer">${esc(r.title)}</a></b><p>${esc(r.finding)}</p><small>${esc(r.limit)}</small></div>`).join('');
}
function showConditions(){table('condition-table',g.conditions.filter(r=>r.year===$('annual-year').value&&r.factor===$('condition-factor').value),[['condition','Condition / Uslov'],['kwh_per_100km','kWh/100 km'],['distance_km','km',1],['segments','Segments'],['trips','Trips'],['median_speed','Median km/h'],['median_outside_c','Median outside °C']])}
function drawMonths(){const c=canvas('monthly-chart');if(!c||!g)return;const [ctx,w,h]=c,year=$('annual-year').value,ms=g.monthly.filter(m=>year==='all'||m.month.startsWith(year));if(!ms.length)return;
 const max=Math.max(20,...ms.map(m=>m.kwh_per_100km||0))*1.15,{X,Y}=plotGrid(ctx,w,h,0,ms.length,0,max,'Driving energy / Potrošnja [kWh/100 km]');ctx.fillStyle='white';ctx.fillRect(48,h-40,w-55,40);
 const width=Math.max(3,Math.min(58,(w-100)/ms.length*.62));ms.forEach((m,i)=>{const x=X(i+.5);ctx.fillStyle=m.month===latest.slice(0,7)?'#138e85':'#c68c45';if(m.kwh_per_100km!=null){ctx.fillRect(x-width/2,Y(m.kwh_per_100km),width,Y(0)-Y(m.kwh_per_100km));ctx.textAlign='center';ctx.fillStyle='#153449';ctx.fillText(fmt(m.kwh_per_100km,1),x,Y(m.kwh_per_100km)-8)}ctx.textAlign='center';if(ms.length<18||i===0||i===ms.length-1||i%3===0)ctx.fillText(m.month,x,h-15)});ctx.textAlign='left';}
$('soc-model').onchange=drawSoc;$('soc-probe').oninput=drawSoc;
$('download-soc-conditions').onclick=()=>download(g.soc_conditions,'soc-by-conditions.csv');
$('annual-year').onchange=showAnnual;$('condition-factor').onchange=showConditions;
$('download-monthly').onclick=()=>download(g.monthly,'monthly-report.csv');

function showFecExample(){const inputs=['fec-low','fec-high','fec-count'].map(id=>$(id).value.trim()),[a,b,n]=inputs.map(Number);if(inputs.some(x=>x==='')||![a,b,n].every(Number.isFinite)||a<0||b>100||a>=b||n<0||n>1000000){$('fec-example').textContent='Enter 0 ≤ lower < upper ≤ 100 and a non-negative cycle count. / Proveri SOC opseg i broj ciklusa.';return}const dod=b-a;$('fec-example').innerHTML=`<b>${fmt(n)} × ${fmt(dod)}% DoD = ${fmt(n*dod/100,3)} FEC</b><br>${fmt(a)}–${fmt(b)}% SOC · ${fmt(100/dod,2)} such partial cycles per nominal full cycle / takvih delimičnih ciklusa za jedan puni ekvivalent.`;}
for(const id of ['fec-low','fec-high','fec-count'])$(id).oninput=showFecExample;
