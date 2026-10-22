package com.blaze.assistant.agent

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * v0.39.0 Errors as fixes: when a tool result says an access Blaze needs is off, the chat shows a one-button card
 * ("Turn it on") that opens the right system screen, instead of an error. The matching is pure (FixCardsTest);
 * the card and the intent live in ui/SimpleCards.
 */
object FixCards {
    enum class Kind(val title: String, val body: String, val action: String) {
        NOTIFICATIONS("Turn on notification access?",
            "This lets me see new messages so I can tell you what they say. I never send anything without asking you.",
            "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"),
        PHONE_CONTROL("Turn on phone control?",
            "This lets me open apps, tap and type for you. You can pause me any time by touching the screen.",
            "android.settings.ACCESSIBILITY_SETTINGS"),
        USAGE("Turn on usage access?",
            "This lets me see which apps you used today, so I can help at the right moment.",
            "android.settings.USAGE_ACCESS_SETTINGS"),
        OVERLAY("Let Blaze show over other apps?",
            "This lets me float a small card over the app you're in while I work.",
            "android.settings.action.MANAGE_OVERLAY_PERMISSION"),
        APP_PERMISSION("Allow this for Blaze?",
            "Blaze needs one more permission for that. Turn it on in Blaze's app settings, then ask me again.",
            "android.settings.APPLICATION_DETAILS_SETTINGS"),
    }

    data class Fix(val kind: Kind, val what: String, val n: Long = System.nanoTime()) {
        /** "I couldn't read WhatsApp" style headline. */
        val headline: String get() = what.ifBlank { "I couldn't do that yet" }
    }

    private val rules = listOf(
        Regex("(?i)notification access (is )?(off|not (on|granted|enabled))|turn on notification access|needs notification access") to Kind.NOTIFICATIONS,
        Regex("(?i)phone control is off|accessibility (service )?(is )?(off|not (on|enabled))|turn on .?blaze phone control") to Kind.PHONE_CONTROL,
        Regex("(?i)needs usage access|usage access (is )?(off|not granted)") to Kind.USAGE,
        Regex("(?i)(overlay|draw over other apps|display over other apps) (permission )?(is )?(off|not granted|needed)") to Kind.OVERLAY,
        Regex("(?i)\\b(permission (denied|not granted|is off|missing))|(needs the [a-z ]{3,30} permission)") to Kind.APP_PERMISSION,
    )

    /** The fix a tool result asks for, or null. */
    fun kindOf(result: String): Kind? {
        val t = result.take(600)
        return rules.firstOrNull { it.first.containsMatchIn(t) }?.second
    }

    /** What couldn't happen, in plain words, from the tool that hit the wall. */
    fun headline(tool: String, kind: Kind): String = when (kind) {
        Kind.NOTIFICATIONS -> if (tool.contains("whatsapp", true)) "I couldn't read WhatsApp" else "I couldn't read your messages"
        Kind.PHONE_CONTROL -> "I couldn't use your phone for that"
        Kind.USAGE -> "I couldn't see which apps you used"
        Kind.OVERLAY -> "I couldn't show over other apps"
        Kind.APP_PERMISSION -> "I couldn't do that yet"
    }

    /** The card on screen now (null = none). The UI clears it after a tap. */
    val current = MutableStateFlow<Fix?>(null)

    fun afterTool(tool: String, result: String) {
        val k = kindOf(result) ?: return
        current.value = Fix(k, headline(tool, k))
    }

    /** A line for the model so it doesn't also print an error: the card already shows the fix. */
    const val NOTE = "\n(The user now sees a one-button card in the chat that turns this on. Say in one plain line what you couldn't do; no error codes, no settings paths.)"
}
