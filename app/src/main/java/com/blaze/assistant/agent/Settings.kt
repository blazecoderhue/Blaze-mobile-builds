package com.blaze.assistant.agent

import android.content.Context
import android.content.SharedPreferences

object Settings {
    const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/openai/"
    const val DEFAULT_MODEL = "gemini-3.8-flash"

    private lateinit var prefs: SharedPreferences

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("blaze", Context.MODE_PRIVATE)
        migrateTypes()
        // v1.1 kept one key/model/address. Move them under Gemini (or Custom if the address was changed).
        if (!prefs.contains("provider")) {
            val oldBase = s("base_url", DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
            val pid = if (oldBase.contains("generativelanguage")) "gemini" else Providers.CUSTOM
            val e = prefs.edit().putString("provider", pid)
            s("api_key", null)?.let { e.putString("key_$pid", it) }
            s("model", null)?.let { e.putString("model_$pid", it) }
            if (pid == Providers.CUSTOM) e.putString("custom_base", oldBase)
            e.apply()
        }
        // v1.9: the fast lane misread things like "shut down" at 0.3; raise an untouched low threshold.
        if (!b("v19_fl_min", false)) {
            val e = prefs.edit().putBoolean("v19_fl_min", true)
            if (prefs.getFloat("fast_lane_min", 0.55f) < 0.5f) e.putFloat("fast_lane_min", 0.55f)
            e.apply()
        }
        // v0.9.1: secrets restored from another phone (Android backup / device transfer) were encrypted with
        // that phone's Keystore key, which never leaves it. They can't be read here, so drop them, and drop
        // their key-sync stamps too so the Blaze account's copy wins instead of being overwritten with nothing.
        dropUnreadableSecrets()
        // v0.9.1: key_rest was wrongly treated as a secret and encrypted at start; it's only resting marks.
        if (Secrets.isEncrypted(s("key_rest", "") ?: "")) prefs.edit().remove("key_rest").apply()
        // v1.7: API keys and tokens move into Keystore-encrypted form (plain values still read).
        try {
            val e = prefs.edit(); var n = 0
            for ((k, v) in prefs.all) if (Secrets.isSecretPref(k) && v is String && v.isNotEmpty() && !Secrets.isEncrypted(v)) {
                val enc = Secrets.encrypt(v); if (enc != v) { e.putString(k, enc); n++ }
            }
            if (n > 0) e.apply()
        } catch (_: Exception) { }
        // 2.x Gemini models are closed to new users; move old installs to the current default.
        if (s("model_gemini", null)?.startsWith("gemini-2.") == true) {
            prefs.edit().putString("model_gemini", DEFAULT_MODEL).apply()
        }
    }

    private fun dropUnreadableSecrets() {
        try {
            val enc = prefs.all.filter { (k, v) -> Secrets.isSecretPref(k) && v is String && Secrets.isEncrypted(v) }.keys
            if (enc.isEmpty() || Secrets.hasKey()) return
            val e = prefs.edit()
            enc.forEach { k ->
                e.remove(k)
                Secrets.providerOf(k)?.let { e.remove("keysync_t_$it") }
            }
            if ("mcp_config" in enc) e.remove("keysync_t_mcp")
            e.putString("secrets_dropped", enc.size.toString()).putBoolean("secrets_dropped_told", false).commit()
            com.blaze.assistant.device.CrashLog.write("Dropped ${enc.size} secrets restored from another phone")
        } catch (_: Exception) { }
    }

    /** How many saved keys/sign-ins came from another phone and had to be dropped (0 if none). */
    val secretsDropped: Int get() = s("secrets_dropped", "")?.toIntOrNull() ?: 0

