package com.blaze.assistant.ui

/**
 * v0.45.7: what the Cloud sync switch shows. Before a passphrase is set the switch said ON while the label said
 * "Off. Nothing leaves this phone." (it showed `on || setup`). Now the switch is on only when sync really is on;
 * while the passphrase form is open it stays off and the label says what turns it on.
 */
object CloudSyncSwitch {
    data class View(val checked: Boolean, val label: String)

    const val OFF = "Off. Nothing leaves this phone."
    const val PENDING = "Off until you set a passphrase below and tap \u201cTurn on cloud sync\u201d."

    fun view(on: Boolean, setupOpen: Boolean, @Suppress("UNUSED_PARAMETER") needsPassphrase: Boolean, statusLine: String): View = when {
        // On (also on another phone, waiting for this phone's passphrase: the status line says so; tapping turns it off).
        on -> View(true, statusLine)
        setupOpen -> View(false, PENDING)
        else -> View(false, OFF)
    }
}
