/* Original photo coordinates are user-confirmed. M↔B is explicitly a working association. */
(() => {
 'use strict';
 const $=s=>document.querySelector(s), ns='http://www.w3.org/2000/svg';
 const q=new URLSearchParams(location.search), native=q.get('native')==='1';
 let mode=Number(q.get('mode')||0), selected=Math.min(18,Math.max(1,Number(q.get('module')||1))), zoom=1;
 if(![0,1,2].includes(mode)) mode=0;
 let reading={}, archive=false, data={v:[],t:[],h:[]};
 const pad=n=>String(n).padStart(2,'0'), valid=n=>typeof n==='number'&&Number.isFinite(n);
 const fmt=(n,d=1)=>valid(n)?n.toFixed(d):'—';
 const min=a=>a.length?Math.min(...a):null, max=a=>a.length?Math.max(...a):null;
 const avg=a=>a.length?a.reduce((s,n)=>s+n,0)/a.length:null;
 const sum=a=>a.length?a.reduce((s,n)=>s+n,0):null;
 const slice=(a,id,width)=>a.slice((id-1)*width,id*width);
 function fresh(at,ttl=120000){const now=archive?reading.timestamp_ms:Date.now();return valid(at)&&valid(now)&&now-at>=0&&now-at<=ttl;}
 function array(key,time,count,lo,hi,ttl){const a=reading[key];return fresh(reading[time],ttl)&&Array.isArray(a)&&a.length===count&&a.every(n=>valid(n)&&n>=lo&&n<=hi)?a:[];}
 function scalar(key,time='timestamp_ms'){return fresh(reading[time])&&valid(reading[key])?reading[key]:null;}
 function values(id){return {v:slice(data.v,id,6),t:slice(data.t,id,3),h:slice(data.h,id,6)};}
 function current(id){const b=values(id);return mode===0?min(b.v):mode===1?max(b.t):min(b.h);}
 function label(id){return fmt(current(id),mode===0?3:1)+(mode===0?' V':mode===1?' °C':' %');}
 const metricNames=['Najniži napon grupe','Najviša temp. sonde','Najmanji SOH grupe'];
 function svg(tag,attrs={},text){const el=document.createElementNS(ns,tag);for(const [k,v] of Object.entries(attrs))el.setAttribute(k,v);if(text!==undefined)el.textContent=text;return el;}
 function part(parent,tag,attrs,text){const n=svg(tag,attrs,text);parent.append(n);return n;}
 const diagram=$('#diagram'), photo={x:115,y:125,w:970,h:970*425/794};
 function anchor(id){const p=window.packPositions.points[id];return {x:photo.x+p.x*photo.w,y:photo.y+p.y*photo.h};}
 function select(id,scroll=false){selected=id;render();if(scroll)$('#detail').scrollIntoView({behavior:'smooth',block:'start'});}
 function activate(el,id){el.addEventListener('click',()=>select(id,true));el.addEventListener('keydown',e=>{if(e.key==='Enter'||e.key===' '){e.preventDefault();select(id,true);}});}
 const upper=[1,6,7,2,3,9,10,11],lower=[4,5,8,18,17,12,13,16,14,15];
 function draw(){
  diagram.replaceChildren();
  part(diagram,'rect',{width:1200,height:850,fill:'#080f17'});
  part(diagram,'image',{href:'original.png',x:photo.x,y:photo.y,width:photo.w,height:photo.h});
  const leaders=part(diagram,'g'),cards=part(diagram,'g'),dots=part(diagram,'g');
  for(const [ids,top] of [[upper,true],[lower,false]]) ids.forEach((id,j)=>{
   const w=top?135:108,gap=top?12:10,tot=ids.length*w+(ids.length-1)*gap;
   const x=(1200-tot)/2+j*(w+gap),y=top?24:735,h=83,a=anchor(id),endY=top?y+h:y;
   part(leaders,'path',{class:'leader'+(id===selected?' active':''),d:`M${a.x} ${a.y} L${a.x} ${top?a.y-35:a.y+35} L${x+w/2} ${top?endY+22:endY-22} L${x+w/2} ${endY}`});
   const g=part(cards,'g',{class:'callout'+(id===selected?' active':''),tabindex:0,role:'button','aria-label':`Modul ${id}, ${metricNames[mode]} ${label(id)}`,'data-module':id});
   part(g,'rect',{x,y,width:w,height:h,rx:10});
   part(g,'text',{x:x+12,y:y+22,fill:'#eaf5fb','font-size':14,'font-weight':700},`M${pad(id)}`);
   part(g,'text',{x:x+w-10,y:y+22,fill:'#99a9b2','font-size':10,'text-anchor':'end'},`B${pad(id)}*`);
   part(g,'text',{x:x+12,y:y+49,fill:valid(current(id))?(mode===1?'#edc078':'#63ddeb'):'#9baebd','font-size':20,'font-weight':600},label(id));
   part(g,'text',{x:x+12,y:y+69,fill:'#9baebd','font-size':10},mode===0?'MIN NAPON':mode===1?'MAX TEMP.':'MIN SOH');activate(g,id);
   const dot=part(dots,'g',{class:'marker'+(id===selected?' active':''),tabindex:0,role:'button','aria-label':`Pozicija modula ${id}`});
   part(dot,'circle',{cx:a.x,cy:a.y,r:id===selected?15:11});part(dot,'text',{x:a.x,y:a.y},id);activate(dot,id);
  });
  const components=[['OBC',20,190,'OBC · punjač',scalar('obc_temp_c','vcu_extra_at_ms')],['MOTOR',20,273,'Motor · stator',scalar('motor_temp_c','vcu_extra_at_ms')],['BMS',20,356,'BMS · položaj',null]];
  components.forEach(([id,x,y,title,value])=>{const a=anchor(id),g=part(diagram,'g',{class:'component'});part(g,'line',{x1:x+165,y1:y+28,x2:a.x,y2:a.y});part(g,'circle',{cx:a.x,cy:a.y,r:4,fill:'#edc078'});part(g,'rect',{x,y,width:165,height:59,rx:8});part(g,'text',{x:x+12,y:y+22},title);part(g,'text',{x:x+12,y:y+44,style:'fill:#edc078;font-size:17px'},id==='BMS'?'oznaka na slici':fmt(value)+' °C');});
  [['Spolja',scalar('outside_temp_c','vcu_extra_at_ms')],['Tečnost · ulaz u motor',scalar('motor_coolant_temp_c','vcu_extra_at_ms')],['Baterija · BMS prosek',scalar('battery_temp_avg_c')]].forEach(([title,value],i)=>{const x=200+i*285,y=660;part(diagram,'rect',{x,y,width:270,height:52,rx:8,fill:'#101f2a',stroke:'#2e414d'});part(diagram,'text',{x:x+12,y:y+20,fill:'#a6bac8','font-size':12},title);part(diagram,'text',{x:x+12,y:y+41,fill:'#edc078','font-size':17},fmt(value)+' °C');});
 }
 function metric(title,value,unit='',type=''){return `<div class="metric ${type}"><small>${title}</small><strong>${value}</strong><em>${unit}</em></div>`;}
 function detail(){
  const b=values(selected),lo=min(b.v),hi=max(b.v),sh=min(b.h),g0=(selected-1)*6+1,t0=(selected-1)*3+1;
  $('#detail-title').textContent=`Modul ${pad(selected)} · BMS blok B${pad(selected)}*`;
  $('#module-metrics').innerHTML=metric('Najniži napon grupe',fmt(lo,3),'V','primary')+metric('Razlika min–max',fmt(lo!==null&&hi!==null?(hi-lo)*1000:null,0),'mV')+metric('Zbir 6 naponskih grupa',fmt(sum(b.v),3),'V')+metric('Srednji napon grupe',fmt(avg(b.v),3),'V')+metric('Najmanji SOH grupe',fmt(sh),'%','warn')+metric('Najviša temp. sonde',fmt(max(b.t)),'°C');
  $('#probes').innerHTML=[0,1,2].map(i=>`<div class="probe"><small>Sonda T${pad(t0+i)}</small>${fmt(b.t[i])} °C</div>`).join('');
  $('#group-range').textContent=`B${pad(selected)} · G${g0}–G${g0+5} · 6s2p`;
  $('#cells').innerHTML=Array.from({length:6},(_,i)=>{const v=b.v[i],h=b.h[i],lowest=valid(v)&&v===lo;return `<article class="cell ${lowest?'lowest':''}"><header><span>G${String(g0+i).padStart(3,'0')}</span>${lowest?'<b>MIN V</b>':''}</header><div class="pair"><span>Ć${pad(i*2+1)}</span><span>Ć${pad(i*2+2)}</span></div><strong>${fmt(v,3)} V</strong><div class="health">SOH ${fmt(h)} %</div><div class="bar"><i style="width:${valid(h)?Math.min(100,Math.max(0,h)):0}%"></i></div></article>`;}).join('');
  $('#weakest').textContent=sh!==null?`Najmanji SOH: G${g0+b.h.indexOf(sh)} · ${fmt(sh)} %`:'SOH po grupama: čeka svež odgovor BMS-a';
 }
 function summary(){
  const items=mode===0?[['SOC paketa',scalar('soc_percent'),1,'%'],['Srednji napon',avg(data.v),3,'V'],['Najniži napon',min(data.v),3,'V'],['Razlika min–max',data.v.length?(max(data.v)-min(data.v))*1000:null,0,'mV']]:mode===1?[['SOC paketa',scalar('soc_percent'),1,'%'],['Srednja temp.',avg(data.t),1,'°C'],['Najhladnija',min(data.t),1,'°C'],['Najtoplija',max(data.t),1,'°C']]:[['SOH paketa',scalar('soh_percent'),1,'%'],['Najmanji SOH',min(data.h),1,'%'],['Srednji SOH',avg(data.h),1,'%'],['Najveći SOH',max(data.h),1,'%']];
  $('#summary').innerHTML=items.map(([name,val,d,u])=>`<div class="stat"><small>${name}</small><strong>${fmt(val,d)}</strong><em>${u}</em></div>`).join('');
  const thermal=[['Motor · stator','motor_temp_c','vcu_extra_at_ms','na pogonskom sklopu'],['OBC · punjač','obc_temp_c','vcu_extra_at_ms','iznad motora'],['Baterija · prosek','battery_temp_avg_c','timestamp_ms','očitavanje BMS-a'],['Spolja','outside_temp_c','vcu_extra_at_ms','spoljašnji senzor'],['Tečnost · ulaz u motor','motor_coolant_temp_c','vcu_extra_at_ms','rashladni krug'],['Tečnost · pogonski krug','traction_coolant_temp_c','vcu_extra_at_ms','rashladni krug'],['Tečnost · grejanje','heating_coolant_temp_c','vcu_extra_at_ms','krug grejanja'],['Inverter','inverter_temp_c','inverter_at_ms','pogonska elektronika'],['DC/DC','dcdc_temp_c','vcu_extra_at_ms','naponski pretvarač']];
  $('#thermal').innerHTML=thermal.map(([name,key,time,note])=>`<div class="thermal"><div>${name}<small>${note}</small></div><strong>${fmt(scalar(key,time))} °C</strong></div>`).join('');
 }
 function render(){
  document.querySelectorAll('[data-mode]').forEach(b=>{b.classList.toggle('active',Number(b.dataset.mode)===mode);b.setAttribute('aria-pressed',String(Number(b.dataset.mode)===mode));});
  draw();summary();detail();
  $('#mobile-modules').innerHTML=Array.from({length:18},(_,i)=>`<button data-pick="${i+1}" class="${selected===i+1?'active':''}" aria-label="Otvori modul ${i+1}"><span>M${pad(i+1)}*</span><strong>${label(i+1)}</strong></button>`).join('');
  document.querySelectorAll('[data-pick]').forEach(b=>b.onclick=()=>select(Number(b.dataset.pick),true));
 }
 function accept(r,isArchive=false){
  reading=r&&typeof r==='object'?r:{};archive=isArchive;
  data={v:array('cell_voltages_v','cell_voltages_at_ms',108,0,5,120000),t:array('probe_temperatures_c','probe_temperatures_at_ms',54,-40,120,120000),h:array('cell_soh_percent','cell_soh_at_ms',108,0,100,600000)};
  const counts=`${data.v.length}/108 napona · ${data.t.length}/54 sondi · ${data.h.length}/108 SOH`;
  $('#source').textContent=archive?`Tvoje merenje · ${new Date(reading.timestamp_ms).toLocaleString(q.get('lang')==='sr'?'sr-Latn-RS':'en-GB')} · ${counts}`:fresh(reading.timestamp_ms)?`OBD očitavanje · ${counts}`:'Čeka sveže OBD očitavanje';
  render();
 }
 window.setPackReading=(r,saved=false)=>accept(r,saved);
 document.querySelectorAll('[data-mode]').forEach(b=>b.onclick=()=>{mode=Number(b.dataset.mode);render();});
 $('#previous').onclick=()=>select(selected===1?18:selected-1);$('#next').onclick=()=>select(selected===18?1:selected+1);
 document.querySelectorAll('[data-photo]').forEach(b=>b.onclick=()=>{$('#module-photo').src=b.dataset.photo;});
 function setZoom(z){zoom=Math.min(4,Math.max(1,z));diagram.style.width=100*zoom+'%';$('#reset').textContent=zoom===1?'Cela slika':Math.round(zoom*100)+'%';}
 $('#more').onclick=()=>setZoom(zoom+.5);$('#less').onclick=()=>setZoom(zoom-.5);$('#reset').onclick=()=>setZoom(1);
 $('#fullscreen').onclick=()=>{const p=$('.map-panel');if(document.fullscreenElement)document.exitFullscreen();else if(p.requestFullscreen)p.requestFullscreen().catch(()=>setZoom(2));else setZoom(2);};
 const vp=$('#viewport');let drag=null;
 vp.addEventListener('pointerdown',e=>{if(e.pointerType==='mouse'&&zoom>1){drag={x:e.clientX,y:e.clientY,left:vp.scrollLeft,top:vp.scrollTop};}});
 vp.addEventListener('pointermove',e=>{if(drag&&e.buttons){vp.scrollLeft=drag.left+drag.x-e.clientX;vp.scrollTop=drag.top+drag.y-e.clientY;}});window.addEventListener('pointerup',()=>drag=null);
 let pinch=null;
 vp.addEventListener('touchstart',e=>{if(e.touches.length===2){const [a,b]=e.touches;pinch={distance:Math.hypot(a.clientX-b.clientX,a.clientY-b.clientY),zoom};}},{passive:true});
 vp.addEventListener('touchmove',e=>{if(pinch&&e.touches.length===2){e.preventDefault();const [a,b]=e.touches;setZoom(pinch.zoom*Math.hypot(a.clientX-b.clientX,a.clientY-b.clientY)/pinch.distance);}},{passive:false});
 vp.addEventListener('touchend',()=>pinch=null,{passive:true});
 if(native){$('#back').hidden=false;$('#stop-exit').hidden=false;$('#edit').hidden=true;}else{fetch('/api/reading').then(r=>r.ok?r.json():null).then(r=>r&&accept(r,true)).catch(()=>{});}
 accept({});setInterval(()=>{if(!archive)accept(reading,false);},5000);
})();
