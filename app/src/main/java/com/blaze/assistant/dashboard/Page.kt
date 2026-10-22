package com.blaze.assistant.dashboard

/** The dashboard web page (served by Dashboard). */
object Page {
    val html = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Blaze dashboard</title>
<style>
:root{--paper:#fcfcfc;--ink:#111110;--sub:#6b6b68;--weak:#a5a5a0;--hair:#e6e6e2;--faint:#f3f3f0;--accent:#1f4bd8}
@media (prefers-color-scheme:dark){:root{--paper:#26262a;--ink:#f0f0ee;--sub:#a9a9a4;--weak:#77777a;--hair:#3a3a3f;--faint:#2e2e33;--accent:#6b8cff}}
*{box-sizing:border-box;border-radius:0}body{margin:0;background:var(--paper);color:var(--ink);font:15px/1.5 Inter,system-ui,sans-serif}
.lab{font:11px ui-monospace,Menlo,monospace;letter-spacing:.14em;text-transform:uppercase;color:var(--sub)}
header{display:flex;align-items:center;gap:12px;padding:18px 24px;border-bottom:1px solid var(--hair)}
.mark{width:22px;height:22px;background:var(--accent)}h1{font:600 18px "Inter Tight",Inter,sans-serif;margin:0}
main{display:grid;grid-template-columns:260px 1fr 340px;min-height:calc(100vh - 59px)}
@media (max-width:1000px){main{grid-template-columns:1fr}}
aside,section{border-right:1px solid var(--hair);padding:18px}
.chat{padding:10px 8px;border-bottom:1px solid var(--hair);cursor:pointer}.chat:hover,.chat.on{background:var(--faint)}
.chat b{display:block;font-weight:500;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.chat small{color:var(--sub)}
#msgs{height:calc(100vh - 190px);overflow:auto;padding-right:6px}
.m{margin:10px 0;max-width:85%;white-space:pre-wrap}.m.USER{margin-left:auto;background:var(--ink);color:var(--paper);padding:10px 14px}
.m.BLAZE{border-left:2px solid var(--accent);padding:4px 12px}.m.STEP{color:var(--sub);font-size:12px;font-family:ui-monospace,monospace}
.m.ERROR{color:#c33}.meta{font:10px ui-monospace,monospace;color:var(--weak);margin-top:4px}
form{display:flex;border:1px solid var(--ink);margin-top:12px}input{flex:1;border:0;padding:14px;background:transparent;color:var(--ink);font:15px Inter,sans-serif;outline:0}
button{border:0;background:var(--ink);color:var(--paper);padding:0 22px;font:11px ui-monospace,monospace;letter-spacing:.14em;text-transform:uppercase;cursor:pointer}
.card{border:1px solid var(--hair);padding:14px;margin-bottom:12px}.big{font:600 26px "Inter Tight",Inter,sans-serif}
.row{display:flex;justify-content:space-between;padding:6px 0;border-bottom:1px solid var(--hair);font-size:13px}
.bars{display:flex;align-items:flex-end;gap:4px;height:90px;margin-top:10px}.bars div{flex:1;background:var(--accent);min-height:2px}
.warn{color:var(--accent);font-size:13px}
</style></head><body>
<header><div class="mark"></div><h1>Blaze</h1><span class="lab" id="ver"></span><span style="flex:1"></span><span class="lab" id="model"></span></header>
<main>
<aside><div class="lab" style="margin-bottom:8px">Chats</div><div class="chat" onclick="openChat('')"><b>+ New chat</b></div><div id="chats"></div></aside>
<section><div class="lab" id="title">New chat</div><div id="msgs"></div>
<form onsubmit="return send()"><input id="inp" placeholder="Message Blaze… (it can use your phone)" autocomplete="off"><button id="btn">Send</button></form></section>
<section style="border-right:0">
<div class="card"><div class="lab">Today</div><div class="big" id="tok">–</div><div class="lab" id="cost"></div>
<div class="bars" id="bars"></div><div class="lab" style="margin-top:6px">Tokens, last 14 days</div></div>
${Bridge.CARDS}
<div class="card"><div class="lab" style="margin-bottom:6px">Phone</div><div id="dev"></div><div id="warn"></div></div>
<div class="card"><div class="lab" style="margin-bottom:6px">Blaze</div><div id="blz"></div></div>
</section></main>
<script>
var T=new URLSearchParams(location.search).get('t'),chat='';
function api(p,o){o=o||{};o.headers={'x-blaze-token':T,'Content-Type':'application/json'};return fetch(p+(p.indexOf('?')<0?'?':'&')+'t='+T,o).then(function(r){return r.json()})}
function esc(s){var d=document.createElement('div');d.textContent=s;return d.innerHTML}
function row(k,v){return '<div class="row"><span>'+k+'</span><b>'+v+'</b></div>'}
function stats(){api('/api/stats').then(function(s){
 document.getElementById('ver').textContent='v'+s.version;document.getElementById('model').textContent=s.provider+' · '+s.model;
 document.getElementById('tok').textContent=s.today.tokens.toLocaleString()+' tokens';
 document.getElementById('cost').textContent=s.today.calls+' calls · ${'$'}'+s.today.cost.toFixed(4)+' today · ${'$'}'+s.month.cost.toFixed(3)+' this month';
 var mx=1;s.daily.forEach(function(d){mx=Math.max(mx,d.tokens)});
 document.getElementById('bars').innerHTML=s.daily.map(function(d){return '<div title="'+d.day+': '+d.tokens+'" style="height:'+Math.round(d.tokens/mx*100)+'%"></div>'}).join('');
 var d=s.device;document.getElementById('dev').innerHTML=row('Battery',d.battery+'%'+(d.charging?' ⚡':''))+row('Temperature',d.temp.toFixed(0)+' °C')+row('Storage free',d.storageFree.toFixed(1)+' / '+d.storageTotal.toFixed(0)+' GB')+row('Memory free',d.ramFree+' / '+d.ramTotal+' MB')+row('Network',d.network);
 document.getElementById('warn').innerHTML=s.warnings.map(function(w){return '<div class="warn">● '+esc(w)+'</div>'}).join('');
 document.getElementById('blz').innerHTML=row('Chats',s.chats)+row('Memories',s.memories)+row('Learned skills',s.skills)+row('Instant commands',s.instant)+row('Background jobs',s.jobs);
})}
function chats(){api('/api/chats').then(function(l){document.getElementById('chats').innerHTML=l.map(function(c){return '<div class="chat'+(c.id==chat?' on':'')+'" onclick="openChat(\''+c.id+'\')"><b>'+esc(c.title||'Untitled')+'</b><small>'+esc(c.preview||'')+'</small></div>'}).join('')})}
function render(m){var b=document.getElementById('msgs');b.innerHTML=m.map(function(x){return '<div class="m '+x.role+'">'+esc(x.text)+(x.meta?'<div class="meta">'+esc(x.meta)+'</div>':'')+'</div>'}).join('');b.scrollTop=b.scrollHeight}
function openChat(id){chat=id;document.getElementById('title').textContent=id?'Chat':'New chat';if(!id){render([]);chats();return}api('/api/chat?id='+id).then(render);chats()}
function send(){var i=document.getElementById('inp'),t=i.value.trim();if(!t)return false;i.value='';
 var b=document.getElementById('msgs');b.innerHTML+='<div class="m USER">'+esc(t)+'</div><div class="m STEP" id="wait">Blaze is working…</div>';b.scrollTop=b.scrollHeight;
 document.getElementById('btn').disabled=true;
 api('/api/send',{method:'POST',body:JSON.stringify({chat:chat,text:t})}).then(function(r){chat=r.chat;document.getElementById('btn').disabled=false;openChat(chat);stats()});return false}
${Bridge.SCRIPT}
stats();chats();setInterval(stats,10000);setInterval(function(){if(chat&&!document.getElementById('wait'))api('/api/chat?id='+chat).then(render)},5000);
</script></body></html>
"""
}