    /** Once per restore: a heads-up saying keys and sign-ins need to come back. Call after Notifier.init. */
    fun tellIfSecretsDropped() {
        if (secretsDropped == 0 || b("secrets_dropped_told", true)) return
        prefs.edit().putBoolean("secrets_dropped_told", true).apply()
        try {
            com.blaze.assistant.notify.Notifier.proactive(
                "Your settings and chats came over from your old phone, but API keys and sign-ins can't move between phones. " +
                    "Sign in to your Blaze account to sync your model keys back, then reconnect your accounts in Settings.",
                "", false)
        } catch (_: Exception) { }
    }

    /**
     * v0.25.0: there is one brain, Blaze Cloud (the gateway): it holds the provider keys and picks the model for
     * every message. The old saved provider / model / key prefs stay on disk untouched (backward compatible) but
     * are never read for routing, and setting them does nothing.
     */
    var providerId: String
        get() = Providers.GATEWAY
        @Suppress("UNUSED_PARAMETER") set(v) { }

    /** The provider an older version saved (diagnostics only; ignored for routing). */
    val savedProviderId: String get() = s("provider", null) ?: ""

    /** The Blaze account session token the gateway takes as its API key ("" when signed out). */
    fun gatewayToken(): String = try { com.blaze.assistant.connect.BlazeAccount.sessionToken() } catch (_: Throwable) { "" }

    val provider: Provider get() = Providers.byId(providerId)

    fun keyFor(pid: String) = Secrets.decrypt(s("key_$pid", "") ?: "")
    fun setKeyFor(pid: String, v: String) {
        val t = v.trim()
        if (t == keyFor(pid)) return
        prefs.edit().putString("key_$pid", Secrets.encrypt(t)).remove("secrets_dropped").apply()
        com.blaze.assistant.connect.KeySync.localChange(pid)   // follows the Blaze account to the PC
    }
    /** Writes a key that came from the Blaze account (no new change stamp, no sync back). */
    fun setKeyForSynced(pid: String, v: String) {
        val t = v.trim()
        if (t.isEmpty()) prefs.edit().remove("key_$pid").apply()
        else prefs.edit().putString("key_$pid", Secrets.encrypt(t)).remove("secrets_dropped").apply()   // keys are back
    }

    /** Any secret pref (connector tokens etc.), stored encrypted. */
    /**
     * v0.45.8: a decrypted secret is kept in memory, keyed by its stored ciphertext, so the session token isn't decrypted
     * (Android Keystore) on every request; a new value has a new ciphertext, so it is never stale.
     */
    fun secret(name: String): String {
        val raw = s(name, "") ?: ""
        if (raw.isEmpty()) return ""
        secretMemo[raw]?.let { return it }
        val v = Secrets.decrypt(raw)
        if (v.isNotEmpty()) { if (secretMemo.size > 32) secretMemo.clear(); secretMemo[raw] = v }
        return v
    }
    private val secretMemo = java.util.concurrent.ConcurrentHashMap<String, String>()
    fun setSecret(name: String, v: String) {
        if (v.isEmpty()) prefs.edit().remove(name).apply() else prefs.edit().putString(name, Secrets.encrypt(v)).apply()
    }
    fun str(name: String, def: String = "") = s(name, def) ?: def

    // v0.45.5 hotfix: every boolean / string read tolerates the other type (PrefCoerce); a settings read never crashes a turn.
    private fun b(name: String, def: Boolean): Boolean = try { prefs.getBoolean(name, def) }
        catch (_: ClassCastException) { try { PrefCoerce.bool(prefs.all[name], def) } catch (_: Throwable) { def } }
    private fun s(name: String, def: String?): String? = try { prefs.getString(name, def) }
        catch (_: ClassCastException) { try { prefs.all[name].let { if (it == null) def else PrefCoerce.str(it, def ?: "") } } catch (_: Throwable) { def } }

