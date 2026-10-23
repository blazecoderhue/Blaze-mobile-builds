package com.blaze.assistant.browser.phone

import org.json.JSONObject

/**
 * 0.45.19 Blaze Phone browser: the page scripts (run with evaluateJavascript). Element ids are stable for a page's
 * life: each element keeps its `data-bzp` id across reads. Positions come back in the WebView's own pixels (CSS px ×
 * pinch scale × devicePixelRatio, minus the visual viewport's offset), so a MotionEvent lands on the element.
 * Secret values (passwords, codes, card fields, values Blaze typed from the vault) never leave the page: "(filled, hidden)".
 */
object PhoneScripts {
    private const val COMMON = """
      var dpr=window.devicePixelRatio||1, vv=window.visualViewport, sc=vv?vv.scale:1, ox=vv?vv.offsetLeft:0, oy=vv?vv.offsetTop:0;
      function bzVis(e){var r=e.getBoundingClientRect();if(r.width<=0||r.height<=0)return false;var s=getComputedStyle(e);return s.visibility!='hidden'&&s.display!='none'&&s.opacity!='0';}
      function bzId(e){var m=window.__bzpMap||(window.__bzpMap=new WeakMap()),id=m.get(e);
        if(id&&e.getAttribute('data-bzp')===id)return id;
        if(typeof window.__bzpN!=='number')window.__bzpN=0;id='e'+(++window.__bzpN);m.set(e,id);e.setAttribute('data-bzp',id);return id;}
      function bzRole(e){var t=e.tagName.toLowerCase(),ty=(e.getAttribute('type')||'').toLowerCase(),r=e.getAttribute('role');
        if(r)return r;if(t=='a')return 'link';if(t=='button'||t=='summary')return 'button';if(t=='select')return 'combobox';if(t=='textarea')return 'textbox';
        if(t=='input'){if(ty=='checkbox')return 'checkbox';if(ty=='radio')return 'radio';if(/submit|button|reset|image/.test(ty))return 'button';return 'textbox';}
        if(e.isContentEditable)return 'textbox';return 'button';}
      function bzLab(e){var l=e.getAttribute('aria-label')||'';if(!l&&e.labels&&e.labels.length)l=e.labels[0].innerText||'';
        if(!l&&e.getAttribute('aria-labelledby')){var x=document.getElementById(e.getAttribute('aria-labelledby'));if(x)l=x.innerText||'';}
        var t=e.tagName;if(!l&&!(t=='INPUT'||t=='TEXTAREA'||t=='SELECT'))l=e.innerText||'';
        if(!l)l=e.getAttribute('placeholder')||e.getAttribute('title')||e.getAttribute('alt')||(t=='INPUT'&&/submit|button/.test(e.type)?e.value:'')||e.getAttribute('name')||'';
        return (l||'').replace(/\s+/g,' ').trim().slice(0,100);}
      function bzKey(e){return ((e.getAttribute('name')||'')+' '+(e.id||'')+' '+(e.getAttribute('autocomplete')||'')+' '+(e.getAttribute('placeholder')||'')+' '+(e.getAttribute('aria-label')||'')).slice(0,160);}
      function bzSens(e){var ty=(e.getAttribute('type')||'').toLowerCase(),ac=(e.getAttribute('autocomplete')||'').toLowerCase();
        return e.hasAttribute('data-bz-secret')||ty=='password'||/^cc-/.test(ac)||/one-time-code|password/.test(ac)||/(pass(word|wd|code)?|pwd|otp|one.?time|cvv|cvc|csc|card.?(number|no)|iban|ssn|security.?code|verification.?code)/i.test(bzKey(e));}
      function bzCard(e){var ac=(e.getAttribute('autocomplete')||'').toLowerCase();return /^cc-/.test(ac)||/(card.?(number|no)|cvv|cvc|csc|security.?code|expir)/i.test(bzKey(e));}
      function bzPos(e){var r=e.getBoundingClientRect();return {x:Math.round(((r.left+r.width/2)-ox)*sc*dpr),y:Math.round(((r.top+r.height/2)-oy)*sc*dpr),w:Math.round(r.width*sc*dpr),h:Math.round(r.height*sc*dpr),
        inView:r.bottom>0&&r.right>0&&r.top<innerHeight&&r.left<innerWidth};}
      function bzNode(e,withPos){
        var id=bzId(e),t=e.tagName.toLowerCase(),ty=(e.getAttribute('type')||(t=='textarea'?'textarea':'')).toLowerCase();
        var sens=bzSens(e),v='',len=-1;if(t=='select'){var o=e.options[e.selectedIndex];v=o?o.text:'';}
        else if(t=='input'||t=='textarea'){if(!/checkbox|radio|submit|button|reset|image/.test(ty)){v=e.value||'';len=v.length;}}
        else if(e.isContentEditable){v=e.innerText||'';len=v.length;}
        if(sens&&v)v='(filled, hidden)';
        var p=bzPos(e);
        var n={id:id,role:bzRole(e),name:bzLab(e),value:String(v).slice(0,100),type:ty,ac:(e.getAttribute('autocomplete')||'').toLowerCase(),key:bzKey(e).slice(0,80),
          sens:sens,marked:e.hasAttribute('data-bz-secret'),disabled:!!e.disabled,inView:p.inView};
        if(withPos){n.x=p.x;n.y=p.y;n.w=p.w;n.h=p.h;}
        if(len>=0)n.len=len;
        if(/checkbox|radio/.test(ty)||/checkbox|radio|switch/.test(e.getAttribute('role')||''))n.checked=!!(e.checked||e.getAttribute('aria-checked')=='true');
        if(t=='a'&&e.href&&e.href.indexOf('javascript')!=0)n.href=e.href;
        if(!n.name&&n.role=='link')return null;
        return n;}
      function bzHeads(){return [].slice.call(document.querySelectorAll('h1,h2,[role=heading],[role=alert],[role=status]')).filter(bzVis).slice(0,12)
        .map(function(h){return (h.innerText||'').replace(/\s+/g,' ').trim().slice(0,120);}).filter(function(x){return x;});}
      function bzCardOnPage(){return [].slice.call(document.querySelectorAll('input,iframe')).some(function(x){return bzVis(x)&&(x.tagName=='IFRAME'?/card|payment|stripe|braintree|adyen|checkout/i.test((x.name||'')+' '+(x.title||'')+' '+(x.src||'')):bzCard(x));});}
      var BZSEL='a[href],button,input:not([type=hidden]),select,textarea,summary,[role=button],[role=link],[role=tab],[role=menuitem],[role=checkbox],[role=radio],[role=option],[role=switch],[role=combobox],[role=textbox],[onclick],[contenteditable=true],[tabindex]:not([tabindex="-1"])';
    """

