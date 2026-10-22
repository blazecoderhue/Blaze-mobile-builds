package com.blaze.assistant.agent

import org.json.JSONObject

/**
 * v0.45.0 recovery: one-shot actions never repeat. A message, a payment, a call, a delete or an uninstall that already
 * ran in this turn (or in the Cloud run this turn resumed) with the same arguments is NOT run again when the model
 * (or a retry, a re-prompt, a resumed run) asks for it a second time: the earlier result comes back instead, marked.
 * A different text or recipient is a new action and runs. Pure Kotlin; OneShotTest.
 */
class OneShot {
    companion object {
        /** Tools whose effect can't be taken back or would annoy someone twice. */
        val RISKY = setOf("message_contact", "send_sms", "send_text", "reply_to_notification", "notif_reply", "gmail_send", "send_email", "slack_post",
            "call_contact", "call_number", "dial", "run_ussd", "file_delete", "delete_routine", "delete_trigger", "delete_event", "uninstall_app",
            "memory_forget", "memory_delete", "share_to_app", "share_text", "github_install_apk", "pay", "payment", "send_money", "momo_send", "browser_submit", "post_notification")

        private val payish = Regex("(?i)pay|purchase|checkout|transfer|send_money|momo|withdraw|delete|remove|wipe|erase|format")

        fun risky(name: String): Boolean = name in RISKY || payish.containsMatchIn(name)

        /** Stable key: tool + arguments with keys sorted, whitespace-normalised text. */
        fun key(name: String, args: JSONObject): String {
            val keys = args.keys().asSequence().toList().sorted()
            return name + "(" + keys.joinToString(",") { k -> k + "=" + args.opt(k).toString().trim().replace(Regex("\\s+"), " ").lowercase() } + ")"
        }

        const val NOTE = "[Not repeated: this exact action already ran earlier in this task. Its result was:] "
    }

    private val done = LinkedHashMap<String, String>()

    /** The earlier result when this exact risky action already ran (and didn't fail), else null: run it. */
    @Synchronized fun earlier(name: String, args: JSONObject): String? {
        if (!risky(name)) return null
        return done[key(name, args)]?.let { NOTE + it }
    }

    /** Records a risky action's result. A clear failure is not recorded, so a real retry can run. */
    @Synchronized fun record(name: String, args: JSONObject, result: String) {
        if (!risky(name)) return
        if (!ClaimGuard.succeeded(result)) return
        done[key(name, args)] = result.take(2_000)
    }

    @Synchronized fun clear() = done.clear()
    @Synchronized fun size() = done.size
}
