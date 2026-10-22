package com.blaze.assistant.phone

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.blaze.assistant.agent.Processes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Watchers (reflexes): tiny on-device rules that react to what appears on screen without asking
 * the model. "When 'Sign in with Google' shows in Chrome, tap it, then arm the 'Choose an account'
 * watcher." They run off accessibility events, so a reaction takes tens of milliseconds instead of
 * a model round trip.
 *
 * Each watcher is true/false on every screen change (is its text visible?). When true it acts once
 * (tap a label, press back, or just signal), and can arm the next watcher, so a chain works like a
 * small state machine. Every watcher is a W process: listed, paused and deleted by ID.
 *
 * Safety: watchers never tap anything that pays, sends, deletes, installs or grants a permission,
 * and they stand down on captchas, codes and 2-step screens, leaving those to the model and user.
 */
object Watchers {
    data class W(
        val id: String, val name: String, val pkg: String, val see: String, val tap: String,
        val then: String, val repeat: Boolean, val expires: Long, val taskOnly: Boolean,
        var enabled: Boolean, var armed: Boolean, var fired: Int = 0, var lastFired: Long = 0
    )

    private lateinit var file: File
    private val list = mutableListOf<W>()
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val log = ArrayDeque<String>()
    @Volatile private var scheduled = false
    @Volatile private var acting = false
    private var lastHumanNote = 0L

    /** While Blaze is doing a phone task, task-only watchers (the built-in helpers) are live. */
    @Volatile var taskUntil = 0L
    fun taskTick() { taskUntil = SystemClock.uptimeMillis() + 90_000 }

    private val risky = Regex("""\b(pay|send|delete|remove|buy|purchase|order|install|uninstall|transfer|withdraw|deposit|allow|grant|permit|approve|confirm payment|sign out|log ?out|factory|reset|erase|format|subscribe|checkout|place order)\b""", RegexOption.IGNORE_CASE)
    private val needsHuman = Regex("""captcha|i'?m not a robot|verify (that )?you'?re human|verification code|one[- ]time (pass)?code|\botp\b|2-step|two[- ]step|authenticator|security code|enter the code""", RegexOption.IGNORE_CASE)

    /** Ready-made watchers the model can switch on by name. All task-only unless asked otherwise. */
    val presets: Map<String, List<Triple<String, String, String>>> = mapOf(
        // name to list of (see, tap, label)
        "popups" to listOf(
            Triple("Not now", "Not now", "Not now"), Triple("Maybe later", "Maybe later", "Maybe later"),
            Triple("No thanks", "No thanks", "No thanks"), Triple("Skip", "Skip", "Skip"), Triple("Later", "Later", "Later"),
            Triple("Rate this app", "back", "Rating prompt")),
        "cookies" to listOf(Triple("cookies", "Accept all", "Cookie banner"), Triple("cookies", "Got it", "Cookie notice"), Triple("cookies", "Accept", "Cookies")),
        "google_signin" to listOf(Triple("Sign in with Google", "Sign in with Google", "Google sign-in"),
            Triple("Continue with Google", "Continue with Google", "Continue with Google")),
        "continue_as" to listOf(Triple("Continue as", "Continue as", "Continue as")),
    )

    fun init(ctx: Context) {
        file = File(ctx.applicationContext.filesDir, "watchers.json")
        try {
            val a = JSONArray(file.readText())
            for (i in 0 until a.length()) { val o = a.getJSONObject(i)
                list += W(o.getString("id"), o.optString("name"), o.optString("pkg"), o.optString("see"), o.optString("tap"),
                    o.optString("then"), o.optBoolean("repeat"), o.optLong("expires"), o.optBoolean("taskOnly"),
                    o.optBoolean("enabled", true), o.optBoolean("armed", true), o.optInt("fired"))
            }
        } catch (_: Exception) { }
    }

    @Synchronized private fun save() {
        if (!::file.isInitialized) return
        try {
            file.writeText(JSONArray(list.map {
                JSONObject().put("id", it.id).put("name", it.name).put("pkg", it.pkg).put("see", it.see).put("tap", it.tap)
                    .put("then", it.then).put("repeat", it.repeat).put("expires", it.expires).put("taskOnly", it.taskOnly)
                    .put("enabled", it.enabled).put("armed", it.armed).put("fired", it.fired)
            }).toString())
        } catch (_: Exception) { }
    }