    /** The accessibility-like tree: interactive elements (id, role, name, value, position), headings, page text. */
    const val SNAPSHOT = """/*bz:snapshot*/(function(){$COMMON
      var els=[].slice.call(document.querySelectorAll(BZSEL)).filter(bzVis).slice(0,250);
      var nodes=[],pw=false;
      els.forEach(function(e){ if((e.getAttribute('type')||'').toLowerCase()=='password')pw=true; var n=bzNode(e,true); if(n)nodes.push(n); });
      var text=(document.body?document.body.innerText:'').replace(/\n{3,}/g,'\n\n').trim().slice(0,12000);
      return {title:document.title,url:location.href,text:text,nodes:nodes,pw:pw,headings:bzHeads()};
    })()"""

    /** Where to touch element [id]: scrolled into view first; says whether something covers it. Null when gone. */
    fun rect(id: String): String = """/*bz:rect*/(function(id){$COMMON
      var e=document.querySelector('[data-bzp="'+id+'"]'); if(!e) return null;
      var r=e.getBoundingClientRect();
      if(r.top<0||r.bottom>innerHeight||r.left<0||r.right>innerWidth){ try{e.scrollIntoView({block:'center',inline:'center',behavior:'instant'});}catch(x){e.scrollIntoView();} r=e.getBoundingClientRect(); }
      var cx=r.left+r.width/2, cy=r.top+r.height/2, h=document.elementFromPoint(cx,cy);
      var covered = !!h && !(e===h||e.contains(h)||h.contains(e));
      var p=bzPos(e);
      return {id:id,x:p.x,y:p.y,w:p.w,h:p.h,label:bzLab(e),role:bzRole(e),tag:e.tagName.toLowerCase(),type:(e.getAttribute('type')||'').toLowerCase(),
        card:bzCard(e),sens:bzSens(e),editable:(/INPUT|TEXTAREA/.test(e.tagName)&&!/checkbox|radio|submit|button|reset|image/.test(e.type||''))||e.isContentEditable,
        disabled:!!e.disabled,covered:covered,coveredBy:covered?bzLab(h).slice(0,60):'' };
    })(${JSONObject.quote(id)})"""

