package com.blaze.assistant.desktop

import com.blaze.assistant.terminal.AlpineNet

/**
 * v0.33.3, pure (DesktopPrefetchTest): the desktop's ~260 MB of packages are downloaded by Blaze itself, resumably,
 * before apk installs them. Up to v0.33.2 `apk add` fetched them; apk can't resume a package, keeps nothing from a
 * batch that failed half-way, and on a flaky mobile connection the install never finished.
 *
 * Per batch: `apk add --simulate` says which packages (name + version) are still missing; each is fetched with
 * [com.blaze.assistant.terminal.Fetch] (HTTP Range from its .part file, many retries with backoff, other mirrors, main
 * then community) into [STAGE] inside the rootfs; then `apk add` installs those local files (signatures still checked
 * against Alpine's keys) and a plain `apk add <names>` records the names in /etc/apk/world. When anything here fails
 * the step simply falls back to the old `apk add` path, which now also keeps an apk cache.
 */
object DesktopPrefetch {
    /** Inside the rootfs (no leading slash for File(rootfs, …)). */
    const val STAGE = "var/cache/blaze-apk"

    data class Pkg(val name: String, val version: String) {
        val file: String get() = "$name-$version.apk"
    }

    private val installing = Regex("""\(\s*\d+/\d+\)\s+(?:Installing|Upgrading|Replacing)\s+(\S+)\s+\(([^)\s]+)(?:\s*->\s*([^)\s]+))?\)""")

    /** Packages `apk add --simulate` would install ("(3/41) Installing libxfce4util (4.18.2-r0)"); upgrades take the new version. */
    fun parseSimulate(out: String): List<Pkg> = out.lineSequence().mapNotNull { l ->
        val m = installing.find(l) ?: return@mapNotNull null
        val name = m.groupValues[1]; val ver = m.groupValues[3].ifEmpty { m.groupValues[2] }
        if (!safe(name) || !safe(ver)) null else Pkg(name, ver)
    }.distinct().toList()

    /** Names and versions only ever become file names and URL parts: letters, digits and . _ + - . */
    fun safe(s: String): Boolean = s.isNotEmpty() && s.length < 128 && s.all { it.isLetterOrDigit() || it in "._+-" } && !s.startsWith(".")

    /** Where a package can be: each mirror (starting at [first]), main then community. */
    fun urls(p: Pkg, arch: String, mirrors: List<String> = AlpineNet.MIRRORS, first: Int = 0): List<String> {
        if (mirrors.isEmpty()) return emptyList()
        val order = mirrors.indices.map { mirrors[(first + it) % mirrors.size].trimEnd('/') }
        return order.flatMap { m -> listOf("main", "community").map { r -> "$m/${AlpineNet.BRANCH}/$r/$arch/${p.file}" } }
    }

    /** The shell step that asks apk what is missing (no download). */
    fun simulateScript(mirror: String, pkgs: List<String>, update: Boolean): String =
        "set -e\n" + DesktopPlan.reposLine(mirror) + "\n" + DesktopPlan.APK_TIMEOUT_PROBE + "\n" + (if (update) "apk \$T update\n" else "") +
            "apk \$T add --simulate " + pkgs.joinToString(" ") + "\necho BLAZE_SIM_OK"

    /** Installs the staged files, then records the names; removes the staged files once installed. */
    fun installLines(pkgs: List<String>, staged: List<Pkg>): String = buildString {
        if (staged.isNotEmpty()) append("apk \$T add ").append(staged.joinToString(" ") { "/$STAGE/${it.file}" }).append('\n')
        append("apk \$T add ").append(pkgs.joinToString(" ")).append('\n')
        if (staged.isNotEmpty()) append("rm -f ").append(staged.joinToString(" ") { "/$STAGE/${it.file}" }).append('\n')
    }

    /** Overall MB line for a batch: "Downloading xfce4-panel (3 of 23) · 41.2 MB so far". */
    fun batchLine(p: Pkg, i: Int, n: Int, doneBytes: Long, resuming: Boolean): String {
        val mb = "%.1f".format(java.util.Locale.US, doneBytes / 1_000_000.0)
        return (if (resuming) "Resuming " else "Downloading ") + "${p.name} (${i + 1} of $n) \u00b7 $mb MB so far"
    }
}