    @Synchronized fun add(name: String, pkg: String, see: String, tap: String, then: String, repeat: Boolean,
                          minutes: Int, taskOnly: Boolean, armed: Boolean = true): String {
        if (see.isBlank()) return "Say what text to watch for (when_visible)."
        if (tap.isNotBlank() && tap != "back" && risky.containsMatchIn(tap))
            return "I won't let a watcher tap \u201c$tap\u201d on its own: that could pay, send, delete, install or grant something. I'll ask you at that step instead."
        val id = Processes.nextId("watcher")
        val exp = if (minutes <= 0) 0L else System.currentTimeMillis() + minutes * 60_000L
        val w = W(id, name.ifBlank { see }.take(40), pkg, see, tap, then.trim().uppercase(), repeat, exp, taskOnly, true, armed)
        list += w; save()
        Processes.register("watcher", w.name, id, exp, JSONObject().put("app", pkg.ifBlank { "any" }).put("when_visible", see)
            .put("tap", tap.ifBlank { "(signal only)" }).put("then", w.then).put("repeat", repeat).put("task_only", taskOnly), id = id)
        return "Watcher $id on: when \u201c$see\u201d shows${if (pkg.isNotBlank()) " in $pkg" else ""}, " +
            (if (tap.isBlank()) "I'll note it" else if (tap == "back") "press back" else "tap \u201c$tap\u201d") +
            (if (w.then.isNotBlank()) ", then arm ${w.then}" else "") + (if (!armed) " (waits to be armed)" else "") + "."
    }

    fun preset(name: String, pkg: String, minutes: Int): String {
        val p = presets[name] ?: return "No preset $name. Presets: ${presets.keys.joinToString()}."
        val ids = p.map { (see, tap, label) -> add(label, pkg, see, tap, "", true, minutes, taskOnly = true).substringAfter("Watcher ").substringBefore(" ") }
        return "Preset $name on as ${ids.joinToString()} (only while I'm working on a task)."
    }

    @Synchronized fun remove(id: String): Boolean { val r = list.removeAll { it.id == id }; if (r) save(); return r }
    @Synchronized fun setEnabled(id: String, on: Boolean): Boolean {
        val w = list.firstOrNull { it.id == id } ?: return false
        w.enabled = on; if (on) w.armed = true; save(); return true
    }

    @Synchronized fun states(): Map<String, Boolean> = list.associate { it.id to it.enabled }

    /** What fired since the last call, for the model's next step. */
    @Synchronized fun drain(): String {
        if (log.isEmpty()) return ""
        val s = log.joinToString("; "); log.clear(); return "\nWatchers: $s"
    }
    private fun note(s: String) { synchronized(this) { log.addLast(s); while (log.size > 8) log.removeFirst() } }

    // ---------- Event loop ----------

    fun onEvent(svc: BlazeAccessibilityService, pkg: String) {
        if (list.isEmpty() || scheduled || acting) return
        scheduled = true
        main.postDelayed({ scheduled = false; try { check(svc, pkg) } catch (_: Exception) { } }, 60)
    }

    private fun live(pkg: String): List<W> {
        val now = System.currentTimeMillis()
        val task = SystemClock.uptimeMillis() < taskUntil
        synchronized(this) {
            val dead = list.filter { it.expires in 1..now }
            if (dead.isNotEmpty()) { list.removeAll(dead); save() }
            return list.filter { it.enabled && it.armed && (it.pkg.isBlank() || pkg.startsWith(it.pkg) || it.pkg.equals(pkg, true)) && (!it.taskOnly || task) }
        }
    }

