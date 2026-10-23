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
      function bzId(e){var id=e.getAttribute('data-bzp');if(!id){if(window.__bzpN===undefined)window.__bzpN=0;id='e'+(++window.__bzpN);e.setAttribute('data-bzp',id);}return id;}
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
      var BZSEL='a[href],button,input:not([type=hidden]),select,textarea,summary,[role=button],[role=link],[role=tab],[role=menuitem],[role=checkbox],[role=radio],[role=option],[role=switch],[role=combobox],[role=textbox],[onclick],[contenteditable=true],[tabindex]:not([tabindex="-1"])';
    """

    /** The accessibility-like tree: interactive elements (id, role, name, value, position), headings, page text. */
    const val SNAPSHOT = """/*bz:snapshot*/(function(){$COMMON
      var els=[].slice.call(document.querySelectorAll(BZSEL)).filter(bzVis).slice(0,250);
      var nodes=[],pw=false;
      els.forEach(function(e){
        var id=bzId(e),t=e.tagName.toLowerCase(),ty=(e.getAttribute('type')||(t=='textarea'?'textarea':'')).toLowerCase();
        var sens=bzSens(e); if(ty=='password')pw=true;
        var v='';if(t=='select'){var o=e.options[e.selectedIndex];v=o?o.text:'';}
        else if(t=='input'||t=='textarea'){if(!/checkbox|radio|submit|button|reset|image/.test(ty))v=e.value||'';}
        else if(e.isContentEditable)v=e.innerText||'';
        if(sens&&v)v='(filled, hidden)';
        var p=bzPos(e);
        var n={id:id,role:bzRole(e),name:bzLab(e),value:String(v).slice(0,100),type:ty,ac:(e.getAttribute('autocomplete')||'').toLowerCase(),key:bzKey(e).slice(0,80),
          sens:sens,marked:e.hasAttribute('data-bz-secret'),disabled:!!e.disabled,x:p.x,y:p.y,w:p.w,h:p.h,inView:p.inView};
        if(/checkbox|radio/.test(ty)||/checkbox|radio|switch/.test(e.getAttribute('role')||''))n.checked=!!(e.checked||e.getAttribute('aria-checked')=='true');
        if(t=='a'&&e.href&&e.href.indexOf('javascript')!=0)n.href=e.href;
        if(!n.name&&n.role=='link')return;
        nodes.push(n);
      });
      var hs=[].slice.call(document.querySelectorAll('h1,h2,[role=heading],[role=alert],[role=status]')).filter(bzVis).slice(0,12)
        .map(function(h){return (h.innerText||'').replace(/\s+/g,' ').trim().slice(0,120);}).filter(function(x){return x;});
      var text=(document.body?document.body.innerText:'').replace(/\n{3,}/g,'\n\n').trim().slice(0,12000);
      return {title:document.title,url:location.href,text:text,nodes:nodes,pw:pw,headings:hs};
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
}
