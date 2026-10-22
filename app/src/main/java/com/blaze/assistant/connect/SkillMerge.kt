package com.blaze.assistant.connect

/**
 * Pure merge rules for skill sync (no Android, so it is unit-tested on the JVM).
 * One record per skill name: [t] is when it last changed (unix ms), [files] its text files
 * by relative path; empty [files] is a deletion. Per skill the newer [t] wins; on a tie the
 * account's copy wins. Mirrors the format in Blaze-dekstop cloud/src/skills.ts.
 */
object SkillMerge {
    data class Rec(val t: Long, val files: Map<String, String>) { val deleted get() = files.isEmpty() }

    data class Result(
        /** What the account should hold afterwards. */
        val merged: Map<String, Rec>,
        /** Skills to write (or delete, when [Rec.deleted]) on this device. */
        val apply: Map<String, Rec>,
        /** True when the account copy must be updated. */
        val push: Boolean,
    )

    fun merge(local: Map<String, Rec>, remote: Map<String, Rec>): Result {
        val merged = HashMap<String, Rec>(); val apply = HashMap<String, Rec>(); var push = false
        for (n in local.keys + remote.keys) {
            val l = local[n]; val r = remote[n]
            when {
                l != null && r == null -> { merged[n] = l; push = true }
                l == null && r != null -> { merged[n] = r; if (!r.deleted) apply[n] = r }
                l != null && r != null && l.t > r.t -> { merged[n] = l; if (l.files != r.files) push = true }
                l != null && r != null -> { merged[n] = r; if (l.files != r.files) apply[n] = r }
            }
        }
        return Result(merged, apply, push)
    }

    /** Skill names are folder names: keep them to safe slugs. */
    fun safeName(n: String) = n.isNotBlank() && n.length <= 64 && Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$").matches(n)

    /** Relative file paths inside a skill: no absolute paths, no "..", no hidden sync files. */
    fun safePath(p: String) = p.isNotBlank() && !p.startsWith("/") && !p.contains("\\") &&
        p.split('/').none { it.isEmpty() || it == "." || it == ".." } && p.length <= 200
}
