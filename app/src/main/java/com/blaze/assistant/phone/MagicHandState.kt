package com.blaze.assistant.phone

import com.blaze.assistant.phone.MagicHandLogic.Action
import com.blaze.assistant.phone.MagicHandLogic.Intent
import com.blaze.assistant.phone.MagicHandLogic.Kind

/**
 * Magic Hand (v0.24.7): what the dock shows, as one immutable value, plus the pure transitions the controller
 * (MagicHand) applies. No Android types, so the rules are unit tested (MagicHandStateTest) and the dock, menu,
 * grab and teach-review cards render straight from it in ShotsTest.
 */
object MagicHandState {
    /** What the dock is showing below its header. */
    enum class Panel { STATUS, MENU, ASKING, ANSWER, CONFIRM, GRAB, TEACH_INTRO, TEACH, TEACH_REVIEW }

    /** Actions offered on grabbed (circled) text. */
    enum class GrabAction(val label: String) { COPY("Copy"), TRANSLATE("Translate"), SEARCH("Search"), SAVE("Save"), CARRY("Carry") }

    /** A pending "do this?" card. Nothing happens until the user says yes. */
    data class Confirm(val intent: Intent, val line: String, val yes: String = "Yes", val no: String = "Cancel")

    data class Ui(
        val on: Boolean = false,
        val panel: Panel = Panel.STATUS,
        val mode: MagicHandLogic.Mode = MagicHandLogic.Mode.POINT,
        /** Label of the element under the tip ("" = nothing). */
        val pointing: String = "",
        val kind: Kind = Kind.EMPTY,
        val app: String = "",
        val menu: List<Action> = emptyList(),
        val question: String = "",
        val answer: String = "",
        val confirm: Confirm? = null,
        val grabbed: String = "",
        val grabSource: String = "",
        val carried: String = "",
        val teachSteps: List<String> = emptyList(),
        val teachName: String = "",
        val private: Boolean = false,
        val note: String = "",
        /** The teach explainer's words (TEACH_INTRO panel). */
        val intro: String = "",
        /** v0.26.0: the grab box was moved or resized and its text is being read again. */
        val grabBusy: Boolean = false,
        /** v0.27.0: Grab mode is on (the Grab button): circle or drag anywhere on the screen with a finger. */
        val grabMode: Boolean = false,
        /** v0.27.0: the grab box has an earlier shape to go back to (Undo). */
        val canUndo: Boolean = false,
        /** v0.27.0: show the "Drag corners to adjust" coach mark on the grab card (first few grabs only). */
        val coach: Boolean = false,
        /** v0.28.1: an action tapped while the text was still being read; it runs the moment the text lands. */
        val queued: GrabAction? = null,
    ) {
        val carrying: Boolean get() = carried.isNotBlank()
    }

    const val HELLO = "Drag me. Double-tap for a menu, hold on a value to watch it, draw a circle to grab text."
    const val NOTHING = "Nothing readable here. Move me onto some text or a button."
    const val NEEDS_A11Y = "Magic Hand needs phone control. Turn on Blaze in Settings \u203a Accessibility."
    const val TEACH_HELLO = "Teaching: do the task once in any app. I only watch your taps and typing. Tap \u2713 when done."
    const val TEACH_SHORT = "Tap Start, do the task once, then tap Done."
    const val TEACH_FULL = "That's the step limit (${MagicHandTeach.MAX_STEPS}). Tap \u2713 to save what I have."
    const val BUSY = "Blaze is still answering. Try again in a moment."

    fun start(private: Boolean): Ui = Ui(on = true, private = private, note = if (private) MagicHandGuard.PRIVATE_NOTE else HELLO)

    /** Closing the dock stops everything: no menu, no question in flight, nothing carried, no teach recording. */
    fun close(): Ui = Ui(on = false)

    fun point(u: Ui, label: String, kind: Kind, app: String): Ui =
        if (u.panel == Panel.STATUS || u.panel == Panel.TEACH) u.copy(pointing = label, kind = kind, app = app, note = if (label.isBlank()) u.note else "") else u.copy(pointing = label, kind = kind, app = app)

    /** The radial menu for what's under the tip, with anything that captures removed in Private. */
    fun menu(u: Ui, label: String, kind: Kind, app: String): Ui {
        val acts = MagicHandMenu.actions(kind, u.carrying).filter { MagicHandGuard.allowed(it, u.private) }
        if (acts.isEmpty()) return u.copy(panel = Panel.STATUS, note = if (kind == Kind.SECRET) "That's a password field. Magic Hand leaves it alone." else NOTHING)
        return u.copy(panel = Panel.MENU, menu = acts, pointing = label, kind = kind, app = app, note = "")
    }

