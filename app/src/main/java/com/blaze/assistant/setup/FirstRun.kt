package com.blaze.assistant.setup

/**
 * 0.45.23 first run, the same two steps as Blaze Desktop (onboarding.go / onboard.js v0.19.14):
 *  1. Sign in (Blaze Cloud needs no keys). "Not now" is allowed and lands in the chat too.
 *  2. The chat, with a starter prompt typed into an empty composer (Desktop's land()): nothing is sent until the user taps Send.
 *
 * Nothing else is asked up front. Permissions are asked when a task first needs them (phone control: agent/PermissionGate;
 * notifications: [NotifyAsk]; mic, camera, contacts...: Android's own prompt at the tap). Themes, voice, the Home
 * interests and the Desktop pairing live in Settings or behind one-time cards. A returning user (signed in, or with
 * chats on the phone) and anyone upgrading never see the step.
 *
 * After sign-in on a phone with no chats, Blaze checks whether the account keeps an encrypted copy (cloud sync) and offers
 * to bring chats and memory back in the chat itself ([offerRestore]); the passphrase only the user knows opens it.
 *
 * Pure: FirstRunTest drives every rule here without a phone.
 */
object FirstRun {
    /** Set once the first run is over (signed in, "Not now", or a returning user). Same key as 0.45.22 and earlier. */
    const val PREF = "onboarded"
    /** The user said "Not now" to the restore card for this account (stores the account id). */
    const val PREF_RESTORE_DISMISSED = "restore_offer_dismissed"

    /** The steps, in order (Desktop's list is ["signin", "folder"]; the phone has no folder step). */
    val STEPS = listOf("signin", "chat")

    /** What lands in the composer after sign-in (Desktop fills the first "Explore" starter the same way). */
    const val STARTER = "What can you do for me on this phone? Give me three things I can try right now."

    /**
     * Show the sign-in step only on a brand-new install: not onboarded, not signed in, no chats here, and not an update
     * over an older Blaze ([upgraded]: Android's lastUpdateTime is later than firstInstallTime), so nobody who already
     * used Blaze sees a first run again.
     */
    fun shouldShow(onboarded: Boolean, signedIn: Boolean, hasChats: Boolean, upgraded: Boolean = false): Boolean =
        !onboarded && !signedIn && !hasChats && !upgraded

    /** An update over an earlier install (times from PackageInfo; a fresh install has them equal, give or take a second). */
    fun upgraded(firstInstallTime: Long, lastUpdateTime: Long): Boolean = lastUpdateTime - firstInstallTime > 2_000L

    enum class Step { SIGN_IN, CHAT }

    sealed class Event {
        object SignedIn : Event()
        object NotNow : Event()
        /** The browser sign-in (Google / GitHub) was cancelled from the step. */
        object Cancel : Event()
        /** System Back on the step; [pending] = a browser sign-in is waiting. */
        data class Back(val pending: Boolean) : Event()
    }

    /** Where the chat lands: the composer draft (only into an empty composer) and whether the user is signed in. */
    data class Landing(val draft: String, val signedIn: Boolean)

    data class Out(val step: Step, val cancelBrowser: Boolean = false, val leaveApp: Boolean = false, val land: Landing? = null)

    fun reduce(step: Step, e: Event): Out = when (step) {
        Step.CHAT -> Out(Step.CHAT)
        Step.SIGN_IN -> when (e) {
            Event.SignedIn -> Out(Step.CHAT, land = Landing(STARTER, signedIn = true))
            Event.NotNow -> Out(Step.CHAT, land = Landing(STARTER, signedIn = false))
            Event.Cancel -> Out(Step.SIGN_IN, cancelBrowser = true)
            // Back while the browser sign-in waits cancels it and stays; otherwise Back leaves the app as usual and the
            // step shows again next time (the flag is only set when the first run is over).
            is Event.Back -> if (e.pending) Out(Step.SIGN_IN, cancelBrowser = true) else Out(Step.SIGN_IN, leaveApp = true)
        }
    }

    /** The starter goes only into an empty composer (a shared text or a half-typed message wins). */
    fun draftFor(current: String, landing: Landing): String? = if (current.isBlank()) landing.draft else null

    /**
     * Offer to bring chats and memory back: signed in, cloud sync off on this phone, the account keeps a copy
     * ([accountHasCopy] null = couldn't check, e.g. offline: ask again later), and not dismissed for this account.
     */
    fun offerRestore(signedIn: Boolean, syncOn: Boolean, accountHasCopy: Boolean?, dismissedFor: String, accountId: String): Boolean =
        signedIn && !syncOn && accountHasCopy == true && (dismissedFor.isEmpty() || dismissedFor != accountId)

    /** Check the account's copy only when it can matter: signed in, sync off here. */
    fun shouldCheckCopy(signedIn: Boolean, syncOn: Boolean, dismissedFor: String, accountId: String): Boolean =
        signedIn && !syncOn && (dismissedFor.isEmpty() || dismissedFor != accountId)
}

/**
 * 0.45.23: notifications are asked when a task first needs them, not at launch. A task that is still working
 * [AFTER_MS] after Send (long enough that the user may leave Blaze and want the "done" heads-up) asks once, while
 * Blaze is in front. The secure-form card and Music still ask at their own moment.
 */
object NotifyAsk {
    const val PREF = "notif_asked"
    const val AFTER_MS = 8_000L

    fun shouldAsk(sdk: Int, granted: Boolean, askedBefore: Boolean, workingMs: Long, foreground: Boolean): Boolean =
        sdk >= 33 && !granted && !askedBefore && foreground && workingMs >= AFTER_MS
}
