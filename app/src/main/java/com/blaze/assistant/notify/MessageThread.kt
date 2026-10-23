package com.blaze.assistant.notify

/** Ack-and-background (HQ 22 Oct 20:36): Blaze's off-screen messages read like texts from Blaze. Pure constants. */
object MessageThread {
    /** How many of Blaze's recent lines the text-style notification keeps (ack, then Done ✓). */
    const val KEEP = 5
    /** The title when the task failed: honest, never a "done". */
    const val FAILED_TITLE = "Blaze couldn't finish that"
}
