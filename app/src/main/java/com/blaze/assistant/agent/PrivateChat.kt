package com.blaze.assistant.agent

import org.json.JSONArray

/**
 * v0.24.4 private chat: a thread that lives in memory only. Nothing is saved (no chat file, no journal, no
 * long-chat index), nothing is learned (no memories, skills, titles or traces) and memory-writing tools are
 * removed from the model's list. Leaving it (after a confirm) throws it away.
 */
object PrivateChat {
    const val PREFIX = "private-"

    /** True for a private thread's id. Storage layers refuse these ids as a second line of defence. */
    fun isPrivateId(id: String?): Boolean = id != null && id.startsWith(PREFIX)

    /** Tools that save or learn something; never offered (and refused at dispatch) in a private chat. */
    val blockedTools = setOf(
        "remember", "forget", "memory_save", "memory_update", "memory_delete",
        "skill_manage", "write_skill", "install_skill", "save_workflow",
    )

    fun blocked(name: String): Boolean = name in blockedTools

    /** The tool list without the blocked tools. */
    fun filterTools(all: JSONArray): JSONArray = JSONArray().apply {
        for (i in 0 until all.length()) {
            val t = all.optJSONObject(i) ?: continue
            val n = t.optJSONObject("function")?.optString("name") ?: t.optString("name")
            if (!blocked(n)) put(t)
        }
    }

    const val REFUSAL = "Private chat: nothing is saved or learned here, so I didn't do that. Leave private chat if you want me to remember it."

    const val PROMPT_LINE = "\nPRIVATE CHAT: the user opened a private chat. Nothing from it is saved, remembered or learned, and it is thrown away when they leave. Don't save memories or skills and don't refer back to it later. You may still read existing memories to help.\n"

    const val LEAVE_TITLE = "Leave private chat?"
    const val LEAVE_TEXT = "This chat disappears. Nothing in it was saved or learned."
}
