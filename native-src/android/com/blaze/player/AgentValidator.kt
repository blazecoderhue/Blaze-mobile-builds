// Blaze Player (Apache-2.0). Added by Blazeinchue.
package com.blaze.player

import android.net.Uri

/** Accepts only licensed targets: the user's content:// files and Internet Archive items. */
object AgentValidator {
    private val IDENT = Regex("^[A-Za-z0-9._-]{1,100}$")
    private val ARCHIVE_PATH = Regex("^/(details|download|embed)/([A-Za-z0-9._-]{1,100})(/.*)?$")

    class Rejected(val code: String, message: String) : Exception(message)

    fun isArchiveHost(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        return h == "archive.org" || h.endsWith(".archive.org")
    }

    /** Returns a normalized source id (archive:<id> or local:<uri>) or throws Rejected. */
    fun normalize(uri: String?, sourceId: String?): String {
        if (!sourceId.isNullOrBlank()) {
            if (sourceId.length > 1024) throw Rejected("E_BAD_SOURCE_ID", "sourceId too long")
            if (sourceId.startsWith("archive:")) {
                val id = sourceId.removePrefix("archive:")
                if (!IDENT.matches(id)) throw Rejected("E_BAD_SOURCE_ID", "bad archive identifier")
                return sourceId
            }
            if (sourceId.startsWith("local:content://")) return sourceId
            throw Rejected("E_SOURCE_NOT_ALLOWED", "unknown source id")
        }
        if (uri.isNullOrBlank()) throw Rejected("E_MISSING_TARGET", "uri or sourceId required")
        if (uri.length > 2048) throw Rejected("E_BAD_URI", "uri too long")
        val u = Uri.parse(uri)
        return when (u.scheme?.lowercase()) {
            "content" -> "local:$uri"
            "https" -> {
                if (!isArchiveHost(u.host)) throw Rejected("E_SOURCE_NOT_ALLOWED", "only archive.org https uris are accepted")
                val m = ARCHIVE_PATH.find(u.path ?: "") ?: throw Rejected("E_SOURCE_NOT_ALLOWED", "not an archive.org item uri")
                "archive:${m.groupValues[2]}"
            }
            else -> throw Rejected("E_SOURCE_NOT_ALLOWED", "scheme not allowed")
        }
    }
}
