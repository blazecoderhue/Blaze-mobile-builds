package com.blaze.assistant.phone

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.TelephonyManager
import com.blaze.assistant.agent.Agent
import com.blaze.assistant.notify.NotificationBrain
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Text Mary on WhatsApp: on my way." Background first: reply through the chat's live notification
 * if there is one, SMS straight from the phone, or WhatsApp opened on the chat with the message typed,
 * and Send pressed for you when phone control is on.
 */
object Messaging {
    const val NAME = "message_contact"

    fun tool(): JSONObject = JSONObject().put("type", "function").put("function", JSONObject().put("name", NAME)
        .put("description", "Send a message and/or a picture to a person on WhatsApp, SMS, Telegram or another chat app. Finds the contact itself (emoji in saved names are fine); if several fit it returns the options: ask the user with ask_user, then call again with the exact name. To send a picture you made, set image=\"last\" (or a Gallery file name); text becomes the caption. It only says Sent when it saw it go; any other result means NOT sent, so never tell the user it was sent. The user approves first.")
        .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()
            .put("to", JSONObject().put("type", "string").put("description", "Contact name or phone number"))
            .put("text", JSONObject().put("type", "string").put("description", "Message text, or the caption when sending an image (may be empty then)"))
            .put("image", JSONObject().put("type", "string").put("description", "Optional picture to send: \"last\" = the newest image in the Gallery (e.g. one generate_image just made), or a Gallery file name. WhatsApp/Telegram only."))
            .put("app", JSONObject().put("type", "string").put("enum", JSONArray(listOf("whatsapp", "sms", "telegram", "any")))
                .put("description", "Which app; 'any' = whichever chat they last messaged from, else WhatsApp")))
            .put("required", JSONArray(listOf("to")))))

    private fun appLabel(a: String) = when (a) { "sms" -> "Messages"; "telegram" -> "Telegram"; "whatsapp" -> "WhatsApp"; else -> "" }

    /**
     * [always]: kept for callers; since v0.28.2 every send in the user's name shows the confirm card whatever the
     * standing permissions or Autopilot say. [from]: who drafted it, shown on the card.
     */
    suspend fun run(ctx: Context, a: JSONObject, always: Boolean = false, from: String = ""): String {
        val by = if (from.isBlank()) "" else "Drafted on $from. "
        val to = a.optString("to").trim(); val text = a.optString("text").trim()
        val app = a.optString("app", "any").lowercase()
        val imageArg = a.optString("image").trim()
        val image: java.io.File? = if (imageArg.isEmpty()) null else {
            com.blaze.assistant.media.Media.refresh()
            val items = com.blaze.assistant.media.Media.items.value.filter { it.kind == "image" }
            val name = SendCheck.imageArg(imageArg, items.map { it.file.name to it.time })
                ?: return "Couldn't find the picture \u201c$imageArg\u201d in the Gallery. Make it with generate_image first, then call message_contact with image=\"last\". Nothing was sent."
            items.first { it.file.name == name }.file
        }
        if (to.isEmpty() || (text.isEmpty() && image == null)) return "Couldn't send: need who to message and what to send. Nothing was sent."

        // 1) Live notification from them: reply in the background (text only).
        if (image == null) {
            val live = NotificationBrain.replyable(to, appLabel(app).ifBlank { null })
            if (live != null) {
                if (!Agent.confirm("Reply to ${live.title}?", "$by\u201c$text\u201d (in ${live.app})", "Send", always = true)) return "The user declined. Nothing was sent."
                if (NotificationBrain.sendReply(ctx, live, text)) return "Sent to ${live.title} in ${live.app}, in the background."
            }
        }

        // 2) SMS: straight from the phone.
        if (app == "sms") {
            if (image != null) return "Couldn't send: SMS can't carry pictures here. Use app=whatsapp to send the image. Nothing was sent."
            return Skills.run(ctx, "send_sms", JSONObject().put("to", to).put("message", text))
        }

        // 3) WhatsApp / Telegram: find exactly one person first.
        val contact: Pair<String, String> = kotlin.run {
            val digits = to.filter { it.isDigit() || it == '+' }
            if (digits.length >= 5 && digits.length >= to.count { !it.isWhitespace() } - 2) return@run to to digits
            if (!com.blaze.assistant.device.Perms.ensure(ctx, android.Manifest.permission.READ_CONTACTS)) return com.blaze.assistant.device.Perms.denied("contacts")
            val p = SendCheck.pick(to, Skills.contacts(ctx, to))
            p.one ?: return if (p.choices.isEmpty()) SendCheck.notFound(to) else SendCheck.ambiguous(to, p.choices)
        }
        val number = international(ctx, contact.second)
        val label = if (app == "telegram") "Telegram" else "WhatsApp"
        val pkgs = if (app == "telegram") listOf("org.telegram.messenger", "org.telegram.messenger.web") else listOf("com.whatsapp", "com.whatsapp.w4b")
        val pkg = pkgs.firstOrNull { p -> try { ctx.packageManager.getPackageInfo(p, 0); true } catch (_: Exception) { false } }
            ?: return "Couldn't send: $label isn't installed. Want me to send it as an SMS instead? Nothing was sent."
        val what = when {
            image != null && text.isNotEmpty() -> "A picture (${image.name}) with \u201c$text\u201d"
            image != null -> "A picture (${image.name})"
            else -> "\u201c$text\u201d"
        }
        if (!Agent.confirm("Message ${contact.first} on $label?", "$by$what", "Send", always = true)) return "The user declined. Nothing was sent."

        val intent = if (image != null) {
            val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", image)
            Intent(Intent.ACTION_SEND).setType("image/*").putExtra(Intent.EXTRA_STREAM, uri)
                .apply { if (text.isNotEmpty()) putExtra(Intent.EXTRA_TEXT, text) }
                .putExtra("jid", "$number@s.whatsapp.net")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } else if (app == "telegram") Intent(Intent.ACTION_VIEW, Uri.parse("tg://msg?to=+$number&text=" + Uri.encode(text)))
            else Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send?phone=$number&text=" + Uri.encode(text)))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(intent.setPackage(pkg)) } catch (e: Exception) { return "Couldn't open $label: ${e.message}. Nothing was sent." }

        val svc = BlazeAccessibilityService.instance
            ?: return "Not sent yet: $label is open on ${contact.first}'s chat with it ready, but phone control is off, so the user must tap Send. Tell them exactly that."
        // Only press Send inside the right chat. A wrong chat, a chooser or a "not on WhatsApp" screen stops here.
        val send = svc.waitFor("Send", 9000)
            ?: return "Couldn't send: $label opened but its Send button never appeared (the number may not be on $label). Nothing was sent; tell the user and offer SMS or the right number."
        val words = svc.visibleWords()
        if (image == null && !SendCheck.chatOpenFor(contact.first, number, words))
            return "Couldn't send: $label didn't open ${contact.first}'s chat, so I didn't press Send. Nothing was sent."
        svc.tap(send)
        kotlinx.coroutines.delay(1200)
        svc.settle(1500)
        var v = SendCheck.verdict(text, image != null, svc.fieldText(), svc.visibleWords())
        if (v == SendCheck.Verdict.UNSURE) {
            // One more press when the preview or draft is still there (the first tap can land during an animation).
            svc.waitFor("Send", 1500)?.let { svc.tap(it); kotlinx.coroutines.delay(1200); svc.settle(1500) }
            v = SendCheck.verdict(text, image != null, svc.fieldText(), svc.visibleWords())
        }
        if (v != SendCheck.Verdict.SENT)
            return "Couldn't confirm it was sent: I pressed Send in ${contact.first}'s $label chat but the ${if (image != null) "picture" else "message"} still looks unsent. Tell the user to check $label; do NOT say it was sent."
        try { svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK) } catch (_: Exception) { }
        return "Sent to ${contact.first} on $label: ${if (image != null) "the picture" + (if (text.isNotEmpty()) " with \u201c$text\u201d" else "") else "\u201c$text\u201d"} (seen leaving the chat)."
    }

    /** WhatsApp needs the country code: 0760… in Zambia → 260760… */
    private fun international(ctx: Context, raw: String): String {
        val d = raw.filter { it.isDigit() || it == '+' }
        if (d.startsWith("+")) return d.drop(1)
        if (d.startsWith("00")) return d.drop(2)
        val iso = try { (ctx.getSystemService(TelephonyManager::class.java)?.simCountryIso ?: "").lowercase() } catch (_: Exception) { "" }
        val cc = mapOf("zm" to "260", "za" to "27", "zw" to "263", "mw" to "265", "tz" to "255", "ke" to "254", "ug" to "256", "ng" to "234",
            "gh" to "233", "bw" to "267", "mz" to "258", "cd" to "243", "ao" to "244", "na" to "264", "rw" to "250", "et" to "251",
            "gb" to "44", "us" to "1", "ca" to "1", "in" to "91", "ae" to "971", "cn" to "86", "de" to "49", "fr" to "33")[iso] ?: return d
        return if (d.startsWith("0")) cc + d.drop(1) else if (d.startsWith(cc)) d else cc + d
    }
}
