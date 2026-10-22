package com.blaze.assistant.phone

/**
 * Magic Hand teach mode (v0.24.7), pure part (MagicHandTest): the recorder that turns the user's own taps, typing and
 * screen changes (seen passively through accessibility events) into steps, and the conversion of those steps into a
 * workflow in the v0.24.3 `save_workflow` format (SkillPacks.saveWorkflow), always saved as tested:false.
 *
 * Passwords are never recorded: typing into a password field becomes "ask the user for it" with no value.
 */
object MagicHandTeach {
    enum class Kind { OPEN_APP, SCREEN, TAP, TYPE }

    data class Step(
        val kind: Kind,
        val pkg: String,
        val app: String = "",
        val screen: String = "",
        val label: String = "",
        val viewId: String = "",
        val className: String = "",
        val text: String = "",
        val secret: Boolean = false,
        val at: Long = 0L,
    )

    /** A workflow ready for SkillPacks.saveWorkflow (steps / checks are one per line). */
    data class Draft(val name: String, val trigger: String, val steps: String, val checks: String, val tags: List<String>, val tested: Boolean = false)

    const val MAX_STEPS = 30

    /**
     * Collects steps in order. Repeated screens are folded, typing into the same field keeps only its final text, and
     * Blaze's own package is ignored (the hand, the dock and the review card aren't part of what's being taught).
     */
    class Recorder(private val ownPkg: String) {
        private val list = ArrayList<Step>()
        val steps: List<Step> @Synchronized get() = list.toList()
        val size: Int @Synchronized get() = list.size
        private var lastPkg = ""
        private var lastScreen = ""

        @Synchronized
        fun window(pkg: String, app: String, screen0: String, at: Long = 0L) {
            if (pkg.isBlank() || pkg == ownPkg || full()) return
            val screen = MagicHandSafety.humanLabel(screen0)
            if (pkg != lastPkg) {
                list += Step(Kind.OPEN_APP, pkg, app = app, screen = screen, at = at)
                lastPkg = pkg; lastScreen = screen; return
            }
            if (screen.isNotBlank() && screen != lastScreen && screen != app) {
                // Two screen changes with nothing done in between: keep the newer one.
                if (list.lastOrNull()?.kind == Kind.SCREEN) list.removeAt(list.size - 1)
                list += Step(Kind.SCREEN, pkg, app = app, screen = screen, at = at)
                lastScreen = screen
            }
        }

        @Synchronized
        fun click(pkg: String, app: String, label0: String, viewId: String, className: String, at: Long = 0L) {
            if (pkg.isBlank() || pkg == ownPkg || full()) return
            val label = MagicHandSafety.humanLabel(label0)
            if (label.isBlank() && viewId.isBlank()) return
            ensureApp(pkg, app, at)
            list += Step(Kind.TAP, pkg, app = app, screen = lastScreen, label = label.trim().take(80), viewId = viewId, className = className, at = at)
        }

        @Synchronized
        fun typed(pkg: String, app: String, field: String, viewId: String, text: String, password: Boolean, at: Long = 0L) {
            if (pkg.isBlank() || pkg == ownPkg) return
            val prev = list.lastOrNull()
            if (prev != null && prev.kind == Kind.TYPE && prev.pkg == pkg && prev.viewId == viewId && prev.label == field.trim().take(80)) {
                list[list.size - 1] = prev.copy(text = if (password) "" else text.take(500), secret = password, at = at)
                return
            }
            if (full()) return
            ensureApp(pkg, app, at)
            list += Step(Kind.TYPE, pkg, app = app, screen = lastScreen, label = field.trim().take(80), viewId = viewId,
                text = if (password) "" else text.take(500), secret = password, at = at)
        }

        @Synchronized
        fun undo() { if (list.isNotEmpty()) list.removeAt(list.size - 1) }
        @Synchronized
        fun clear() { list.clear(); lastPkg = ""; lastScreen = "" }

        private fun ensureApp(pkg: String, app: String, at: Long) {
            if (pkg != lastPkg) { list += Step(Kind.OPEN_APP, pkg, app = app, at = at); lastPkg = pkg; lastScreen = "" }
        }
        private fun full() = list.size >= MAX_STEPS
    }

    private fun q(s: String) = "\u201c" + s.replace(Regex("\\s+"), " ").trim() + "\u201d"
    private fun id(s: String) = s.substringAfterLast('/').takeIf { it.isNotBlank() }

    /** One plain instruction per step, with the identifiers Blaze needs to find the element again. */
    fun line(s: Step): String = when (s.kind) {
        Kind.OPEN_APP -> "Open ${s.app.ifBlank { s.pkg }} (package ${s.pkg})"
        Kind.SCREEN -> "Wait for the ${q(s.screen)} screen"   // only human screen titles reach here (MagicHandSafety.humanLabel)
        Kind.TAP -> buildString {
            append("Tap ").append(if (s.label.isNotBlank()) q(s.label) else "the ${id(s.viewId) ?: "element"} button")
            // v0.25.0: the view id helps Blaze find it again; class names never appear in a step.
            id(s.viewId)?.let { append(" (id ").append(it).append(")") }
            if (s.screen.isNotBlank()) append(" on ").append(q(s.screen))
        }
        Kind.TYPE -> buildString {
            val field = if (s.label.isNotBlank()) q(s.label) else "the ${id(s.viewId) ?: "text"} field"
            if (s.secret) append("Ask the user for the password, then type it into ").append(field).append(" (never saved)")
            else append("Type ").append(q(s.text)).append(" into ").append(field)
            id(s.viewId)?.let { append(" (id ").append(it).append(")") }
        }
    }

    /** The workflow for the recorded steps. [name] / [trigger] come from the review card; blanks get sensible defaults. */
    fun toWorkflow(steps: List<Step>, name: String, trigger: String): Draft {
        val apps = steps.mapNotNull { s -> s.app.ifBlank { null } }.distinct()
        val n = name.trim().ifBlank { "Taught: " + (apps.firstOrNull() ?: "phone task") }
        val tr = trigger.trim().ifBlank { "the user asks to " + n.removePrefix("Taught: ").lowercase() }
        val lines = steps.map(::line)
        val checks = buildList {
            steps.lastOrNull { it.kind == Kind.TAP }?.let { add("The screen changed after tapping ${if (it.label.isNotBlank()) q(it.label) else "the last button"}") }
            add("Taps go through the normal confirm card; anything that pays, sends or deletes asks the user first")
            if (steps.any { it.secret }) add("Passwords were not recorded; ask the user each time")
        }
        val tags = (listOf("taught", "phone") + apps.map { it.lowercase() }).distinct().take(8)
        return Draft(n, tr, lines.joinToString("\n"), checks.joinToString("\n"), tags, tested = false)
    }
}