    /** v0.45.5 hotfix: fixes stored value types once (PrefCoerce.migrate). Upgrades keep their data. */
    private fun migrateTypes() {
        if (try { prefs.getBoolean(PrefCoerce.MIGRATED, false) } catch (_: ClassCastException) { false }) return
        try {
            val ops = PrefCoerce.migrate(prefs.all)
            val e = prefs.edit()
            for (op in ops) when (op) {
                is PrefCoerce.Op.PutBool -> e.remove(op.key).putBoolean(op.key, op.v)
                is PrefCoerce.Op.PutStr -> e.remove(op.key).putString(op.key, op.v)
                is PrefCoerce.Op.Remove -> e.remove(op.key)
            }
            e.putBoolean(PrefCoerce.MIGRATED, true).commit()
        } catch (_: Throwable) { }
    }
    fun setStr(name: String, v: String) = prefs.edit().putString(name, v).apply()
    fun bool(name: String, def: Boolean) = b(name, def)
    fun int(name: String, def: Int) = try { prefs.getInt(name, def) } catch (_: ClassCastException) { def }
    fun setInt(name: String, v: Int) = prefs.edit().putInt(name, v).apply()

    // v0.40.0 cloud sync (connect/CloudSync): raw access for its allow-list of user choices (never secrets).
    fun rawAll(): Map<String, *> = prefs.all
    fun rawPut(name: String, v: Any?) {
        val e = prefs.edit()
        when (v) {
            null -> e.remove(name); is String -> e.putString(name, v); is Boolean -> e.putBoolean(name, v)
            is Int -> e.putInt(name, v); is Long -> e.putLong(name, v); is Float -> e.putFloat(name, v)
            else -> return
        }
        e.apply()
    }
    fun listen(l: SharedPreferences.OnSharedPreferenceChangeListener) = prefs.registerOnSharedPreferenceChangeListener(l)
    fun setBool(name: String, v: Boolean) = prefs.edit().putBoolean(name, v).apply()
    /** Wave B-4: drops saved keys that no feature reads any more (e.g. the removed Linux desktop's prefs). */
    fun removeKeys(names: Collection<String>) { if (names.isNotEmpty()) prefs.edit().apply { names.forEach { remove(it) } }.apply() }

    /** Fast lane (on-device Needle model) for direct commands. */
    var fastLane: Boolean
        get() = b("fast_lane", true)
        set(v) = prefs.edit().putBoolean("fast_lane", v).apply()
    /** Minimum Needle confidence (0-1) to act without the cloud. */
    var fastLaneMin: Float
        get() = prefs.getFloat("fast_lane_min", 0.55f)
        set(v) = prefs.edit().putFloat("fast_lane_min", v).apply()

    /** Optional quicker model for in-between phone steps; blank = use the main model. */
    var stepsProvider: String
        get() = s("steps_provider", "") ?: ""
        set(v) = prefs.edit().putString("steps_provider", v).apply()
    var stepsModel: String
        get() = s("steps_model", "") ?: ""
        set(v) = prefs.edit().putString("steps_model", v.trim()).apply()

    /** Auto (free) chain: JSON array of "providerId|model". */
    var freeChain: String
        get() = s("free_chain", "") ?: ""
        set(v) = prefs.edit().putString("free_chain", v).apply()
    /** Start the next model in the chain if the first hasn't answered within this many ms (0 = off). */
    var hedgeMs: Int
        get() = prefs.getInt("hedge_ms", 0)
        set(v) = prefs.edit().putInt("hedge_ms", v).apply()

    /** Self-improvement: learn memories and skills after chats. */
    var autoLearn: Boolean
        get() = b("auto_learn", true)
        set(v) = prefs.edit().putBoolean("auto_learn", v).apply()
    /** v0.8.0: AI chat titles from the conversation's main theme (Desktop titles.go). */
    var autoTitles: Boolean
        get() = b("auto_titles", true)
        set(v) = prefs.edit().putBoolean("auto_titles", v).apply()
    var learnApproval: Boolean
        get() = b("learn_approval", false)
        set(v) = prefs.edit().putBoolean("learn_approval", v).apply()
    /** Opt-in: keep recent on-screen text on the phone so Blaze can recall it. */
    var screenMemory: Boolean
        get() = b("screen_memory", false)
        set(v) = prefs.edit().putBoolean("screen_memory", v).apply()