    /** The best visible element whose words contain [text] (shortest label wins); returns its id or null. */
    fun find(text: String, field: Boolean = false): String = """/*bz:find*/(function(q,field){$COMMON
      q=q.toLowerCase().trim(); var sel=field?'input:not([type=hidden]),textarea,select,[contenteditable=true],[role=textbox],[role=combobox]':BZSEL+',label';
      var best=null,bs=1e9;
      [].slice.call(document.querySelectorAll(sel)).filter(bzVis).forEach(function(e){
        var l=(bzLab(e)+' '+(e.value&&!bzSens(e)?e.value:'')+' '+bzKey(e)).toLowerCase(); var i=l.indexOf(q); if(i<0)return;
        var s=l.length-q.length+i; if(s<bs){bs=s;best=e;}});
      if(best&&best.tagName=='LABEL'&&best.control)best=best.control;
      return best?bzId(best):null;
    })(${JSONObject.quote(text)}, $field)"""

    /** The focused field's id (or the page's first text field), or null. */
    const val FOCUSED_ID = """/*bz:focused*/(function(){$COMMON
      var e=document.activeElement; if(!e||!(/INPUT|TEXTAREA|SELECT/.test(e.tagName)||e.isContentEditable)) e=[].slice.call(document.querySelectorAll('input[type=search],input[type=text],input[type=email],input:not([type]),textarea')).filter(bzVis)[0]||null;
      return e?bzId(e):null; })()"""

    /** JS fallback click (only when a touch can't land: zero-size or covered element). */
    fun click(id: String): String = """/*bz:click*/(function(id){var e=document.querySelector('[data-bzp="'+id+'"]');if(!e)return 'missing';
      e.scrollIntoView({block:'center'});e.focus&&e.focus();e.click();return 'ok';})(${JSONObject.quote(id)})"""

    /** JS fallback typing: sets the value the way frameworks notice (native setter + input/change). [secret] marks it. */
    fun type(id: String, value: String, secret: Boolean, clear: Boolean): String = """/*bz:type*/(function(id,v,secret,clear){var e=document.querySelector('[data-bzp="'+id+'"]');if(!e)return 'missing';
      e.focus();
      if(e.isContentEditable){ if(clear) e.innerText=v; else e.innerText+=v; }
      else { var proto=e.tagName=='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;
        Object.getOwnPropertyDescriptor(proto,'value').set.call(e, clear?v:(e.value||'')+v); }
      if(secret) e.setAttribute('data-bz-secret','1');
      e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));return 'ok';})(${JSONObject.quote(id)}, ${JSONObject.quote(value)}, $secret, $clear)"""

    /** Marks a field Blaze filled from the vault so reads never show it. */
    fun markSecret(id: String): String = """/*bz:mark*/(function(id){var e=document.querySelector('[data-bzp="'+id+'"]');if(e)e.setAttribute('data-bz-secret','1');return 'ok';})(${JSONObject.quote(id)})"""

    /** The field's current value length (what typing landed), -1 when it's gone. */
    fun valueLen(id: String): String = """/*bz:len*/(function(id){var e=document.querySelector('[data-bzp="'+id+'"]');if(!e)return -1;var v=e.isContentEditable?e.innerText:(e.value||'');return v.length;})(${JSONObject.quote(id)})"""

    /** Focuses an element (when a touch can't land). */
    fun focus(id: String): String = """/*bz:focusel*/(function(id){var e=document.querySelector('[data-bzp="'+id+'"]');if(e&&e.focus)e.focus();return 'ok';})(${JSONObject.quote(id)})"""

