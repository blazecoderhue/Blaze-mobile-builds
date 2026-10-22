package com.blaze.assistant.agent

/** v0.45.9: which name greetings use (pure; GreetNameTest). */
object GreetName {
    /** The user's own pick, else the account's first name, else null. Emails, handles and placeholders never count. */
    fun pick(setName: String?, accountName: String?): String? {
        fun ok(n: String?): String? = n?.trim()?.takeIf { it.isNotEmpty() && '@' !in it && it.length <= 40 && !it.equals("user", true) && !it.equals("blaze", true) }
        return ok(setName) ?: ok(accountName)?.substringBefore(' ')
    }

    /** The prompt rule: greet by that name or by none (the maker named in Blaze's own docs is not the user). */
    const val RULE = "\nNAMES: call the user only by the name given above as the user's name; with none given, use no name. Names in Blaze's own docs (its maker, testers) are not the user's."
}
