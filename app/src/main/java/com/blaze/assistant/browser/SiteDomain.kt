package com.blaze.assistant.browser

/**
 * v0.29.7: the "site" a saved login belongs to: the registrable domain (eTLD+1), so a password saved on
 * accounts.example.co.uk is offered on www.example.co.uk and m.example.co.uk, but never on example.com
 * or evil-example.co.uk. A compact public-suffix list (multi-label country suffixes plus hosting suffixes
 * where every subdomain is somebody else's site) instead of the full 200 KB PSL.
 */
object SiteDomain {
    /** Two-label public suffixes (country second-level domains). */
    private val multi = setOf(
        "co.uk", "org.uk", "ac.uk", "gov.uk", "me.uk", "ltd.uk", "plc.uk", "net.uk", "nhs.uk", "sch.uk",
        "com.au", "net.au", "org.au", "edu.au", "gov.au", "id.au", "co.nz", "org.nz", "net.nz", "govt.nz", "ac.nz",
        "co.za", "org.za", "gov.za", "ac.za", "net.za", "web.za",
        "co.zm", "com.zm", "org.zm", "gov.zm", "ac.zm", "net.zm", "edu.zm", "sch.zm",
        "co.zw", "org.zw", "ac.zw", "gov.zw", "co.bw", "org.bw", "co.mw", "com.mw", "org.mw", "co.mz", "org.mz",
        "co.ke", "or.ke", "ac.ke", "go.ke", "ne.ke", "co.tz", "or.tz", "ac.tz", "go.tz", "co.ug", "or.ug", "ac.ug", "go.ug",
        "com.ng", "org.ng", "gov.ng", "edu.ng", "com.gh", "org.gh", "gov.gh", "edu.gh", "com.eg", "gov.eg", "edu.eg",
        "co.jp", "ne.jp", "or.jp", "ac.jp", "go.jp", "co.kr", "or.kr", "ac.kr", "go.kr",
        "co.in", "net.in", "org.in", "gov.in", "ac.in", "edu.in", "firm.in", "gen.in", "ind.in",
        "com.br", "net.br", "org.br", "gov.br", "edu.br", "com.cn", "net.cn", "org.cn", "gov.cn", "edu.cn",
        "com.mx", "org.mx", "gob.mx", "edu.mx", "com.ar", "gob.ar", "com.co", "gov.co", "com.pe", "gob.pe", "com.ve", "com.ec",
        "com.tr", "gov.tr", "edu.tr", "com.sg", "gov.sg", "edu.sg", "com.my", "gov.my", "edu.my",
        "co.id", "or.id", "ac.id", "go.id", "web.id", "com.ph", "gov.ph", "edu.ph", "com.vn", "gov.vn", "edu.vn",
        "com.tw", "gov.tw", "edu.tw", "com.hk", "gov.hk", "edu.hk", "com.pk", "gov.pk", "edu.pk",
        "com.sa", "gov.sa", "edu.sa", "com.ua", "gov.ua", "co.il", "org.il", "ac.il", "gov.il",
        "com.bd", "gov.bd", "com.np", "com.lk", "gov.lk", "co.th", "or.th", "ac.th", "go.th", "in.th",
        // Hosting suffixes: each subdomain is a different owner, so it's its own site.
        "github.io", "gitlab.io", "pages.dev", "workers.dev", "vercel.app", "netlify.app", "herokuapp.com", "appspot.com",
        "web.app", "firebaseapp.com", "blogspot.com", "azurewebsites.net", "cloudfront.net", "onrender.com", "glitch.me",
        "fly.dev", "repl.co", "replit.app", "ngrok.io", "ngrok-free.app", "trycloudflare.com", "s3.amazonaws.com",
        "wordpress.com", "wixsite.com", "squarespace.com", "myshopify.com", "carrd.co", "surge.sh", "deno.dev",
    )

    private val ipv4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    /** Host of a URL ("" when there is none), lowercased, without "www." or a port. Pure (no android.net.Uri). */
    fun hostOf(url: String): String {
        val m = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://(?:[^@/?#]*@)?(\\[[^\\]]*\\]|[^/:?#]+)").find(url.trim()) ?: return ""
        return m.groupValues[1].lowercase().trimEnd('.').removePrefix("www.")
    }

    /** The registrable domain of [host0] ("accounts.google.com" → "google.com"); IPs and single labels as they are. */
    fun registrable(host0: String): String {
        val host = host0.trim().lowercase().trimEnd('.').removePrefix("www.").substringBefore(':').let {
            if (host0.trim().startsWith("[")) host0.trim().lowercase() else it }
        if (host.isEmpty() || host.startsWith("[") || ipv4.matches(host) || !host.contains('.')) return host
        val labels = host.split('.').filter { it.isNotEmpty() }
        if (labels.size <= 2) return labels.joinToString(".")
        // Longest matching public suffix wins (three-label suffixes like s3.amazonaws.com first).
        for (n in 3 downTo 2) {
            if (labels.size <= n) continue
            val suffix = labels.takeLast(n).joinToString(".")
            if (suffix in multi) return labels.takeLast(n + 1).joinToString(".")
        }
        val last2 = labels.takeLast(2).joinToString(".")
        return if (last2 in multi) labels.takeLast(3).joinToString(".") else last2
    }

    fun siteOfUrl(url: String): String = registrable(hostOf(url))

    /** True when two hosts are the same site (and neither is empty). */
    fun sameSite(a: String, b: String): Boolean { val x = registrable(a); return x.isNotEmpty() && x == registrable(b) }
}
