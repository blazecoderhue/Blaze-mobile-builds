package com.blaze.assistant.update

/**
 * 0.45.15 signing key rotation (user-approved 22 Oct 2026 08:32). Old key e9183294… (the public debug keystore) handed over
 * to the private release key e7b0281f… through a v3 lineage that leaves the old key only "installed data", so current
 * installs update while an APK signed only with the old key can't replace a rotated install. Pure; UpdateKeysTest.
 */
object UpdateKeys {
    /** SHA-256 of the release signing certificate every update must carry as its current signer (Android 9+). */
    const val RELEASE_CERT = "e7b0281fdf44c45c09240977eaf0a2999913e3681382ea9248b0b4f8b8e369ac"
    /** The old, public, burned key: never enough on its own. */
    const val BURNED_CERT = "e918329414572c95b896d20884992edd91cf69af59bc443fe9cfb59fb5b20b9e"

    /** True when [currentSigners] (lowercase hex SHA-256 of each current signer cert) holds the release key. */
    fun pinned(currentSigners: Set<String>): Boolean = RELEASE_CERT in currentSigners.map { it.lowercase() }

    /**
     * 0.45.18: Android 7/8 (API < 28) has no v3 key rotation, and 0.45.17's v2 block is signed by the release key, so a
     * phone below Android 9 whose Blaze carries the old key can't update in place. One line telling the user what to do,
     * or null when the phone can update normally. [installedSigners] = lowercase hex SHA-256 of the installed signers.
     */
    fun legacyNote(sdk: Int, installedSigners: Set<String>): String? =
        if (sdk >= 28 || installedSigners.isEmpty() || RELEASE_CERT in installedSigners.map { it.lowercase() }) null
        else LEGACY_NOTE

    const val LEGACY_NOTE = "On Android 7 and 8 this update can't install over your Blaze: back up (Settings \u203a Backup), " +
        "uninstall Blaze, then reinstall it from blazeinchue.pages.dev."
}
