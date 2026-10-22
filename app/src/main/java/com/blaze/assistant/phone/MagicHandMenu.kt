package com.blaze.assistant.phone

import com.blaze.assistant.phone.MagicHandLogic.Action
import com.blaze.assistant.phone.MagicHandLogic.Kind

/**
 * The seam between Magic Hand and a shared element → action mapping (v0.24.7).
 *
 * The browser pointer (v0.24.6, branch browser-assistant-ui) brings its own radial menu and element → action mapping.
 * Magic Hand asks [MagicHandMenu.mapper] for its actions and draws them with the radial renderer in
 * ui/magichand/RadialMenu.kt, so switching both to the shared implementation is one assignment each: point [mapper]
 * at an adapter over the browser mapping and `RadialMenus.renderer` at its menu, and keep the Magic Hand-only actions
 * (Carry, Watch this, Drop here) appended by [withHandActions].
 */
interface ElementActionMapper {
    /** Actions for an element of [kind]; [carrying] = the hand holds something that could be dropped. */
    fun actionsFor(kind: Kind, carrying: Boolean): List<Action>
}

/** Magic Hand's own mapping (MagicHandLogic.actionsFor). */
object DefaultHandMapper : ElementActionMapper {
    override fun actionsFor(kind: Kind, carrying: Boolean): List<Action> = MagicHandLogic.actionsFor(kind, carrying)
}

object MagicHandMenu {
    /** v0.24.7: the shared v0.24.6 browser pointer mapping, so both pointers offer the same menu. */
    @Volatile var mapper: ElementActionMapper = BrowserMenuMapper

    /** The menu for [kind], from the current mapper, with the hand-only actions kept and the result capped. */
    fun actions(kind: Kind, carrying: Boolean): List<Action> = withHandActions(mapper.actionsFor(kind, carrying), kind, carrying)

    /** Makes sure Carry / Watch / Drop survive a mapper that doesn't know them (e.g. the browser's). */
    fun withHandActions(base: List<Action>, kind: Kind, carrying: Boolean): List<Action> {
        if (kind == Kind.SECRET) return emptyList()
        val out = base.toMutableList()
        if (carrying && kind == Kind.FIELD && Action.DROP !in out) out.add(0, Action.DROP)
        if (kind == Kind.VALUE && Action.WATCH !in out) out.add(minOf(1, out.size), Action.WATCH)
        if (kind != Kind.EMPTY && Action.CARRY !in out && !(carrying && kind == Kind.FIELD)) out.add(Action.CARRY)
        if (carrying && kind != Kind.FIELD && Action.DROP_IN_CHAT !in out) out.add(Action.DROP_IN_CHAT)
        val ask = out.indexOf(Action.ASK)
        if (ask > 0 && !(carrying && kind == Kind.FIELD)) { out.removeAt(ask); out.add(0, Action.ASK) }
        return out.distinct().let { l ->
            if (l.size <= MagicHandLogic.MAX_ACTIONS) l
            else {
                // Keep the hand-only actions; trim generic ones from the end.
                val keep = setOf(Action.ASK, Action.DROP, Action.WATCH, Action.CARRY, Action.DROP_IN_CHAT)
                val extra = l.size - MagicHandLogic.MAX_ACTIONS
                val drop = l.reversed().filter { it !in keep }.take(extra).toSet()
                l.filter { it !in drop }.take(MagicHandLogic.MAX_ACTIONS)
            }
        }
    }
}

/**
 * v0.24.7: the browser pointer's own element → action mapping (PagePointer.actionsFor, v0.24.6) as Magic Hand's
 * mapper, so both pointers offer the same menu for the same kind of thing. Hand-only actions are added back by
 * [MagicHandMenu.withHandActions].
 */
object BrowserMenuMapper : ElementActionMapper {
    fun pageKind(k: Kind): com.blaze.assistant.browser.PagePointer.Kind = when (k) {
        Kind.LINK -> com.blaze.assistant.browser.PagePointer.Kind.LINK
        Kind.IMAGE -> com.blaze.assistant.browser.PagePointer.Kind.IMAGE
        Kind.TEXT, Kind.VALUE, Kind.BUTTON, Kind.FIELD -> com.blaze.assistant.browser.PagePointer.Kind.TEXT
        Kind.EMPTY, Kind.SECRET -> com.blaze.assistant.browser.PagePointer.Kind.OTHER
    }

