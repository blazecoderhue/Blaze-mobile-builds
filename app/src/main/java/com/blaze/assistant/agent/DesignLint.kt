package com.blaze.assistant.agent

/**
 * v0.26.2 Designer playbook, enforced: after design create / update the file is read back and checked for the
 * failures Jack called out ("a blank screen with text and arrows"): no styling, raw arrows standing in for a
 * diagram, Mermaid shown as text, almost no content, placeholder copy, no colour palette. The notes go back to
 * the model with the tool result so it fixes the page before saying it's done. Pure (DesignLintTest).
 */
object DesignLint {
    private val tag = Regex("<[^>]+>")
    private val script = Regex("<(script|style)[^>]*>[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE)
    private val arrows = Regex("(?:-{1,2}>|=>|→|⇒|⟶|➜|➔|↓|↑|<-{1,2})")
    private val mermaidText = Regex("(?m)^\\s*(?:graph|flowchart)\\s+(?:TD|TB|LR|RL|BT)\\b|^\\s*sequenceDiagram\\b|^\\s*classDiagram\\b|^\\s*gantt\\b")
    private val blocks = Regex("<(div|section|article|header|footer|main|nav|ul|ol|li|table|svg|canvas|img|figure|button|h[1-6]|card)\\b", RegexOption.IGNORE_CASE)

    /** Visible text of an HTML page (scripts and styles dropped, tags removed, spaces folded). */
    fun visibleText(html: String): String = html.replace(script, " ").replace(tag, " ")
        .replace("&nbsp;", " ").replace("&gt;", ">").replace("&lt;", "<").replace("&amp;", "&").replace(Regex("\\s+"), " ").trim()

    /** Problems a page has ("" list = it looks like a real design). 3D scenes and SVG art are skipped (no text layout). */
    fun check(html: String, kind: String = "page"): List<String> {
        val k = kind.lowercase()
        if (k == "3d" || k == "svg" || k == "game") return emptyList()
        val out = ArrayList<String>()
        val text = visibleText(html)
        val styled = Regex("<style[\\s>]", RegexOption.IGNORE_CASE).containsMatchIn(html) || Regex("\\sstyle\\s*=", RegexOption.IGNORE_CASE).findAll(html).count() >= 3 ||
            Regex("tailwind|bootstrap", RegexOption.IGNORE_CASE).containsMatchIn(html)
        val rendersMermaid = Regex("mermaid(?:\\.min)?\\.js|mermaid\\.initialize|class=\"mermaid\"|class='mermaid'", RegexOption.IGNORE_CASE).containsMatchIn(html)
        val drawn = Regex("<(svg|canvas|img)\\b", RegexOption.IGNORE_CASE).containsMatchIn(html) || rendersMermaid
        if (!styled) out += "The page has no styling (no <style> or CSS): add a palette (2-3 colours as CSS variables), a type scale, spacing and layout."
        if (text.length < 40 && !drawn) out += "The page is almost empty: add the real content from the brief."
        val nArrows = arrows.findAll(text).count()
        if (nArrows >= 3 && !drawn) out += "It shows $nArrows text arrows on a plain page: draw the flow as a real diagram (design kind=diagram renders Mermaid, or SVG shapes with connectors) instead of raw text and arrows."
        if (mermaidText.containsMatchIn(text) && !rendersMermaid) out += "Mermaid code is showing as text: render it as a picture (design kind=diagram, or include mermaid.js and a class=\"mermaid\" block)."
        if (Regex("lorem ipsum|your (?:text|title|headline) here", RegexOption.IGNORE_CASE).containsMatchIn(text)) out += "Placeholder copy (lorem ipsum / \"your text here\"): use real content."
        if (styled && !Regex("#[0-9a-fA-F]{3,8}\\b|rgb|hsl|var\\(--", RegexOption.IGNORE_CASE).containsMatchIn(html)) out += "No colour palette: define 2-3 colours (CSS variables) with readable contrast."
        if (text.length > 600 && blocks.findAll(html).count() < 4) out += "Long text with almost no structure: break it into sections, cards or a grid with headings."
        return out
    }

    /** The note appended to a design tool result ("" when it passes). */
    fun note(html: String, kind: String = "page"): String {
        val p = check(html, kind); if (p.isEmpty()) return ""
        return "\n[Design check] Not done yet, fix this first and look again (design action=read):\n" + p.joinToString("\n") { "- $it" }
    }
}