    /** Vertical scroll position (CSS px) as a string. */
    const val SCROLL_Y = "/*bz:sy*/(function(){return String(Math.round(window.scrollY));})()"

    /** Picks an option of a <select> (or an ARIA listbox's option) by its text or value. */
    fun select(id: String, option: String): String = """/*bz:select*/(function(id,q){var e=document.querySelector('[data-bzp="'+id+'"]');if(!e)return 'missing';
      q=q.toLowerCase().trim();
      if(e.tagName=='SELECT'){var os=[].slice.call(e.options),hit=null;
        os.forEach(function(o){if(hit)return;if(o.text.toLowerCase().trim()==q||o.value.toLowerCase()==q)hit=o;});
        if(!hit)os.forEach(function(o){if(hit)return;if(o.text.toLowerCase().indexOf(q)>=0)hit=o;});
        if(!hit)return 'nooption:'+os.slice(0,20).map(function(o){return o.text.trim();}).join(' | ');
        var proto=HTMLSelectElement.prototype;Object.getOwnPropertyDescriptor(proto,'value').set.call(e,hit.value);
        e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));return 'ok:'+hit.text.trim();}
      return 'notselect';})(${JSONObject.quote(id)}, ${JSONObject.quote(option)})"""

    /** What Enter would do: the focused field's form submit label, and whether the page has card fields. */
    const val FOCUS = """/*bz:focus*/(function(){$COMMON
      var e=document.activeElement; var f=e&&e.form; var b=f&&f.querySelector('button[type=submit],input[type=submit],button:not([type])');
      var card=[].slice.call(document.querySelectorAll('input,iframe')).some(function(x){return bzVis(x)&&(x.tagName=='IFRAME'?/card|payment|stripe|braintree|adyen|checkout/i.test((x.name||'')+' '+(x.title||'')+' '+(x.src||'')):bzCard(x));});
      return {tag:e?e.tagName.toLowerCase():'',inForm:!!f,submit:b?bzLab(b):'',card:card,editable:!!e&&(/INPUT|TEXTAREA/.test(e.tagName)||e.isContentEditable)};
    })()"""

    /** Is the condition met? [selector] (visible), or [text] on the page, or the page finished loading. */
    fun waitCheck(selector: String?, text: String?, gone: Boolean): String = """/*bz:wait*/(function(sel,txt,gone){
      function vis(e){var r=e.getBoundingClientRect();var s=getComputedStyle(e);return r.width>0&&r.height>0&&s.visibility!='hidden'&&s.display!='none';}
      var ok=document.readyState=='complete';
      if(sel){var e=null;try{e=document.querySelector(sel);}catch(x){return 'badselector';} ok=!!e&&vis(e);}
      if(txt){ok=(document.body?document.body.innerText:'').toLowerCase().indexOf(txt.toLowerCase())>=0;}
      if(gone)ok=!ok; return ok?'yes':'no';
    })(${JSONObject.quote(selector.orEmpty())}, ${JSONObject.quote(text.orEmpty())}, $gone)"""

    /** Scrolls the page (fallback when a fling gesture doesn't move it, e.g. a scroll-locked body). */
    fun scrollBy(down: Boolean, amount: Double = 0.85): String = "/*bz:scrollby*/(function(){var h=window.innerHeight*$amount;window.scrollBy(0,${if (down) "" else "-"}h);return String(Math.round(window.scrollY));})()"

    // ------------------------------------------------------------------ 0.45.19-speed: the DOM bridge
    /** The bridge's names: a WebMessageListener object (androidx.webkit) or, on old WebViews, a JavascriptInterface. */
    const val BRIDGE_WM = "blazePhoneBridge"
    const val BRIDGE_JI = "BlazePhoneBridge"