    private fun check(svc: BlazeAccessibilityService, pkgHint: String) {
        val root = svc.rootInActiveWindow ?: return
        val pkg = root.packageName?.toString() ?: pkgHint
        if (pkg == svc.packageName) return
        val ws = live(pkg); if (ws.isEmpty()) return
        // Stand down on anything a human must do.
        val gate = root.findAccessibilityNodeInfosByText("code") + root.findAccessibilityNodeInfosByText("robot") +
            root.findAccessibilityNodeInfosByText("verif") + root.findAccessibilityNodeInfosByText("captcha")
        if (gate.any { n -> n.isVisibleToUser && needsHuman.containsMatchIn(listOfNotNull(n.text, n.contentDescription).joinToString(" ")) }) {
            val now = SystemClock.uptimeMillis()
            if (now - lastHumanNote > 20_000) { lastHumanNote = now; note("stood down: the screen wants a captcha or code, that's for you or the model") }
            return
        }
        for (w in ws) {
            val hit = root.findAccessibilityNodeInfosByText(w.see).firstOrNull { it.isVisibleToUser } ?: continue
            if (SystemClock.uptimeMillis() - w.lastFired < 1500) continue
            fire(svc, root, w, hit)
            return  // one action per screen change; the next event re-checks
        }
    }

    private fun fire(svc: BlazeAccessibilityService, root: AccessibilityNodeInfo, w: W, hit: AccessibilityNodeInfo) {
        w.lastFired = SystemClock.uptimeMillis()
        val after = {
            synchronized(this) {
                w.fired++
                if (!w.repeat) w.armed = false
                if (w.then.isNotBlank()) list.firstOrNull { it.id == w.then }?.let { it.armed = true; it.enabled = true }
                save()
            }
        }
        when {
            w.tap.isBlank() -> { note("${w.id} saw \u201c${w.see}\u201d"); after() }
            w.tap == "back" -> { svc.press("back"); note("${w.id} pressed back on \u201c${w.see}\u201d"); after() }
            else -> {
                val target = if (w.tap.equals(w.see, true)) hit
                    else root.findAccessibilityNodeInfosByText(w.tap).firstOrNull { it.isVisibleToUser }
                if (target == null) return
                val words = listOfNotNull(target.text, target.contentDescription).joinToString(" ")
                if (risky.containsMatchIn(words)) { note("${w.id} skipped \u201c$words\u201d: needs your OK"); return }
                acting = true
                scope.launch {
                    try { svc.tapNode(target); note("${w.id} tapped \u201c${w.tap}\u201d"); after() }
                    finally { acting = false }
                }
            }
        }
    }

    // ---------- Tool ----------

    fun tool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject().put("name", "watch")
        .put("description", "Set a watcher (reflex): when some text appears on screen, react instantly on the phone without a model step: tap a label, press back, or just signal. " +
            "Chain them with then (the ID of a watcher created with armed=false) to walk a sign-in flow. Or switch on a preset: " + presets.keys.joinToString() +
            ". Watchers never pay, send, delete, install or grant permissions and stop at captchas/codes. Manage with processes/process (IDs start with W).")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("when_visible", JSONObject().put("type", "string").put("description", "Text that must be on screen"))
            .put("tap", JSONObject().put("type", "string").put("description", "Label to tap, 'back', or empty to just signal"))
            .put("app", JSONObject().put("type", "string").put("description", "Package to limit it to, e.g. com.android.chrome"))
            .put("then", JSONObject().put("type", "string").put("description", "Watcher ID to arm after this fires"))
            .put("armed", JSONObject().put("type", "string").put("enum", JSONArray(listOf("on", "off"))))
            .put("repeat", JSONObject().put("type", "string").put("enum", JSONArray(listOf("on", "off"))))
            .put("minutes", JSONObject().put("type", "integer").put("description", "Lifetime, 0 = until deleted. Default 30"))
            .put("preset", JSONObject().put("type", "string").put("enum", JSONArray(presets.keys.toList())))
            .put("name", JSONObject().put("type", "string")))))

    fun run(a: JSONObject): String {
        val minutes = if (a.has("minutes")) a.optInt("minutes") else 30
        val pkg = a.optString("app").trim()
        if (a.optString("preset").isNotBlank()) return preset(a.optString("preset"), pkg, minutes)
        return add(a.optString("name"), pkg, a.optString("when_visible"), a.optString("tap").trim(), a.optString("then"),
            a.optString("repeat") == "on", minutes, taskOnly = false, armed = a.optString("armed") != "off")
    }
}
