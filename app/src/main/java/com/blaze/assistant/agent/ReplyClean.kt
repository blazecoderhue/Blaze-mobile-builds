package com.blaze.assistant.agent

/**
 * v0.23.2: strips reasoning and chat-template tokens that some free models leak into the visible
 * reply, and moves their reasoning to the thinking pane instead.
 *
 * Seen in the wild:
 *  - Gemma-style models on NVIDIA NIM (google/…): `<|channel>thought\n…<channel|>` before the answer.
 *  - gpt-oss "harmony" format: `<|channel|>analysis<|message|>…<|end|><|start|>assistant<|channel|>final<|message|>…`.
 *  - `<think>…</think>` (and a lone `</think>` when the opening tag was eaten by the template).
 *  - A bare `thought` / `analysis` line at the very start, left when the provider removed the tokens but not the word.
 *  - Stray template tokens: `<start_of_turn>model`, `<end_of_turn>`, `<|im_start|>assistant`, `<|im_end|>`, `<|eot_id|>`…
 *
 * One shared cleaner: [ReplyParts.split] calls it, so the streamed text, the stored message, speech and the
 * history sent back to the model all see the same clean text. Pure Kotlin, unit-tested in ReplyCleanTest.
 */
object ReplyClean {

    data class Result(val text: String, val thinking: String)

    private val ic = setOf(RegexOption.IGNORE_CASE)
    private const val REASON = "thought|thoughts|thinking|think|analysis|reasoning|reflection|commentary"

    // gpt-oss harmony: <|channel|>analysis<|message|> … (<|end|> | next <|start|> | <|channel|> | end)
    private val harmony = Regex("""<\|channel\|>\s*($REASON)\s*(?:<\|constrain\|>\S*\s*)?<\|message\|>([\s\S]*?)(?=<\|end\|>|<\|start\|>|<\|channel\|>|<\|return\|>|$)""", ic)
    private val harmonyFinal = Regex("""<\|channel\|>\s*final\s*(?:<\|constrain\|>\S*\s*)?<\|message\|>""", ic)
    // Gemma-style: <|channel>thought\n … <channel|>   (also <|channel|>thought … <|channel|> without <|message|>)
    private val gemma = Regex("""<\|channel\|?>\s*($REASON)\b([\s\S]*?)(?:<\|?channel\|>|$)""", ic)
    private val gemmaOther = Regex("""<\|channel\|?>\s*(?:final|response|answer)?\s*""", ic)
    // <think>…</think> and friends.
    private val thinkTag = Regex("""<(think|thought|thinking|reasoning|analysis)>([\s\S]*?)(?:</\1>|$)""", ic)
    private val loneClose = Regex("""^([\s\S]*?)</(?:think|thought|thinking|reasoning)>""", ic)
    // Any leftover template token.
    private val specials = Regex(
        """<\|(?:start|im_start)\|>\s*(?:assistant|model|user|system)?|<\|(?:start|end|message|channel|constrain|return|call|im_start|im_end|im_sep|eot_id|begin_of_text|end_of_text|start_header_id|end_header_id|endoftext|assistant|user|system)\|?>|""" +
            """<(?:channel|message|start|end)\|>|<(?:start_of_turn|end_of_turn|bos|eos)>(?:\s*(?:model|assistant)\b)?""", ic)
    private val roleAfterStart = Regex("""(?m)^\s*(?:assistant|model)\s*(?=\S)""")
    // A bare reasoning word alone on the first line: "thought\n…".
    private val bareLead = Regex("""^\s*(?:$REASON)\s*:?\s*\n""", ic)
    private val partialTail = Regex("""<\|?[a-z_]{0,16}\|?$|<\|[a-z_]{0,16}\|?>\s*[a-z]{0,10}$|<[a-z_]{2,16}\|>?$""", ic)
    // Zero-width characters inside a token ("<\u200B|channel|>") would hide it from the patterns above.
    private val zeroWidth = Regex("""(?<=[<|])[\u200B-\u200D\u2060\uFEFF]+|[\u200B-\u200D\u2060\uFEFF]+(?=[|>])""")

    /** Cleans [raw]. [final] = the whole reply is here (while streaming, half-typed tokens and openers are hidden). */
    fun clean(raw: String, final: Boolean = true): Result {
        if (raw.isEmpty()) return Result("", "")
        val think = ArrayList<String>()
        var t = if (raw.contains('<')) zeroWidth.replace(raw, "") else raw
        // The fast path: nothing that looks like a token or a reasoning lead.
        if (!t.contains('<') && !bareLead.containsMatchIn(t) && (final || !leadPrefix(t))) return Result(t, "")

        t = harmony.replace(t) { m -> think += m.groupValues[2].trim(); "" }
        t = harmonyFinal.replace(t, "")
        t = gemma.replace(t) { m -> think += m.groupValues[2].trim(); "" }
        t = gemmaOther.replace(t, "")
        t = thinkTag.replace(t) { m -> think += m.groupValues[2].trim(); "" }
        if (final || t.contains(Regex("</(?:think|thought|thinking|reasoning)>", ic))) {
            loneClose.find(t)?.let { m -> think += m.groupValues[1].trim(); t = t.substring(m.range.last + 1) }
        }
        val hadSpecial = specials.containsMatchIn(t)
        t = specials.replace(t, "")
        if (hadSpecial) t = roleAfterStart.replaceFirst(t, "")
        t = bareLead.replaceFirst(t, "")
        if (!final) {
            // Hide a half-typed token at the end ("<|chan", "<|channel>thou") and a reply that so far is just "thou…".
            partialTail.find(t)?.let { m -> if (t.length - m.range.first < 32) t = t.substring(0, m.range.first) }
            if (leadPrefix(t)) t = ""
        }
        return Result(t.trim(), think.filter { it.isNotBlank() }.joinToString("\n\n"))
    }

    /** While streaming: the text so far could still become a bare "thought\n" lead. */
    private fun leadPrefix(t: String): Boolean {
        val s = t.trimStart()
        if (s.isEmpty() || s.length > 10 || s.contains(' ')) return false
        return listOf("thought", "thinking", "analysis", "reasoning").any { it.startsWith(s.trimEnd().lowercase()) } && s == s.lowercase()
    }
}