    /**
     * Injected at document start (main frame only). Keeps a compact model of the page's visible, actionable elements
     * (role, name, value, state, stable ids) and sends it to Kotlin as diffs whenever the page changes: a
     * MutationObserver plus input / change / focus / scroll / history events, batched 30 ms after the first change.
     * Also counts in-flight fetch / XHR requests (network idle) and the focused field. Messages carry [token] (kept in
     * this closure, never on window), so frames and other scripts can't post as the bridge. Secrets are masked here
     * exactly like [SNAPSHOT] (and again in Kotlin). `window.__bzpB.full()` returns a full model (resync).
     */
    fun bridge(token: String): String = """/*bz:bridge*/(function(K){
      if(window.top!==window||window.__bzpB)return;
      var WM=window.$BRIDGE_WM,JI=window.$BRIDGE_JI;
      function post(s){try{if(WM&&WM.postMessage)WM.postMessage(s);else if(JI&&JI.post)JI.post(s);}catch(x){}}
      var DOC=Math.random().toString(36).slice(2)+Date.now().toString(36),seq=0,prev={},prevOrder='',prevText=null,prevHead='',last={};
      var timer=0,textDirty=true,forceFull=true,inflight=0;
      function sched(t){if(t)textDirty=true;if(!timer)timer=setTimeout(flush,30);}
      function geo(){dpr=window.devicePixelRatio||1;vv=window.visualViewport;sc=vv?vv.scale:1;ox=vv?vv.offsetLeft:0;oy=vv?vv.offsetTop:0;}
      $COMMON
      function focusInfo(){var e=document.activeElement;if(!e||e===document.body||e===document.documentElement||!e.getAttribute)return null;
        var f=e.form,b=f&&f.querySelector('button[type=submit],input[type=submit],button:not([type])');
        return {id:e.getAttribute('data-bzp')||'',submit:b?bzLab(b):'',inForm:!!f,editable:/INPUT|TEXTAREA/.test(e.tagName)||!!e.isContentEditable};}
      function build(full){
        geo();
        var o={doc:DOC,seq:++seq,full:!!full,url:location.href,title:document.title,net:inflight,ready:document.readyState,sy:Math.round(window.scrollY||0)};
        var els=document.body?[].slice.call(document.querySelectorAll(BZSEL)).filter(bzVis).slice(0,250):[];
        var set=[],ids=[],cur={},pw=false;
        els.forEach(function(e){if((e.getAttribute('type')||'').toLowerCase()=='password')pw=true;var n=bzNode(e,false);if(!n)return;
          var k=JSON.stringify(n);cur[n.id]=k;ids.push(n.id);if(full||prev[n.id]!==k)set.push(n);});
        var del=[];if(!full)for(var id in prev)if(!(id in cur))del.push(id);
        var ord=ids.join(',');if(full||ord!==prevOrder){o.order=ids;prevOrder=ord;}
        prev=cur;if(set.length)o.set=set;if(del.length)o.del=del;
        if(full||textDirty){var tx=(document.body?document.body.innerText:'').replace(/\n{3,}/g,'\n\n').trim().slice(0,12000);if(full||tx!==prevText){o.text=tx;prevText=tx;}textDirty=false;}
        var hs=document.body?bzHeads():[],hd=JSON.stringify(hs);if(full||hd!==prevHead){o.headings=hs;prevHead=hd;}
        o.pw=pw;o.card=document.body?bzCardOnPage():false;o.focus=focusInfo();
        return o;}
      function same(o){var keys=['url','title','net','ready','sy','pw','card'];for(var i=0;i<keys.length;i++)if(o[keys[i]]!==last[keys[i]])return false;
        return JSON.stringify(o.focus)===last.focus;}
      function remember(o){last={url:o.url,title:o.title,net:o.net,ready:o.ready,sy:o.sy,pw:o.pw,card:o.card,focus:JSON.stringify(o.focus)};}
      function flush(){timer=0;try{var f=forceFull;forceFull=false;var o=build(f);
        if(!f&&!o.set&&!o.del&&!o.order&&o.text===undefined&&!o.headings&&same(o)){seq--;return;}
        remember(o);o.k=K;post(JSON.stringify(o));}catch(x){}}
      try{Object.defineProperty(window,'__bzpB',{value:{full:function(){var o=build(true);remember(o);forceFull=false;return o;}},enumerable:false});}catch(x){window.__bzpB={full:function(){return build(true);}};}
      new MutationObserver(function(ms){for(var i=0;i<ms.length;i++){var m=ms[i];if(m.type!=='attributes'||m.attributeName!=='data-bzp'){sched(true);return;}}})
        .observe(document,{subtree:true,childList:true,attributes:true,characterData:true});
      ['input','change','focusin','focusout'].forEach(function(n){document.addEventListener(n,function(){sched(false);},true);});
      ['scroll','resize'].forEach(function(n){window.addEventListener(n,function(){sched(false);},{passive:true,capture:true});});
      ['popstate','hashchange','load'].forEach(function(n){window.addEventListener(n,function(){sched(true);});});
      document.addEventListener('DOMContentLoaded',function(){forceFull=true;sched(true);});
      window.addEventListener('pageshow',function(ev){if(ev.persisted){forceFull=true;sched(true);}});
      ['pushState','replaceState'].forEach(function(n){var f=history[n];if(f)history[n]=function(){var r=f.apply(this,arguments);sched(true);return r;};});
      var of=window.fetch;if(of)window.fetch=function(){inflight++;sched(false);var p;try{p=of.apply(window,arguments);}catch(x){inflight=Math.max(0,inflight-1);throw x;}
        var d=function(){inflight=Math.max(0,inflight-1);sched(false);};try{p.then(d,d);}catch(x){d();}return p;};
      if(window.XMLHttpRequest){var XS=XMLHttpRequest.prototype.send;XMLHttpRequest.prototype.send=function(){var done=false;
        var end=function(){if(done)return;done=true;inflight=Math.max(0,inflight-1);sched(false);};inflight++;sched(false);
        try{this.addEventListener('loadend',end);return XS.apply(this,arguments);}catch(x){end();throw x;}};}
      sched(true);
    })(${JSONObject.quote(token)})"""