    fun asking(u: Ui, question: String): Ui = u.copy(panel = Panel.ASKING, question = question, answer = "", menu = emptyList())
    fun answered(u: Ui, answer: String): Ui = if (u.panel == Panel.ASKING || u.panel == Panel.ANSWER) u.copy(panel = Panel.ANSWER, answer = answer) else u

    fun confirm(u: Ui, intent: Intent, label: String): Ui {
        val line = MagicHandGuard.confirmLine(intent, label, u.carried)
        val yes = when (intent) { Intent.TEACH_TAP -> "Tap it"; Intent.DROP -> "Drop"; Intent.WATCH -> "Watch"; else -> "Yes" }
        return u.copy(panel = Panel.CONFIRM, confirm = Confirm(intent, line, yes), menu = emptyList())
    }

    /** Back to the plain status line (after an answer, a cancel or a done action). */
    fun back(u: Ui, note: String = ""): Ui = u.copy(panel = if (u.mode == MagicHandLogic.Mode.TEACH) Panel.TEACH else Panel.STATUS,
        menu = emptyList(), confirm = null, note = note, grabBusy = false, canUndo = false, coach = false, queued = null)

    fun grabbed(u: Ui, text: String, source: String): Ui =
        if (text.isBlank()) back(u.copy(grabMode = false, queued = null), "I couldn't read any text there. Tap Grab and try a bigger area.")
        else u.copy(panel = Panel.GRAB, grabbed = text.trim(), grabSource = source, menu = emptyList(), grabBusy = false, grabMode = false)

    /**
     * v0.28.1: the grab card opens the instant the finger lifts, with shimmer lines and its actions ready; the text
     * is filled in by [grabbed] / [regrabbed] when OCR finishes. Nothing from an earlier grab is shown meanwhile.
     */
    fun reading(u: Ui, source: String): Ui =
        if (!MagicHandGuard.canGrab(u.private)) back(u.copy(grabMode = false), MagicHandGuard.PRIVATE_NOTE)
        else u.copy(panel = Panel.GRAB, grabbed = "", grabSource = source, menu = emptyList(), confirm = null, grabBusy = true,
            grabMode = false, note = "", canUndo = false, queued = null)

    /** v0.28.1: actions on the grab card are usable while reading: tapped early, they wait for the text ([queued]). */
    fun grabActionsEnabled(u: Ui): Boolean = u.grabbed.isNotBlank() || u.grabBusy

    /** v0.28.1: tap on a grab action. Returns the new state and the action to run now (null = queued or ignored). */
    fun tapGrab(u: Ui, g: GrabAction): Pair<Ui, GrabAction?> = when {
        u.panel != Panel.GRAB -> u to null
        u.grabBusy -> u.copy(queued = g) to null   // the card says "Copy when ready"
        u.grabbed.isBlank() -> u to null
        else -> u.copy(queued = null) to g
    }

    /**
     * v0.27.0: the Grab button. On: a finger circles or drags anywhere on the screen to grab (the hand's own circle
     * still works too); [firstTimes] shows the full hint. Off: back to pointing. Not in Private or while teaching.
     */
    fun grabMode(u: Ui, on: Boolean, firstTimes: Boolean = true): Ui = when {
        !on -> u.copy(grabMode = false, note = if (u.grabMode) "" else u.note)
        !MagicHandGuard.canGrab(u.private) -> back(u.copy(grabMode = false), MagicHandGuard.PRIVATE_NOTE)
        u.mode == MagicHandLogic.Mode.TEACH -> u
        else -> back(u, if (firstTimes) GRAB_MODE_HINT else GRAB_MODE_SHORT).copy(grabMode = true)
    }

    const val GRAB_MODE_HINT = "Circle or drag over what you want"
    const val GRAB_MODE_SHORT = "Grab: circle or drag"

    /** v0.26.0: the grab box moved or was resized; the card stays open while its text is read again. */
    fun regrabbing(u: Ui): Ui = if (u.panel == Panel.GRAB) u.copy(grabBusy = true, note = "") else u

    /** v0.26.0: the text read for the moved / resized box. An empty read keeps the card (and the box) open. */
    fun regrabbed(u: Ui, text: String, source: String): Ui =
        if (u.panel != Panel.GRAB) u else u.copy(grabbed = text.trim(), grabSource = source.ifBlank { u.grabSource }, grabBusy = false,
            queued = if (text.isBlank()) null else u.queued, note = if (u.queued != null && text.isBlank()) GRAB_EMPTY else u.note)

    const val GRAB_EMPTY = "No text in this area. Move the box or drag its corners."
    const val GRAB_HINT = "Drag the blue box to move it, or its corners to resize."
    /** v0.27.0: the short coach mark on the first few grabs. */
    const val COACH = "Drag corners to adjust"