    /** A browser action as a hand action; null for the ones that only make sense in a WebView (download, pop out). */
    fun handAction(a: com.blaze.assistant.browser.PagePointer.Action): Action? = when (a) {
        com.blaze.assistant.browser.PagePointer.Action.ASK -> Action.ASK
        com.blaze.assistant.browser.PagePointer.Action.COPY_TEXT -> Action.COPY
        com.blaze.assistant.browser.PagePointer.Action.TRANSLATE -> Action.TRANSLATE
        com.blaze.assistant.browser.PagePointer.Action.EXPLAIN, com.blaze.assistant.browser.PagePointer.Action.SUMMARISE -> Action.EXPLAIN
        com.blaze.assistant.browser.PagePointer.Action.OPEN, com.blaze.assistant.browser.PagePointer.Action.OPEN_BG -> Action.OPEN_LINK
        com.blaze.assistant.browser.PagePointer.Action.COPY_LINK, com.blaze.assistant.browser.PagePointer.Action.COPY_IMAGE_LINK -> Action.COPY_LINK
        com.blaze.assistant.browser.PagePointer.Action.SHARE -> Action.SHARE
        com.blaze.assistant.browser.PagePointer.Action.SAVE_IMAGE -> Action.SAVE_IMAGE
        com.blaze.assistant.browser.PagePointer.Action.SEARCH_IMAGE -> Action.DESCRIBE
        com.blaze.assistant.browser.PagePointer.Action.DOWNLOAD, com.blaze.assistant.browser.PagePointer.Action.PIP -> null
    }

    override fun actionsFor(kind: Kind, carrying: Boolean): List<Action> {
        if (kind == Kind.SECRET) return emptyList()
        val base = com.blaze.assistant.browser.PagePointer.actionsFor(pageKind(kind)).mapNotNull(::handAction).toMutableList()
        // The browser's text menu has no Search / Share / Remind; text on the phone gets them like the hand's own menu.
        if (kind == Kind.TEXT) listOf(Action.SEARCH, Action.SHARE, Action.REMIND).forEach { if (it !in base) base += it }
        if (kind == Kind.FIELD && carrying) base.add(0, Action.DROP)
        return base.distinct()
    }
}

/**
 * v0.24.7 Magic Hand safety rules (pure, tested): what the hand may do while a private chat (or a private browser
 * tab) is in front. In private nothing is captured: no screenshot, no OCR, no teach recording, no watch, nothing
 * sent to Blaze or saved. Moving text the user can already see (copy, carry, drop, share, open) still works.
 */
object MagicHandGuard {
    private val capturing = setOf(Action.ASK, Action.EXPLAIN, Action.DESCRIBE, Action.TRANSLATE, Action.REMIND,
        Action.WATCH, Action.SAVE_IMAGE, Action.DROP_IN_CHAT)

    fun allowed(a: Action, private: Boolean): Boolean = !private || a !in capturing
    fun canScreenshot(private: Boolean) = !private
    fun canTeach(private: Boolean) = !private
    fun canGrab(private: Boolean) = !private
    fun canWatch(private: Boolean) = !private

    const val PRIVATE_NOTE = "Private is on, so Magic Hand captures nothing. Copy, carry and drop still work."

    /** The hand never taps on its own: every tap (teach) and every drop into a field asks first. */
    fun needsConfirm(i: MagicHandLogic.Intent): Boolean = i == MagicHandLogic.Intent.TEACH_TAP || i == MagicHandLogic.Intent.DROP || i == MagicHandLogic.Intent.WATCH

    /** The confirm line shown before the hand acts. */
    fun confirmLine(i: MagicHandLogic.Intent, label: String, carried: String = ""): String {
        val l = MagicHandLogic.preview(label.ifBlank { "this" }, 36)
        return when (i) {
            MagicHandLogic.Intent.TEACH_TAP -> "Tap \u201c$l\u201d for you?"
            MagicHandLogic.Intent.DROP -> "Drop \u201c${MagicHandLogic.preview(carried, 24)}\u201d into \u201c$l\u201d?"
            MagicHandLogic.Intent.WATCH -> "Watch \u201c$l\u201d and tell you when it changes?"
            else -> l
        }
    }
}
