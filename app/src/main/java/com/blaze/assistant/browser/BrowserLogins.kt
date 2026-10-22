package com.blaze.assistant.browser

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * v0.29.7 Blaze Browser saved passwords: what you typed into a site's own sign-in form, saved when you tap
 * "Save" on the prompt. One entry per site (registrable domain, [SiteDomain]) and username; several accounts
 * per site. `filesDir/browser_logins.json`, every password sealed with Blaze's Android Keystore key
 * ([com.blaze.assistant.agent.Secrets], AES-256-GCM, key never leaves the phone). Never written from private tabs.
 * Separate from [Vault] (values Blaze's secure chat form collected for the agent), which keeps its own page.
 */
object BrowserLogins {
    data class Login(val id: String, val site: String, val host: String, val user: String, val sealed: String,
                     val created: Long, val used: Long) {
        val password: String get() = open(sealed)
    }

    sealed class Decision {
        /** New account for this site: offer "Save password?". */
        object Save : Decision()
        /** Same username, different password: offer "Update password?". */
        data class Update(val id: String) : Decision()
        /** Already saved exactly like this: nothing to ask. */
        data class Same(val id: String) : Decision()
    }

    @Volatile var dir: File? = null
    @Volatile var fileOverride: File? = null
    /** Encrypt / decrypt; tests swap these for plain functions (no Keystore on the JVM). */
    var seal: (String) -> String = { com.blaze.assistant.agent.Secrets.encrypt(it) }
    var open: (String) -> String = { com.blaze.assistant.agent.Secrets.decrypt(it) }
    var clock: () -> Long = { System.currentTimeMillis() }

    private val lock = Any()
    private fun file(): File? = fileOverride ?: dir?.let { File(it, "browser_logins.json") }

    private fun data(): JSONObject = synchronized(lock) {
        try { file()?.takeIf { it.exists() }?.readText()?.let { JSONObject(it) } } catch (_: Exception) { null } ?: JSONObject()
    }
    private fun write(o: JSONObject) = synchronized(lock) {
        try { file()?.let { f -> f.parentFile?.mkdirs(); val tmp = File(f.path + ".tmp"); tmp.writeText(o.toString()); if (!tmp.renameTo(f)) { f.writeText(o.toString()); tmp.delete() } } } catch (_: Exception) { }
    }

    private fun parse(o: JSONObject) = Login(o.optString("id"), o.optString("site"), o.optString("host"), o.optString("user"),
        o.optString("pass"), o.optLong("created"), o.optLong("used"))
    private fun json(l: Login) = JSONObject().put("id", l.id).put("site", l.site).put("host", l.host).put("user", l.user)
        .put("pass", l.sealed).put("created", l.created).put("used", l.used)

    fun all(): List<Login> {
        val a = data().optJSONArray("logins") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::parse) }.sortedWith(compareBy({ it.site }, { it.user.lowercase() }))
    }
    private fun putAll(l: List<Login>) { val d = data(); d.put("logins", JSONArray().apply { l.forEach { put(json(it)) } }); write(d) }

    /** Saved accounts for the site of [host], most recently used first. */
    fun forHost(host: String): List<Login> {
        val site = SiteDomain.registrable(host); if (site.isEmpty()) return emptyList()
        return all().filter { it.site == site }.sortedByDescending { maxOf(it.used, it.created) }
    }

    /** What to offer after a sign-in form was sent on [host] with [user] / [pass]. Pure apart from reading the store. */
    fun decide(host: String, user: String, pass: String): Decision = decide(forHost(host), user, pass)

    fun decide(existing: List<Login>, user: String, pass: String): Decision {
        val same = existing.firstOrNull { it.user.equals(user, ignoreCase = true) && user.isNotEmpty() }
            ?: existing.firstOrNull { user.isEmpty() && it.user.isEmpty() }
        return when {
            same == null -> Decision.Save
            same.password == pass -> Decision.Same(same.id)
            else -> Decision.Update(same.id)
        }
    }

    /** Saves (or replaces) the account [user] on the site of [host]. */
    fun save(host: String, user: String, pass: String): Login? {
        val site = SiteDomain.registrable(host); if (site.isEmpty() || pass.isEmpty()) return null
        val now = clock()
        val list = all().toMutableList()
        val i = list.indexOfFirst { it.site == site && it.user.equals(user, ignoreCase = true) }
        val l = if (i >= 0) list[i].copy(user = user, host = host, sealed = seal(pass), used = now)
            else Login(newId(), site, host, user, seal(pass), now, now)
        if (i >= 0) list[i] = l else list += l
        putAll(list); removeNever(site)
        return l
    }

    /** Edit from the Saved passwords sheet. */
    fun update(id: String, user: String, pass: String): Boolean {
        if (pass.isEmpty()) return false
        val list = all().toMutableList(); val i = list.indexOfFirst { it.id == id }; if (i < 0) return false
        list[i] = list[i].copy(user = user, sealed = seal(pass)); putAll(list); return true
    }

    fun delete(id: String): Boolean { val list = all(); val keep = list.filter { it.id != id }; if (keep.size == list.size) return false; putAll(keep); return true }

    fun touch(id: String) { val list = all().toMutableList(); val i = list.indexOfFirst { it.id == id }; if (i < 0) return; list[i] = list[i].copy(used = clock()); putAll(list) }

    // ---------- "Never for this site" ----------
    fun never(): List<String> { val a = data().optJSONArray("never") ?: return emptyList(); return (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }.sorted() }
    fun isNever(host: String) = SiteDomain.registrable(host).let { s -> s.isNotEmpty() && s in never() }
    fun addNever(host: String) { val s = SiteDomain.registrable(host); if (s.isEmpty()) return; val d = data(); d.put("never", JSONArray((never() + s).distinct())); write(d) }
    fun removeNever(site: String) { val n = never(); if (site !in n) return; val d = data(); d.put("never", JSONArray(n - site)); write(d) }

    private fun newId() = java.lang.Long.toString(clock(), 36) + java.lang.Long.toString((Math.random() * 1e9).toLong(), 36)

    /** "jo•••@gmail.com" for the list; the password itself is only shown after device unlock. */
    fun maskUser(u: String): String = if (u.isEmpty()) "(no username)" else u

    // ---------- page scripts ----------
    /** Name of the JS object the capture script talks to (WebViewCompat.addWebMessageListener). */
    const val BRIDGE = "blazeLogin"

    /**
     * Watches sign-in forms on the page: when one is sent (submit, Enter in a password field, or a sign-in button)
     * it posts {t:"submit", u, p} to Blaze; when a visible password field appears it posts {t:"form"} once, so the
     * browser can offer saved accounts. Fields Blaze's agent filled (data-bz-secret) are ignored: those are in the vault.
     * Two-step sign-ins (username page, then password page) keep the username in sessionStorage (same origin only).
     */
    const val CAPTURE_JS = """(function(){
      if (window.__bzLogin) return; window.__bzLogin = 1;
      var B = window.$BRIDGE; if (!B || !B.postMessage) return;
      function vis(e){ try { var r=e.getBoundingClientRect(); var s=getComputedStyle(e); return r.width>0&&r.height>0&&s.visibility!='hidden'&&s.display!='none'; } catch(x){ return false; } }
      function userBefore(p){
        var root = p.form || document; var ins = [].slice.call(root.querySelectorAll('input')); var i = ins.indexOf(p);
        for (var j=i-1;j>=0;j--){ var e=ins[j]; var t=(e.getAttribute('type')||'text').toLowerCase(); if (/^(text|email|tel)$/.test(t) && e.value && vis(e)) return e.value; }
        var a = document.querySelector('input[autocomplete~=username],input[autocomplete~=email],input[type=email]'); if (a && a.value) return a.value;
        try { return sessionStorage.getItem('__bzUser') || ''; } catch(x) { return ''; }
      }
      function pick(){
        var pws = [].slice.call(document.querySelectorAll('input[type=password]')).filter(function(e){ return e.value && !e.hasAttribute('data-bz-secret'); });
        if (!pws.length) return null;
        var p = pws[0];
        // New / confirm (sign-up or change password): the new one is the one to keep.
        if (pws.length >= 2 && pws[pws.length-1].value === pws[pws.length-2].value) p = pws[pws.length-1];
        return { u: userBefore(pws[0]).trim().slice(0,200), p: p.value.slice(0,500) };
      }
      function stash(){
        if (document.querySelector('input[type=password]')) return;
        var a = document.querySelector('input[autocomplete~=username],input[type=email],input[name*=user i],input[name*=email i],input[name=login],input[id*=email i],input[id*=user i]');
        if (a && a.value && !a.hasAttribute('data-bz-secret')) try { sessionStorage.setItem('__bzUser', a.value.slice(0,200)); } catch(x) {}
      }
      var last = 0;
      function send(){ var r = pick(); if (!r) return; var now = Date.now(); if (now - last < 1500) return; last = now;
        try { B.postMessage(JSON.stringify({t:'submit', u:r.u, p:r.p})); } catch(x) {} }
      document.addEventListener('submit', function(){ stash(); send(); }, true);
      document.addEventListener('keydown', function(ev){ var t = ev.target; if (ev.key === 'Enter' && t && t.tagName === 'INPUT') { stash(); if ((t.type||'') === 'password') send(); } }, true);
      document.addEventListener('click', function(ev){
        var b = ev.target && ev.target.closest && ev.target.closest('button,input[type=submit],input[type=button],[role=button],a');
        if (!b) return;
        var txt = ((b.innerText||'') + ' ' + (b.value||'') + ' ' + (b.getAttribute('aria-label')||'')).toLowerCase();
        var submitty = (b.type === 'submit') || /(log ?in|sign ?in|sign ?up|continue|next|submit|enter|register|create account|save)/.test(txt);
        if (!submitty) return;
        stash(); setTimeout(send, 0);
      }, true);
      var told = false;
      function scan(){ if (told) return; var p = [].slice.call(document.querySelectorAll('input[type=password]')).filter(vis)[0];
        var u = !p && document.querySelector('input[autocomplete~=username]');
        if (p || (u && vis(u))) { told = true; try { B.postMessage(JSON.stringify({t:'form'})); } catch(x) {} } }
      var pend = 0;
      function watch(){ scan(); if (!document.body) return; new MutationObserver(function(){ if (told || pend) return; pend = setTimeout(function(){ pend = 0; scan(); }, 400); })
        .observe(document.body, {childList:true, subtree:true, attributes:true, attributeFilter:['type','style','class','hidden']}); }
      if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', watch); else watch();
    })()"""

    /** Fills [user] / [pass] into the page's sign-in fields (never submits). Returns "ok", "user" (username only) or "missing". */
    fun fillJs(user: String, pass: String): String = """(function(){
      function vis(e){ var r=e.getBoundingClientRect(); var s=getComputedStyle(e); return r.width>0&&r.height>0&&s.visibility!='hidden'&&s.display!='none'; }
      function set(e, v){ if (!e) return; e.focus();
        var proto = e.tagName=='TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
        Object.getOwnPropertyDescriptor(proto,'value').set.call(e, v);
        e.dispatchEvent(new Event('input',{bubbles:true})); e.dispatchEvent(new Event('change',{bubbles:true})); }
      var u = ${JSONObject.quote(user)}, p = ${JSONObject.quote(pass)};
      var pw = [].slice.call(document.querySelectorAll('input[type=password]')).filter(function(e){ return vis(e) && !e.disabled && !e.readOnly; })[0];
      var root = (pw && pw.form) || document;
      var ins = [].slice.call(root.querySelectorAll('input')).filter(function(e){ return vis(e) && !e.disabled && !e.readOnly; });
      var uf = null;
      if (pw) { var i = ins.indexOf(pw); for (var j=i-1;j>=0;j--){ var t=(ins[j].getAttribute('type')||'text').toLowerCase(); if (/^(text|email|tel)$/.test(t)) { uf = ins[j]; break; } } }
      if (!uf) uf = [].slice.call(document.querySelectorAll('input[autocomplete~=username],input[type=email],input[name*=user i],input[name*=email i],input[name=login],input[id*=email i],input[id*=user i]')).filter(vis)[0] || null;
      if (uf && u) set(uf, u);
      if (pw) { set(pw, p); pw.setAttribute('data-bz-secret','1'); return 'ok'; }
      return uf ? 'user' : 'missing';
    })()"""
}
