// An asset used more than once is inlined ONCE: the first use carries the
// data: URI and an id, every later use carries data-from="<id>". Re-point the
// copies before anything else runs, so a decoder never sees an empty src.
document.querySelectorAll('[data-from]').forEach(e=>{
  const s=document.getElementById(e.dataset.from);
  if(s&&s.src){e.src=s.src;}
});

  const slides=[...document.querySelectorAll('.slide')];
  const nav=document.getElementById('nav'), pageno=document.getElementById('pageno');
  let i=0;
  slides.forEach((_,k)=>{
    const d=document.createElement('button');
    d.className='dot';d.setAttribute('aria-label','Go to slide '+(k+1));
    d.onclick=()=>show(k);nav.appendChild(d);
  });
  const dots=[...nav.children];
  function show(k){
    i=(k+slides.length)%slides.length;
    slides.forEach((s,j)=>s.classList.toggle('on',j===i));
    dots.forEach((d,j)=>d.classList.toggle('on',j===i));
    pageno.textContent=(i+1)+' / '+slides.length;
    slides[i].scrollTop=0;
    // a <video> that is off-screen keeps decoding; play only what is shown
    slides.forEach((s,j)=>s.querySelectorAll('video').forEach(v=>{
      if(j===i){const p=v.play();if(p&&p.catch)p.catch(()=>{});}else{v.pause();}
    }));
    try{history.replaceState(null,'','#'+(i+1))}catch{
      // A browser may refuse history updates for a locally opened document.
      // The visible slide and page number still update above.
    }
  }
  function go(d){show(i+d)}
  addEventListener('keydown',e=>{
    if(e.key==='ArrowRight'||e.key==='PageDown'||e.key===' ')go(1);
    if(e.key==='ArrowLeft'||e.key==='PageUp')go(-1);
    if(e.key==='Home')show(0); if(e.key==='End')show(slides.length-1);
  });
  let tx=null,ty=null;
  addEventListener('touchstart',e=>{tx=e.touches[0].clientX;ty=e.touches[0].clientY},{passive:true});
  addEventListener('touchend',e=>{
    if(tx===null)return;
    const dx=e.changedTouches[0].clientX-tx, dy=e.changedTouches[0].clientY-ty;
    if(Math.abs(dx)>60 && Math.abs(dx)>Math.abs(dy)*1.5)go(dx<0?1:-1);
    tx=ty=null;
  },{passive:true});
  addEventListener('beforeprint',()=>document.querySelectorAll('video').forEach(v=>v.pause()));
  const h=parseInt((location.hash||'').slice(1));
  show(Number.isFinite(h)&&h>=1?h-1:0);
  addEventListener('hashchange',()=>{
    const k=parseInt((location.hash||'').slice(1));
    if(Number.isFinite(k)&&k>=1&&k<=slides.length&&k-1!==i)show(k-1);
  });
