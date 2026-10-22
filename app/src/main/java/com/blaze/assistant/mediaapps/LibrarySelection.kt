package com.blaze.assistant.mediaapps

/**
 * Wave B item 20: press-and-hold multi-select in the Music and Video libraries. Pure state rules (no Android) so the
 * behaviour is unit tested: the selection is an immutable set of track keys; every op returns the new set.
 *
 *  - long-press a row → selection mode with that row picked ([start])
 *  - tap while selecting → [toggle]; removing the last pick leaves selection mode
 *  - Select all picks everything shown; pressed again when all are picked it clears them ([selectAll])
 *  - Back leaves selection mode before anything else ([backClears])
 *  - rows that vanish (deleted, filtered by search) drop out of the selection ([prune])
 */
object LibrarySelection {
    val NONE: Set<String> = emptySet()

    fun active(sel: Set<String>): Boolean = sel.isNotEmpty()

    fun start(key: String): Set<String> = setOf(key)

    fun toggle(sel: Set<String>, key: String): Set<String> = if (key in sel) sel - key else sel + key

    fun allSelected(sel: Set<String>, shown: List<String>): Boolean = shown.isNotEmpty() && sel.containsAll(shown)

    /** Picks every shown key; when they are all picked already, clears the selection (the button toggles). */
    fun selectAll(sel: Set<String>, shown: List<String>): Set<String> =
        if (allSelected(sel, shown)) NONE else LinkedHashSet(shown)

    fun clear(): Set<String> = NONE

    fun count(sel: Set<String>): Int = sel.size

    /** Top bar title: "1 selected", "12 selected". */
    fun label(sel: Set<String>): String = "${sel.size} selected"

    /** Keeps only keys that are still in the library / on screen. Returns the same set when nothing changed. */
    fun prune(sel: Set<String>, present: Collection<String>): Set<String> {
        if (sel.isEmpty()) return sel
        val p = present as? Set<String> ?: present.toHashSet()
        return if (sel.all { it in p }) sel else sel.filterTo(LinkedHashSet()) { it in p }
    }

    /** Back press: true when it should only leave selection mode (and not close a group, the player or the app). */
    fun backClears(sel: Set<String>): Boolean = sel.isNotEmpty()

    /** Picked tracks, in the order they are shown. */
    fun picked(sel: Set<String>, tracks: List<Track>): List<Track> = tracks.filter { it.key in sel }

    /** Only Blaze's own downloads are deleted from the phone; Gallery files are the Gallery's to delete. */
    fun deletable(picked: List<Track>): List<Track> = picked.filter { it.source == "download" }

    /** The delete confirmation: title and body for what was picked. */
    fun deleteText(picked: List<Track>, kind: String): Pair<String, String> {
        val del = deletable(picked).size
        val kept = picked.size - del
        val noun = if (kind == "video") "video" else "song"
        val title = if (del == 1) "Delete 1 $noun?" else "Delete $del ${noun}s?"
        val body = buildString {
            append(if (kind == "video") "They're removed from your phone." else "They're removed from your phone and from every playlist.")
            if (kept > 0) append(" $kept from Blaze's Gallery stay" + (if (kept == 1) "s" else "") + "; delete those in Gallery.")
        }
        return title to body
    }

    /** One MIME type for a share of several files: exact when they agree, "audio/\*" when only the family agrees. */
    fun shareType(mimes: List<String>): String {
        val d = mimes.distinct()
        return d.singleOrNull() ?: d.map { it.substringBefore('/') }.distinct().singleOrNull()?.let { "$it/*" } ?: "*/*"
    }

    /** A safe file name for an exported copy: the title (no path characters) plus the source's extension. */
    fun exportName(title: String, uri: String, kind: String): String {
        val ext = uri.substringAfterLast('/').substringAfterLast('.', "").lowercase().takeIf { it.length in 2..4 && it.all(Char::isLetterOrDigit) }
            ?: if (kind == "video") "mp4" else "m4a"
        val base = title.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), " ").replace(Regex("\\s+"), " ").trim().take(80).ifBlank { "Blaze $kind" }
        return if (base.lowercase().endsWith(".$ext")) base else "$base.$ext"
    }
}