    var apiKey: String
        get() = if (providerId == Providers.GATEWAY) gatewayToken() else keyFor(providerId)
        set(v) = setKeyFor(providerId, v)

    var customBase: String
        get() = s("custom_base", "") ?: ""
        set(v) = prefs.edit().putString("custom_base", v.trim()).apply()

    /** True when Blaze can answer: a key is set, or Auto (free) is picked. */
    val ready: Boolean get() = gatewayToken().isNotBlank()

    val baseUrl: String
        get() = if (providerId == Providers.CUSTOM) customBase else provider.baseUrl

    fun modelFor(pid: String) = s("model_$pid", null) ?: Providers.byId(pid).defaultModel
    /** v0.25.0: the gateway picks the model; this is its "pick for me" name. Setting it does nothing. */
    var model: String
        get() = Cloud.MODEL
        @Suppress("UNUSED_PARAMETER") set(v) { }

    /** Long-chat engine (v0.15.0): rolling summary, search_chat, artifacts, token meter. */
    var longChat: Boolean
        get() = b("long_chat", true)
        set(v) = prefs.edit().putBoolean("long_chat", v).apply()

    /**
     * Low-data mode (student pack, v0.16.0): text models get the turn (photos are described to text once by a
     * vision model instead of being re-sent every step), screenshots are skipped for the text screen readout,
     * the instant picture shortcut is off, and each answer shows the MB Blaze used.
     */
    var lowData: Boolean
        get() = b("low_data", false)
        set(v) = prefs.edit().putBoolean("low_data", v).apply()

    var phoneControl: Boolean
        get() = b("phone_control", true)
        set(v) = prefs.edit().putBoolean("phone_control", v).apply()

    /** Read replies aloud when you asked by voice. */
    var speakReplies: Boolean
        get() = b("speak_replies", true)
        set(v) = prefs.edit().putBoolean("speak_replies", v).apply()

    /** Listen for "Hey Blaze" / "Wake up Blaze" in the background. */
    var wakeWord: Boolean
        get() = b("wake_word", false)
        set(v) = prefs.edit().putBoolean("wake_word", v).apply()

    var personaId: String
        get() = s("persona", "blaze") ?: "blaze"
        set(v) = prefs.edit().putString("persona", v).apply()

    var customPersona: String
        get() = s("persona_custom", "") ?: ""
        set(v) = prefs.edit().putString("persona_custom", v).apply()

    var streamReplies: Boolean
        get() = b("stream", true)
        set(v) = prefs.edit().putBoolean("stream", v).apply()

    var lastChatId: String
        get() = s("last_chat", "") ?: ""
        set(v) = prefs.edit().putString("last_chat", v).apply()

    /** Everything in settings (keys included) as JSON, for Backup → Export. */
    fun exportJson(): String {
        val o = org.json.JSONObject().put("blaze_backup", 1)
        val p = org.json.JSONObject()
        for ((k, v) in prefs.all) {
            if (k.startsWith("model_cache")) continue
            when (v) {
                is String -> p.put(k, if (Secrets.isSecretPref(k)) Secrets.decrypt(v) else v)
                is Boolean, is Int, is Long, is Float -> p.put(k, v)
                is Set<*> -> p.put(k, org.json.JSONArray(v.toList()))
            }
        }
        return o.put("prefs", p).toString(2)
    }

    /** Restore an export. Returns how many settings were restored. */
    fun importJson(text: String): Int {
        val p = org.json.JSONObject(text).getJSONObject("prefs")
        val e = prefs.edit()
        var n = 0
        for (k in p.keys()) {
            when (val v = p.get(k)) {
                is Boolean -> e.putBoolean(k, v)
                is Int -> e.putInt(k, v)
                is Long -> e.putLong(k, v)
                is Double -> e.putFloat(k, v.toFloat())
                is String -> e.putString(k, if (Secrets.isSecretPref(k)) Secrets.encrypt(v) else v)
                else -> continue
            }
            n++
        }
        e.commit()
        return n
    }

