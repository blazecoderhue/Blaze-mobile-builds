const {JSDOM}=require('jsdom'); const S=require('./out/scripts.json');
const html=`<html><head><title>T</title></head><body><h1>Login Page</h1><form><input id=u name=username placeholder="Username"><input id=p type=password name=password placeholder="Password"><button type=submit>Login</button></form><a href="/x">Elemental</a><div id=list></div></body></html>`;
const dom=new JSDOM(html,{runScripts:'outside-only',url:'https://the-internet.herokuapp.com/login'});
const w=dom.window, posts=[];
w.Element.prototype.getBoundingClientRect=function(){return {left:10,top:10,width:100,height:30,right:110,bottom:40};};
Object.defineProperty(w.HTMLElement.prototype,'innerText',{get(){return this.textContent;},set(v){this.textContent=v;}});
w.document.elementFromPoint=()=>null; w.innerHeight=800; w.innerWidth=400;
let resolveFetch; w.fetch=function(){return new Promise(r=>{resolveFetch=r;});};
w.blazePhoneBridge={postMessage:s=>posts.push(JSON.parse(s))};
w.eval(S.bridge);
const wait=ms=>new Promise(r=>setTimeout(r,ms));
function ok(c,m){ if(!c){console.log('FAIL',m); process.exitCode=1;} else console.log('ok',m); }
(async()=>{
  await wait(60);
  ok(posts.length===1 && posts[0].full && posts[0].k==='TOK','first message is full with token');
  const f=posts[0]; ok(f.set.length===4 && f.order.length===4,'4 nodes: '+f.set.map(n=>n.role+':'+n.name).join(','));
  ok(f.pw===true && f.text.includes('Login Page') && f.headings[0]==='Login Page','pw/text/headings');
  ok(f.set.every(n=>n.x===undefined),'no positions in bridge nodes');
  // typing
  const u=w.document.getElementById('u'); u.focus(); u.value='tomsmith'; u.dispatchEvent(new w.Event('input',{bubbles:true}));
  await wait(60);
  const d1=posts[posts.length-1]; ok(d1.seq===2 && !d1.full,'diff seq 2');
  ok(d1.set && d1.set.length===1 && d1.set[0].value==='tomsmith' && d1.set[0].len===8,'value diff only the field');
  ok(d1.focus && d1.focus.id===d1.set[0].id && d1.focus.submit==='Login' && d1.focus.inForm,'focus info');
  ok(d1.text===undefined,'no text resend on value change');
  // password masked
  const p=w.document.getElementById('p'); p.value='secret'; p.dispatchEvent(new w.Event('input',{bubbles:true})); await wait(60);
  const d2=posts[posts.length-1]; ok(d2.set[0].value==='(filled, hidden)' && d2.set[0].len===6 && !JSON.stringify(posts).includes('secret"'),'password masked');
  // add element (mutation)
  const n0=posts.length; const b=w.document.createElement('button'); b.textContent='Book now'; w.document.getElementById('list').appendChild(b); await wait(60);
  const d3=posts[posts.length-1]; ok(posts.length===n0+1 && d3.set.length===1 && d3.set[0].name==='Book now' && d3.order.length===5 && d3.text.includes('Book now'),'added node diff + text');
  // clone keeps a fresh id
  const c=b.cloneNode(true); w.document.getElementById('list').appendChild(c); await wait(60);
  const d4=posts[posts.length-1]; ok(d4.set.length===1 && d4.set[0].id!==d3.set[0].id,'clone gets a fresh id '+d4.set[0].id+' vs '+d3.set[0].id);
  // remove
  b.remove(); c.remove(); await wait(60);
  const d5=posts[posts.length-1]; ok(d5.del && d5.del.length===2,'removed nodes');
  // own attribute changes alone post nothing
  const n1=posts.length; w.document.getElementById('u').setAttribute('data-bz-secret','1'); await wait(60);
  ok(posts.length===n1+1 ? posts[posts.length-1].set[0].marked===true : false,'marking posts the marked flag');
  // net
  const n2=posts.length; w.fetch('/api'); await wait(60); ok(posts[posts.length-1].net===1,'fetch counted');
  resolveFetch({}); await wait(60); ok(posts[posts.length-1].net===0,'fetch done');
  // seq contiguous
  const seqs=posts.map(x=>x.seq); ok(seqs.every((s,i)=>s===i+1),'seq contiguous '+seqs.join(','));
  // full()
  const full=w.eval(S.bridge.replace(/[\s\S]*/,'window.__bzpB.full()'));
  ok(full.full && full.set.length===4 && full.k===undefined && full.seq===seqs.length+1,'full() resync, no token');
  // second inject is a no-op
  const n3=posts.length; w.eval(S.bridge); await wait(60); ok(posts.length===n3,'second inject no-op');
  // snapshot + locate share ids
  const snap=w.eval(S.snapshot); ok(snap.nodes.length===4 && snap.nodes[0].id===f.set[0].id && snap.nodes[0].x!==undefined,'snapshot same ids, positions');
  const loc=w.eval(S.locate); ok(loc && loc.label==='Login' && loc.role==='button','locate one call: '+JSON.stringify(loc&&loc.label));
  // page-set bogus id is replaced
  const evil=w.document.createElement('button'); evil.textContent='Evil'; evil.setAttribute('data-bzp','x"]);alert(1);//'); w.document.body.appendChild(evil); await wait(60);
  const d6=posts[posts.length-1]; ok(d6.set.some(n=>n.name==='Evil' && /^e\d+$/.test(n.id)),'page-set id replaced');
  // iframe does not run bridge
  console.log('done', posts.length,'posts');
})();