    /**
     * v0.26.0: a long-press on the dock closes Magic Hand, except where the dock shows text you can select (the
     * grabbed text, an answer) or type (the teach review): there a long-press selects text and must never close it.
     * Close (X), Back twice and the notification's Stop always work.
     */
    fun longPressCloses(u: Ui): Boolean = u.panel != Panel.GRAB && u.panel != Panel.ANSWER && u.panel != Panel.TEACH_REVIEW

    /** Grab actions: Translate and Save send or keep what was captured, so Private has none of them (grab itself is off there). */
    fun grabActions(private: Boolean): List<GrabAction> =
        if (private) listOf(GrabAction.COPY, GrabAction.CARRY) else GrabAction.values().toList()

    fun carry(u: Ui, text: String): Ui = back(u.copy(carried = text.trim()), "Carrying \u201c${MagicHandLogic.preview(text)}\u201d. Double-tap a text box in any app to drop it.")
    fun dropped(u: Ui): Ui = back(u.copy(carried = ""), "Dropped.")

    /** v0.25.0: the explainer before recording starts (full words the first time, short after). */
    fun intro(u: Ui, firstTime: Boolean): Ui = if (!MagicHandGuard.canTeach(u.private)) back(u, MagicHandGuard.PRIVATE_NOTE)
        else u.copy(panel = Panel.TEACH_INTRO, menu = emptyList(), confirm = null, note = "Teach Blaze a routine",
            intro = if (firstTime) MagicHandSafety.TEACH_INTRO else TEACH_SHORT)

    fun teach(u: Ui): Ui = if (!MagicHandGuard.canTeach(u.private)) back(u, MagicHandGuard.PRIVATE_NOTE)
        else u.copy(mode = MagicHandLogic.Mode.TEACH, panel = Panel.TEACH, teachSteps = emptyList(), note = TEACH_HELLO, menu = emptyList())
    fun teachSteps(u: Ui, lines: List<String>): Ui = u.copy(teachSteps = lines,
        note = if (u.mode == MagicHandLogic.Mode.TEACH && lines.size >= MagicHandTeach.MAX_STEPS) TEACH_FULL else if (u.note == TEACH_FULL) TEACH_HELLO else u.note)
    fun review(u: Ui, lines: List<String>, defaultName: String): Ui =
        u.copy(panel = Panel.TEACH_REVIEW, teachSteps = lines, teachName = u.teachName.ifBlank { defaultName })
    fun endTeach(u: Ui, note: String): Ui = u.copy(mode = MagicHandLogic.Mode.POINT, panel = Panel.STATUS, teachSteps = emptyList(), teachName = "", note = note)

    /** Dock header buttons for the current state (pure, tested). Close is always last and always there. */
    enum class DockButton(val desc: String) { GRAB("Grab: circle or drag over text"), TEACH("Teach Blaze a task"), DONE("Done teaching"), DROP_CHAT("Drop in chat"), CAMERA("Open Blaze Camera"), CLOSE("Close Magic Hand") }
    fun dockButtons(u: Ui): List<DockButton> = buildList {
        // v0.27.0: Grab is a visible button now, not only a hidden circle gesture.
        if (u.mode == MagicHandLogic.Mode.POINT && MagicHandGuard.canGrab(u.private) && u.panel != Panel.TEACH_INTRO && u.panel != Panel.TEACH_REVIEW) add(DockButton.GRAB)
        if (u.mode == MagicHandLogic.Mode.TEACH) { if (u.panel == Panel.TEACH) add(DockButton.DONE) }
        else if (!u.private && u.panel != Panel.TEACH_INTRO) add(DockButton.TEACH)
        if (u.carrying && !u.private && u.mode == MagicHandLogic.Mode.POINT) add(DockButton.DROP_CHAT)
        // v0.28.x: Blaze Camera from the hand (not in Private, where nothing is captured, and not mid-teach).
        if (!u.private && u.mode == MagicHandLogic.Mode.POINT && u.panel != Panel.TEACH_INTRO && u.panel != Panel.TEACH_REVIEW) add(DockButton.CAMERA)
        add(DockButton.CLOSE)
    }

    /** The one status line under the dock header. */
    fun statusLine(u: Ui): String = when {
        u.note.isNotBlank() -> u.note
        u.pointing.isNotBlank() -> (if (u.app.isNotBlank()) u.app + " \u00b7 " else "") + MagicHandLogic.preview(u.pointing, 60)
        u.mode == MagicHandLogic.Mode.TEACH -> TEACH_HELLO
        u.grabMode -> GRAB_MODE_SHORT
        else -> HELLO
    }

    /** The search URL for grabbed text (DuckDuckGo: free, no key). */
    fun searchUrl(text: String): String = "https://duckduckgo.com/?q=" + java.net.URLEncoder.encode(text.trim().take(300), "UTF-8")
}