    /** Slim live task card over other apps while Blaze works. */
    var taskCard: Boolean
        get() = b("task_card", true)
        set(v) = prefs.edit().putBoolean("task_card", v).apply()

    /** Haptic ticks when actions land. */
    var haptics: Boolean
        get() = b("haptics", true)
        set(v) = prefs.edit().putBoolean("haptics", v).apply()

    /** Chat text size multiplier (accessibility). */
    var textScale: Float
        get() = prefs.getFloat("text_scale", 1f)
        set(v) = prefs.edit().putFloat("text_scale", v).apply()

    /** Small "⚡ on-device" / "☁ model · 2.1 s" badge under replies. */
    var showRoute: Boolean
        get() = b("show_route", true)
        set(v) = prefs.edit().putBoolean("show_route", v).apply()

    /** Drifting background dots. Off = lightest on slower phones. */
    var particles: Boolean
        get() = b("particles", true)
        set(v) = prefs.edit().putBoolean("particles", v).apply()

    /** Show the model's reasoning (brain button) when the provider sends it. */
    var showReasoning: Boolean
        get() = b("show_reasoning", true)
        set(v) = prefs.edit().putBoolean("show_reasoning", v).apply()

    /** Show every step as it happens (the old list) instead of one calm plain-English line. */
    var showSteps: Boolean
        get() = b("show_steps", false)
        set(v) = prefs.edit().putBoolean("show_steps", v).apply()

    /** Fold long tables, lists, code and reports into cards you tap to open. */
    var foldAnswers: Boolean
        get() = b("fold_answers", true)
        set(v) = prefs.edit().putBoolean("fold_answers", v).apply()

    /** system, light or dark (the sun/moon button in the top bar). */
    var themeMode: String
        get() = s("theme_mode", "system") ?: "system"
        set(v) = prefs.edit().putString("theme_mode", v).apply()

    /** Three tap-to-send suggestions under replies. */
    var followups: Boolean
        get() = b("followups", true)
        set(v) = prefs.edit().putBoolean("followups", v).apply()

    /** User-made slash commands: JSON array of {name, desc, prompt}. */
    var userCommands: String
        get() = s("user_commands", "[]") ?: "[]"
        set(v) = prefs.edit().putString("user_commands", v).apply()

    var confirmRisky: Boolean
        get() = b("confirm_risky", true)
        set(v) = prefs.edit().putBoolean("confirm_risky", v).apply()

    var appLock: Boolean
        get() = b("app_lock", false)
        set(v) = prefs.edit().putBoolean("app_lock", v).apply()

    var orbPinned: Boolean
        get() = b("orb_pinned", false)
        set(v) = prefs.edit().putBoolean("orb_pinned", v).apply()
    var orbX: Int
        get() = prefs.getInt("orb_x", -1)
        set(v) = prefs.edit().putInt("orb_x", v).apply()
    var orbY: Int
        get() = prefs.getInt("orb_y", -1)
        set(v) = prefs.edit().putInt("orb_y", v).apply()

    var userName: String
        get() = s("user_name", "") ?: ""
        set(v) = prefs.edit().putString("user_name", v.trim()).apply()

    /**
     * v0.45.9: the name Blaze may greet the user by: the one they typed (Settings › Your name), else the signed-in Blaze
     * account's first name, else none. Never any other name the prompt mentions (0.45.8: "Hey Luke" with no name set).
     */
    fun greetName(): String? = GreetName.pick(userName, try { com.blaze.assistant.connect.BlazeAccount.user()?.name } catch (_: Throwable) { null })

    /** Last plan-detection result, per provider. */
    var modelCache: String
        get() = s("model_cache_$providerId", "") ?: ""
        set(v) = prefs.edit().putString("model_cache_$providerId", v).apply()
}
