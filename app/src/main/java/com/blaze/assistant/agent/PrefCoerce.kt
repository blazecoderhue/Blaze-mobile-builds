package com.blaze.assistant.agent

/**
 * v0.45.5 hotfix (P0, user 2026-10-20 20:38): "hey how's it going" failed at once with
 * "java.lang.String cannot be cast to java.lang.Boolean".
 *
 * Root cause: two features shared the pref key "followups". Settings.followups (the "Suggested follow-ups" switch)
 * reads it with getBoolean; the Follow-ups tracker (proactive/FollowUps, v0.38.0) saved its list of tracked plans under
 * the same key as a JSON string. Once a chat notification carried a plan, the string replaced the boolean and every
 * turn crashed building its prompt (Agent.systemPrompt and ChatLane read Settings.followups). Cloud sync also carries
 * "followups", so the string could reach the user's other phones.
 *
 * Now: the tracker has its own key ([FOLLOWUP_ITEMS]); every boolean / string pref read tolerates the other type
 * ([bool] / [str]); [migrate] fixes stored values once at startup (the tracker's list moves to its key, "true"/"1"
 * strings become booleans); and synced settings are written with the type this phone uses ([forSync]).
 * Pure Kotlin (PrefCoerceTest).
 */
object PrefCoerce {
    const val FOLLOWUP_ITEMS = "followup_items"
    const val OLD_SHARED = "followups"
    const val MIGRATED = "pref_types_v0455"

    /** Every pref the app reads as a boolean. */
    val BOOL_KEYS = setOf("a11y_seen", "adaptive_think", "app_lock", "auto_hide_panel", "auto_learn", "auto_titles", "bg_agents", "call_bye",
        "call_earpiece", "call_greet", "chat_lane", "classroom_on", "compact_panel", "confirm_risky", "dash_auto", "dash_lan", "digest_mode", "duo_on",
        "duo_split", "duo_team", "evening_brief", "fast_lane", "fastest_first", "fold_answers", "followups", "habit_offers", "haptics", "home_headsups",
        "home_look_glass_v31", "home_onboarded", "home_wifi_only", "jarvis_camera", "jarvis_screen", "key_catch_armed", "key_catcher", "learn_approval",
        "learned_router", "life_logs_record", "long_chat", "look_music", "look_still", "low_data", "mh_teach_intro_seen", "mind_review", "mode_bar",
        "morning_brief", "myvoice_keep_slow", "onboarded", "orb_pinned", "particles", "pc_inbox", "phone_control", "proactive", "proactive_speak",
        "prompt_stable_order", "quick_think", "rich_replies", "router_autostart", "screen_memory", "secrets_dropped_told", "show_reasoning",
        "show_route", "show_steps", "show_taps", "skill_starters_done", "sounds", "speak_replies", "speak_streaming", "specialists_on",
        "speculative_fetch", "speed_mode", "stream", "task_card", "terminal_tools", "token_ledger", "tool_cache", "tool_diet", "tool_lean",
        "tool_slim", "v19_fl_min", "voice_loop", "wake_charging", "wake_gate", "wake_pocket", "wake_screen_off", "wake_word")

    private val yes = setOf("true", "1", "yes", "on")
    private val no = setOf("false", "0", "no", "off")

    /** A boolean from whatever was stored (Boolean, "true"/"false"/"1"/"0", a number); [def] for anything else. */
    fun bool(v: Any?, def: Boolean): Boolean = when (v) {
        is Boolean -> v
        is String -> v.trim().lowercase().let { if (it in yes) true else if (it in no) false else def }
        is Int -> v != 0
        is Long -> v != 0L
        is Float -> v != 0f
        else -> def
    }

    /** The boolean a string means, or null when it isn't one. */
    fun parseBool(s: String?): Boolean? = s?.trim()?.lowercase()?.let { if (it in yes) true else if (it in no) false else null }

    /** A string from whatever was stored (a Boolean or number becomes its text); [def] for null / a string set. */
    fun str(v: Any?, def: String): String = when (v) {
        null -> def
        is String -> v
        is Boolean, is Int, is Long, is Float -> v.toString()
        else -> def
    }

    /** One startup fix to the stored prefs. */
    sealed class Op {
        data class PutBool(val key: String, val v: Boolean) : Op()
        data class PutStr(val key: String, val v: String) : Op()
        data class Remove(val key: String) : Op()
    }

    /**
     * The fixes for [all] (the prefs as stored): the tracker's list under "followups" moves to [FOLLOWUP_ITEMS] (kept when
     * that already has a list), and "followups" goes back to its default switch; a boolean key holding "true"/"1"/"false"/"0"
     * becomes a real boolean; a boolean key holding anything else is removed so its default applies. Everything else is kept.
     */
    fun migrate(all: Map<String, *>): List<Op> {
        val ops = ArrayList<Op>()
        val shared = all[OLD_SHARED]
        if (shared is String) {
            val t = shared.trim()
            if (parseBool(t) == null) {
                if (t.startsWith("[") && all[FOLLOWUP_ITEMS] !is String) ops += Op.PutStr(FOLLOWUP_ITEMS, t)
                ops += Op.Remove(OLD_SHARED)
            }
        }
        for ((k, v) in all) {
            if (k !in BOOL_KEYS || v !is String || (k == OLD_SHARED && ops.any { it is Op.Remove && it.key == k })) continue
            val b = parseBool(v)
            ops += if (b != null) Op.PutBool(k, b) else Op.Remove(k)
        }
        return ops
    }

    /**
     * A synced setting from another device, written with the type this phone uses: a boolean key gets a Boolean (a
     * "true"/"1" string is converted; any other string is refused = null); otherwise the incoming value as it is.
     * Returns [SKIP] when it must not be written.
     */
    val SKIP = Any()
    fun forSync(key: String, local: Any?, incoming: Any?): Any? {
        if (incoming == null) return null
        val wantBool = key in BOOL_KEYS || local is Boolean
        if (wantBool) return when (incoming) {
            is Boolean -> incoming
            is String -> parseBool(incoming) ?: SKIP
            is Int, is Long -> bool(incoming, false)
            else -> SKIP
        }
        if (local is String && incoming is Boolean) return incoming.toString()
        return incoming
    }
}
