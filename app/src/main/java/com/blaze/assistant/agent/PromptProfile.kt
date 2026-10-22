package com.blaze.assistant.agent

/**
 * v0.45.0 (#13, #16) what goes into the system prompt, by who is asking and what the turn needs.
 *
 * #13 Simple mode used to hide only the UI: the model still read the TERMINAL and MAKE THINGS paragraphs (run_shell,
 * git, http_request, `/root/phone/` paths, a Python one-liner for beats) and the coding core skill, so replies to
 * non-technical users mentioned terminals, jobs and file paths the Simple UI never shows. Now [Kind.SIMPLE]:
 *  - leaves out the TERMINAL paragraph, the long MAKE THINGS paragraph and the coding / specialists core skills;
 *  - keeps the abilities (the tools are still there; the terminal / connectors groups load only on a strong match,
 *    see [simpleGroupOk]) with one short line saying to use them silently ([MAKE_SIMPLE]);
 *  - adds [SIMPLE_RULE]: everyday words, never paths, commands, tool or model names.
 *
 * #16 the prompt was ~40k characters on every full call. Blocks that only matter for some turns now go only then:
 *  - TERMINAL / MAKE THINGS in full only when the turn is about code, files or making content ([coding]) or the
 *    terminal / make groups are on; otherwise one pointer line ([TERMINAL_SHORT]);
 *  - the full VIEWS block only when a view is routed this turn (or a design follow-on); otherwise one line naming the
 *    views ([viewsShort]);
 *  - the coding core skill only on coding turns.
 * Everything else (rules about honesty, asking, background-first, verification) stays every turn. Pure; PromptProfileTest.
 */
object PromptProfile {
    enum class Kind { SIMPLE, ADVANCED }

    data class Plan(
        val kind: Kind,
        /** Full TERMINAL paragraph (else [TERMINAL_SHORT], or nothing in Simple). */
        val terminal: Boolean,
        /** Full MAKE THINGS paragraph (else the short line for the kind). */
        val make: Boolean,
        /** Full VIEWS block (else [viewsShort]). */
        val views: Boolean,
        /** Core skills to pin, in CoreSkills order. */
        val core: List<String>,
    ) {
        val simple get() = kind == Kind.SIMPLE
    }

    private val ic = setOf(RegexOption.IGNORE_CASE)

    /** Code, files, servers, data and making content: the turns the terminal guidance is for. */
    val CODING = Regex("""\b(code|coding|script|python|node|npm|pip|java|kotlin|html|css|javascript|js|json|csv|sql|regex|api|http|curl|git|github|repo|commit|clone|terminal|shell|bash|command line|linux|server|deploy|compile|build (?:an? |the )?(?:app|site|website|page|game|tool)|website|web ?site|web ?app|app (?:that|which)|program|function|bug|debug|error|stack ?trace|file|files|folder|pdf|docx|excel|spreadsheet|convert|download|chart|graph|plot|data|dataset|calculate|beat|beats|music|audio|wav|mp3|video|ffmpeg|image|resize|compress|zip|cron|schedule_command|job|jobs)\b""", ic)

    /** Groups whose tools are terminal-ish. */
    val TECH_GROUPS = setOf("terminal", "make", "pc")

    fun kind(simple: Boolean): Kind = if (simple) Kind.SIMPLE else Kind.ADVANCED

    fun coding(userText: String, groups: Set<String> = emptySet()): Boolean =
        groups.any { it in TECH_GROUPS } || CODING.containsMatchIn(userText)

    fun plan(simple: Boolean, userText: String, groups: Set<String> = emptySet(), viewRouted: Boolean = false,
             names: List<String> = CoreSkills.NAMES): Plan {
        val code = coding(userText, groups)
        val core = names.filter { n ->
            when (n) {
                "blaze-coding-workflow" -> !simple && code
                "blaze-specialists" -> !simple
                else -> true
            }
        }
        return Plan(kind(simple), terminal = !simple && code, make = !simple && code, views = viewRouted, core = core)
    }

    /**
     * Simple mode: may the router add [group] for this request? terminal / pc / connectors (MCP) only on a strong
     * match (the user names the thing), so a Simple user's "make me a birthday card" never pulls in the terminal.
     */
    fun simpleGroupOk(group: String, userText: String): Boolean = when (group) {
        "terminal", "pc" -> STRONG_TECH.containsMatchIn(userText)
        "connectors", "mcp" -> Regex("""\b(mcp|connector|connectors|server)\b""", ic).containsMatchIn(userText)
        else -> true
    }

    private val STRONG_TECH = Regex("""\b(terminal|shell|bash|command line|linux|python|script|run (?:this|the) code|code|git|github|ssh|my (?:pc|computer|laptop)|desktop)\b""", ic)

    /** v0.45.0 (#13 leftover): the files line of BACKGROUND FIRST; Simple never hears about run_shell. */
    fun filesLine(simple: Boolean): String =
        if (simple) "Files → the file tools; anything technical runs quietly in the background, never mentioned." else "Files/code → run_shell."

    const val SIMPLE_RULE = "\nEVERYDAY WORDS: this user chose Simple mode. Explain results in plain everyday words, like to a friend who isn't technical. " +
        "Never show or mention file paths, folders like /root, commands, code, terminals, jobs, tool names, model names, JSON or error codes. " +
        "Do technical work silently and give only the result (\u201cYour card is ready, it's in your Gallery\u201d). If something fails, say what it means for them and the one simple next step."

    const val MAKE_SIMPLE = "\nMAKE THINGS: you can make pictures, music, documents, charts and edits yourself in the background (generate_image for art and photos; " +
        "your own tools for the rest). Do it quietly and hand over the result; never ask the user to type commands or open technical screens. " +
        "Only drive the screen when the task lives inside another app. Never say you can't make images or music."

    const val MAKE_SHORT = "\nMAKE THINGS IN THE BACKGROUND: you can create images (generate_image, free) and you have a real Linux terminal with Python (run_code). " +
        "Content (pictures, music, charts, documents, conversions, data) is made in the background, not by tapping through apps; outputs under /root/phone/ show in the Gallery. " +
        "Never tell the user you can't make images or music. When something needs a permission or a setting, fix it yourself; never ask the user to type commands."

    const val TERMINAL_SHORT = "\nTERMINAL: for files, code, data or servers load the terminal tools (load_tools terminal) and follow the coding skill (use_skill blaze-coding-workflow)."

    /** One line instead of the full VIEWS block when no view is routed this turn. */
    fun viewsShort(viewNames: List<String>): String =
        "\nVIEWS: output that fits a Blaze view (" + viewNames.joinToString(", ") + ") is created in that view with its tool and opened with open_view; " +
            "the chat reply is one or two lines. Never claim you made or opened something in a view unless its tool succeeded this turn."
}
