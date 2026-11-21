/* Offline presentation translation only: never touches measurement objects or DOM identifiers. */
(() => {
  if (new URLSearchParams(location.search).get('lang') === 'sr') return;
  document.documentElement.lang = 'en';
  fetch('i18n-en.json').then(r => { if (!r.ok) throw Error('catalogue'); return r.json(); }).then(catalog => {
    const slot = /\{(\d+)\}/g, escape = s => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    const templates = Object.entries(catalog).filter(([s]) => /\{\d+\}/.test(s))
      .sort((a,b) => b[0].replace(slot,'').length - a[0].replace(slot,'').length)
      .map(([source,target]) => {
        const slots=[];let last=0,pattern='^';
        for(const m of source.matchAll(slot)){pattern+=escape(source.slice(last,m.index))+'([\\s\\S]*?)';slots.push(m[1]);last=m.index+m[0].length;}
        return {pattern:new RegExp(pattern+escape(source.slice(last))+'$'),slots,target};
      });
    const literals = Object.keys(catalog).filter(s => s.length>=3 && !/\{\d+\}/.test(s)).sort((a,b)=>b.length-a.length);
    const phrases=new RegExp('(?<![\\p{L}\\p{N}_])(?:'+literals.map(escape).join('|')+')(?![\\p{L}\\p{N}_])','gu');
    function translate(source,depth=0){
      if(Object.hasOwn(catalog,source))return catalog[source];
      if(depth>4)return source;
      for(const t of templates){const m=source.match(t.pattern);if(!m)continue;const args={};t.slots.forEach((s,i)=>args[s]=m[i+1]);return t.target.replace(slot,(_,s)=>args[s]===source?source:translate(args[s]||'',depth+1));}
      for(const sep of ['\n',' • ',' · '])if(source.includes(sep))return source.split(sep).map(s=>translate(s,depth+1)).join(sep);
      return source.replace(phrases,m=>catalog[m]);
    }
    function translateText(value){const trim=value.trim();return trim ? value.replace(trim,()=>translate(trim)) : value;}
    let queued=false;
    const observer=new MutationObserver(()=>{if(!queued){queued=true;requestAnimationFrame(apply);}});
    function apply(){
      queued=false;observer.disconnect();
      const walker=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT);
      while(walker.nextNode()) {const n=walker.currentNode;if(['SCRIPT','STYLE'].includes(n.parentElement?.tagName))continue;const out=translateText(n.nodeValue);if(out!==n.nodeValue)n.nodeValue=out;}
      document.querySelectorAll('[aria-label],[title],[alt]').forEach(el=>{for(const a of ['aria-label','title','alt'])if(el.hasAttribute(a)){const before=el.getAttribute(a),after=translateText(before);if(before!==after)el.setAttribute(a,after);}});
      document.title=translate(document.title);
      observer.observe(document.body,{childList:true,subtree:true,characterData:true,attributes:true,attributeFilter:['aria-label','title','alt']});
    }
    window.translatePackText=translate;
    apply();
  }).catch(()=>{ document.documentElement.lang='sr-Latn'; });
})();