    /** A full model straight from the bridge (resync after a missed message), or null when the page has no bridge. */
    const val BRIDGE_FULL = "/*bz:full*/(function(){return window.__bzpB?window.__bzpB.full():null;})()"

    /**
     * One call: the best element for [text] (as [find]) scrolled into view, with where to touch it (as [rect]); null
     * when nothing matches. Saves a round trip per action.
     */
    fun locate(text: String, field: Boolean = false): String = """/*bz:locate*/(function(q,field){$COMMON
      q=q.toLowerCase().trim(); var sel=field?'input:not([type=hidden]),textarea,select,[contenteditable=true],[role=textbox],[role=combobox]':BZSEL+',label';
      var e=null,bs=1e9;
      [].slice.call(document.querySelectorAll(sel)).filter(bzVis).forEach(function(x){
        var l=(bzLab(x)+' '+(x.value&&!bzSens(x)?x.value:'')+' '+bzKey(x)).toLowerCase(); var i=l.indexOf(q); if(i<0)return;
        var s=l.length-q.length+i; if(s<bs){bs=s;e=x;}});
      if(e&&e.tagName=='LABEL'&&e.control)e=e.control;
      if(!e)return null;
      var id=bzId(e), r=e.getBoundingClientRect();
      if(r.top<0||r.bottom>innerHeight||r.left<0||r.right>innerWidth){ try{e.scrollIntoView({block:'center',inline:'center',behavior:'instant'});}catch(x){e.scrollIntoView();} r=e.getBoundingClientRect();
        vv=window.visualViewport; sc=vv?vv.scale:1; ox=vv?vv.offsetLeft:0; oy=vv?vv.offsetTop:0; }
      var cx=r.left+r.width/2, cy=r.top+r.height/2, h=document.elementFromPoint(cx,cy);
      var covered = !!h && !(e===h||e.contains(h)||h.contains(e));
      var p=bzPos(e);
      return {id:id,x:p.x,y:p.y,w:p.w,h:p.h,label:bzLab(e),role:bzRole(e),tag:e.tagName.toLowerCase(),type:(e.getAttribute('type')||'').toLowerCase(),
        card:bzCard(e),sens:bzSens(e),editable:(/INPUT|TEXTAREA/.test(e.tagName)&&!/checkbox|radio|submit|button|reset|image/.test(e.type||''))||e.isContentEditable,
        disabled:!!e.disabled,covered:covered,coveredBy:covered?bzLab(h).slice(0,60):'' };
    })(${JSONObject.quote(text)}, $field)"""
}
