package com.blaze.assistant.agent

/**
 * v0.26.2: questions about the phone's own state ("what sound profile is my phone set to?", "am I on mute?",
 * "how's my battery", "am I on Wi-Fi") are answered on the phone, instantly, from the same readings as the
 * "Phone now" prompt line (DeviceStatus), with no model call and no tools. A question that still reaches the
 * model (longer, mixed) gets [RULE] and no skill packs, so nothing pushes it into a tool loop.
 * Pure (PhoneStateTest).
 */
object PhoneState {
    enum class Kind { RINGER, BATTERY, NETWORK }

    const val RULE = "\nPHONE STATE QUESTIONS: for the ringer / sound profile (normal, vibrate, silent, mute), Do Not Disturb, battery, charging, " +
        "network, Wi-Fi or mobile data, answer straight from the 'Phone now' line in one sentence. No tools, no skills, no steps."

    private val ask = Regex("^(what|what's|whats|which|is|am|are|do|does|how|how's|hows|check|tell me|show me)\\b|\\?\\s*$", RegexOption.IGNORE_CASE)
    private val change = Regex("^(?:please |can you |could you )?(turn|switch|set|put|change|make|mute|unmute|silence|enable|disable|toggle|raise|lower|increase|decrease)\\b", RegexOption.IGNORE_CASE)
    private val ringer = Regex("\\b(sound profile|sound mode|ringer|ring(?:ing)? mode|ringtone mode|on mute|muted|mute|silent|on silent|vibrat\\w*|do not disturb|dnd|volume profile|sound on|sounds? off)\\b", RegexOption.IGNORE_CASE)
    private val battery = Regex("\\b(battery|charging|charge level|percent(?:age)? left)\\b", RegexOption.IGNORE_CASE)
    private val network = Regex("\\b(wi-?fi|mobile data|data connection|network|signal|connected to the internet|online)\\b", RegexOption.IGNORE_CASE)
    private val other = Regex("\\b(why|fix|how do i|how to|steps|settings app|app\\b|apps\\b|notification|message|text|email|call|whatsapp|song|music|video|alarm)", RegexOption.IGNORE_CASE)

    /** The phone-state question [text] asks, or null (an action, a longer task, or not about the phone's state). */
    fun kind(text: String): Kind? {
        val t = text.trim().replace(Regex("\\s+"), " ")
        if (t.isEmpty() || t.length > 90 || t.startsWith("/") || t.startsWith("[")) return null
        val low = t.lowercase().removePrefix("hey blaze ").removePrefix("blaze ").removePrefix("blaze, ")
        if (change.containsMatchIn(low)) return null
        if (!ask.containsMatchIn(low)) return null
        if (other.containsMatchIn(low)) return null
        return when {
            ringer.containsMatchIn(low) -> Kind.RINGER
            battery.containsMatchIn(low) -> Kind.BATTERY
            network.containsMatchIn(low) -> Kind.NETWORK
            else -> null
        }
    }

    /** One sentence from the readings ([ringer] = normal | vibrate | silent; [dnd] = Do Not Disturb is on). */
    fun answer(k: Kind, ringer: String, dnd: Boolean, battery: Int, charging: Boolean, network: String): String = when (k) {
        Kind.RINGER -> when (ringer.lowercase()) {
            "silent" -> "Your phone is on silent: no ring and no vibration" + (if (dnd) ", and Do Not Disturb is on too." else ".")
            "vibrate" -> "Your phone is on vibrate, so calls buzz but don't ring" + (if (dnd) ", and Do Not Disturb is on." else ".")
            else -> if (dnd) "The ringer is on normal, but Do Not Disturb is on, so most calls and notifications stay quiet."
                else "You're not on mute: the ringer is on normal, so calls and notifications ring."
        }
        Kind.BATTERY -> if (battery < 0) "I can't read the battery right now." else "$battery%${if (charging) " and charging" else ""}."
        Kind.NETWORK -> "You're on ${network.ifBlank { "an unknown network" }}."
    }
}
