// BlazeOS bridge shim: the native object is window.BlazeOS inside BlazeOS; outside it (a desktop browser) this falls
// back to localStorage so the app still runs.
(function () {
  var n = window.BlazeOS;
  var os = {
    inBlazeOS: !!n,
    get: function (k, d) { try { var v = n ? n.getItem(k) : localStorage.getItem(k); return v == null || v === "" ? d : v; } catch (e) { return d; } },
    set: function (k, v) { try { n ? n.setItem(k, String(v)) : localStorage.setItem(k, String(v)); } catch (e) {} },
    remove: function (k) { try { n ? n.removeItem(k) : localStorage.removeItem(k); } catch (e) {} },
    notify: function (t, b) { try { if (n) n.notify(t, b || ""); } catch (e) {} },
    theme: function () { try { return JSON.parse(n ? n.theme() : '{"dark":true,"accent":"#FF9A3C","fontScale":1}'); } catch (e) { return { dark: true, accent: "#FF9A3C", fontScale: 1 }; } }
  };
  window.os = os;
  var t = os.theme();
  document.documentElement.style.setProperty("--accent", t.accent || "#FF9A3C");
  document.documentElement.style.fontSize = (16 * (t.fontScale || 1)) + "px";
  document.documentElement.className = t.dark ? "dark" : "light";
})();
